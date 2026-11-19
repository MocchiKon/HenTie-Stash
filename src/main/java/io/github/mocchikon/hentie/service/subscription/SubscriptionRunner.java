package io.github.mocchikon.hentie.service.subscription;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.LibraryBusyException;
import io.github.mocchikon.hentie.config.SqliteLocks;
import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.entity.Subscription;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.SearchSource;
import io.github.mocchikon.hentie.scrapper.SearchSource.SearchPage;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import io.github.mocchikon.hentie.service.download.RetryLaterException;
import io.github.mocchikon.hentie.service.subscription.SubscriptionService.PlannedStep;
import io.github.mocchikon.hentie.service.subscription.SubscriptionService.Turn;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Walks the subscriptions' searches, one page per step, and queues what they find.
 * <p>
 * <b>One thread per search source</b>, so a site that asks to slow down (nhentai's 429s are sat out for up to minutes)
 * never holds up another. Platform threads: a virtual one would pin its carrier while SQLite's native code waits for
 * the lock.
 * <p>
 * <b>Each thread takes its source's subscriptions by phase, then priority</b> ({@link SubscriptionService#ORDER}):
 * every check before any re-check, every re-check before any backfill, so a long backfill never delays another
 * subscription's new galleries; within a phase 0 first, and in turn among equals, so a long walk never starves them.
 * <b>A subscription does not step while one of a higher priority, of any source, has a phase of the same or a more
 * important kind left</b> ({@link SubscriptionService.Turn#holdsBack}), also while that one waits for its downloads:
 * after days off, priority 0 lists all its new galleries before priority 1 looks for its own. So every step wakes the
 * other threads, since the phase it ended may free theirs.
 * <p>
 * <b>A step fetches outside any transaction and writes in one short one</b> ({@link SubscriptionService#applyPage}),
 * so the site's pace never holds SQLite's lock. Its writes wait for the {@link WriteGate} as long as they must, as on
 * every thread that serves no request.
 * <p>
 * <b>Starting on {@link ApplicationReadyEvent} is the whole resume</b>: every subscription's walk is on its row, so the
 * first step after a restart continues where the last one committed.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionRunner
{
    private static final Logger log = LoggerFactory.getLogger(SubscriptionRunner.class);

    /** How long shutdown waits for a step in flight: a step that is cut off repeats from the top on the next start. */
    private static final long STOP_WAIT_MILLIS = 5_000;

    /** A site that asks to wait without saying how long is asked again after this. */
    private static final Duration DEFAULT_SITE_WAIT = Duration.ofMinutes(1);

    private final SubscriptionService service;
    private final DataDownloaderRegistry registry;
    private final DownloadWorker worker;
    private final WriteGate writeGate;
    private final AppProperties appProperties;
    private final SettingsService settingsService;
    /** Sites not asked after a step was told to wait. */
    private final ParkedSites parkedSites;

    private final Object monitor = new Object();
    private final List<Thread> threads = new ArrayList<>();
    /** Per source, the subscription stepped last, so the next turn goes to the one after it. */
    private final Map<String, Integer> lastStepped = new ConcurrentHashMap<>();

    private volatile boolean running;
    /** Set by {@link #stop()}: a failure after it is the shutdown's, never the subscription's. */
    private volatile boolean stopping;

    // ---- lifecycle ---------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void startOnBoot()
    {
        if (appProperties.getSubscriptions().isEnabled())
        {
            start();
        }
    }

    public synchronized void start()
    {
        if (running)
        {
            return;
        }
        stopping = false;
        running = true;
        for (SearchSource source : registry.searchSources())
        {
            Thread thread = new Thread(() -> loop(source), "subscriptions-" + source.sourcePrefix());
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
    }

    /**
     * Interrupts, unlike the download worker: a step has nothing half-done outside its one transaction, and an
     * interrupted wait (for the site's pace, or the gate) throws before anything is written.
     */
    @PreDestroy
    public synchronized void stop()
    {
        stopping = true;
        running = false;
        threads.forEach(Thread::interrupt);
        for (Thread thread : threads)
        {
            try
            {
                thread.join(STOP_WAIT_MILLIS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    /**
     * After a subscription changed (created, edited, resumed, "Check now"), subscriptions were turned on, or Settings
     * they depend on changed.
     */
    public void kick()
    {
        parkedSites.clear();
        wakeAll();
    }

    private void wakeAll()
    {
        synchronized (monitor)
        {
            monitor.notifyAll();
        }
    }

    private void loop(SearchSource source)
    {
        while (running)
        {
            boolean worked = false;
            try
            {
                worked = runNext(source);
            }
            catch (RuntimeException e)
            {
                // A bug, but ending the source's subscriptions for the rest of the session would be worse.
                log.error("Subscriptions of {}: unexpected error, continuing", source.sourcePrefix(), e);
            }
            if (!worked)
            {
                idle();
            }
        }
    }

    private void idle()
    {
        synchronized (monitor)
        {
            try
            {
                monitor.wait(Math.max(1, appProperties.getSubscriptions().getIdleCheckSeconds()) * 1000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    // ---- one step ----------------------------------------------------------

    /**
     * One step of the first subscription of any source with something due. Public because the integration tests call
     * it directly, which makes the walk deterministic.
     *
     * @return false when nothing was due
     */
    public boolean runNext()
    {
        for (SearchSource source : registry.searchSources())
        {
            if (runNext(source))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @return false when none of the source's subscriptions had a step to take now (nothing due, or held back by a
     *         higher priority), or subscriptions are turned off
     */
    public boolean runNext(SearchSource source)
    {
        // Checked per step, not by stopping the threads: turning them back on is then only a kick.
        if (settingsService.isSubscriptionsOff())
        {
            return false;
        }
        // A ban holds every site of the source, and asking during it would only be refused, or extend it.
        if (source.refusingUntil().isPresent())
        {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        // Every source's: a higher priority holds back the lower ones of the other sources too.
        List<Turn> turns = service.turns(now);
        for (Turn turn : inTurn(source, turns))
        {
            Subscription subscription = turn.subscription();
            if (!subscription.isEnabled() || parkedSites.isParked(subscription.getSource()))
            {
                continue;
            }
            Optional<String> notReady = source.notReady(subscription.getSource());
            if (notReady.isPresent())
            {
                // Said once: a write transaction every idle round would only hold the library for nothing.
                if (subscription.getFailedSteps() == 0 && !notReady.get().equals(subscription.getLastError()))
                {
                    writeGate.background("the subscription " + subscription.getId(), () -> service.recordProblem(
                            subscription.getId(), subscription.getRevision(), notReady.get(), false, now));
                }
                continue;
            }
            Optional<PlannedStep> step = SubscriptionService.holder(turns, turn).isPresent()
                    ? Optional.empty() : service.plan(subscription, now);
            if (step.isPresent())
            {
                lastStepped.put(source.sourcePrefix(), subscription.getId());
                run(source, step.get(), now);
                wakeAll();
                return true;
            }
            if (subscription.getFailedSteps() == 0 && subscription.getLastError() != null)
            {
                // A problem that counted no failed step (a missing setting, a site's ban) is over, since the
                // subscription is ready and its site asks no wait, and no step is clearing it now: the page would say
                // it until the next one, maybe a whole polling interval away, and it would hold back nothing.
                writeGate.background("the subscription " + subscription.getId(),
                        () -> service.clearProblem(subscription.getId(), subscription.getRevision()));
            }
        }
        return false;
    }

    /**
     * The source's subscriptions in {@link SubscriptionService#ORDER}, but within the same phase and priority from the
     * one after the last stepped, so every subscription of the same importance gets its turn.
     */
    private List<Turn> inTurn(SearchSource source, List<Turn> turns)
    {
        Set<String> sites = source.searchSites().stream().map(SearchSource.SearchSite::key).collect(Collectors.toSet());
        List<Turn> own = turns.stream().filter(turn -> sites.contains(turn.subscription().getSource())).toList();
        Integer last = lastStepped.get(source.sourcePrefix());
        if (last == null)
        {
            return own;
        }
        var ordered = new ArrayList<Turn>(own.size());
        for (int start = 0; start < own.size(); )
        {
            Turn first = own.get(start);
            int end = start;
            while (end < own.size() && own.get(end).phase() == first.phase()
                    && own.get(end).subscription().getPriority() == first.subscription().getPriority())
            {
                end++;
            }
            var group = new ArrayList<>(own.subList(start, end));
            int lastIndex = indexOf(group, last);
            if (lastIndex >= 0)
            {
                Collections.rotate(group, -(lastIndex + 1));
            }
            ordered.addAll(group);
            start = end;
        }
        return ordered;
    }

    private static int indexOf(List<Turn> turns, Integer id)
    {
        for (int i = 0; i < turns.size(); i++)
        {
            if (turns.get(i).subscription().getId().equals(id))
            {
                return i;
            }
        }
        return -1;
    }

    private void run(SearchSource source, PlannedStep planned, LocalDateTime now)
    {
        try
        {
            SearchPage page = source.search(planned.site(), planned.query(), planned.step().after());
            SubscriptionWalk.Transition transition = SubscriptionWalk.after(planned.state(), planned.step(), page,
                    SubscriptionService.positions(source));
            String cursor = transition.cursor() == SubscriptionWalk.CursorField.NONE
                    ? null : source.cursorAfter(planned.site(), planned.step().after(), page.resourceIds());
            // Named, so a request that gives up waiting says what the library was busy with.
            Optional<SubscriptionService.Applied> applied = writeGate.background(
                    "the subscription " + planned.title(),
                    () -> service.applyPage(planned, page, transition, cursor, now));
            if (applied.isEmpty())
            {
                log.info("Subscription {} changed while its page was fetched; the page was dropped",
                        planned.subscriptionId());
                return;
            }
            log.info("Subscription {} ({}): {} {} gallery(s), {} queued", planned.subscriptionId(),
                    planned.title(), planned.step().kind(), applied.get().listed(), applied.get().queued());
            if (applied.get().queued() > 0)
            {
                worker.kick();
            }
        }
        catch (RuntimeException e)
        {
            failed(planned, e, now);
        }
    }

    private void failed(PlannedStep planned, RuntimeException failure, LocalDateTime now)
    {
        // Told by the flag, never by the exception's type: a socket timeout is an InterruptedIOException too.
        if (stopping || Thread.currentThread().isInterrupted())
        {
            log.info("Step of subscription {} ended by the shutdown; it runs again on the next start",
                    planned.subscriptionId());
            return;
        }
        // A writer outside the app, said nothing about the search; the step simply runs again.
        if (failure instanceof LibraryBusyException || SqliteLocks.isLockError(failure))
        {
            log.warn("Subscription {} could not write to the database ({}); its step runs again",
                    planned.subscriptionId(), failure.toString());
            return;
        }
        Optional<RetryLaterException> retryLater = RetryLaterException.find(failure);
        if (retryLater.isPresent())
        {
            Instant until = retryLater.get().retryAt().orElse(Instant.now().plus(DEFAULT_SITE_WAIT));
            parkedSites.park(planned.site(), until);
            log.info("Subscriptions of {} wait until {}: {}", planned.site(), until, retryLater.get().getMessage());
            writeGate.background("the subscription " + planned.title(), () -> service.recordProblem(
                    planned.subscriptionId(), planned.revision(), retryLater.get().getMessage(), false, now));
            return;
        }
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        log.warn("Subscription {} ({}) failed: {}", planned.subscriptionId(), planned.title(), message);
        writeGate.background("the subscription " + planned.title(), () -> service.recordProblem(
                planned.subscriptionId(), planned.revision(), message, true, now));
    }
}

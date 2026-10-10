package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.LibraryBusyException;
import io.github.mocchikon.hentie.config.SqliteLocks;
import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.scrapper.GalleryNotFoundException;
import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>Starting on {@link ApplicationReadyEvent} is the whole resume</b>: a queue row is deleted only at the
 * end, so an item in flight when the app died runs again from the top.
 *
 * <p>One thread on purpose: SQLite has one writer and chapter creation runs series matching, so parallel
 * downloads would gain little and mix up the progress the user reads.
 *
 * <p>Its writes wait for the {@link WriteGate} as long as they must, as on every thread that serves no request, so
 * a busy library slows a download down but never fails it.
 */
@Service
@RequiredArgsConstructor
public class DownloadWorker
{
    private static final Logger log = LoggerFactory.getLogger(DownloadWorker.class);

    /** In case a kick was missed. */
    private static final long IDLE_POLL_MILLIS = 2_000;

    /**
     * How long shutdown waits for the item in flight: enough for its last steps, never for a whole gallery.
     * An item still running after it dies with the JVM and runs again on the next start.
     */
    private static final long STOP_WAIT_MILLIS = 10_000;

    private final DownloadQueueService queueService;
    private final ChapterDownloadService chapterDownloadService;
    private final GalleryImportService importService;
    private final SettingsService settingsService;
    private final AppProperties appProperties;
    private final WriteGate writeGate;

    private final Object monitor = new Object();
    private final AtomicInteger processed = new AtomicInteger();

    private volatile Thread thread;
    private volatile boolean running;
    /**
     * Set by {@link #stop()}, unlike {@link #running}, which is also false while no loop was ever started (the
     * tests drive {@link #processNext()} themselves).
     */
    private volatile boolean stopping;
    private volatile Current current;

    /**
     * Set before any download work starts (unlike {@link #current}), so no in-flight item is ever reported as not
     * running. Written under {@link #abortLock} together with {@link #currentThread}, so an abort never interrupts a
     * thread that has moved on to the next item.
     */
    private volatile Integer currentItemId;
    private Thread currentThread;
    /** The user asked to abort {@link #currentItemId}; it then fails for good, whatever the interrupt broke. */
    private volatile boolean abortRequested;
    private final Object abortLock = new Object();

    // ---- lifecycle ---------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void startOnBoot()
    {
        if (!appProperties.getDownload().isWorkerEnabled())
        {
            return;
        }
        long pending = queueService.pendingCount();
        if (pending > 0)
        {
            log.info("Download queue: resuming with {} pending item(s)", pending);
        }
        start();
    }

    public synchronized void start()
    {
        if (running)
        {
            return;
        }
        stopping = false;
        running = true;
        thread = new Thread(this::loop, "download-worker");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    public synchronized void stop()
    {
        stopping = true;
        running = false;
        kick();
        Thread worker = thread;
        if (worker != null)
        {
            // Not interrupted: the item either finishes or is still queued on the next boot.
            try
            {
                worker.join(STOP_WAIT_MILLIS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    public void kick()
    {
        synchronized (monitor)
        {
            monitor.notifyAll();
        }
    }

    private void loop()
    {
        while (running)
        {
            boolean worked = false;
            try
            {
                worked = processNext();
            }
            catch (RuntimeException e)
            {
                // A bug, but killing the worker for the rest of the session would be worse.
                log.error("Download worker: unexpected error, continuing", e);
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
                monitor.wait(IDLE_POLL_MILLIS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    // ---- one item ----------------------------------------------------------

    /**
     * Public because the integration tests call it directly, which makes the pipeline deterministic.
     *
     * @return false when there was nothing to do (queue empty or paused)
     */
    public boolean processNext()
    {
        if (isPaused())
        {
            return false;
        }
        DownloadQueueItem item = queueService.nextPending().orElse(null);
        if (item == null)
        {
            // The next batch counts from 1 again.
            processed.set(0);
            current = null;
            currentItemId = null;
            return false;
        }

        int index = processed.get() + 1;
        long total = processed.get() + queueService.runnableCount();
        synchronized (abortLock)
        {
            currentItemId = item.getId();
            currentThread = Thread.currentThread();
            abortRequested = false;
        }
        current = new Current(item.getLink(), "Starting");
        try
        {
            // Named, so a request that gives up waiting says what the library was busy with.
            writeGate.background("the download of " + item.getLink(), () -> work(item, index, total));
        }
        finally
        {
            synchronized (abortLock)
            {
                currentItemId = null;
                currentThread = null;
            }
            // An abort that came after the item's last interruptible step must not reach the next item.
            if (abortRequested)
            {
                Thread.interrupted();
            }
            current = null;
        }
        return true;
    }

    /**
     * Stops the item in flight by interrupting its thread, which every step of the pipeline answers (a page fetch,
     * gallery-dl, which is killed, a backoff), and moves it to the Failed list, where it can be retried or removed.
     * The pages that already landed stay, with the chapter {@code PENDING}, as after a crash, so a retry fetches only
     * what is missing.
     *
     * @return the link being aborted, or empty when nothing was running
     */
    public Optional<String> abortCurrent()
    {
        synchronized (abortLock)
        {
            Thread running = currentThread;
            if (currentItemId == null || running == null)
            {
                return Optional.empty();
            }
            abortRequested = true;
            running.interrupt();
        }
        Current now = current;
        return Optional.of(now == null ? "the current download" : now.link());
    }

    private void work(DownloadQueueItem item, int index, long total)
    {
        try
        {
            int chapterId = chapterDownloadService.download(item, phase -> current = new Current(item.getLink(), phase));
            if (queueService.complete(item))
            {
                processed.incrementAndGet();
                log.info("Finished {} -> chapter {} [{}/{}]", item.getLink(), chapterId, index, total);
            }
            else
            {
                // Still pending, so not counted as done too.
                log.info("Finished {} -> chapter {}; its queued link now asks for another download, which runs "
                        + "next", item.getLink(), chapterId);
            }
        }
        catch (RuntimeException e)
        {
            if (abortRequested)
            {
                recordAborted(item);
                return;
            }
            handleFailure(item, e, index, total);
        }
    }

    /** As a permanent failure, so the user decides what happens to it; its own failure was the interrupt's doing. */
    private void recordAborted(DownloadQueueItem item)
    {
        // Cleared first: the interrupt would fail the very writes that record the abort.
        Thread.interrupted();
        Integer chapterId = item.getGalleryId() == null
                ? null : importService.findChapterId(item.getGalleryId()).orElse(null);
        var outcome = queueService.recordFailure(item, "Aborted on request.", chapterId, true,
                appProperties.getDownload().getMaxAttempts());
        if (outcome == DownloadQueueService.FailureOutcome.GAVE_UP)
        {
            chapterDownloadService.discardStaged(chapterId);
            processed.incrementAndGet();
        }
        log.info("Aborted {} on request", item.getLink());
    }

    /**
     * Here rather than on {@link DownloadQueueService} because only the worker knows if the item is running.
     * A running item's staging is left alone: its encoder may be reading a page there, and the full-quality
     * re-download keeps every page there until it publishes. The worker sees the row is gone, stops without
     * finishing the chapter, and discards staging itself.
     */
    public void remove(int id)
    {
        Integer chapterId = queueService.remove(id);
        if (chapterId != null && !Integer.valueOf(id).equals(currentItemId))
        {
            chapterDownloadService.discardStaged(chapterId);
        }
    }

    /**
     * Removes every waiting row and aborts the item in flight; failed rows stay. The running item's staging is
     * discarded by the worker once it stops; its row is gone, so the abort records nothing and it does not land on
     * the Failed list.
     *
     * @return how many rows were removed
     */
    public int clearAll()
    {
        int removed = queueService.clearWaiting(currentItemId);
        abortCurrent();
        return removed;
    }

    /**
     * Backs off before a retry so a briefly failing source is not hammered; the item stays at the head of the
     * queue.
     * <p>
     * <b>Records nothing once {@link #stop()} has run.</b> When it stops waiting, the pools and the database
     * close under an item still running, so its failure most likely says nothing about the link, and counting
     * it could use up the link's last attempt. The row stays as it is, and the next start runs the item again.
     */
    private void handleFailure(DownloadQueueItem item, RuntimeException failure, int index, long total)
    {
        if (stopping)
        {
            log.info("Download of {} ended by the shutdown ({}); it runs again on the next start", item.getLink(),
                    failure.toString());
            return;
        }
        // The worker's writes wait for the lock instead, so this is a writer outside the app or an interrupt:
        // nothing about the link. The row stays as it is, and the item runs again from the top. Without a limit on
        // purpose: the library has to be waited for, and the next item would fail the same way, so counting the
        // attempt or moving on would only spend attempts on a failure no link can cause.
        if (failure instanceof LibraryBusyException || SqliteLocks.isLockError(failure))
        {
            log.warn("Download of {} could not write to the database ({}); it runs again without counting an "
                    + "attempt", item.getLink(), failure.toString());
            backOff(item, appProperties.getDownload().getRetryBackoffMillis());
            return;
        }
        if (RetryLaterException.isIn(failure))
        {
            log.info("Download of {} has to wait ({}); it runs again without counting an attempt", item.getLink(),
                    failure.getMessage());
            backOff(item, appProperties.getDownload().getRetryBackoffMillis());
            return;
        }
        // A subscription's row of a source now refusing every download (a ban it sits out, which this item may just
        // have run into) waits for the ban to end: its failure says nothing about the gallery. nextPending skips it
        // meanwhile. A row the user asked for (claimed by a paste while it ran, too) fails, saying why, and so does a
        // gallery that is gone, which no ban explains.
        var deferredUntil = failure instanceof GalleryNotFoundException
                ? Optional.<Instant>empty() : queueService.deferredUntil(item.getId());
        if (deferredUntil.isPresent())
        {
            log.info("Download of {} waits until {}, when its source is asked again: {}", item.getLink(),
                    deferredUntil.get(), failure.getMessage());
            return;
        }
        boolean permanent = failure instanceof PermanentDownloadException
                || failure instanceof GalleryNotFoundException;
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        // The chapter may already exist; the failed list shows it.
        Integer chapterId = item.getGalleryId() == null
                ? null : importService.findChapterId(item.getGalleryId()).orElse(null);

        var outcome = queueService.recordFailure(item, message, chapterId, permanent,
                appProperties.getDownload().getMaxAttempts());
        if (outcome == DownloadQueueService.FailureOutcome.SUPERSEDED)
        {
            // No backoff: the changed request has not been tried yet.
            log.info("Failed {} [{}/{}]: {}; its queued link now asks for another download, which runs next",
                    item.getLink(), index, total, message);
            return;
        }
        if (outcome == DownloadQueueService.FailureOutcome.GAVE_UP)
        {
            chapterDownloadService.discardStaged(chapterId);
            processed.incrementAndGet();
            log.warn("Failed {} [{}/{}]: {}", item.getLink(), index, total, message);
            return;
        }
        log.warn("Attempt {} failed for {}, retrying: {}", item.getAttempts() + 1, item.getLink(), message);
        backOff(item, appProperties.getDownload().getRetryBackoffMillis() * Math.max(1, item.getAttempts() + 1));
    }

    /** The item is still current while it waits, so an abort can arrive here, after its failure was handled. */
    private void backOff(DownloadQueueItem item, long millis)
    {
        sleep(millis);
        if (abortRequested)
        {
            recordAborted(item);
        }
    }

    private void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    // ---- pause + progress --------------------------------------------------

    public boolean isPaused()
    {
        return settingsService.isDownloadPaused();
    }

    public void setPaused(boolean paused)
    {
        settingsService.setDownloadPaused(paused);
        if (!paused)
        {
            kick();
        }
    }

    public DownloadProgress progress()
    {
        int done = processed.get();
        long pending = queueService.pendingCount();
        // The queue page asks every few seconds, and no source refusing is the usual case: one count, not two.
        long runnable = queueService.anySourceRefusing() ? queueService.runnableCount() : pending;
        Current now = current;
        return new DownloadProgress(done, done + runnable, pending, runnable, queueService.failedCount(),
                now == null ? null : now.link(), now == null ? null : now.phase(), isPaused());
    }

    private record Current(String link, String phase)
    {
    }
}

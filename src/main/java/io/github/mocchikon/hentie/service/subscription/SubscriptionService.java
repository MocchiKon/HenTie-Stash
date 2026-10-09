package io.github.mocchikon.hentie.service.subscription;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.SubscriptionForm;
import io.github.mocchikon.hentie.dto.SubscriptionView;
import io.github.mocchikon.hentie.entity.Subscription;
import io.github.mocchikon.hentie.entity.SubscriptionCheckpoint;
import io.github.mocchikon.hentie.repository.SubscriptionCheckpointRepository;
import io.github.mocchikon.hentie.repository.SubscriptionRepository;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.SearchSource;
import io.github.mocchikon.hentie.scrapper.SearchSource.SearchPage;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlDownloader;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.download.DownloadChoices;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.GalleryImportService;
import io.github.mocchikon.hentie.service.subscription.SubscriptionWalk.Kind;
import io.github.mocchikon.hentie.service.subscription.SubscriptionWalk.Listed;
import io.github.mocchikon.hentie.service.subscription.SubscriptionWalk.State;
import io.github.mocchikon.hentie.service.subscription.SubscriptionWalk.Step;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Subscriptions as the page edits them, and the database half of the runner's steps: which page a subscription needs
 * next, and what the page does once fetched ({@link SubscriptionRunner} does the fetching, outside any transaction).
 * <p>
 * <b>A page is applied in one transaction</b>: its queue rows, the walk moving past it, the counters and the
 * checkpoint commit together or not at all, so a crash at any point repeats at most one page, and a repeated page
 * queues nothing twice. The row is read afresh there, and the page is dropped if the walk it was fetched for has
 * changed meanwhile (the user edited the search, started over, paused or deleted it).
 * <p>
 * <b>Never queued:</b> a gallery in the library, one downloaded before ({@code downloaded_gallery}, which covers a
 * download never finished too), one with a queue row of any kind (a paste's choices stay its own), and one the site
 * marks as blacklisted for the user.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionService
{
    /** One checkpoint an hour is as fine as a re-check's depth, counted in hours, can use. */
    private static final Duration CHECKPOINT_SPACING = Duration.ofHours(1);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_AND_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    private final SubscriptionRepository repository;
    private final SubscriptionCheckpointRepository checkpoints;
    private final DataDownloaderRegistry registry;
    private final DownloadQueueService queueService;
    private final GalleryImportService importService;
    private final ImageCompressionModeService modeService;
    private final SettingsService settingsService;
    private final AppProperties appProperties;

    /** A form field the save refused, worded for the user. */
    @Getter
    public static final class Refused extends RuntimeException
    {
        private final String field;

        public Refused(String field, String message)
        {
            super(message);
            this.field = field;
        }
    }

    // ---- the page -------------------------------------------------------------------------------------------

    /** Starts at Settings' choices, as the Download page does; storableKey turns a deleted mode into None. */
    @Transactional(readOnly = true)
    public SubscriptionForm newForm()
    {
        var form = new SubscriptionForm();
        form.setSource(registry.searchSites().stream().findFirst().map(SearchSource.SearchSite::key).orElse(null));
        form.setCompressionMode(modeService.storableKey(settingsService.getImageCompressionMode()));
        GalleryDlOptions galleryDl = settingsService.getGalleryDlDefaults();
        form.setCookiesBrowser(galleryDl.cookiesBrowser());
        form.setDownloadOriginals(galleryDl.originals());
        form.setRequestDelay(galleryDl.delay());
        return form;
    }

    @Transactional(readOnly = true)
    public Optional<SubscriptionForm> form(int id)
    {
        return repository.findById(id).map(subscription ->
        {
            var form = new SubscriptionForm();
            form.setId(subscription.getId());
            form.setName(subscription.getName());
            form.setSource(subscription.getSource());
            form.setQuery(subscription.getQuery());
            form.setPollMinutes(subscription.getPollMinutes());
            form.setRecheckEveryHours(subscription.getRecheckEveryHours());
            form.setRecheckDepthHours(subscription.getRecheckDepthHours());
            form.setCompressionMode(modeService.storableKey(subscription.getCompressionMode()));
            form.setAvoidDuplicateTitles(subscription.isAvoidDuplicateTitles());
            form.setCookiesBrowser(subscription.getCookiesBrowser());
            form.setDownloadOriginals(subscription.isDownloadOriginals());
            form.setRequestDelay(subscription.getRequestDelay());
            return form;
        });
    }

    /** Whether the site's galleries go through gallery-dl, whose choices the form then shows. */
    public boolean usesGalleryDl(String site)
    {
        return registry.searchSource(site).filter(GalleryDlDownloader.class::isInstance).isPresent();
    }

    /** Sites whose galleries go through gallery-dl, for the form's show-and-hide. */
    public List<String> galleryDlSites()
    {
        return registry.searchSites().stream().map(SearchSource.SearchSite::key).filter(this::usesGalleryDl).toList();
    }

    /**
     * Another site or query starts the walk over: the old one's head and tail mean nothing in the new search. The
     * other choices apply to what is queued from now on.
     *
     * @return the subscription's id
     * @throws Refused                for a value the site cannot use
     * @throws NoSuchElementException when the subscription was deleted meanwhile
     */
    @Transactional
    public int save(SubscriptionForm form, GalleryDlOptions galleryDl)
    {
        String site = StringUtils.strip(form.getSource());
        SearchSource source = registry.searchSource(site)
                .orElseThrow(() -> new Refused("source", "Choose one of the sites offered."));
        String query;
        try
        {
            query = source.normalizedQuery(site, form.getQuery());
        }
        catch (IllegalArgumentException e)
        {
            throw new Refused("query", e.getMessage());
        }
        if (query.length() > 1000)
        {
            throw new Refused("query", "The search must be at most 1000 characters.");
        }
        Optional<String> notReady = source.notReady(site);
        if (notReady.isPresent())
        {
            throw new Refused("source", notReady.get());
        }
        Optional<String> choicesProblem = source.choicesProblem(site, galleryDl);
        if (choicesProblem.isPresent())
        {
            throw new Refused("cookiesBrowser", choicesProblem.get());
        }

        Subscription subscription = form.getId() == null ? new Subscription()
                : repository.findById(form.getId()).orElseThrow(() -> new NoSuchElementException(
                "The subscription was deleted meanwhile."));
        boolean startsOver = subscription.getId() != null
                && (!site.equals(subscription.getSource()) || !query.equals(subscription.getQuery()));
        subscription.setName(StringUtils.trimToNull(form.getName()));
        subscription.setSource(site);
        subscription.setQuery(query);
        subscription.setPollMinutes(form.getPollMinutes());
        subscription.setRecheckEveryHours(form.getRecheckEveryHours());
        subscription.setRecheckDepthHours(form.getRecheckDepthHours());
        subscription.setCompressionMode(modeService.storableKey(form.getCompressionMode()));
        subscription.setAvoidDuplicateTitles(form.isAvoidDuplicateTitles());
        subscription.setCookiesBrowser(galleryDl.cookiesBrowser());
        subscription.setDownloadOriginals(galleryDl.originals());
        subscription.setRequestDelay(galleryDl.delay());
        if (subscription.getId() == null)
        {
            LocalDateTime now = LocalDateTime.now();
            subscription.setCreatedAt(now);
            // The first check lists the newest page at once; recent galleries are re-checked a whole interval later.
            subscription.setLastRecheckedAt(now);
        }
        else if (startsOver)
        {
            resetWalk(subscription);
        }
        return repository.save(subscription).getId();
    }

    /**
     * What it queued stays queued, at a subscription's priority: the foreign key only forgets which one it was.
     *
     * @return the deleted subscription's title; empty when there was none
     */
    @Transactional
    public Optional<String> delete(int id)
    {
        return repository.findById(id).map(subscription ->
        {
            repository.delete(subscription);
            return title(subscription);
        });
    }

    /** Resuming clears a failure's wait: the user asks for it to run. */
    @Transactional
    public boolean setEnabled(int id, boolean enabled)
    {
        return repository.findById(id).map(subscription ->
        {
            subscription.setEnabled(enabled);
            if (enabled)
            {
                clearError(subscription);
            }
            return true;
        }).orElse(false);
    }

    /** The next step is a check, without waiting for the polling interval or a failure's wait. */
    @Transactional
    public boolean checkNow(int id)
    {
        return repository.findById(id).map(subscription ->
        {
            subscription.setLastCheckedAt(null);
            clearError(subscription);
            return true;
        }).orElse(false);
    }

    /** Lists the whole search again; what it finds in the library or the queue is passed over as before. */
    @Transactional
    public boolean startOver(int id)
    {
        return repository.findById(id).map(subscription ->
        {
            resetWalk(subscription);
            return true;
        }).orElse(false);
    }

    private void resetWalk(Subscription subscription)
    {
        subscription.setRevision(subscription.getRevision() + 1);
        apply(subscription, State.EMPTY);
        subscription.setLastCheckedAt(null);
        subscription.setLastRecheckedAt(LocalDateTime.now());
        subscription.setTopSeenAt(null);
        subscription.setResultTotal(null);
        subscription.setQueuedCount(0);
        subscription.setInLibraryCount(0);
        subscription.setDeletedSinceCount(0);
        subscription.setAlreadyQueuedCount(0);
        subscription.setBlacklistedCount(0);
        clearError(subscription);
        checkpoints.deleteAllOf(subscription.getId());
    }

    private static void clearError(Subscription subscription)
    {
        subscription.setLastError(null);
        subscription.setLastErrorAt(null);
        subscription.setFailedSteps(0);
    }

    /** For the queue page: each row names the subscription that queued it. */
    @Transactional(readOnly = true)
    public Map<Integer, String> titlesById()
    {
        var titles = new HashMap<Integer, String>();
        repository.findAll().forEach(subscription -> titles.put(subscription.getId(), title(subscription)));
        return titles;
    }

    @Transactional(readOnly = true)
    public List<SubscriptionView> overview()
    {
        Map<Integer, Long> waiting = queueService.waitingBySubscription();
        Map<Integer, Long> failed = queueService.failedBySubscription();
        Map<String, String> modeNames = modeService.namesByKey();
        LocalDateTime now = LocalDateTime.now();
        return repository.findAllByOrderByIdAsc().stream()
                .map(subscription -> view(subscription, waiting.getOrDefault(subscription.getId(), 0L),
                        failed.getOrDefault(subscription.getId(), 0L), modeNames, now))
                .toList();
    }

    private SubscriptionView view(Subscription subscription, long waiting, long failed, Map<String, String> modeNames,
                                  LocalDateTime now)
    {
        Optional<SearchSource> source = registry.searchSource(subscription.getSource());
        String siteLabel = source.flatMap(s -> s.searchSites().stream()
                        .filter(site -> site.key().equals(subscription.getSource())).findFirst())
                .map(SearchSource.SearchSite::label).orElse(subscription.getSource());
        Status status = status(subscription, source.orElse(null), waiting, failed, now);
        return new SubscriptionView(subscription.getId(), title(subscription), siteLabel, subscription.getQuery(),
                source.map(s -> s.searchPageUrl(subscription.getSource(), subscription.getQuery())).orElse(null),
                subscription.isEnabled(), status.text(), status.kind(),
                subscription.getNewestGalleryId(), pageLink(subscription.getNewestGalleryId(), subscription.getSource()),
                subscription.getOldestGalleryId(), pageLink(subscription.getOldestGalleryId(), subscription.getSource()),
                subscription.getQueuedCount(), subscription.getInLibraryCount(), subscription.getDeletedSinceCount(),
                subscription.getAlreadyQueuedCount(), subscription.getBlacklistedCount(), waiting, failed,
                choices(subscription, modeNames), schedule(subscription));
    }

    /** On the subscription's site: an exhentai search lists galleries e-hentai may hide. */
    private String pageLink(String galleryId, String site)
    {
        return galleryId == null ? null : registry.pageLinkFor(galleryId, site).orElse(null);
    }

    private static String title(Subscription subscription)
    {
        return StringUtils.defaultIfBlank(subscription.getName(), subscription.getQuery());
    }

    private record Status(String text, String kind)
    {
    }

    /** What the subscription waits for, or does next; in the order a cause would hold it up. */
    private Status status(Subscription subscription, SearchSource source, long waiting, long failed,
                          LocalDateTime now)
    {
        AppProperties.Subscriptions limits = appProperties.getSubscriptions();
        if (!subscription.isEnabled())
        {
            return new Status("Paused: it lists nothing, and what it queued still downloads.", "warning");
        }
        if (source == null)
        {
            return new Status("No source offers the site \"" + subscription.getSource() + "\" any more.", "error");
        }
        Optional<String> notReady = source.notReady(subscription.getSource());
        if (notReady.isPresent())
        {
            return new Status(notReady.get(), "error");
        }
        Optional<Instant> cooling = source.refusingUntil();
        if (cooling.isPresent())
        {
            return new Status("The site is not asked until " + when(LocalDateTime.ofInstant(cooling.get(),
                    ZoneId.systemDefault()), now) + ", after a ban or a used-up image limit; its galleries wait in "
                    + "the queue meanwhile.", "warning");
        }
        if (subscription.getLastError() != null)
        {
            LocalDateTime retryAt = SubscriptionWalk.retryAt(subscription.getLastErrorAt(),
                    subscription.getFailedSteps(), subscription.getPollMinutes(), limits.getRetryAfterFailureSeconds());
            return new Status(subscription.getLastError() + (retryAt == null ? ""
                    : " Tried again at " + when(retryAt, now) + "."), "error");
        }
        if (failed >= limits.getFailedLimit())
        {
            return new Status("Stopped listing: " + failed + " of its downloads failed. Retry or remove them in the "
                    + "download queue, and it goes on.", "error");
        }
        boolean full = waiting >= limits.getQueueAhead();
        String listed = listedSoFar(subscription);
        if (subscription.getCatchUpTop() != null)
        {
            return full ? new Status("Waiting for " + waiting + " of its downloads before listing more new galleries.",
                    "ok") : new Status("Listing new galleries.", "ok");
        }
        if (subscription.getOldestGalleryId() != null && !subscription.isReachedEnd())
        {
            return full ? new Status("Waiting for " + waiting + " of its downloads before listing older galleries; "
                    + listed + ".", "ok") : new Status("Listing older galleries; " + listed + ".", "ok");
        }
        if (subscription.getLastCheckedAt() == null)
        {
            return new Status("Starting: the newest galleries are listed first.", "ok");
        }
        LocalDateTime nextCheck = subscription.getLastCheckedAt().plusMinutes(subscription.getPollMinutes());
        if (subscription.getNewestGalleryId() == null)
        {
            return new Status("No gallery matches yet. Next check at " + when(nextCheck, now) + ".", "ok");
        }
        return new Status("Up to date (" + listed + "). Next check at " + when(nextCheck, now) + ".", "ok");
    }

    private static String listedSoFar(Subscription subscription)
    {
        String listed = String.format(Locale.ENGLISH, "%,d", subscription.listedCount());
        return subscription.getResultTotal() == null ? listed + " listed"
                : listed + " of about " + String.format(Locale.ENGLISH, "%,d", subscription.getResultTotal())
                + " listed";
    }

    /** The day is named unless it is today: a wait such as a site's cooldown can run past midnight. */
    public static String when(LocalDateTime at, LocalDateTime now)
    {
        return at.toLocalDate().equals(now.toLocalDate()) ? at.format(TIME) : at.format(DAY_AND_TIME);
    }

    private String choices(Subscription subscription, Map<String, String> modeNames)
    {
        var parts = new ArrayList<String>();
        parts.add("compression: " + modeNames.getOrDefault(subscription.getCompressionMode(), "a mode since deleted"));
        if (subscription.isAvoidDuplicateTitles())
        {
            parts.add("avoiding duplicated titles");
        }
        if (usesGalleryDl(subscription.getSource()))
        {
            parts.add(subscription.getCookiesBrowser() == null ? "no cookies"
                    : "cookies from " + subscription.getCookiesBrowser());
            if (subscription.isDownloadOriginals())
            {
                parts.add("originals");
            }
            parts.add("delay " + subscription.getRequestDelay() + " s");
        }
        return String.join(" · ", parts);
    }

    private static String schedule(Subscription subscription)
    {
        String checks = "new galleries every " + subscription.getPollMinutes() + " min";
        return subscription.getRecheckEveryHours() == 0 ? checks + ", no re-check"
                : checks + ", the last " + subscription.getRecheckDepthHours() + " h re-checked every "
                + subscription.getRecheckEveryHours() + " h";
    }

    // ---- the runner's steps ---------------------------------------------------------------------------------

    /**
     * What the runner fetches for a subscription, decided from a snapshot of its row; applied only while the row
     * still says the same.
     *
     * @param lostSight a check after the walk lost sight of the search (see {@link SubscriptionWalk})
     */
    public record PlannedStep(int subscriptionId, int revision, String title, String site, String query,
                              State state, Step step, boolean lostSight)
    {
    }

    /** @param queued rows inserted; @param listed galleries the page held for this walk */
    public record Applied(int queued, int listed)
    {
    }

    /** Snapshots: the runner holds them across network calls, so they must never be written back. */
    @Transactional(readOnly = true)
    public List<Subscription> of(SearchSource source)
    {
        return repository.findBySourceInOrderByIdAsc(
                source.searchSites().stream().map(SearchSource.SearchSite::key).toList());
    }

    /**
     * The step a subscription needs now, if any: a catch-up under way first, then a due check (a re-check when that is
     * due), then a page of the backfill. Listing beyond a check waits while the subscription has many downloads
     * waiting or failed (see {@code app.subscriptions.*}); checks go on meanwhile, also beside a catch-up that waits,
     * stopping at its top (see {@link SubscriptionWalk}).
     */
    @Transactional(readOnly = true)
    public Optional<PlannedStep> plan(Subscription subscription, LocalDateTime now)
    {
        if (!subscription.isEnabled())
        {
            return Optional.empty();
        }
        SearchSource source = registry.searchSource(subscription.getSource()).orElse(null);
        if (source == null)
        {
            return Optional.empty();
        }
        AppProperties.Subscriptions limits = appProperties.getSubscriptions();
        LocalDateTime retryAt = SubscriptionWalk.retryAt(subscription.getLastErrorAt(), subscription.getFailedSteps(),
                subscription.getPollMinutes(), limits.getRetryAfterFailureSeconds());
        if (retryAt != null && now.isBefore(retryAt))
        {
            return Optional.empty();
        }
        State state = stateOf(subscription);
        if (state.catchingUp() && mayList(subscription, limits))
        {
            return Optional.of(planned(subscription, state,
                    new Step(Kind.CATCH_UP, state.catchUpCursor(), state.catchUpStop(), false), false));
        }
        // A re-check waits for a catch-up to end: it has a stop of its own below the head.
        boolean recheck = state.newest() != null && !state.catchingUp() && SubscriptionWalk.recheckDue(
                subscription.getLastRecheckedAt(), subscription.getRecheckEveryHours(), now);
        if (recheck || SubscriptionWalk.checkDue(subscription.getLastCheckedAt(), subscription.getPollMinutes(), now))
        {
            String stop = state.catchingUp() ? state.catchUpTop()
                    : recheck ? recheckStop(subscription, state, positions(source), now) : state.newest();
            boolean lostSight = state.newest() != null && !state.catchingUp() && SubscriptionWalk.lostSight(
                    subscription.getTopSeenAt(), subscription.getRecheckDepthHours(), now);
            return Optional.of(planned(subscription, state, new Step(Kind.CHECK, null, stop, recheck), lostSight));
        }
        if (state.oldest() != null && !state.reachedEnd() && state.oldestCursor() != null
                && mayList(subscription, limits))
        {
            return Optional.of(planned(subscription, state, new Step(Kind.BACKFILL, state.oldestCursor(), null,
                    false), false));
        }
        return Optional.empty();
    }

    /** Asked only for a step that lists beyond a check: a subscription with nothing more to list costs no count. */
    private boolean mayList(Subscription subscription, AppProperties.Subscriptions limits)
    {
        return queueService.waitingFor(subscription.getId()) < limits.getQueueAhead()
                && queueService.failedFor(subscription.getId()) < limits.getFailedLimit();
    }

    private static PlannedStep planned(Subscription subscription, State state, Step step, boolean lostSight)
    {
        return new PlannedStep(subscription.getId(), subscription.getRevision(), title(subscription),
                subscription.getSource(), subscription.getQuery(), state, step, lostSight);
    }

    /**
     * The head as it was {@code recheck_depth_hours} before the re-check was due
     * ({@link SubscriptionWalk#recheckCutoff}); the oldest checkpoint when there is none that old: the subscription is
     * younger, or the walk lost sight of the search since. Never above the head, or the galleries between them would
     * be passed over.
     */
    private String recheckStop(Subscription subscription, State state, SubscriptionWalk.Positions positions,
                               LocalDateTime now)
    {
        LocalDateTime cutoff = SubscriptionWalk.recheckCutoff(subscription.getLastRecheckedAt(),
                subscription.getRecheckEveryHours(), subscription.getRecheckDepthHours(), now);
        String stop = checkpoints.findFirstBySubscriptionIdAndRecordedAtLessThanEqualOrderByRecordedAtDesc(
                        subscription.getId(), cutoff)
                .or(() -> checkpoints.findFirstBySubscriptionIdOrderByRecordedAtAsc(subscription.getId()))
                .map(SubscriptionCheckpoint::getGalleryId)
                .orElse(state.newest());
        return SubscriptionWalk.older(state.newest(), stop, positions);
    }

    /**
     * One fetched page, in one transaction with its queue rows.
     *
     * @param cursor where the walk continues below the page, when the transition needs one
     * @return what it queued; empty when the page was dropped because the subscription changed meanwhile
     */
    @Transactional
    public Optional<Applied> applyPage(PlannedStep planned, SearchPage page, SubscriptionWalk.Transition transition,
                                       String cursor, LocalDateTime now)
    {
        Subscription subscription = repository.findById(planned.subscriptionId()).orElse(null);
        if (subscription == null || subscription.getRevision() != planned.revision() || !subscription.isEnabled()
                || !stateOf(subscription).equals(planned.state()))
        {
            return Optional.empty();
        }
        SearchSource source = registry.searchSource(subscription.getSource()).orElse(null);
        if (source == null)
        {
            return Optional.empty();
        }
        int queued = handle(subscription, source, transition.listed(), page.blacklisted());

        State next = transition.next(cursor);
        apply(subscription, next);
        // A catch-up's top was the newest gallery when a check last saw the top, not when the catch-up ended, which
        // may be days later: a checkpoint that late would send the next re-check down the whole catch-up again.
        LocalDateTime checkpointAt = planned.step().kind() == Kind.CATCH_UP && subscription.getTopSeenAt() != null
                ? subscription.getTopSeenAt() : now;
        if (planned.step().kind() == Kind.CHECK)
        {
            subscription.setLastCheckedAt(now);
            if (planned.step().recheck())
            {
                subscription.setLastRecheckedAt(now);
            }
            if (transition.sawTop())
            {
                subscription.setTopSeenAt(now);
            }
        }
        if (page.total() != null)
        {
            subscription.setResultTotal(Math.toIntExact(Math.min(page.total(), Integer.MAX_VALUE)));
        }
        clearError(subscription);
        if (planned.lostSight())
        {
            // Everything this check finds above the old head is listed now, in one go, and mostly uploaded long before.
            // A re-check reaching below it would list all of it again; this check's own stop was taken already.
            checkpoints.deleteAllOf(subscription.getId());
        }
        if (transition.newestGrew() && next.newest() != null)
        {
            recordCheckpoint(subscription.getId(), next.newest(), checkpointAt);
        }
        if (planned.step().recheck())
        {
            // Older ones are of no use to a re-check any more: the one it stopped at is the oldest it needs.
            checkpoints.findFirstBySubscriptionIdAndRecordedAtLessThanEqualOrderByRecordedAtDesc(subscription.getId(),
                            now.minusHours(subscription.getRecheckDepthHours()))
                    .ifPresent(stop -> checkpoints.deleteRecordedBefore(subscription.getId(), stop.getRecordedAt()));
        }
        return Optional.of(new Applied(queued, transition.listed().size()));
    }

    /** Sorts every gallery into what becomes of it, queues the rest, and counts each the first time it is listed. */
    private int handle(Subscription subscription, SearchSource source, List<Listed> listed, Set<String> blacklisted)
    {
        Map<String, Listed> byGalleryId = new LinkedHashMap<>();
        listed.forEach(gallery -> byGalleryId.put(source.galleryId(gallery.resourceId()), gallery));
        Map<String, GalleryImportService.Holding> holdings = importService.holdings(byGalleryId.keySet());
        var links = new LinkedHashMap<String, String>();
        int queued = 0;
        for (var entry : byGalleryId.entrySet())
        {
            String galleryId = entry.getKey();
            Listed gallery = entry.getValue();
            GalleryImportService.Holding holding = holdings.get(galleryId);
            if (blacklisted.contains(gallery.resourceId()))
            {
                count(gallery, () -> subscription.setBlacklistedCount(subscription.getBlacklistedCount() + 1));
            }
            else if (holding == GalleryImportService.Holding.DELETED)
            {
                count(gallery, () -> subscription.setDeletedSinceCount(subscription.getDeletedSinceCount() + 1));
            }
            else if (holding != null)
            {
                // In the library, or downloaded once and never finished: either way in downloaded_gallery or a chapter.
                count(gallery, () -> subscription.setInLibraryCount(subscription.getInLibraryCount() + 1));
            }
            else
            {
                links.put(galleryId, source.link(gallery.resourceId()));
            }
        }
        Set<String> inserted = links.isEmpty() ? Set.of()
                : queueService.queueForSubscription(subscription.getId(), links.values(), choices(subscription));
        for (String galleryId : links.keySet())
        {
            Listed gallery = byGalleryId.get(galleryId);
            if (inserted.contains(galleryId))
            {
                queued++;
                // A gallery listed again (a re-check) counts once it is queued: it newly matched the search.
                subscription.setQueuedCount(subscription.getQueuedCount() + 1);
            }
            else
            {
                count(gallery, () -> subscription.setAlreadyQueuedCount(subscription.getAlreadyQueuedCount() + 1));
            }
        }
        return queued;
    }

    private static void count(Listed gallery, Runnable increment)
    {
        if (gallery.firstSeen())
        {
            increment.run();
        }
    }

    /** As stored; a delay edited into something gallery-dl cannot read falls back to Settings' default. */
    private DownloadChoices choices(Subscription subscription)
    {
        GalleryDlOptions galleryDl;
        try
        {
            galleryDl = new GalleryDlOptions(subscription.getCookiesBrowser(), subscription.isDownloadOriginals(),
                    subscription.getRequestDelay());
        }
        catch (IllegalArgumentException e)
        {
            galleryDl = new GalleryDlOptions(subscription.getCookiesBrowser(), subscription.isDownloadOriginals(),
                    settingsService.getGalleryDlDefaults().delay());
        }
        return new DownloadChoices(subscription.getCompressionMode(), subscription.isAvoidDuplicateTitles(), galleryDl);
    }

    private void recordCheckpoint(int subscriptionId, String newest, LocalDateTime at)
    {
        boolean due = checkpoints.findFirstBySubscriptionIdOrderByRecordedAtDesc(subscriptionId)
                .map(latest -> !latest.getRecordedAt().isAfter(at.minus(CHECKPOINT_SPACING)))
                .orElse(true);
        if (due)
        {
            var checkpoint = new SubscriptionCheckpoint();
            checkpoint.setSubscriptionId(subscriptionId);
            checkpoint.setRecordedAt(at);
            checkpoint.setGalleryId(newest);
            checkpoints.save(checkpoint);
        }
    }

    /**
     * A failed step, said on the page. {@code counted} failures back the subscription off (see
     * {@link SubscriptionWalk#retryAt}); the others (a site cooling down, a missing setting) only say why it waits,
     * and are written only when the reason changes. They never replace a counted failure: its time is when the backoff
     * runs from, and its text is why the subscription waits once they are over (the page names them anyway).
     */
    @Transactional
    public void recordProblem(int subscriptionId, int revision, String message, boolean counted, LocalDateTime now)
    {
        Subscription subscription = repository.findById(subscriptionId).orElse(null);
        if (subscription == null || subscription.getRevision() != revision)
        {
            return;
        }
        String text = StringUtils.abbreviate(StringUtils.defaultIfBlank(message, "Listing failed."), 2000);
        if (!counted && (subscription.getFailedSteps() > 0 || text.equals(subscription.getLastError())))
        {
            return;
        }
        subscription.setLastError(text);
        subscription.setLastErrorAt(now);
        if (counted)
        {
            subscription.setFailedSteps(subscription.getFailedSteps() + 1);
        }
    }

    /**
     * Once the cause of a problem that counted no failed step is over: nothing else clears it before the next step
     * succeeds, which may be a whole polling interval away. A counted failure stays, since its backoff runs from it.
     */
    @Transactional
    public void clearProblem(int subscriptionId, int revision)
    {
        Subscription subscription = repository.findById(subscriptionId).orElse(null);
        if (subscription != null && subscription.getRevision() == revision && subscription.getFailedSteps() == 0)
        {
            clearError(subscription);
        }
    }

    // ---- the walk's columns ---------------------------------------------------------------------------------

    static State stateOf(Subscription subscription)
    {
        return new State(subscription.getNewestGalleryId(), subscription.getOldestGalleryId(),
                subscription.getOldestCursor(), subscription.isReachedEnd(), subscription.getCatchUpTop(),
                subscription.getCatchUpCursor(), subscription.getCatchUpStop());
    }

    private static void apply(Subscription subscription, State state)
    {
        subscription.setNewestGalleryId(state.newest());
        subscription.setOldestGalleryId(state.oldest());
        subscription.setOldestCursor(state.oldestCursor());
        subscription.setReachedEnd(state.reachedEnd());
        subscription.setCatchUpTop(state.catchUpTop());
        subscription.setCatchUpCursor(state.catchUpCursor());
        subscription.setCatchUpStop(state.catchUpStop());
    }

    /** Gallery ids are the source's prefix and its resource id; positions are the source's own. */
    static SubscriptionWalk.Positions positions(SearchSource source)
    {
        String prefix = source.galleryId("");
        return new SubscriptionWalk.Positions()
        {
            @Override
            public String galleryId(String resourceId)
            {
                return source.galleryId(resourceId);
            }

            @Override
            public long of(String galleryId)
            {
                return source.position(StringUtils.removeStart(galleryId, prefix));
            }
        };
    }
}

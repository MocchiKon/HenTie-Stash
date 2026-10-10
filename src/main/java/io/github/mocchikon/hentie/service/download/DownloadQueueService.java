package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.ResourceLink;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Every method is its own short transaction, so the long download between them never holds the SQLite
 * write lock.
 */
@Service
@RequiredArgsConstructor
public class DownloadQueueService
{
    /**
     * Rows per list on the queue page; the counts stay exact. Deliberately no pagination: the queue empties
     * itself, the visible rows are the ones about to run, bulk actions cover every row, and page state on a
     * self-refreshing list of disappearing rows would mean nothing.
     */
    public static final int LIST_LIMIT = 5;

    /** What the user asked for first, then what subscriptions found, each oldest first. */
    private static final Sort PENDING_ORDER = Sort.by("priority", "id");

    private final DownloadQueueRepository repository;
    private final DataDownloaderRegistry registry;
    private final GalleryImportService importService;
    private final ImageService imageService;
    private final ImageCompressionModeService modeService;

    /**
     * A link already queued is never duplicated: a failed row is reset to pending. Either way the row takes
     * this paste's choices, since the form is how the user says how the link should be downloaded, and becomes the
     * user's: a row a subscription queued moves up among the rows the user asked for.
     */
    @Transactional
    public EnqueueResult enqueue(List<String> links, DownloadChoices choices)
    {
        // The one seam every enqueue passes, so an invalid key (the "Custom" sentinel, a hand-made POST)
        // becomes NONE here instead of being stored.
        String mode = modeService.storableKey(choices.compressionMode());
        boolean avoidDuplicateTitles = choices.avoidDuplicateTitles();
        GalleryDlOptions galleryDl = choices.galleryDl();
        int accepted = 0;
        int requeued = 0;
        int alreadyQueued = 0;
        int rejected = 0;
        for (String link : links)
        {
            Optional<ResourceLink> parsed = registry.parse(link);
            if (parsed.isEmpty())
            {
                rejected++;
                continue;
            }
            // Keyed on the gallery id, not the link text: "mock:12", "MOCK:12" and "mock: 12" are one
            // gallery, but `link` is UNIQUE under BINARY collation and would give each spelling a row.
            String galleryId = parsed.get().galleryId();
            String trimmedLink = StringUtils.trimToEmpty(link);
            Optional<DownloadQueueItem> existing = repository.findFirstByGalleryIdOrderByIdAsc(galleryId);
            if (existing.isPresent())
            {
                DownloadQueueItem item = existing.get();
                if (item.getError() != null)
                {
                    item.setError(null);
                    item.setAttempts(0);
                    // A paste asks for an ordinary, strict download with this paste's choices.
                    item.setIgnoreImageErrors(false);
                    item.setCompressionMode(mode);
                    item.setAvoidDuplicateTitles(avoidDuplicateTitles);
                    item.setReplacePages(false);
                    setGalleryDlOptions(item, galleryDl);
                    claim(item);
                    repository.save(item);
                    requeued++;
                }
                else
                {
                    // A waiting row takes this paste's choices too; a running attempt keeps its own.
                    if (!mode.equals(item.getCompressionMode())
                            || item.isAvoidDuplicateTitles() != avoidDuplicateTitles || item.isReplacePages()
                            || !StoredGalleryDl.of(item).equals(StoredGalleryDl.of(galleryDl)) || !isClaimed(item))
                    {
                        item.setCompressionMode(mode);
                        item.setAvoidDuplicateTitles(avoidDuplicateTitles);
                        item.setReplacePages(false);
                        setGalleryDlOptions(item, galleryDl);
                        claim(item);
                        repository.save(item);
                    }
                    // Counted, or the page would say "Nothing to queue" about a link about to download.
                    alreadyQueued++;
                }
                continue;
            }
            var item = new DownloadQueueItem();
            // Trimmed, so the row holds what the registry parsed.
            item.setLink(trimmedLink);
            item.setGalleryId(galleryId);
            item.setCompressionMode(mode);
            item.setAvoidDuplicateTitles(avoidDuplicateTitles);
            setGalleryDlOptions(item, galleryDl);
            item.setQueuedAt(LocalDateTime.now());
            repository.save(item);
            accepted++;
        }
        return new EnqueueResult(accepted, requeued, alreadyQueued, rejected);
    }

    /**
     * Queues the galleries a subscription found and nobody queued yet, at {@link DownloadQueueItem#FROM_SUBSCRIPTION}.
     * <b>An existing row is never touched</b>, pending or failed: a paste chose its own way to download the gallery,
     * and a failed row waits for the user's decision on the Failed list.
     * <p>
     * Part of the subscription's page transaction (MANDATORY), so the rows commit together with the walk moving
     * past them, or neither does.
     *
     * @param links the source's own link for each gallery
     * @return the gallery ids queued; the others had a row already
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<String> queueForSubscription(int subscriptionId, Collection<String> links, DownloadChoices choices)
    {
        String mode = modeService.storableKey(choices.compressionMode());
        var queued = new HashSet<String>();
        for (String link : links)
        {
            Optional<ResourceLink> parsed = registry.parse(link);
            if (parsed.isEmpty())
            {
                continue;
            }
            String galleryId = parsed.get().galleryId();
            String trimmedLink = StringUtils.trimToEmpty(link);
            if (repository.existsByGalleryIdOrLink(galleryId, trimmedLink))
            {
                continue;
            }
            var item = new DownloadQueueItem();
            item.setLink(trimmedLink);
            item.setGalleryId(galleryId);
            item.setCompressionMode(mode);
            item.setAvoidDuplicateTitles(choices.avoidDuplicateTitles());
            setGalleryDlOptions(item, choices.galleryDl());
            item.setPriority(DownloadQueueItem.FROM_SUBSCRIPTION);
            item.setSubscriptionId(subscriptionId);
            item.setQueuedAt(LocalDateTime.now());
            repository.save(item);
            queued.add(galleryId);
        }
        return queued;
    }

    /** The user's from now on: their request outranks the subscription that found the gallery. */
    private static void claim(DownloadQueueItem item)
    {
        item.setPriority(DownloadQueueItem.ASKED_FOR);
        item.setSubscriptionId(null);
    }

    private static boolean isClaimed(DownloadQueueItem item)
    {
        return item.getPriority() == DownloadQueueItem.ASKED_FOR && item.getSubscriptionId() == null;
    }

    /**
     * The row's gallery-dl choices.
     *
     * @throws PermanentDownloadException when the row's delay is none gallery-dl reads (a hand-edited row): a retry
     *                                    keeps it, a re-paste replaces it
     */
    public static GalleryDlOptions galleryDlOptions(DownloadQueueItem item)
    {
        try
        {
            return new GalleryDlOptions(item.getCookiesBrowser(), item.isDownloadOriginals(), item.getRequestDelay());
        }
        catch (IllegalArgumentException e)
        {
            throw new PermanentDownloadException(e.getMessage() + "; paste the link again to choose one.", e);
        }
    }

    /**
     * The row's gallery-dl choices as stored, compared as one value wherever a change matters, so a new choice
     * cannot be left out of one comparison. Not {@link GalleryDlOptions}: a hand-edited delay must not throw here.
     */
    private record StoredGalleryDl(String cookiesBrowser, boolean originals, String delay)
    {
        static StoredGalleryDl of(DownloadQueueItem item)
        {
            return new StoredGalleryDl(item.getCookiesBrowser(), item.isDownloadOriginals(), item.getRequestDelay());
        }

        static StoredGalleryDl of(GalleryDlOptions options)
        {
            return new StoredGalleryDl(options.cookiesBrowser(), options.originals(), options.delay());
        }
    }

    private static void setGalleryDlOptions(DownloadQueueItem item, GalleryDlOptions options)
    {
        item.setCookiesBrowser(options.cookiesBrowser());
        item.setDownloadOriginals(options.originals());
        item.setRequestDelay(options.delay());
    }

    /**
     * Always uncompressed and strict: a page that failed to arrive should be a visible failure, not a page
     * silently left compressed. The gallery's existing row is reused rather than joined by a second. Its
     * gallery-dl choices are Settings' defaults: the paste that once chose others is long gone.
     */
    @Transactional
    public void enqueueFullQuality(String link, String galleryId, GalleryDlOptions galleryDl)
    {
        DownloadQueueItem item = repository.findFirstByGalleryIdOrderByIdAsc(galleryId).orElseGet(() ->
        {
            var created = new DownloadQueueItem();
            created.setLink(link);
            created.setGalleryId(galleryId);
            created.setQueuedAt(LocalDateTime.now());
            return created;
        });
        item.setError(null);
        item.setAttempts(0);
        item.setIgnoreImageErrors(false);
        item.setCompressionMode(BuiltInCompressionMode.NONE.getKey());
        item.setAvoidDuplicateTitles(false);
        item.setReplacePages(true);
        setGalleryDlOptions(item, galleryDl);
        claim(item);
        repository.save(item);
    }

    /**
     * Skips a subscription's rows of a source refusing every download meanwhile (a ban it sits out): they wait for it
     * to end instead of failing one after the other. A row the user asked for still runs and fails at once, saying
     * why: the user is there to read it and decide.
     */
    @Transactional(readOnly = true)
    public Optional<DownloadQueueItem> nextPending()
    {
        return repository.findBy(pendingExceptSubscriptionRowsOf(registry.refusals()),
                query -> query.sortBy(PENDING_ORDER).first());
    }

    /**
     * Until when a row waits for its source instead of running (see {@link #nextPending}); empty for a row that runs.
     * The worker, the queue page and {@link #nextPending} agree on it through here.
     */
    public Optional<Instant> deferredUntil(DownloadQueueItem item)
    {
        return item.isFromSubscription() ? registry.refusingUntil(item.getGalleryId()) : Optional.empty();
    }

    /**
     * As {@link #deferredUntil(DownloadQueueItem)}, for the row as it is now: a paste may have claimed it while it ran,
     * and then it fails for the user to see. Empty for a row removed meanwhile.
     */
    @Transactional(readOnly = true)
    public Optional<Instant> deferredUntil(int itemId)
    {
        return repository.findById(itemId).flatMap(this::deferredUntil);
    }

    /**
     * Pending rows that run now, without those {@link #nextPending} skips: a batch's progress and the queue page's
     * refresh would otherwise wait for rows that cannot run until a ban ends.
     */
    @Transactional(readOnly = true)
    public long runnableCount()
    {
        return repository.count(pendingExceptSubscriptionRowsOf(registry.refusals()));
    }

    /**
     * Whether {@link #runnableCount} can differ from {@link #pendingCount}: only while a source refuses, so a caller
     * needing both counts once otherwise.
     */
    public boolean anySourceRefusing()
    {
        return !registry.refusals().isEmpty();
    }

    /** The prefix is compared with {@code substring}, as the duplicate-title check does: a filter on each row. */
    private static Specification<DownloadQueueItem> pendingExceptSubscriptionRowsOf(Set<String> prefixes)
    {
        return (root, query, cb) ->
        {
            if (prefixes.isEmpty())
            {
                return cb.isNull(root.get("error"));
            }
            var galleryId = root.<String>get("galleryId");
            var ofRefusingSource = prefixes.stream()
                    .map(prefix -> cb.equal(cb.substring(galleryId, 1, prefix.length()), prefix))
                    .toArray(jakarta.persistence.criteria.Predicate[]::new);
            return cb.and(cb.isNull(root.get("error")), cb.or(
                    cb.notEqual(root.get("priority"), DownloadQueueItem.FROM_SUBSCRIPTION),
                    cb.isNull(galleryId),
                    cb.not(cb.or(ofRefusingSource))));
        };
    }

    /**
     * Keeps the row when it was rewritten (a paste or {@link #enqueueFullQuality}) while running: deleting
     * it would drop that new request unseen.
     *
     * @return false when the row stays queued for the changed request
     */
    @Transactional
    public boolean complete(DownloadQueueItem finished)
    {
        Optional<DownloadQueueItem> row = repository.findById(finished.getId());
        if (row.isPresent() && requestChanged(row.get(), finished))
        {
            return false;
        }
        row.ifPresent(repository::delete);
        return true;
    }

    /** Field by field rather than a version column: a row rewritten to the same choices asks for nothing new. */
    private static boolean requestChanged(DownloadQueueItem row, DownloadQueueItem attempted)
    {
        return row.isReplacePages() != attempted.isReplacePages()
                || row.isIgnoreImageErrors() != attempted.isIgnoreImageErrors()
                || row.isAvoidDuplicateTitles() != attempted.isAvoidDuplicateTitles()
                || !Objects.equals(row.getCompressionMode(), attempted.getCompressionMode())
                || !StoredGalleryDl.of(row).equals(StoredGalleryDl.of(attempted));
    }

    public enum FailureOutcome
    {
        RETRY,
        /** Out of attempts, permanent, or removed meanwhile. */
        GAVE_UP,
        /** The row now asks for another download, not yet tried; nothing was counted. */
        SUPERSEDED
    }

    /**
     * Attempts are persisted, so a link that fails after every restart still runs out of them. A row whose
     * request changed meanwhile records nothing: the failure belonged to the old request.
     */
    @Transactional
    public FailureOutcome recordFailure(DownloadQueueItem attempted, String error, Integer chapterId,
                                        boolean permanent, int maxAttempts)
    {
        DownloadQueueItem item = repository.findById(attempted.getId()).orElse(null);
        if (item == null)
        {
            return FailureOutcome.GAVE_UP;
        }
        if (requestChanged(item, attempted))
        {
            return FailureOutcome.SUPERSEDED;
        }
        item.setAttempts(item.getAttempts() + 1);
        if (chapterId != null)
        {
            item.setChapterId(chapterId);
        }
        boolean giveUp = permanent || item.getAttempts() >= maxAttempts;
        if (giveUp)
        {
            item.setError(StringUtils.abbreviate(StringUtils.defaultIfBlank(error, "Download failed."), 2000));
        }
        repository.save(item);
        return giveUp ? FailureOutcome.GAVE_UP : FailureOutcome.RETRY;
    }

    @Transactional(readOnly = true)
    public long pendingCount()
    {
        return repository.countByErrorIsNull();
    }

    @Transactional(readOnly = true)
    public long failedCount()
    {
        return repository.countByErrorIsNotNull();
    }

    /** In the order the worker takes them, so the list shows what runs next. */
    @Transactional(readOnly = true)
    public List<DownloadQueueItem> pending()
    {
        return repository.findByErrorIsNullOrderByPriorityAscIdAsc(Limit.of(LIST_LIMIT));
    }

    @Transactional(readOnly = true)
    public List<DownloadQueueItem> failed()
    {
        return repository.findByErrorIsNotNullOrderByPriorityAscIdAsc(Limit.of(LIST_LIMIT));
    }

    @Transactional(readOnly = true)
    public long waitingFor(int subscriptionId)
    {
        return repository.countBySubscriptionIdAndErrorIsNull(subscriptionId);
    }

    @Transactional(readOnly = true)
    public long failedFor(int subscriptionId)
    {
        return repository.countBySubscriptionIdAndErrorIsNotNull(subscriptionId);
    }

    /** Waiting rows per subscription id, every subscription in one query. */
    @Transactional(readOnly = true)
    public Map<Integer, Long> waitingBySubscription()
    {
        return byId(repository.waitingBySubscription());
    }

    @Transactional(readOnly = true)
    public Map<Integer, Long> failedBySubscription()
    {
        return byId(repository.failedBySubscription());
    }

    private static Map<Integer, Long> byId(List<DownloadQueueRepository.SubscriptionRows> rows)
    {
        var counts = new HashMap<Integer, Long>();
        rows.forEach(row -> counts.put(row.getSubscriptionId(), row.getRowCount()));
        return counts;
    }

    /** The flag is assigned, not OR-ed, so a plain retry undoes an earlier lenient one. */
    @Transactional
    public int retryFailed(boolean ignoreImageErrors)
    {
        return repository.retryFailed(ignoreImageErrors);
    }

    /**
     * The duplicate-title check is kept unless {@code allowDuplicateTitle} clears it, and never turned on:
     * that is a decision made on the paste form.
     */
    @Transactional
    public boolean retry(int id, boolean ignoreImageErrors, boolean allowDuplicateTitle)
    {
        DownloadQueueItem item = repository.findById(id).orElse(null);
        if (item == null)
        {
            return false;
        }
        item.setError(null);
        item.setAttempts(0);
        item.setIgnoreImageErrors(ignoreImageErrors);
        if (allowDuplicateTitle)
        {
            item.setAvoidDuplicateTitles(false);
        }
        repository.save(item);
        return true;
    }

    @Transactional
    public int deleteFailed()
    {
        repository.failedChapterIds().forEach(imageService::discardStagedPages);
        return repository.deleteFailed();
    }

    /**
     * Every waiting row, the running one included; failed rows stay for the user to retry or remove. Subscriptions'
     * rows go too, and nothing remembers them, so a subscription's next check may queue its new galleries again.
     *
     * @param runningId the row the worker is running, whose staging it discards itself (see {@link #remove})
     */
    @Transactional
    public int clearWaiting(Integer runningId)
    {
        repository.waitingChapterIdsExcept(runningId == null ? -1 : runningId)
                .forEach(imageService::discardStagedPages);
        return repository.deleteWaiting();
    }

    @Transactional(readOnly = true)
    public boolean exists(int id)
    {
        return repository.existsById(id);
    }

    /**
     * Falls back on the <b>gallery id</b>: {@code chapter_id} is written only by {@link #recordFailure}, so it
     * is null for a row that never failed, the one most likely to have pages in staging. Discarding is left
     * to the caller, which knows whether the worker is running this item.
     *
     * @return the chapter whose staging to discard, or null when there was no row or no chapter
     */
    @Transactional
    public Integer remove(int id)
    {
        DownloadQueueItem item = repository.findById(id).orElse(null);
        if (item == null)
        {
            return null;
        }
        Integer chapterId = stagingChapterId(item);
        repository.delete(item);
        return chapterId;
    }

    private Integer stagingChapterId(DownloadQueueItem item)
    {
        if (item.getChapterId() != null)
        {
            return item.getChapterId();
        }
        return item.getGalleryId() == null
                ? null : importService.findChapterId(item.getGalleryId()).orElse(null);
    }

    /** Every link lands in exactly one bucket. */
    public record EnqueueResult(int accepted, int requeued, int alreadyQueued, int rejected)
    {
        public static final EnqueueResult NONE = new EnqueueResult(0, 0, 0, 0);

        public int queued()
        {
            return accepted + requeued + alreadyQueued;
        }

        public EnqueueResult plus(EnqueueResult other)
        {
            return new EnqueueResult(accepted + other.accepted, requeued + other.requeued,
                    alreadyQueued + other.alreadyQueued, rejected + other.rejected);
        }
    }
}

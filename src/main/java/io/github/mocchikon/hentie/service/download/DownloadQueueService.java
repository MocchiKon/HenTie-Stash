package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.ResourceLink;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    public static final int LIST_LIMIT = 200;

    private final DownloadQueueRepository repository;
    private final DataDownloaderRegistry registry;
    private final GalleryImportService importService;
    private final ImageService imageService;
    private final ImageCompressionModeService modeService;

    /**
     * A link already queued is never duplicated: a failed row is reset to pending. Either way the row takes
     * this paste's choices, since the form is how the user says how the link should be downloaded.
     */
    @Transactional
    public EnqueueResult enqueue(List<String> links, String compressionMode, boolean avoidDuplicateTitles)
    {
        // The one seam every enqueue passes, so an invalid key (the "Custom" sentinel, a hand-made POST)
        // becomes NONE here instead of being stored.
        String mode = modeService.storableKey(compressionMode);
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
                    repository.save(item);
                    requeued++;
                }
                else
                {
                    // A waiting row takes this paste's choices too; a running attempt keeps its own.
                    if (!mode.equals(item.getCompressionMode())
                            || item.isAvoidDuplicateTitles() != avoidDuplicateTitles || item.isReplacePages())
                    {
                        item.setCompressionMode(mode);
                        item.setAvoidDuplicateTitles(avoidDuplicateTitles);
                        item.setReplacePages(false);
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
            item.setQueuedAt(LocalDateTime.now());
            repository.save(item);
            accepted++;
        }
        return new EnqueueResult(accepted, requeued, alreadyQueued, rejected);
    }

    /**
     * Always uncompressed and strict: a page that failed to arrive should be a visible failure, not a page
     * silently left compressed. The gallery's existing row is reused rather than joined by a second.
     */
    @Transactional
    public void enqueueFullQuality(String link, String galleryId)
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
        repository.save(item);
    }

    @Transactional(readOnly = true)
    public Optional<DownloadQueueItem> nextPending()
    {
        return repository.findFirstByErrorIsNullOrderByIdAsc();
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
                || !Objects.equals(row.getCompressionMode(), attempted.getCompressionMode());
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

    @Transactional(readOnly = true)
    public List<DownloadQueueItem> pending()
    {
        return repository.findByErrorIsNullOrderByIdAsc(Limit.of(LIST_LIMIT));
    }

    @Transactional(readOnly = true)
    public List<DownloadQueueItem> failed()
    {
        return repository.findByErrorIsNotNullOrderByIdAsc(Limit.of(LIST_LIMIT));
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

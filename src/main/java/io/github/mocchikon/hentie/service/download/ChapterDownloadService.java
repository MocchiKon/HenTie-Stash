package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.scrapper.PageDownloader;
import io.github.mocchikon.hentie.scrapper.ResourceLink;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDl;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlDownloader;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Everything that happens for one queued link. <b>Every step can re-run from the top, and that is all of
 * crash recovery</b>: the queue row lives until the end, so after a crash the next boot runs this again and
 * each step sees what the previous run already did. {@link DownloadWorker} owns the queue row.
 *
 * <p>Only a {@code PENDING} chapter (a download this app started and never finished) gets its absent pages
 * fetched. Any other chapter with images keeps them as they are, and nothing on disk is overwritten, except
 * by a full-quality re-download the user asks for.
 *
 * <p>Not transactional: it spans minutes of network and disk work, and holding SQLite's single write lock
 * that long would block the app.
 */
@Service
@RequiredArgsConstructor
public class ChapterDownloadService
{
    private static final Logger log = LoggerFactory.getLogger(ChapterDownloadService.class);

    private final DataDownloaderRegistry registry;
    private final GalleryImportService importService;
    private final DownloadQueueService queueService;
    private final ImageService imageService;
    private final ChapterService chapterService;
    private final ImagePostProcessor imagePostProcessor;
    private final AppProperties appProperties;

    /**
     * What an in-process retry may inherit from staging. The folder is stored, not looked up again, because
     * the automatic choice follows the mode and the RAM disk's free space, so a retry could get an empty
     * folder. The compressed count is carried because inherited pages are not compressed again, so the
     * attempt that publishes them cannot count them itself.
     */
    private record StagedRun(int chapterId, String compressionMode, Path dir, int compressedPages)
    {
        boolean continues(int chapterId, String compressionMode)
        {
            return this.chapterId == chapterId && Objects.equals(this.compressionMode, compressionMode);
        }

        StagedRun plusCompressed(int pages)
        {
            return new StagedRun(chapterId, compressionMode, dir, compressedPages + pages);
        }
    }

    /**
     * The staging this process filled, or null when nothing in it is ours. Only such pages are known to be
     * whole: a page from a killed process may be torn, so a run's first attempt discards staging. Cleared
     * on a failed staging write, a publish or a discard.
     * <p>
     * The mode is part of it because inherited pages are not compressed again, so a mode changed between
     * attempts would publish a gallery half in each mode.
     * <p>
     * No locking: one thread works one item at a time. {@code volatile} only so a restart on another thread
     * sees the current value.
     */
    private volatile StagedRun stagedByThisProcess;

    /**
     * @param phase told each step's name, only for the queue page
     * @return the chapter downloaded into
     * @throws PermanentDownloadException when retrying cannot help (unknown link, missing gallery,
     *                                    metadata that cannot become a chapter)
     * @throws UncheckedIOException on a transient failure, so the worker retries the item
     */
    public int download(DownloadQueueItem item, Consumer<String> phase)
    {
        ResourceLink link = registry.parse(item.getLink())
                .orElseThrow(() -> new PermanentDownloadException(
                        "No data source recognizes the link " + item.getLink()));

        // --- 1. metadata only ---------------------------------------------------------------------
        phase.accept("Downloading metadata");
        log.info("Downloading metadata for {}", link.galleryId());
        GalleryData data = link.downloader().downloadGalleryInfo(link.resourceId());

        // --- 2 + 3. save the chapter unless this gallery is already in the library -----------------
        GalleryImportService.ExistingChapter existing = importService.findChapter(link.galleryId()).orElse(null);
        int chapterId;
        DownloadStatus status;
        if (existing != null)
        {
            log.info("Skipping metadata save for {} - already in the database as chapter {}",
                    link.galleryId(), existing.id());
            chapterId = existing.id();
            status = existing.downloadStatus();
        }
        else
        {
            // A re-download with its chapter gone has nothing to replace; importing afresh was not asked for.
            if (item.isReplacePages())
            {
                throw new PermanentDownloadException("The chapter of " + link.galleryId()
                        + " no longer exists, so there are no pages to download again in full quality.");
            }
            // Only a new gallery is checked, so a re-run of one already here can always finish.
            if (item.isAvoidDuplicateTitles())
            {
                importService.rejectTitleFromOtherSources(data, link);
            }
            chapterId = importService.importChapter(data, link.galleryId());
            status = DownloadStatus.PENDING;
        }

        // --- 4. remember that this gallery has been downloaded ------------------------------------
        importService.recordDownloaded(link.galleryId(), chapterId);

        // --- 5. what, if anything, still has to be fetched? ---------------------------------------
        // A non-PENDING chapter with images keeps them whatever the source says: comparing counts would put
        // back pages the user deleted on purpose.
        int pageCount = data.pages();
        // A re-download replaces only the pages the chapter has, and only canonically named ones, so a page
        // the user keeps under another name is not taken for the source's.
        Set<Integer> onDisk;
        Set<Integer> replacing;
        if (item.isReplacePages())
        {
            var numbers = imageService.pageNumbersForReplacing(chapterId);
            onDisk = numbers.all();
            replacing = numbers.canonical();
        }
        else
        {
            onDisk = imageService.pageNumbersOnDisk(chapterId);
            replacing = Set.of();
        }
        boolean fillGaps = status == DownloadStatus.PENDING || onDisk.isEmpty();
        if (!fillGaps && !item.isReplacePages())
        {
            log.info("Skipping image download for chapter {} ({}) - {} page(s) already on disk, download "
                    + "status {}", chapterId, link.galleryId(), onDisk.size(), status);
            // These pages may never have been through this pipeline, so page_num/disk_size may still be 0.
            chapterService.syncImageStats(chapterId);
            return chapterId;
        }
        // An empty page list must not reach markSuccessful below: an empty SUCCESSFUL chapter can never be
        // fixed by re-queueing. Transient, because a source may serve metadata before its images are ready;
        // if it truly has none, the item runs out of attempts and shows in the Failed list.
        if (pageCount == 0)
        {
            throw new UncheckedIOException(new IOException(
                    "The source lists no pages for " + link.galleryId()));
        }
        List<Integer> missing = new ArrayList<>();
        for (int page = 1; page <= pageCount; page++)
        {
            if ((fillGaps && !onDisk.contains(page)) || replacing.contains(page))
            {
                missing.add(page);
            }
        }
        if (missing.isEmpty() && item.isReplacePages())
        {
            // Finishing quietly would leave a re-download button that can never do anything; say why instead.
            throw new PermanentDownloadException("None of the pages of chapter " + chapterId + " is one of the "
                    + pageCount + " the source lists, named the way this app names them (1.jpg, 2.jpg, ...), "
                    + "so none could be downloaded again in full quality.");
        }
        if (missing.isEmpty())
        {
            log.info("Skipping image download for chapter {} ({}) - all {} page(s) already on disk",
                    chapterId, link.galleryId(), pageCount);
            chapterService.syncImageStats(chapterId);
            markSuccessful(chapterId, status);
            return chapterId;
        }

        // Checked before fetching too: publishReplacing would be refused anyway, a sweep holds the lock for
        // hours, and giving up discards whatever was staged.
        if (item.isReplacePages() && imagePostProcessor.isRunInProgress())
        {
            throw compressionRunInProgress(chapterId);
        }

        // --- 6. fetch the missing pages into staging ----------------------------------------------
        phase.accept("Downloading images");
        // A killed run may have left a torn page, so the first attempt discards staging. A retry in the same
        // process keeps what it fetched (so a failure on page 199 does not restart from page 1), unless the
        // compression mode changed.
        StagedRun previous = stagedByThisProcess;
        if (previous == null || !previous.continues(chapterId, item.getCompressionMode()))
        {
            imageService.discardStagedPages(chapterId);
            previous = new StagedRun(chapterId, item.getCompressionMode(),
                    imageService.stagingDir(chapterId, imagePostProcessor.compresses(item.getCompressionMode())), 0);
        }
        stagedByThisProcess = previous;
        Path staging = previous.dir();
        Set<Integer> staged = imageService.stagedPageNumbers(staging);

        log.info("Downloading {} image(s) for chapter {} ({}){}", missing.size() - staged.size(), chapterId,
                link.galleryId(), onDisk.isEmpty() ? "" : " - " + onDisk.size() + " already on disk");
        int skipped;
        int compressedPages = previous.compressedPages();
        var fetch = new Fetch(link, item, chapterId, missing, staged, staging, phase);
        // Each page is compressed as it lands, overlapping with the download. The run must close on every
        // exit: an encoder still writing while staging is discarded or published would lose a page.
        var processing = imagePostProcessor.start(chapterId, item.getCompressionMode());
        try (processing)
        {
            skipped = switch (link.downloader())
            {
                case PageDownloader source -> fetchPageByPage(source, data, fetch, processing);
                case GalleryDlDownloader source -> fetchWithGalleryDl(source, fetch, processing);
                default -> throw new PermanentDownloadException("The source of " + link.galleryId()
                        + " fetches its pages in no way this app knows.");
            };
        }
        finally
        {
            // After the close, so the count is complete; on failure too, since the next attempt inherits these
            // pages. Not when a failed write or a discard has already cleared the record.
            compressedPages += processing.compressedPages();
            if (stagedByThisProcess == previous)
            {
                stagedByThisProcess = previous.plusCompressed(processing.compressedPages());
            }
        }
        if (skipped > 0)
        {
            log.warn("Finished downloading {} of {} image(s) for chapter {} ({}) - {} could not be fetched "
                            + "and were skipped on request", missing.size() - skipped, missing.size(), chapterId,
                    link.galleryId(), skipped);
        }
        else
        {
            log.info("Finished downloading {} image(s) for chapter {} ({})", missing.size(), chapterId,
                    link.galleryId());
        }
        // A lenient run that got no page at all would leave a SUCCESSFUL chapter without pages, which
        // re-queueing could never fix.
        if (onDisk.isEmpty() && imageService.stagedPageNumbers(staging).isEmpty())
        {
            throw new UncheckedIOException(new IOException("No page of " + link.galleryId() + " could be downloaded"));
        }

        stopIfInterrupted(link);
        // Removed from the queue during the download: publishing a partial set and marking it SUCCESSFUL
        // could never be undone. Checked right before the publish, so only the publish itself is uncovered.
        if (!queueService.exists(item.getId()))
        {
            discardStaged(chapterId);
            throw new PermanentDownloadException("Download of " + link.galleryId()
                    + " was cancelled - its queue item was removed");
        }
        // For a chapter delete path that forgets the queue row: nothing would ever clean up these pages.
        if (!Objects.equals(importService.findChapterId(link.galleryId()).orElse(null), chapterId))
        {
            discardStaged(chapterId);
            throw new PermanentDownloadException("Download of " + link.galleryId()
                    + " was cancelled - its chapter was deleted");
        }

        // --- 7. publish in one move ---------------------------------------------------------------
        phase.accept("Publishing images");
        // Before the first page lands, so a crash while publishing leaves something the next run finishes:
        // only a PENDING chapter gets its absent pages fetched. A re-download's queue row repeats the whole
        // replacement instead.
        if (!item.isReplacePages() && status != DownloadStatus.PENDING)
        {
            chapterService.setDownloadStatus(chapterId, DownloadStatus.PENDING);
            status = DownloadStatus.PENDING;
        }
        // Before the publish too: nothing rebuilds the mode from the files, and a rerun with nothing left to
        // compress would never record it. A mode ahead of its pages is put right by a full-quality
        // re-download; a missing one never is.
        recordCompression(chapterId, item, compressedPages);
        int published;
        try
        {
            published = item.isReplacePages()
                    ? publishReplacing(chapterId, staging, missing, skipped > 0)
                    : imageService.publishStagedPages(chapterId, staging);
            stagedByThisProcess = null;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Could not publish the downloaded pages of chapter " + chapterId, e);
        }
        log.info("Finished publishing images for chapter {} ({}) - {} page(s) published",
                chapterId, link.galleryId(), published);

        chapterService.syncImageStats(chapterId);
        // Also after a lenient run: its missing pages are on purpose, so re-queueing must be a no-op.
        markSuccessful(chapterId, status);
        return chapterId;
    }

    /** What both ways of fetching need to know about this attempt. */
    private record Fetch(ResourceLink link, DownloadQueueItem item, int chapterId, List<Integer> missing,
                         Set<Integer> staged, Path staging, Consumer<String> phase)
    {
        void reportProgress(int done)
        {
            phase.accept("Downloading images (" + done + " of " + missing.size() + ")");
        }
    }

    /** @return how many pages were skipped (lenient mode only) */
    private int fetchPageByPage(PageDownloader source, GalleryData data, Fetch fetch, ImagePostProcessor.Run processing)
    {
        List<URI> pages = new ArrayList<>(data.getPageUrls() == null ? List.<URI>of() : data.getPageUrls());
        int skipped = 0;
        int done = (int) fetch.missing().stream().filter(fetch.staged()::contains).count();
        for (int page : fetch.missing())
        {
            if (fetch.staged().contains(page))
            {
                continue;
            }
            fetch.reportProgress(done++);
            // Not every source blocks in a way an interrupt ends (the mock reads files).
            stopIfInterrupted(fetch.link());

            URI url = pages.get(page - 1);
            byte[] bytes;
            try
            {
                bytes = fetchPage(fetch.link(), source, url, page);
            }
            catch (IOException e)
            {
                // An interrupt is no missing page: skipping would fail every page after it the same way.
                if (!fetch.item().isIgnoreImageErrors() || Thread.currentThread().isInterrupted())
                {
                    // The chapter stays PENDING; publishing the rest would leave a gap nothing fills.
                    throw new UncheckedIOException("Could not download page " + page + " of "
                            + fetch.link().galleryId() + " from " + url, e);
                }
                skipped++;
                log.warn("Ignoring page {} of {} - {}", page, fetch.link().galleryId(), e.toString());
                continue;
            }
            try
            {
                // Numbered by source position, so a skipped page leaves a gap a later run can fill.
                processing.page(imageService.stagePage(fetch.staging(), page, source.pageExtension(url), bytes));
            }
            catch (IOException e)
            {
                // A failed write is never skipped, even in lenient mode: a full staging disk would skip
                // every page and still mark the chapter SUCCESSFUL. The page may be torn, so the next
                // attempt must discard staging.
                stagedByThisProcess = null;
                throw new UncheckedIOException("Could not stage page " + page + " of " + fetch.link().galleryId()
                        + " for chapter " + fetch.chapterId(), e);
            }
        }
        return skipped;
    }

    /**
     * gallery-dl writes into a folder of its own inside staging: on the same filesystem, so a page is moved into
     * staging by a rename, and a gallery-dl left running by a killed app writes only where nothing reads.
     * Discarding staging removes it with the rest.
     *
     * @return how many pages were skipped (lenient mode only)
     */
    private int fetchWithGalleryDl(GalleryDlDownloader source, Fetch fetch, ImagePostProcessor.Run processing)
    {
        SortedSet<Integer> wanted = new TreeSet<>(fetch.missing());
        wanted.removeAll(fetch.staged());
        if (wanted.isEmpty())
        {
            return 0;
        }
        GalleryDlOptions options = DownloadQueueService.galleryDlOptions(fetch.item());
        String galleryId = fetch.link().galleryId();
        Path runFolder = fetch.staging().resolve(".gallery-dl-" + UUID.randomUUID());
        int before = fetch.missing().size() - wanted.size();
        var arrived = new AtomicInteger();
        GalleryDl.Outcome outcome;
        try
        {
            outcome = source.downloadPages(fetch.link().resourceId(), options, wanted, runFolder, (page, file) ->
            {
                Path stagedPage;
                try
                {
                    stagedPage = imageService.stageFile(fetch.staging(), page, file);
                }
                catch (IOException e)
                {
                    throw new StagingFailed(page, e);
                }
                processing.page(stagedPage);
                fetch.reportProgress(before + arrived.incrementAndGet());
            });
        }
        catch (StagingFailed e)
        {
            // Never skipped, even in lenient mode: the next page would fail the same way.
            stagedByThisProcess = null;
            throw new UncheckedIOException("Could not stage page " + e.page + " of " + galleryId + " for chapter "
                    + fetch.chapterId(), e.getCause() instanceof IOException io ? io : e);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Could not download the pages of " + galleryId + ": " + e.getMessage(), e);
        }
        finally
        {
            ImageService.deleteRecursively(runFolder);
        }

        // Where the chapter page links to: a gallery fetched from exhentai may be one e-hentai hides.
        if (!outcome.received().isEmpty())
        {
            chapterService.setSourceSite(fetch.chapterId(), outcome.site());
        }
        SortedSet<Integer> failed = new TreeSet<>(wanted);
        failed.removeAll(outcome.received());
        if (failed.isEmpty())
        {
            return 0;
        }
        String why = outcome.problem() == null ? "" : " - " + outcome.problem();
        // A failed write is never skipped either: a full staging disk would skip every page.
        if (!fetch.item().isIgnoreImageErrors() || outcome.writeFailed())
        {
            // The pages that arrived stay staged, so the next attempt asks only for the rest.
            throw new UncheckedIOException(new IOException("Could not download page(s) " + GalleryDl.rangeSpec(failed)
                    + " of " + galleryId + why));
        }
        log.warn("Ignoring page(s) {} of {}{}", GalleryDl.rangeSpec(failed), galleryId, why);
        return failed.size();
    }

    /** A page gallery-dl delivered that could not be moved into staging; told apart from gallery-dl's failures. */
    private static final class StagingFailed extends IOException
    {
        private final int page;

        StagingFailed(int page, IOException cause)
        {
            super(cause.getMessage(), cause);
            this.page = page;
        }
    }

    /**
     * Takes the Image Compression run lock, unlike other downloads, because it overwrites and deletes
     * published pages a run may be encoding. Refused rather than waited for, since a sweep holds the lock
     * for hours. Reading the folder and clearing the mode happen under the same lock, so no run can compress
     * a page in between.
     *
     * @param skippedAny a skipped page stays compressed, so the mode is kept
     */
    private int publishReplacing(int chapterId, Path staging, List<Integer> fetched, boolean skippedAny)
            throws IOException
    {
        try
        {
            return imagePostProcessor.exclusively(() ->
            {
                try
                {
                    boolean fullQualityNow = !skippedAny && !imageService.encodedPageSurvives(chapterId, fetched);
                    int published = imageService.publishReplacingPages(chapterId, staging);
                    if (fullQualityNow)
                    {
                        clearCompressionMode(chapterId);
                    }
                    return published;
                }
                catch (IOException e)
                {
                    throw new UncheckedIOException(e);
                }
            });
        }
        catch (UncheckedIOException e)
        {
            throw e.getCause();
        }
        catch (ImageCompressionService.RunInProgress busy)
        {
            throw compressionRunInProgress(chapterId);
        }
    }

    /** Does not fail the item: the pages are published, and a retry would fetch the whole gallery for one column. */
    private void clearCompressionMode(int chapterId)
    {
        try
        {
            chapterService.setCompressionMode(chapterId, null);
        }
        catch (RuntimeException e)
        {
            log.warn("Replaced the pages of chapter {} in full quality, but could not record that it is no longer "
                    + "compressed", chapterId, e);
        }
    }

    private static UncheckedIOException compressionRunInProgress(int chapterId)
    {
        return new UncheckedIOException(new IOException("An Image Compression run is in progress, so the pages "
                + "of chapter " + chapterId + " cannot be replaced yet - retry once it has finished."));
    }

    /** A re-download clears the mode instead, in {@link #publishReplacing}. */
    private void recordCompression(int chapterId, DownloadQueueItem item, int compressedPages)
    {
        if (compressedPages > 0)
        {
            chapterService.setCompressionMode(chapterId, item.getCompressionMode());
        }
    }

    /**
     * "Not {@code SUCCESSFUL}" rather than "is {@code PENDING}": a {@code NONE} chapter whose pages a
     * full-quality re-download replaced is a finished download too.
     */
    private void markSuccessful(int chapterId, DownloadStatus status)
    {
        if (status != DownloadStatus.SUCCESSFUL)
        {
            chapterService.setDownloadStatus(chapterId, DownloadStatus.SUCCESSFUL);
        }
    }

    /**
     * Fetches again after a failure, so one dropped connection does not fail the attempt; failing it would
     * back off for seconds and, once the attempts are used up, leave the whole gallery in the Failed list.
     * An interrupt is not retried. It is told by the thread's flag, not the exception's type: a socket timeout
     * is an {@link InterruptedIOException} too.
     */
    private byte[] fetchPage(ResourceLink link, PageDownloader source, URI url, int page) throws IOException
    {
        int retries = Math.max(0, appProperties.getDownload().getPageRetries());
        for (int retry = 1; ; retry++)
        {
            try
            {
                return source.downloadPage(url);
            }
            catch (IOException e)
            {
                if (retry > retries || Thread.currentThread().isInterrupted())
                {
                    throw e;
                }
                log.info("Could not download page {} of {} ({}); trying again ({}/{})", page, link.galleryId(),
                        e.toString(), retry, retries);
                pause(appProperties.getDownload().getPageRetryBackoffMillis());
            }
        }
    }

    /** An aborted download stops before its next page and never publishes. */
    private static void stopIfInterrupted(ResourceLink link)
    {
        if (Thread.currentThread().isInterrupted())
        {
            throw new UncheckedIOException(new InterruptedIOException("Download of " + link.galleryId()
                    + " was interrupted"));
        }
    }

    private static void pause(long millis) throws InterruptedIOException
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to fetch a page again");
        }
    }

    public void discardStaged(Integer chapterId)
    {
        stagedByThisProcess = null;
        if (chapterId != null)
        {
            imageService.discardStagedPages(chapterId);
        }
    }
}

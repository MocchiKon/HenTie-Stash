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
import io.github.mocchikon.hentie.service.ImageDirectory;
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
import java.util.concurrent.atomic.AtomicReference;
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
 * <p><b>A page lands in the chapter's folder as soon as it is whole</b>, so a crash, an abort or a failed attempt
 * loses only the pages in flight: the next attempt fetches what is still absent. Only the full-quality
 * re-download keeps its pages back until every one has arrived.
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
     * What an in-process retry of a full-quality re-download may inherit from staging, where it keeps its pages until
     * the end. The folder is stored, not looked up again, because the automatic choice follows the RAM disk's free
     * space, so a retry could get an empty folder.
     */
    private record StagedRun(int chapterId, Path dir)
    {
    }

    /**
     * The staging this process filled, or null when nothing in it is ours. Only such pages are known to be
     * whole: a page from a killed process may be torn, so a run's first attempt discards staging. Cleared
     * on a failed staging write, a publish or a discard.
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
        String recordedMode;
        if (existing != null)
        {
            log.info("Skipping metadata save for {} - already in the database as chapter {}",
                    link.galleryId(), existing.id());
            chapterId = existing.id();
            status = existing.downloadStatus();
            recordedMode = existing.compressionMode();
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
            recordedMode = null;
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

        // --- 6. fetch the missing pages -----------------------------------------------------------
        phase.accept("Downloading images");
        var attempt = new Attempt(item, link, data, chapterId, status, onDisk, missing, phase);
        return item.isReplacePages() ? replacePages(attempt) : fillIn(attempt, recordedMode);
    }

    /** One attempt at a queue item, from the moment it is known which pages it fetches. */
    private record Attempt(DownloadQueueItem item, ResourceLink link, GalleryData data, int chapterId,
                           DownloadStatus status, Set<Integer> onDisk, List<Integer> missing, Consumer<String> phase)
    {
    }

    /**
     * An ordinary download. A page stored as fetched goes straight into the chapter's folder; one to compress waits
     * in staging only until its encoder is done. So nothing in staging outlives the attempt.
     *
     * @param recordedMode the compression mode the chapter records before this attempt
     */
    private int fillIn(Attempt attempt, String recordedMode)
    {
        int chapterId = attempt.chapterId();
        // Before the first page lands: only a PENDING chapter gets its absent pages fetched, so the pages an
        // attempt cut short leaves behind are completed by the next one. A chapter filled from empty included.
        if (attempt.status() != DownloadStatus.PENDING)
        {
            chapterService.setDownloadStatus(chapterId, DownloadStatus.PENDING);
        }
        // Nothing staged is reused: what a killed process left there may be torn.
        discardStaged(chapterId);

        String mode = attempt.item().getCompressionMode();
        var encoded = new EncodedPages(chapterId);
        Optional<ImagePostProcessor.Run> processing = imagePostProcessor.start(chapterId, mode, encoded);
        // Before any page is re-encoded: nothing rebuilds it from the files, so a crash after a re-encoded page
        // landed would show that page as full quality for good. Taken back if no page was re-encoded after all.
        boolean modeAhead = processing.isPresent() && !Objects.equals(recordedMode, mode);
        if (modeAhead)
        {
            chapterService.setCompressionMode(chapterId, mode);
        }
        Runnable takeBackUnusedMode = () ->
        {
            if (modeAhead && encoded.reEncoded() == 0)
            {
                chapterService.restoreCompressionMode(chapterId, mode, recordedMode);
            }
        };

        Path staging = imageService.stagingDir(chapterId, processing.isPresent());
        Destination destination = processing.isPresent()
                ? new ThroughEncoder(staging, processing.get(), encoded) : new ChapterFolder(chapterId);
        var fetch = new Fetch(attempt, Set.of(), staging, destination);
        log.info("Downloading {} image(s) for chapter {} ({}){}", attempt.missing().size(), chapterId,
                attempt.link().galleryId(), attempt.onDisk().isEmpty() ? ""
                        : " - " + attempt.onDisk().size() + " already on disk");
        boolean finished = false;
        try
        {
            int skipped;
            // Each page is compressed as it arrives, overlapping with the download. The run must close on every
            // exit: an encoder still writing while staging is discarded would lose a page.
            ImagePostProcessor.Run run = processing.orElse(null);
            try (run)
            {
                skipped = fetchPages(fetch);
            }
            stopIfInterrupted(attempt.link());
            stopIfCancelled(attempt);
            PageNotSaved notMovedIn = encoded.failure();
            if (notMovedIn != null)
            {
                throw notSaved(fetch, notMovedIn);
            }
            logFinished(attempt, skipped);
            // A lenient run that got no page at all would leave a SUCCESSFUL chapter without pages, which
            // re-queueing could never fix.
            if (imageService.pageNumbersOnDisk(chapterId).isEmpty())
            {
                throw new UncheckedIOException(new IOException("No page of " + attempt.link().galleryId()
                        + " could be downloaded"));
            }

            takeBackUnusedMode.run();
            chapterService.syncImageStats(chapterId);
            // Also after a lenient run: its missing pages are on purpose, so re-queueing must be a no-op.
            markSuccessful(chapterId, DownloadStatus.PENDING);
            finished = true;
            return chapterId;
        }
        finally
        {
            // After the run closed, so no encoder writes there any more.
            discardStaged(chapterId);
            if (!finished)
            {
                afterFailedAttempt(attempt, takeBackUnusedMode);
            }
        }
    }

    /**
     * Best effort, since the attempt fails anyway and its own failure is what the worker must see. Pages that landed
     * after their chapter was deleted are removed, as nothing else ever would; otherwise the pages that landed get
     * their stats, and a mode recorded for pages never re-encoded is taken back. An abort's interrupt is set aside
     * meanwhile, as it would fail these writes.
     */
    private void afterFailedAttempt(Attempt attempt, Runnable takeBackUnusedMode)
    {
        boolean interrupted = Thread.interrupted();
        try
        {
            if (!chapterExists(attempt))
            {
                imageService.deleteAll(attempt.chapterId());
                return;
            }
            takeBackUnusedMode.run();
            chapterService.syncImageStats(attempt.chapterId());
        }
        catch (RuntimeException e)
        {
            log.warn("Could not tidy up chapter {} after a failed download of {}: {}", attempt.chapterId(),
                    attempt.link().galleryId(), e.toString());
        }
        finally
        {
            if (interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * A full-quality re-download, always uncompressed: nothing is replaced before every original has arrived, so
     * its pages wait in staging until the end. A retry in the same process keeps what an earlier attempt staged.
     */
    private int replacePages(Attempt attempt)
    {
        int chapterId = attempt.chapterId();
        // A killed run may have left a torn page, so the first attempt discards staging. A retry in the same
        // process keeps what it fetched (so a failure on page 199 does not restart from page 1).
        StagedRun previous = stagedByThisProcess;
        if (previous == null || previous.chapterId() != chapterId)
        {
            imageService.discardStagedPages(chapterId);
            previous = new StagedRun(chapterId, imageService.stagingDir(chapterId, false));
        }
        stagedByThisProcess = previous;
        Path staging = previous.dir();
        Set<Integer> staged = imageService.stagedPageNumbers(staging);

        log.info("Downloading {} image(s) for chapter {} ({})", attempt.missing().size() - staged.size(), chapterId,
                attempt.link().galleryId());
        int skipped;
        try
        {
            skipped = fetchPages(new Fetch(attempt, staged, staging, new Staging(staging)));
            logFinished(attempt, skipped);
            // A lenient run that got no page at all would leave a SUCCESSFUL chapter without pages, which
            // re-queueing could never fix.
            if (attempt.onDisk().isEmpty() && imageService.stagedPageNumbers(staging).isEmpty())
            {
                throw new UncheckedIOException(new IOException("No page of " + attempt.link().galleryId()
                        + " could be downloaded"));
            }
            stopIfInterrupted(attempt.link());
            // Checked right before the publish too, so only the publish itself is uncovered.
            stopIfCancelled(attempt);
        }
        catch (Cancelled cancelled)
        {
            discardStaged(chapterId);
            throw cancelled;
        }

        attempt.phase().accept("Publishing images");
        int published;
        try
        {
            published = publishReplacing(chapterId, staging, attempt.missing(), skipped > 0);
            stagedByThisProcess = null;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Could not publish the downloaded pages of chapter " + chapterId, e);
        }
        log.info("Finished publishing images for chapter {} ({}) - {} page(s) published",
                chapterId, attempt.link().galleryId(), published);

        chapterService.syncImageStats(chapterId);
        // Also after a lenient run: its missing pages are on purpose, so re-queueing must be a no-op.
        markSuccessful(chapterId, attempt.status());
        return chapterId;
    }

    private static void logFinished(Attempt attempt, int skipped)
    {
        if (skipped > 0)
        {
            log.warn("Finished downloading {} of {} image(s) for chapter {} ({}) - {} could not be fetched "
                            + "and were skipped on request", attempt.missing().size() - skipped,
                    attempt.missing().size(), attempt.chapterId(), attempt.link().galleryId(), skipped);
        }
        else
        {
            log.info("Finished downloading {} image(s) for chapter {} ({})", attempt.missing().size(),
                    attempt.chapterId(), attempt.link().galleryId());
        }
    }

    // ---- where a fetched page goes -------------------------------------------------------------------------

    private interface Destination
    {
        /** A page fetched into memory. */
        void accept(int page, String extension, byte[] bytes) throws IOException;

        /** A whole page gallery-dl wrote into a folder of its own, which may be moved away from there. */
        void accept(int page, Path file) throws IOException;
    }

    /** A full-quality re-download's pages, kept back until every one has arrived. */
    private final class Staging implements Destination
    {
        private final Path dir;

        Staging(Path dir)
        {
            this.dir = dir;
        }

        @Override
        public void accept(int page, String extension, byte[] bytes) throws IOException
        {
            imageService.stagePage(dir, page, extension, bytes);
        }

        @Override
        public void accept(int page, Path file) throws IOException
        {
            imageService.stageFile(dir, page, file);
        }
    }

    /** Pages stored as fetched. */
    private final class ChapterFolder implements Destination
    {
        private final int chapterId;

        ChapterFolder(int chapterId)
        {
            this.chapterId = chapterId;
        }

        @Override
        public void accept(int page, String extension, byte[] bytes) throws IOException
        {
            imageService.landPage(chapterId, page, extension, bytes);
        }

        @Override
        public void accept(int page, Path file) throws IOException
        {
            imageService.landFile(chapterId, page, file);
        }
    }

    /**
     * Pages to compress: staged for the encoder, which hands each to {@link EncodedPages}. A page that could not
     * move into the chapter's folder stops the download at the next page, which would most likely fail the same
     * way.
     */
    private final class ThroughEncoder implements Destination
    {
        private final Path staging;
        private final ImagePostProcessor.Run run;
        private final EncodedPages encoded;

        ThroughEncoder(Path staging, ImagePostProcessor.Run run, EncodedPages encoded)
        {
            this.staging = staging;
            this.run = run;
            this.encoded = encoded;
        }

        @Override
        public void accept(int page, String extension, byte[] bytes) throws IOException
        {
            stopIfOneFailed();
            run.page(imageService.stagePage(staging, page, extension, bytes));
        }

        @Override
        public void accept(int page, Path file) throws IOException
        {
            stopIfOneFailed();
            run.page(imageService.stageFile(staging, page, file));
        }

        private void stopIfOneFailed() throws PageNotSaved
        {
            PageNotSaved failed = encoded.failure();
            if (failed != null)
            {
                throw failed;
            }
        }
    }

    /**
     * Moves each page into the chapter's folder the moment its encoder is done, on the encoder's thread, re-encoded
     * or kept as it was. A failure waits for the download's own thread, the only one that may fail the attempt.
     */
    private final class EncodedPages implements ImagePostProcessor.Processed
    {
        private final int chapterId;
        private final AtomicInteger reEncoded = new AtomicInteger();
        /** The first page that could not move in. Later pages still try: each one that lands is kept. */
        private final AtomicReference<PageNotSaved> failure = new AtomicReference<>();

        EncodedPages(int chapterId)
        {
            this.chapterId = chapterId;
        }

        @Override
        public void page(Path file, boolean wasReEncoded)
        {
            try
            {
                imageService.landStagedPage(chapterId, file);
                if (wasReEncoded)
                {
                    reEncoded.incrementAndGet();
                }
            }
            catch (IOException | RuntimeException e)
            {
                failure.compareAndSet(null, PageNotSaved.of(ImageDirectory.pageNumber(file.getFileName().toString()),
                        e instanceof IOException io ? io : new IOException(e)));
            }
        }

        /** Re-encoded pages that landed; complete once the run is closed. */
        int reEncoded()
        {
            return reEncoded.get();
        }

        PageNotSaved failure()
        {
            return failure.get();
        }
    }

    /**
     * A page that could not be written into staging or the chapter's folder. Never a page to skip, even in lenient
     * mode: a full disk would skip every page and still mark the chapter {@code SUCCESSFUL}. Told apart from
     * gallery-dl's own failures, which come back the same way.
     */
    private static final class PageNotSaved extends IOException
    {
        private final int page;

        private PageNotSaved(int page, IOException cause)
        {
            super(cause.getMessage(), cause);
            this.page = page;
        }

        /** {@code failure} itself when it already is one, about an earlier page than {@code page}. */
        static PageNotSaved of(int page, IOException failure)
        {
            return failure instanceof PageNotSaved notSaved ? notSaved : new PageNotSaved(page, failure);
        }
    }

    private static UncheckedIOException notSaved(Fetch fetch, PageNotSaved e)
    {
        return new UncheckedIOException("Could not save page " + e.page + " of " + fetch.link().galleryId()
                + " for chapter " + fetch.chapterId(), e);
    }

    // ---- fetching ------------------------------------------------------------------------------------------

    /**
     * What both ways of fetching need to know about this attempt.
     *
     * @param staged pages an earlier attempt of this process already staged
     * @param staging the folder gallery-dl's own folder goes into
     */
    private record Fetch(Attempt attempt, Set<Integer> staged, Path staging, Destination destination)
    {
        ResourceLink link()
        {
            return attempt.link();
        }

        DownloadQueueItem item()
        {
            return attempt.item();
        }

        int chapterId()
        {
            return attempt.chapterId();
        }

        List<Integer> missing()
        {
            return attempt.missing();
        }

        void reportProgress(int done)
        {
            attempt.phase().accept("Downloading images (" + done + " of " + missing().size() + ")");
        }
    }

    /** @return how many pages were skipped (lenient mode only) */
    private int fetchPages(Fetch fetch)
    {
        return switch (fetch.link().downloader())
        {
            case PageDownloader source -> fetchPageByPage(source, fetch);
            case GalleryDlDownloader source -> fetchWithGalleryDl(source, fetch);
            default -> throw new PermanentDownloadException("The source of " + fetch.link().galleryId()
                    + " fetches its pages in no way this app knows.");
        };
    }

    /** @return how many pages were skipped (lenient mode only) */
    private int fetchPageByPage(PageDownloader source, Fetch fetch)
    {
        GalleryData data = fetch.attempt().data();
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
            stopIfRemoved(fetch.attempt());

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
                    // The chapter stays PENDING, so the next attempt fetches this page and the ones after it.
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
                fetch.destination().accept(page, source.pageExtension(url), bytes);
            }
            catch (IOException e)
            {
                // Kept apart from the fetch, so lenient mode never skips it. A staged page may be torn, so the
                // next attempt must discard staging.
                stagedByThisProcess = null;
                throw notSaved(fetch, PageNotSaved.of(page, e));
            }
        }
        return skipped;
    }

    /**
     * gallery-dl writes into a folder of its own inside staging: a page moves on from there by a rename wherever
     * staging shares a filesystem with the page's next folder, and a gallery-dl left running by a killed app writes
     * only where nothing reads. Discarding staging removes it with the rest.
     *
     * @return how many pages were skipped (lenient mode only)
     */
    private int fetchWithGalleryDl(GalleryDlDownloader source, Fetch fetch)
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
                try
                {
                    fetch.destination().accept(page, file);
                }
                catch (IOException e)
                {
                    throw PageNotSaved.of(page, e);
                }
                fetch.reportProgress(before + arrived.incrementAndGet());
            });
        }
        catch (PageNotSaved e)
        {
            // Never skipped, even in lenient mode: the next page would fail the same way.
            stagedByThisProcess = null;
            throw notSaved(fetch, e);
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
        // A failed write is never skipped either: a full disk would skip every page.
        if (!fetch.item().isIgnoreImageErrors() || outcome.writeFailed())
        {
            // The pages that arrived are kept, so the next attempt asks only for the rest.
            throw new UncheckedIOException(new IOException("Could not download page(s) " + GalleryDl.rangeSpec(failed)
                    + " of " + galleryId + why));
        }
        log.warn("Ignoring page(s) {} of {}{}", GalleryDl.rangeSpec(failed), galleryId, why);
        return failed.size();
    }

    // ---- called off ----------------------------------------------------------------------------------------

    /** A download called off while it ran; permanent, as nothing is left to retry. */
    private static final class Cancelled extends PermanentDownloadException
    {
        private Cancelled(String galleryId, String why)
        {
            super("Download of " + galleryId + " was cancelled - " + why);
        }
    }

    /**
     * A download whose queue row or chapter is gone never finishes: a partial set marked {@code SUCCESSFUL} could
     * never be completed. The chapter is checked too, for a delete path that forgets the queue row.
     */
    private void stopIfCancelled(Attempt attempt)
    {
        if (!chapterExists(attempt))
        {
            throw new Cancelled(attempt.link().galleryId(), "its chapter was deleted");
        }
        if (!queueService.exists(attempt.item().getId()))
        {
            throw new Cancelled(attempt.link().galleryId(), "its queue item was removed");
        }
    }

    /**
     * Before each page fetched one at a time, so a download called off stops there, not after its last page. Not
     * between the pages of a gallery-dl run: they arrive on gallery-dl's thread, and the pipeline touches the
     * database only on its own.
     */
    private void stopIfRemoved(Attempt attempt)
    {
        if (!queueService.exists(attempt.item().getId()))
        {
            stopIfCancelled(attempt);
        }
    }

    private boolean chapterExists(Attempt attempt)
    {
        return Objects.equals(importService.findChapterId(attempt.link().galleryId()).orElse(null),
                attempt.chapterId());
    }

    // ---- replacing -----------------------------------------------------------------------------------------

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

    // ---- small steps ---------------------------------------------------------------------------------------

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

    /** An aborted download stops before its next page and never finishes its chapter. */
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

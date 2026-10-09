package io.github.mocchikon.hentie.service.compress;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The single entry point for compressing many images, one per thread on the dedicated pool. Downloads hand
 * over each page as it is fetched ({@link #open}), so encoding overlaps the download.
 *
 * <p><b>One user-started run at a time</b> ({@link #exclusively}): a second is refused, not queued, because
 * the pool is already full and a sweep holds the lock for hours. Downloads do not take the lock: the worker
 * must not stall behind a sweep, and it only compresses staged files under per-call intermediate names.
 */
@Service
@RequiredArgsConstructor
public class ImageCompressionService
{
    private static final Logger log = LoggerFactory.getLogger(ImageCompressionService.class);

    /** Shared by the per-chapter button and the sweep, so both report the same thing. */
    public static final String NO_MODE_MESSAGE =
            "Image Compression is set to \"None\" in Settings, so there was nothing to do.";

    /** Names another tab: the only way a single user starts two runs at once. */
    public static final String BUSY_MESSAGE =
            "Another Image Compression run is still in progress (possibly in another tab) - wait for it to "
                    + "finish, then try again.";

    /** An exception, not an empty result, so a caller that forgets the busy case cannot report "nothing to do". */
    public static class RunInProgress extends RuntimeException
    {
        public RunInProgress()
        {
            super(BUSY_MESSAGE);
        }
    }

    private final AppProperties appProperties;
    private final ImageCompressionModeService modeService;
    private final ImageCompressor compressor;
    private final ImageDirectory imageDirectory;
    private final ImageService imageService;
    private final ScratchSpace scratchSpace;
    private final ExecutorService imageCompressionExecutor;

    /** Reentrant: the sweep holds it around {@link #compressChapter} calls, which take it again. */
    private final ReentrantLock runLock = new ReentrantLock();

    /** @param replaced images actually re-encoded; the rest were kept (saving too small, or a tool failed) */
    public record Summary(int files, int replaced, long before, long after)
    {
        public static final Summary NOTHING = new Summary(0, 0, 0, 0);

        public long saved()
        {
            return before - after;
        }

        public Summary plus(Summary other)
        {
            return new Summary(files + other.files, replaced + other.replaced,
                    before + other.before, after + other.after);
        }

        public String describe()
        {
            if (files == 0)
            {
                return "No images to compress.";
            }
            return replaced + " of " + files + " image(s) re-encoded, "
                    + ImageService.humanReadableSize(Math.max(0, saved())) + " saved.";
        }
    }

    /** Applies the master {@code app.image-compression.enabled} switch. Empty means "keep the images as they are". */
    public Optional<CompressionProfile> profileFor(String key)
    {
        if (!appProperties.getImageCompression().isEnabled())
        {
            return Optional.empty();
        }
        return modeService.resolve(key);
    }

    public Path workDir(int chapterId)
    {
        return scratchSpace.compressionWork().chapter(chapterId);
    }

    /**
     * Never waits, because a caller behind a library sweep would wait for hours.
     *
     * @throws RunInProgress when another thread holds the lock
     */
    public <T> T exclusively(Supplier<T> run)
    {
        if (!runLock.tryLock())
        {
            throw new RunInProgress();
        }
        try
        {
            return run.get();
        }
        finally
        {
            runLock.unlock();
        }
    }

    /** For a caller that would rather not start work {@link #exclusively} will refuse. */
    public boolean isRunInProgress()
    {
        return runLock.isLocked() && !runLock.isHeldByCurrentThread();
    }

    // ---- many files at once ------------------------------------------------

    /**
     * Takes the run lock itself, so no user-started caller can forget to.
     *
     * @throws RunInProgress when another user-started run is already compressing
     */
    public Summary compressFiles(int chapterId, Collection<Path> files, CompressionProfile profile)
    {
        if (profile == null || files.isEmpty())
        {
            return Summary.NOTHING;
        }
        return exclusively(() -> runOver(chapterId, files, profile));
    }

    private Summary runOver(int chapterId, Collection<Path> files, CompressionProfile profile)
    {
        Path work = workDir(chapterId);
        List<CompletableFuture<ImageCompressor.Result>> futures = files.stream()
                .map(file -> submit(file, profile, work))
                .toList();
        Summary summary = await(futures);
        releaseWorkDir(work);
        imageService.invalidateListing(chapterId);
        // The pages have new names, so derived cache entries for the old ones would only waste space.
        imageService.evictDerived(chapterId);
        return summary;
    }

    /**
     * Reads the listing uncached, since a remembered one could name files already replaced. Removes superseded
     * sources first: the listing skips them, so otherwise nothing might ever delete them.
     *
     * @throws RunInProgress when another user-started run is already compressing
     */
    public Summary compressChapter(int chapterId, CompressionProfile profile)
    {
        if (profile == null)
        {
            return Summary.NOTHING;
        }
        // The lock covers the cleanup and listing too, or another run could rename the pages in between.
        return exclusively(() ->
        {
            imageService.removeSupersededPages(chapterId);
            Path dir = imageDirectory.chapterDir(chapterId);
            List<Path> pages = imageDirectory.list(chapterId).stream().map(dir::resolve).toList();
            return compressFiles(chapterId, pages, profile);
        });
    }

    // ---- one page at a time, as it is downloaded ---------------------------

    /**
     * A download's run. Each page is handed to {@code then} on the thread that encoded it, so it can move on at
     * once. The caller must close the run on <b>every</b> exit before discarding staging: an encoder still
     * writing there while files are removed would lose a page.
     *
     * @param then gets every page submitted, re-encoded or kept, even one whose encoding failed; it must not throw
     */
    public Session open(int chapterId, CompressionProfile profile, Consumer<ImageCompressor.Result> then)
    {
        return new Session(chapterId, profile, then);
    }

    /** See {@link #open}. */
    public class Session implements AutoCloseable
    {
        private final CompressionProfile profile;
        private final Path work;
        private final Consumer<ImageCompressor.Result> then;
        private final List<CompletableFuture<ImageCompressor.Result>> futures = new ArrayList<>();
        private Summary summary = Summary.NOTHING;

        private Session(int chapterId, CompressionProfile profile, Consumer<ImageCompressor.Result> then)
        {
            this.profile = profile;
            this.work = workDir(chapterId);
            this.then = then;
        }

        public void submitPage(Path stagedFile)
        {
            futures.add(CompletableFuture.supplyAsync(() ->
            {
                ImageCompressor.Result page = compressKeepingOnFailure(stagedFile, profile, work);
                then.accept(page);
                return page;
            }, imageCompressionExecutor));
        }

        /** Complete only once the session is closed. */
        public Summary summary()
        {
            return summary;
        }

        /** Accumulates, so closing twice (nested try-with-resources) keeps the totals. */
        @Override
        public void close()
        {
            summary = summary.plus(await(futures));
            futures.clear();
            releaseWorkDir(work);
        }
    }

    // ---- plumbing ----------------------------------------------------------

    /**
     * Removes the chapter's work folder and the work root, only if empty; nothing else would, and a sweep
     * would leave one folder per chapter on the RAM disk. Safe only because the work folder is separate
     * from staging, where "empty" can mean "just created". A root deleted under another run is recreated
     * by {@code ImageCompressor} per file.
     */
    private static void releaseWorkDir(Path work)
    {
        deleteIfEmpty(work);
        deleteIfEmpty(work.getParent());
    }

    private static void deleteIfEmpty(Path dir)
    {
        try
        {
            Files.deleteIfExists(dir);
        }
        catch (IOException notEmptyOrBusy)
        {
            // Still in use by another run.
        }
    }

    private CompletableFuture<ImageCompressor.Result> submit(Path file, CompressionProfile profile, Path work)
    {
        return CompletableFuture.supplyAsync(() -> compressor.compress(file, profile, work),
                imageCompressionExecutor);
    }

    /**
     * An unexpected failure counts as "kept as it was" here too, but the page is still handed on: dropped, it would
     * never reach its chapter.
     */
    private ImageCompressor.Result compressKeepingOnFailure(Path file, CompressionProfile profile, Path work)
    {
        try
        {
            return compressor.compress(file, profile, work);
        }
        catch (RuntimeException e)
        {
            log.error("Image compression task failed - the image is kept as it was", e);
            return new ImageCompressor.Result(file, false, 0, 0);
        }
    }

    /** Never throws: a failed task counts as "kept as it was", so one bad page cannot fail a gallery. */
    private static Summary await(List<CompletableFuture<ImageCompressor.Result>> futures)
    {
        var summary = Summary.NOTHING;
        for (CompletableFuture<ImageCompressor.Result> future : futures)
        {
            try
            {
                ImageCompressor.Result result = future.join();
                summary = summary.plus(new Summary(1, result.replaced() ? 1 : 0,
                        result.originalBytes(), result.finalBytes()));
            }
            catch (CompletionException | CancellationException e)
            {
                log.error("Image compression task failed - the image is kept as it was", e);
                summary = summary.plus(new Summary(1, 0, 0, 0));
            }
        }
        return summary;
    }
}

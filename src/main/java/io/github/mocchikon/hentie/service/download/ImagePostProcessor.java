package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The download pipeline's seam onto Image Compression: the download package decides <i>when</i> a page is
 * ready and where it goes once processed, the compress package <i>what</i> to do with it, and the mode key stays
 * out of the compressor.
 *
 * <p>It works on staged files and hands each back once processed, so the data directory only ever receives whole
 * pages. The caller must reach {@link Run#close()} on every exit: an encoder still writing while staging is
 * discarded would lose a page.
 */
@Component
@RequiredArgsConstructor
public class ImagePostProcessor
{
    private static final Logger log = LoggerFactory.getLogger(ImagePostProcessor.class);

    private final ImageCompressionService compressionService;

    /** What becomes of a page once processed; called on the thread that processed it. */
    @FunctionalInterface
    public interface Processed
    {
        /**
         * Must not throw: the page is the caller's from here, failures included.
         *
         * @param file the page as it is now, re-encoded or not
         */
        void page(Path file, boolean reEncoded);
    }

    /**
     * Empty when the mode leaves pages as they arrive ("None", a mode since deleted, compression switched off), so
     * the caller stores them as fetched. Decided once, so one attempt never treats its pages two ways.
     */
    public Optional<Run> start(int chapterId, String modeKey, Processed processed)
    {
        return compressionService.profileFor(modeKey).map(profile ->
        {
            log.info("Compressing images of chapter {} with mode {}", chapterId, profile.name());
            return new Run(compressionService.open(chapterId, profile,
                    page -> processed.page(page.file(), page.replaced())), chapterId);
        });
    }

    /**
     * For a full-quality re-download, the one download that changes published pages a run may be encoding.
     *
     * @throws ImageCompressionService.RunInProgress when a run is in progress; never waits for it
     */
    public <T> T exclusively(Supplier<T> work)
    {
        return compressionService.exclusively(work);
    }

    /** Whether {@link #exclusively} would refuse right now. */
    public boolean isRunInProgress()
    {
        return compressionService.isRunInProgress();
    }

    public record Run(ImageCompressionService.Session session, int chapterId) implements AutoCloseable
    {
        /** Does not wait, so encoding overlaps with the rest of the download. */
        public void page(Path stagedFile)
        {
            session.submitPage(stagedFile);
        }

        /** Waits for every page still being processed. Safe to call more than once. */
        @Override
        public void close()
        {
            session.close();
            ImageCompressionService.Summary summary = session.summary();
            if (summary.files() > 0)
            {
                log.info("Compressed images of chapter {}: {}", chapterId, summary.describe());
            }
        }
    }
}

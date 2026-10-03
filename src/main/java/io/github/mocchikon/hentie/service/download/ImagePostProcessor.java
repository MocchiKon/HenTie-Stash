package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.dto.CompressionProfile;
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
 * ready, the compress package <i>what</i> to do with it, and the mode key stays out of the compressor.
 *
 * <p>It works on staged files, so a failed or interrupted processor never leaves half-processed images in
 * the data directory. The caller must reach {@link Run#close()} on every exit: an encoder still writing
 * while staging is published or discarded would lose a page.
 */
@Component
@RequiredArgsConstructor
public class ImagePostProcessor
{
    private static final Logger log = LoggerFactory.getLogger(ImagePostProcessor.class);

    private final ImageCompressionService compressionService;

    /** Under mode "None" the run is a no-op that still has to be closed, so the caller needs no branch. */
    public Run start(int chapterId, String modeKey)
    {
        Optional<CompressionProfile> profile = compressionService.profileFor(modeKey);
        profile.ifPresent(p -> log.info("Compressing images of chapter {} with mode {}", chapterId, p.name()));
        return new Run(compressionService.open(chapterId, profile.orElse(null)), chapterId);
    }

    /** Decides where pages are staged: only a processed download gains from a RAM disk. */
    public boolean compresses(String modeKey)
    {
        return compressionService.profileFor(modeKey).isPresent();
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

        /** Pages replaced by a re-encoded version (originals kept when not smaller don't count); complete once closed. */
        public int compressedPages()
        {
            return session.summary().replaced();
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

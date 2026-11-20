package io.github.mocchikon.hentie.service.export;

import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Asker;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Failed;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Ready;
import io.github.mocchikon.hentie.service.compress.ImageToolLocator;
import io.github.mocchikon.hentie.service.compress.ImageToolRunner;
import io.github.mocchikon.hentie.service.compress.JxlTranscoder;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns a page into what goes into a CBZ: a PNG or a JPEG, which every reader shows. WebP, AVIF, JPEG XL and the
 * rest are not supported everywhere.
 *
 * <p><b>A page is handed over as an open file</b>, because a cached decode or ComfyUI result may be pruned at any
 * moment, and the archive reads each page twice (a stored entry needs its CRC before its bytes). An open file can
 * still be read after it is deleted.
 *
 * <p><b>A page that cannot be converted goes in as it is, and the log says why</b> (no ImageMagick, a codec
 * error): a reader that cannot show one page is better than no export. A ComfyUI failure fails the export instead,
 * since the user asked for every page through the workflow.
 */
@Component
@RequiredArgsConstructor
public class ExportPages
{
    private static final Logger log = LoggerFactory.getLogger(ExportPages.class);

    private static final Set<String> KEPT = Set.of("png", "jpg", "jpeg");

    /** As long as the viewer's polls: well inside {@code app.comfyui.abandon-after-millis}. */
    private static final Duration PROCESSING_WAIT = Duration.ofSeconds(20);

    private final ImageDirectory imageDirectory;
    private final JxlTranscoder jxlTranscoder;
    private final ImageToolLocator toolLocator;
    private final ImageToolRunner toolRunner;
    private final ScratchSpace scratchSpace;
    private final PageProcessingService processing;

    /** {@code temporary} is deleted on close. */
    public record Page(FileChannel channel, String extension, Path temporary) implements Closeable
    {
        @Override
        public void close() throws IOException
        {
            try
            {
                channel.close();
            }
            finally
            {
                if (temporary != null)
                {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }

    /**
     * One export's interest in ComfyUI pages, under an id of its own: the export must not supersede a viewer's
     * pages, nor a viewer the export's.
     */
    public static final class Processing
    {
        private final String workflow;
        private final String viewer = "export-" + UUID.randomUUID().toString().replace("-", "");
        private final AtomicLong seq = new AtomicLong();

        public Processing(String workflow)
        {
            this.workflow = workflow;
        }

        public String workflow()
        {
            return workflow;
        }

        Asker next(String filename)
        {
            return Asker.of(viewer, seq.incrementAndGet(), List.of(filename));
        }

        Asker leaving()
        {
            return Asker.of(viewer, seq.incrementAndGet());
        }
    }

    public static class ExportFailedException extends RuntimeException
    {
        public ExportFailedException(String message)
        {
            super(message);
        }
    }

    public static class ExportCancelledException extends ExportFailedException
    {
        public ExportCancelledException()
        {
            super("The export was cancelled.");
        }
    }

    /**
     * @param processing null to export the pages as stored
     * @param tracker    told what ComfyUI is doing while the page is waited for
     * @throws ExportFailedException    when ComfyUI could not process the page
     * @throws ExportCancelledException when the user cancelled the export meanwhile
     * @throws IOException              when the page itself cannot be read
     */
    public Page open(int chapterId, String filename, Processing processing, ExportProgress.Tracker tracker)
            throws IOException, InterruptedException
    {
        tracker.checkCancelled();
        if (processing != null)
        {
            return processed(chapterId, filename, processing, tracker);
        }
        Path source = imageDirectory.pageFile(chapterId, filename)
                .orElseThrow(() -> new NoSuchFileException(filename));
        String extension = ImageDirectory.extension(filename).toLowerCase(Locale.ROOT);
        if (KEPT.contains(extension))
        {
            return new Page(read(source), extension.equals("jpeg") ? "jpg" : extension, null);
        }
        Optional<Page> converted = JxlTranscoder.isJxl(filename)
                ? decodedJxl(chapterId, filename)
                : converted(chapterId, source);
        if (converted.isPresent())
        {
            return converted.get();
        }
        log.warn("Exporting page {} of chapter {} as it is: it could not be converted to PNG", filename, chapterId);
        return new Page(read(source), extension, null);
    }

    /** Ends this export's interest, so a page it was waiting for stops unless a viewer wants it too. */
    public void release(Processing processing)
    {
        if (processing != null)
        {
            this.processing.leave(processing.leaving());
        }
    }

    /**
     * The result is opened as soon as it is made, and only then is the next page asked for. A page processed ahead
     * would wait in the results cache, which a large export can outgrow and prune before the page is written.
     */
    private Page processed(int chapterId, String filename, Processing export, ExportProgress.Tracker tracker)
            throws InterruptedException
    {
        while (true)
        {
            tracker.checkCancelled();
            var outcome = processing.request(chapterId, filename, export.workflow(), true, PROCESSING_WAIT,
                    export.next(filename));
            tracker.checkCancelled();
            if (outcome instanceof PageProcessingService.Pending pending)
            {
                tracker.working(pending.progress().message());
            }
            if (outcome instanceof Failed failed)
            {
                throw new ExportFailedException("Page " + filename + " of chapter " + chapterId
                        + " could not be processed: " + failed.message());
            }
            if (outcome instanceof Ready ready)
            {
                try
                {
                    return new Page(read(ready.file()), "png", null);
                }
                catch (IOException prunedMeanwhile)
                {
                    // Asking again makes it again.
                }
            }
        }
    }

    /** The viewer's decode cache, so a page read before is not decoded again. */
    private Optional<Page> decodedJxl(int chapterId, String filename)
    {
        // Twice: the entry may be pruned between the decode and the open.
        for (int attempt = 0; attempt < 2; attempt++)
        {
            Optional<Path> png = jxlTranscoder.pngFor(chapterId, filename);
            if (png.isEmpty())
            {
                return Optional.empty();
            }
            try
            {
                return Optional.of(new Page(read(png.get()), "png", null));
            }
            catch (IOException prunedMeanwhile)
            {
                // Decoded again on the next attempt.
            }
        }
        return Optional.empty();
    }

    /** In the compression work folder, named per call like every intermediate there. */
    private Optional<Page> converted(int chapterId, Path source) throws IOException
    {
        Optional<String> magick = toolLocator.find(ImageToolLocator.MAGICK);
        if (magick.isEmpty())
        {
            log.warn("ImageMagick was not found, so {} cannot be converted to PNG", source);
            return Optional.empty();
        }
        Path work = scratchSpace.compressionWork().chapter(chapterId);
        Files.createDirectories(work);
        Path png = work.resolve(ImageDirectory.baseName(source.getFileName().toString()) + "."
                + UUID.randomUUID() + ".export.png");
        try
        {
            // [0]: the first frame of an animation, or ImageMagick writes one numbered file per frame.
            if (toolRunner.run(List.of(magick.get(), source + "[0]", "png:" + png)) && Files.size(png) > 0)
            {
                // Deleted while open where the system allows it (the file stays readable), so nothing is left
                // behind if the export is cut off; elsewhere on close.
                FileChannel channel = read(png);
                return Optional.of(new Page(channel, "png", deleteQuietly(png) ? null : png));
            }
            deleteQuietly(png);
            return Optional.empty();
        }
        catch (IOException e)
        {
            deleteQuietly(png);
            log.warn("Could not convert {} to PNG", source, e);
            return Optional.empty();
        }
        finally
        {
            deleteQuietly(work);
            deleteQuietly(work.getParent());
        }
    }

    private static FileChannel read(Path file) throws IOException
    {
        return FileChannel.open(file, StandardOpenOption.READ);
    }

    /** A folder is removed only if empty; another run may be using it. */
    private static boolean deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
            return true;
        }
        catch (IOException notEmptyOrBusy)
        {
            // Left for its owner, or for the startup cleanup.
            return false;
        }
    }
}

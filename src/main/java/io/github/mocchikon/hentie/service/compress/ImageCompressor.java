package io.github.mocchikon.hentie.service.compress;

import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Downscales and re-encodes one page image, in place. The order is fixed: format filter, ImageMagick (only
 * when the mode gives it arguments), encoder, validity check, reduction thresholds.
 *
 * <p>ImageMagick always writes PNG, because a lossy intermediate would lose quality twice. An input the
 * encoder cannot read (WebP for both, GIF for AVIF) goes through a lossless PNG first, or it would never be
 * compressed.
 *
 * <p><b>Any failure keeps the original</b>: compression is an improvement, never a precondition.
 * Intermediates go to the work folder the caller passes, which may be a RAM disk.
 */
@Component
@RequiredArgsConstructor
public class ImageCompressor
{
    private static final Logger log = LoggerFactory.getLogger(ImageCompressor.class);

    private static final String INTERMEDIATE_EXTENSION = "png";

    private final ImageToolLocator toolLocator;
    private final ImageToolRunner runner;

    /** @param file the page as it is now: the new file when it was replaced, else the original */
    public record Result(Path file, boolean replaced, long originalBytes, long finalBytes)
    {
        public long saved()
        {
            return originalBytes - finalBytes;
        }
    }

    /** @param workDir where intermediates go; this call deletes its own when done */
    public Result compress(Path source, CompressionProfile profile, Path workDir)
    {
        long originalBytes = sizeOf(source);
        var filename = source.getFileName().toString();
        var extension = ImageDirectory.extension(filename);
        if (!profile.processes(extension) || originalBytes <= 0)
        {
            return unchanged(source, originalBytes);
        }

        var base = ImageDirectory.baseName(filename);
        // Per call, not per page: the work folder is per chapter, so a download staging page 3 and a run on
        // the published page 3 would otherwise share "3.jxl" and delete or tear each other's output.
        var scratch = base + "." + UUID.randomUUID();
        var intermediates = new ArrayList<Path>();
        try
        {
            Files.createDirectories(workDir);

            Path encoderInput = prepareEncoderInput(source, profile, workDir, scratch, extension, intermediates);
            if (encoderInput == null)
            {
                return unchanged(source, originalBytes);
            }

            Optional<String> encoder = toolLocator.find(profile.encoder().getBinary());
            if (encoder.isEmpty())
            {
                log.error("Image Compression mode {} needs {} but no binary was found - keeping {} as it is",
                        profile.name(), profile.encoder().getBinary(), source);
                return unchanged(source, originalBytes);
            }
            var encoded = workDir.resolve(scratch + "." + profile.encoder().getExtension());
            intermediates.add(encoded);
            var command = profile.encoder()
                    .commandLine(encoder.get(), encoderInput.toString(), encoded.toString(), profile.encoderArgs());
            if (!runner.run(command) || !Files.isRegularFile(encoded))
            {
                return unchanged(source, originalBytes);
            }

            long encodedBytes = sizeOf(encoded);
            if (!usable(profile, source, encoded, encodedBytes)
                    || !worthKeeping(profile, source, originalBytes, encodedBytes))
            {
                return unchanged(source, originalBytes);
            }
            Path published = replace(source, encoded, base, profile);
            return new Result(published, true, originalBytes, encodedBytes);
        }
        catch (IOException e)
        {
            log.error("Could not compress {} - keeping it as it is", source, e);
            return unchanged(source, originalBytes);
        }
        finally
        {
            intermediates.forEach(ImageCompressor::deleteQuietly);
        }
    }

    /** Null when ImageMagick was needed and could not run. */
    private Path prepareEncoderInput(Path source, CompressionProfile profile, Path workDir, String scratch,
                                     String extension, List<Path> intermediates)
    {
        boolean needsMagick = profile.usesImageMagick() || !profile.encoder().accepts(extension);
        if (!needsMagick)
        {
            return source;
        }
        Optional<String> magick = toolLocator.find(ImageToolLocator.MAGICK);
        if (magick.isEmpty())
        {
            log.error("Image Compression mode {} needs ImageMagick but no binary was found - keeping {} "
                    + "as it is", profile.name(), source);
            return null;
        }
        var converted = workDir.resolve(scratch + ".magick." + INTERMEDIATE_EXTENSION);
        intermediates.add(converted);

        var command = new ArrayList<String>();
        command.add(magick.get());
        // [0] = first frame: for an animated GIF ImageMagick would otherwise write one numbered PNG per
        // frame and none under the name we asked for.
        command.add(source + "[0]");
        command.addAll(profile.magickArgs());
        command.add(converted.toString());
        if (!runner.run(command) || !Files.isRegularFile(converted))
        {
            return null;
        }
        return converted;
    }

    /**
     * Checked <b>before</b> {@link #worthKeeping}: an empty or header-less file is the smallest result there
     * is, so it would replace the page and the original would be deleted, with nothing to repair it. A short
     * write on a full RAM disk is the realistic cause.
     */
    private static boolean usable(CompressionProfile profile, Path source, Path encoded, long encodedBytes)
    {
        byte[] header = headerOf(encoded);
        if (encodedBytes <= 0 || !profile.encoder().producedValidFile(header))
        {
            log.error("Image Compression mode {} ran {} successfully but produced no usable {} file for {}"
                            + " ({} byte(s)) - keeping the original", profile.name(),
                    profile.encoder().getBinary(), profile.encoder().getExtension(), source, encodedBytes);
            return false;
        }
        return true;
    }

    /** Empty when unreadable, which fails the check. */
    private static byte[] headerOf(Path file)
    {
        try (var in = Files.newInputStream(file))
        {
            return in.readNBytes(ImageEncoder.HEADER_BYTES);
        }
        catch (IOException e)
        {
            return new byte[0];
        }
    }

    /**
     * A result that is not smaller is always rejected: thresholds of 0 mean "no particular saving required",
     * not "store a bigger file".
     */
    private static boolean worthKeeping(CompressionProfile profile, Path source, long originalBytes,
                                        long encodedBytes)
    {
        long saved = originalBytes - encodedBytes;
        if (saved <= 0)
        {
            log.debug("Keeping {} - {} produced no saving ({} -> {})", source, profile.encoder(),
                    ImageService.humanReadableSize(originalBytes), ImageService.humanReadableSize(encodedBytes));
            return false;
        }
        double relative = saved * 100.0 / originalBytes;
        if (profile.minRelativeReduction() > 0 && relative < profile.minRelativeReduction())
        {
            log.debug("Keeping {} - saved {}%, below the {}% minimum of mode {}", source, Math.round(relative),
                    profile.minRelativeReduction(), profile.name());
            return false;
        }
        if (profile.minAbsoluteReduction() > 0 && saved < profile.minAbsoluteReduction() * 1024L)
        {
            log.debug("Keeping {} - saved {}, below the {} KB minimum of mode {}", source,
                    ImageService.humanReadableSize(saved), profile.minAbsoluteReduction(), profile.name());
            return false;
        }
        return true;
    }

    /**
     * Keeps the base name so page 3 stays page 3. The source is deleted only after the move, so an
     * interruption leaves a superseded source (ignored on read) but never a missing page.
     */
    private static Path replace(Path source, Path encoded, String base, CompressionProfile profile)
            throws IOException
    {
        var target = source.resolveSibling(base + "." + profile.encoder().getExtension());
        ImageService.moveInto(encoded, target);
        removeStaleVariants(target);
        return target;
    }

    /**
     * By base name and images only, the same rule as {@code ImageDirectory.supersededVariants}: {@code 03.jpg}
     * is a page of its own and {@code 3.json} is not a page.
     */
    private static void removeStaleVariants(Path keep)
    {
        Path dir = keep.getParent();
        if (dir == null)
        {
            return;
        }
        String keepName = keep.getFileName().toString();
        String base = ImageDirectory.baseName(keepName);
        try (Stream<Path> files = Files.list(dir))
        {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.equals(keepName))
                    .filter(ImageDirectory::isImage)
                    .filter(name -> ImageDirectory.baseName(name).equals(base))
                    .forEach(name -> deleteQuietly(dir.resolve(name)));
        }
        catch (IOException e)
        {
            log.warn("Could not clean up the previous version of {}", keep, e);
        }
    }

    private static Result unchanged(Path source, long originalBytes)
    {
        return new Result(source, false, originalBytes, originalBytes);
    }

    private static long sizeOf(Path file)
    {
        try
        {
            return Files.size(file);
        }
        catch (IOException e)
        {
            return 0;
        }
    }

    private static void deleteQuietly(Path file)
    {
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException ignored)
        {
            // Best effort; ScratchSpace clears leftovers at startup.
        }
    }
}

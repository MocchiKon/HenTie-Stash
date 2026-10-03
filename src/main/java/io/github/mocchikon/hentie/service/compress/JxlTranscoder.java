package io.github.mocchikon.hentie.service.compress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.scratch.CacheFolder;
import io.github.mocchikon.hentie.service.scratch.PageDerivedCache;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace;

/**
 * Decodes a stored JPEG XL page to PNG for browsers that cannot show JPEG XL.
 *
 * <p><b>Cached on disk because it has to be</b>: a search grid asks for up to 36 thumbnails at once.
 * Decodes are also bounded (one per processor) and de-duplicated per version.
 *
 * <p><b>An entry is named after the exact page version</b> ({@code 3.jxl.<mtime>-<size>.png}), and valid
 * when an entry for the current version exists. A "newer than the page" test would hide a replacement
 * copied in with an older timestamp for ever. The name also serves as the {@code ETag}.
 *
 * <p>On failure it returns empty and the caller serves the stored {@code .jxl}.
 */
@Component
public class JxlTranscoder implements PageDerivedCache
{
    private static final Logger log = LoggerFactory.getLogger(JxlTranscoder.class);

    public static final String JXL = "jxl";

    /** Must end in {@code .png}: djxl picks its output format from the extension. */
    private static final String PART_SUFFIX = ".part.png";

    /**
     * An entry or a decode in progress. Housekeeping deletes nothing else: the folder sits inside one the
     * user chose, and other files there are not the cache's.
     */
    private static final Pattern ENTRY =
            Pattern.compile(".+\\.jxl\\.\\d+-\\d+\\.png(\\.[0-9a-f-]+\\.part\\.png)?", Pattern.CASE_INSENSITIVE);

    private final AppProperties appProperties;
    private final ImageDirectory imageDirectory;
    private final ScratchSpace scratchSpace;
    private final ImageToolLocator toolLocator;
    private final ImageToolRunner runner;
    private final CacheFolder folder;

    /** Running decodes, by the entry they will produce. */
    private final ConcurrentHashMap<Path, CompletableFuture<Optional<Path>>> inFlight = new ConcurrentHashMap<>();

    /** Not the Image Compression pool: a page on screen must not queue behind a library sweep. */
    private final Semaphore decodeSlots = new Semaphore(Math.max(1, Runtime.getRuntime().availableProcessors()));

    public JxlTranscoder(AppProperties appProperties, ImageDirectory imageDirectory, ScratchSpace scratchSpace,
                         ImageToolLocator toolLocator, ImageToolRunner runner)
    {
        this.appProperties = appProperties;
        this.imageDirectory = imageDirectory;
        this.scratchSpace = scratchSpace;
        this.toolLocator = toolLocator;
        this.runner = runner;
        // A decode still being written counts as abandoned once it is older than any tool may run.
        this.folder = new CacheFolder("JPEG XL transcode cache",
                () -> scratchSpace.transcodeCache().root(),
                scratchSpace::transcodeCacheCapBytes,
                ENTRY, PART_SUFFIX,
                () -> 2L * Math.max(1, appProperties.getImageCompression().getTimeoutSeconds()) * 1000);
    }

    public static boolean isJxl(String filename)
    {
        return ImageDirectory.extension(filename).equalsIgnoreCase(JXL);
    }

    /**
     * Empty when the page does not exist, is not a JPEG XL, or could not be decoded.
     *
     * @param filename the page's own filename, e.g. {@code "3.jxl"} - never a path
     */
    public Optional<Path> pngFor(int chapterId, String filename)
    {
        Path source = resolveSource(chapterId, filename);
        if (source == null)
        {
            return Optional.empty();
        }
        Optional<Path> entry = entryOf(chapterId, filename, source);
        if (entry.isEmpty())
        {
            return Optional.empty();
        }
        Path cached = entry.get();
        if (Files.isRegularFile(cached))
        {
            return Optional.of(cached);
        }

        var mine = new CompletableFuture<Optional<Path>>();
        CompletableFuture<Optional<Path>> running = inFlight.putIfAbsent(cached, mine);
        if (running != null)
        {
            return running.join();
        }
        try
        {
            Optional<Path> result = decodeBounded(source, cached, filename);
            mine.complete(result);
            return result;
        }
        catch (RuntimeException | Error e)
        {
            // Completed normally, so a waiter falls back to the stored file instead of failing.
            mine.complete(Optional.empty());
            throw e;
        }
        finally
        {
            inFlight.remove(cached, mine);
        }
    }

    /** Null unless the name, which comes from a URL, is a JPEG XL page of the chapter. */
    private Path resolveSource(int chapterId, String filename)
    {
        return isJxl(filename) ? imageDirectory.pageFile(chapterId, filename).orElse(null) : null;
    }

    /** From a {@code stat} alone, so a caller can answer an ETag check or a HEAD without decoding. */
    public Optional<Path> cacheEntry(int chapterId, String filename)
    {
        Path source = resolveSource(chapterId, filename);
        return source == null ? Optional.empty() : entryOf(chapterId, filename, source);
    }

    private Optional<Path> entryOf(int chapterId, String filename, Path source)
    {
        try
        {
            return Optional.of(entryFor(chapterId, filename, source));
        }
        catch (IOException e)
        {
            log.error("Could not read {} to decode it to PNG", source, e);
            return Optional.empty();
        }
    }

    private Path entryFor(int chapterId, String filename, Path source) throws IOException
    {
        var attrs = Files.readAttributes(source, BasicFileAttributes.class);
        return chapterCacheDir(chapterId)
                .resolve(filename + "." + attrs.lastModifiedTime().toMillis() + "-" + attrs.size() + ".png");
    }

    private Optional<Path> decodeBounded(Path source, Path cached, String filename)
    {
        try
        {
            decodeSlots.acquire();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try
        {
            if (Files.isRegularFile(cached))
            {
                return Optional.of(cached);
            }
            return decode(source, cached, filename);
        }
        catch (IOException e)
        {
            log.error("Could not decode {} to PNG", source, e);
            return Optional.empty();
        }
        finally
        {
            decodeSlots.release();
        }
    }

    /**
     * Writes to a temporary name <b>of its own</b>, then renames: a half-written PNG served once would be
     * cached by the browser as the page, and a shared temp name would let two decodes tear one file.
     */
    private Optional<Path> decode(Path source, Path cached, String filename) throws IOException
    {
        Optional<String> djxl = toolLocator.find("djxl");
        if (djxl.isEmpty())
        {
            log.error("A JPEG XL page has to be sent as PNG but no djxl binary was found - serving {} as it is",
                    source);
            return Optional.empty();
        }
        Files.createDirectories(cached.getParent());
        Path part = folder.partFor(cached);
        try
        {
            if (!runner.run(List.of(djxl.get(), source.toString(), part.toString())) || !Files.isRegularFile(part))
            {
                return Optional.empty();
            }
            try
            {
                ImageService.moveInto(part, cached);
            }
            catch (IOException e)
            {
                // Another decode of this version got there first (on Windows a served file cannot be
                // replaced). Its copy is as good as ours.
                if (Files.isRegularFile(cached))
                {
                    return Optional.of(cached);
                }
                throw e;
            }
        }
        finally
        {
            CacheFolder.deleteQuietly(part);
        }
        removeOtherVersions(cached, filename);
        folder.wrote(Files.size(cached));
        return Optional.of(cached);
    }

    // ---- cache housekeeping ------------------------------------------------

    public Path cacheDir()
    {
        return folder.root();
    }

    private Path chapterCacheDir(int chapterId)
    {
        return folder.chapterDir(chapterId);
    }

    @Override
    public void evict(int chapterId)
    {
        folder.evict(chapterId);
    }

    @Override
    public void evict(int chapterId, String filename)
    {
        if (!isJxl(filename))
        {
            return;
        }
        folder.deleteVersions(chapterCacheDir(chapterId), versionsOf(filename), null);
    }

    /** Older versions can never be served again, so waiting for the size-based prune would only waste space. */
    private void removeOtherVersions(Path keep, String filename)
    {
        folder.deleteVersions(keep.getParent(), versionsOf(filename), keep.getFileName().toString());
    }

    /** Exact, so it never matches another page with the same prefix or a decode still being written. */
    private static Pattern versionsOf(String filename)
    {
        return Pattern.compile(Pattern.quote(filename) + "\\.\\d+-\\d+\\.png");
    }

    /** For a folder the cache just moved away from: nothing would read or prune it again. */
    public void clear(Path root)
    {
        folder.clear(root);
    }
}

package io.github.mocchikon.hentie.service.scratch;

import io.github.mocchikon.hentie.service.ImageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared housekeeping for caches of page derivatives at {@code <root>/<chapterId>/<entry>}.
 *
 * <p><b>It deletes only files shaped like its own entries</b> at {@code <root>/<digits>/}, and a chapter
 * folder only once empty, never recursively: the root sits inside a folder the user chose, and other files
 * there are not the cache's.
 *
 * <p>Entries are written under a temporary name of their own ({@link #partFor}) and renamed, so a reader never
 * sees a file still being written and two writers never share one. Nothing is synced before the rename, so a
 * power cut can leave an entry without its data. A reader takes only a {@linkplain #isWholePng whole} one and
 * leaves a broken one for the next write to replace: deleting it on sight could delete a whole one renamed in
 * meanwhile.
 */
public final class CacheFolder
{
    private static final Logger log = LoggerFactory.getLogger(CacheFolder.class);

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    /** The last chunk of every PNG. It holds no data, so it is always these bytes: length, type, CRC. */
    private static final byte[] PNG_END = {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xae, 0x42, 0x60, (byte) 0x82};

    /** A prune runs after writing this fraction of the cap, so pruning is rare. */
    private static final int PRUNE_EVERY_FRACTION = 4;

    /** Pruning goes below the cap rather than to it, so the next few writes do not prune again. */
    private static final double PRUNE_TARGET = 0.8;

    private final String description;
    private final Supplier<Path> root;
    private final LongSupplier capBytes;
    /** A finished entry or a write in progress - the only names any housekeeping may delete. */
    private final Pattern entry;
    private final String partSuffix;
    private final LongSupplier abandonedAfterMillis;

    private final AtomicLong writtenSincePrune = new AtomicLong();

    /**
     * @param root                 asked on every use, since Settings can move it
     * @param capBytes             0 or less never prunes
     * @param entry                matches every entry <b>and</b> every write in progress
     * @param abandonedAfterMillis a temporary file older than this is not being written by anyone
     */
    public CacheFolder(String description, Supplier<Path> root, LongSupplier capBytes, Pattern entry,
                       String partSuffix, LongSupplier abandonedAfterMillis)
    {
        this.description = description;
        this.root = root;
        this.capBytes = capBytes;
        this.entry = entry;
        this.partSuffix = partSuffix;
        this.abandonedAfterMillis = abandonedAfterMillis;
    }

    public Path root()
    {
        return root.get();
    }

    public Path chapterDir(int chapterId)
    {
        return ScratchSpace.chapterFolder(root(), chapterId);
    }

    public Path partFor(Path entry)
    {
        return entry.resolveSibling(entry.getFileName() + "." + UUID.randomUUID() + partSuffix);
    }

    public void evict(int chapterId)
    {
        Path dir = chapterDir(chapterId);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
        {
            return;
        }
        try (Stream<Path> files = Files.list(dir))
        {
            files.filter(this::isEntryFile).forEach(CacheFolder::deleteQuietly);
        }
        catch (IOException e)
        {
            log.warn("Could not clear the {} of chapter {}", description, chapterId, e);
        }
        deleteQuietly(dir);   // refused unless empty
    }

    /**
     * @param version only the caller knows which names are versions of one page; must not match a write in
     *                progress
     * @param keep    the entry to keep, or null
     */
    public void deleteVersions(Path dir, Pattern version, String keep)
    {
        if (!Files.isDirectory(dir))
        {
            return;
        }
        try (Stream<Path> files = Files.list(dir))
        {
            files.filter(p -> version.matcher(p.getFileName().toString()).matches())
                    .filter(p -> !p.getFileName().toString().equals(keep))
                    .filter(this::isEntryFile)
                    .forEach(CacheFolder::deleteQuietly);
        }
        catch (IOException e)
        {
            log.warn("Could not remove old versions from the {} in {}", description, dir, e);
        }
    }

    /** Prunes only after enough writes, or every write would stat the whole cache. */
    public void wrote(long bytes)
    {
        long cap = capBytes.getAsLong();
        if (cap <= 0)
        {
            return;
        }
        if (writtenSincePrune.addAndGet(bytes) < cap / PRUNE_EVERY_FRACTION)
        {
            return;
        }
        writtenSincePrune.set(0);
        prune(root(), cap);
    }

    /** For a folder the cache moved away from: nothing would read or prune it again. */
    public void clear(Path root)
    {
        prune(root, 0);
    }

    /** Walks the folder, so not for a request path. */
    public long sizeBytes(Path root)
    {
        try (Stream<Path> files = entries(root))
        {
            return files.mapToLong(CacheFolder::sizeOf).sum();
        }
        catch (IOException e)
        {
            return 0;
        }
    }

    private void prune(Path root, long capBytes)
    {
        try (Stream<Path> walk = entries(root))
        {
            List<Path> files = walk.filter(this::prunable).toList();
            long total = files.stream().mapToLong(CacheFolder::sizeOf).sum();
            if (total <= capBytes)
            {
                return;
            }
            long target = (long) (capBytes * PRUNE_TARGET);
            List<Path> oldestFirst = files.stream()
                    .sorted(Comparator.comparingLong(CacheFolder::lastModifiedMillis))
                    .toList();
            for (Path file : oldestFirst)
            {
                if (total <= target)
                {
                    break;
                }
                total -= sizeOf(file);
                deleteQuietly(file);
            }
            log.info("Pruned the {} in {} down to {}", description, root, ImageService.humanReadableSize(total));
        }
        catch (IOException e)
        {
            log.warn("Could not prune the {}", description, e);
        }
    }

    private Stream<Path> entries(Path root) throws IOException
    {
        if (!Files.isDirectory(root))
        {
            return Stream.empty();
        }
        // Depth 2 is <root>/<chapterId>/<entry>; nothing deeper is ours.
        return Files.walk(root, 2).filter(file -> isEntry(root, file));
    }

    private boolean isEntry(Path root, Path file)
    {
        Path relative = root.relativize(file);
        return relative.getNameCount() == 2
                && ScratchSpace.isChapterFolderName(relative.getName(0).toString())
                && isEntryFile(file);
    }

    /** A link with an entry's name is not something we wrote. */
    private boolean isEntryFile(Path file)
    {
        return entry.matcher(file.getFileName().toString()).matches()
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    /** A write in progress is kept, unless it is older than any writer may take (a process died mid-write). */
    private boolean prunable(Path file)
    {
        if (!file.getFileName().toString().endsWith(partSuffix))
        {
            return true;
        }
        return System.currentTimeMillis() - lastModifiedMillis(file) > abandonedAfterMillis.getAsLong();
    }

    private static long lastModifiedMillis(Path file)
    {
        try
        {
            return Files.readAttributes(file, BasicFileAttributes.class).lastModifiedTime().toMillis();
        }
        catch (IOException e)
        {
            return 0;
        }
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

    /**
     * Reads only the two ends: a file a power cut damaged before it was synced is empty, zeros or cut short, so
     * it lacks the signature or the closing {@code IEND} chunk. Data lost from the middle would take a decode to
     * find.
     */
    public static boolean isWholePng(Path file)
    {
        try (FileChannel channel = FileChannel.open(file))
        {
            long size = channel.size();
            return size >= PNG_SIGNATURE.length + PNG_END.length
                    && holds(channel, 0, PNG_SIGNATURE)
                    && holds(channel, size - PNG_END.length, PNG_END);
        }
        catch (IOException e)
        {
            // Missing or unreadable: nothing to serve either way.
            return false;
        }
    }

    /** The same test for bytes before they are stored, so nothing is stored that a reader would refuse. */
    public static boolean isWholePng(byte[] png)
    {
        return png.length >= PNG_SIGNATURE.length + PNG_END.length
                && Arrays.equals(png, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)
                && Arrays.equals(png, png.length - PNG_END.length, png.length, PNG_END, 0, PNG_END.length);
    }

    private static boolean holds(FileChannel channel, long position, byte[] expected) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate(expected.length);
        while (buffer.hasRemaining())
        {
            if (channel.read(buffer, position + buffer.position()) < 0)
            {
                return false;
            }
        }
        return Arrays.equals(buffer.array(), expected);
    }

    public static void deleteQuietly(Path file)
    {
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException ignored)
        {
            // Best effort: the next prune tries again.
        }
    }
}

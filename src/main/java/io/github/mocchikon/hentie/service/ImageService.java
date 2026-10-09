package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.scrapper.PageDownloader;
import io.github.mocchikon.hentie.service.scratch.PageDerivedCache;
import io.github.mocchikon.hentie.service.scratch.ScratchArea;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Chapter page images on disk ({@code data/{chapterId}/{page}.{ext}}). Reads that render pages or
 * thumbnails go through {@link ImageDirectoryCache}; everything else reads {@link ImageDirectory}.
 */
@Service
public class ImageService
{
    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    /** Not numeric, so a leftover {@code .part} is never taken for a page. */
    private static final String PART_SUFFIX = ".part";

    /** See {@link #moveRetrying}. */
    private static final int MOVE_ATTEMPTS = 5;
    private static final long MOVE_RETRY_MILLIS = 400;

    private record Move(Path from, Path to) {}

    public static final String PLACEHOLDER = "/images/placeholder.svg";

    private final ImageDirectory imageDirectory;
    private final ImageDirectoryCache directoryCache;
    private final List<PageDerivedCache> derivedCaches;
    private final ScratchSpace scratchSpace;

    public ImageService(ImageDirectory imageDirectory, ImageDirectoryCache directoryCache,
                        List<PageDerivedCache> derivedCaches, ScratchSpace scratchSpace)
    {
        this.imageDirectory = imageDirectory;
        this.directoryCache = directoryCache;
        this.derivedCaches = derivedCaches;
        this.scratchSpace = scratchSpace;
    }

    public String acceptAttribute()
    {
        return ImageDirectory.acceptAttribute();
    }

    public List<String> pageUrls(int chapterId)
    {
        return pageNames(chapterId).stream().map(name -> "/data/" + chapterId + "/" + name).toList();
    }

    public List<String> pageNames(int chapterId)
    {
        return directoryCache.filenames(chapterId);
    }

    public int pageCount(int chapterId)
    {
        return directoryCache.filenames(chapterId).size();
    }

    /** The same files {@link #pageCount} counts, so {@code diskSize / pageCount} is a real average. */
    public long diskSize(int chapterId)
    {
        Path dir = imageDirectory.chapterDir(chapterId);
        long total = 0;
        for (String name : directoryCache.filenames(chapterId))
        {
            try
            {
                total += Files.size(dir.resolve(name));
            }
            catch (IOException ignored)
            {
                // vanished or unreadable between listing and sizing: count it as 0
            }
        }
        return total;
    }

    /** <b>Bypasses the cached listing</b>, as every stats repair must ({@link ImageDirectory#stats(int)}). */
    public PageStats scanStats(int chapterId)
    {
        return imageDirectory.stats(chapterId);
    }

    /** Uncached ({@link ImageDirectory#pageNumbers(int)}), so the pipeline fetches only pages really missing. */
    public Set<Integer> pageNumbersOnDisk(int chapterId)
    {
        return imageDirectory.pageNumbers(chapterId);
    }

    /** See {@link ImageDirectory#canonicalPageNumbers(int)}. */
    public ImageDirectory.PageNumbers pageNumbersForReplacing(int chapterId)
    {
        return imageDirectory.pageNumbersWithCanonical(chapterId);
    }

    public boolean encodedPageSurvives(int chapterId, Collection<Integer> replaced)
    {
        return imageDirectory.encodedPageSurvives(chapterId, replaced);
    }

    /** Uncached, so an in-process retry skips the pages an earlier attempt already fetched. */
    public Set<Integer> stagedPageNumbers(Path stagingDir)
    {
        return imageDirectory.pageNumbersIn(stagingDir);
    }


    /**
     * Deletes what {@link ImageDirectory#supersededVariants} names. Best-effort per file: a failure is logged
     * rather than failing a repair whose real job is the stats.
     */
    public int removeSupersededPages(int chapterId)
    {
        List<String> superseded = imageDirectory.supersededVariants(chapterId);
        if (superseded.isEmpty())
        {
            return 0;
        }
        Path dir = imageDirectory.chapterDir(chapterId);
        int removed = 0;
        for (String name : superseded)
        {
            try
            {
                if (Files.deleteIfExists(dir.resolve(name)))
                {
                    removed++;
                    evictDerived(chapterId, name);
                }
            }
            catch (IOException e)
            {
                log.warn("Could not remove {} of chapter {}, superseded by a compressed copy of page {}", name,
                        chapterId, ImageDirectory.pageNumber(name), e);
            }
        }
        if (removed > 0)
        {
            log.info("Removed {} superseded page file(s) from chapter {}", removed, chapterId);
            directoryCache.evict(chapterId);
        }
        return removed;
    }

    /**
     * Needed only where a rescan must be unconditional ("Rescan images" also repairs an in-place overwrite
     * no timestamp reveals) or right after the app changed the files; otherwise the listing validates itself.
     */
    public void invalidateListing(int chapterId)
    {
        directoryCache.evict(chapterId);
    }

    public String thumbnailUrl(int chapterId)
    {
        List<String> names = directoryCache.filenames(chapterId);
        return names.isEmpty() ? PLACEHOLDER : "/data/" + chapterId + "/" + names.get(0);
    }

    public static String humanReadableSize(long bytes)
    {
        if (bytes < 1024)
        {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        do
        {
            value /= 1024;
            unit++;
        }
        while (value >= 1024 && unit < units.length - 1);
        // Locale.ROOT so the decimal separator is always '.'.
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    public List<Path> saveImages(int chapterId, List<MultipartFile> files) throws IOException
    {
        if (files == null || files.isEmpty())
        {
            return List.of();
        }
        Path dir = imageDirectory.chapterDir(chapterId);
        Files.createDirectories(dir);
        int next = nextPageNumber(chapterId);
        var saved = new ArrayList<Path>();
        for (MultipartFile file : files)
        {
            if (file == null || file.isEmpty())
            {
                continue;
            }
            // Locale.ROOT: a Turkish locale would store "PHOTO.GIF" as "1.gıf", which is not listed as a page.
            String ext = ImageDirectory.extension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
            String filename = ext.isEmpty() ? String.valueOf(next) : next + "." + ext;
            Path target = dir.resolve(filename);
            file.transferTo(target);
            saved.add(target);
            next++;
        }
        directoryCache.evict(chapterId);
        return saved;
    }

    /** Does nothing when {@link ImageDirectory#pageFile} refuses the name. */
    public void deletePage(int chapterId, String filename) throws IOException
    {
        Optional<Path> target = imageDirectory.pageFile(chapterId, filename);
        if (target.isPresent())
        {
            Files.delete(target.get());
            // Full-size derivatives that can never be served again: drop them now, not at the next prune.
            evictDerived(chapterId, filename);
        }
        directoryCache.evict(chapterId);
    }

    /**
     * Renames the pages to the target's next page numbers, extensions kept ({@code 31.jxl} becomes
     * {@code 1.jxl}); returns the new names in order. Never overwrites: numbering starts after the target's
     * highest page.
     * <p>
     * <b>All or nothing, as far as the files allow.</b> On a failure the pages already moved are moved back,
     * since nothing in the app could move the rest later. A page that cannot be moved back is logged and
     * stays in the target folder.
     *
     * @throws IOException when a page could not be moved; the source keeps it and every page after it
     */
    public List<String> movePages(int fromChapterId, List<String> names, int toChapterId) throws IOException
    {
        Path to = imageDirectory.chapterDir(toChapterId);
        var moved = new ArrayList<Move>();
        try
        {
            Files.createDirectories(to);
            int next = imageDirectory.pageNumbersIn(to).stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
            var targets = new ArrayList<String>(names.size());
            for (String name : names)
            {
                Path source = imageDirectory.pageFile(fromChapterId, name).orElseThrow(() ->
                        new NoSuchFileException(name, null, "not a page of chapter " + fromChapterId));
                String ext = ImageDirectory.extension(name);
                Path target = to.resolve(ext.isEmpty() ? String.valueOf(next) : next + "." + ext);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                {
                    throw new FileAlreadyExistsException(target.toString());
                }
                moveRetrying(source, target);
                moved.add(new Move(source, target));
                targets.add(target.getFileName().toString());
                next++;
            }
            return targets;
        }
        catch (IOException | RuntimeException e)
        {
            moveBack(moved, e);
            throw e;
        }
        finally
        {
            directoryCache.evict(fromChapterId);
            directoryCache.evict(toChapterId);
            moved.forEach(move -> evictDerived(fromChapterId, move.from().getFileName().toString()));
        }
    }

    /** Newest first. What is left in {@code moved} afterwards is exactly what stays in the target. */
    private static void moveBack(List<Move> moved, Exception cause)
    {
        for (int i = moved.size() - 1; i >= 0; i--)
        {
            Move move = moved.get(i);
            try
            {
                moveRetrying(move.to(), move.from());
                moved.remove(i);
            }
            catch (IOException e)
            {
                cause.addSuppressed(e);
                log.error("Could not move {} back to {} after a failed page move; it stays where it is",
                        move.to(), move.from(), e);
            }
        }
    }

    /**
     * Retries a file held open for a moment: on Windows djxl (decoding a JPEG XL page for the browser) keeps
     * a page open without {@code FILE_SHARE_DELETE}, so it cannot be renamed. Other failures, and an access
     * denied on a folder that is not writable, cannot change and fail at once.
     */
    private static void moveRetrying(Path source, Path target) throws IOException
    {
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                moveInto(source, target);
                return;
            }
            catch (FileSystemException e)
            {
                boolean heldOpen = e.getClass() == FileSystemException.class
                        || e instanceof AccessDeniedException
                                && Files.isWritable(source.getParent()) && Files.isWritable(target.getParent());
                if (!heldOpen || attempt >= MOVE_ATTEMPTS)
                {
                    throw e;
                }
                try
                {
                    Thread.sleep(MOVE_RETRY_MILLIS);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    public void deleteAll(int chapterId)
    {
        deleteRecursively(imageDirectory.chapterDir(chapterId));
        // Staging and compression intermediates too: nothing else would come back for them, and on a RAM
        // disk they hold memory. Unlike discardStagedPages this may take the intermediates, since a run
        // still encoding a deleted chapter has nowhere left to write them.
        discardStagedPages(chapterId);
        deleteEveryChapterFolder(ScratchArea.COMPRESSION_WORK, chapterId);
        evictDerived(chapterId);
        directoryCache.evict(chapterId);
    }

    public void evictDerived(int chapterId)
    {
        derivedCaches.forEach(cache -> cache.evict(chapterId));
    }

    private void evictDerived(int chapterId, String filename)
    {
        derivedCaches.forEach(cache -> cache.evict(chapterId, filename));
    }


    /**
     * Best-effort recursive delete.
     *
     * <p><b>It never deletes through a link.</b> A symbolic link is removed as a link, and so is a Windows
     * junction, which needs its own check: the JDK reports it as a plain directory, so {@code Files.walk}
     * would empty the folder it points at, on any drive.
     */
    public static void deleteRecursively(Path dir)
    {
        if (!Files.isDirectory(dir))
        {
            return;
        }
        try
        {
            Files.walkFileTree(dir, new SimpleFileVisitor<>()
            {
                @Override
                public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attrs)
                {
                    if (leadsElsewhere(folder, attrs))
                    {
                        deleteQuietly(folder);   // the junction itself, not its target
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                {
                    // Links are not followed, so only a symbolic link itself is deleted here.
                    deleteQuietly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e)
                {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path folder, IOException e)
                {
                    deleteQuietly(folder);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException ignored)
        {
            // best-effort cleanup
        }
    }

    /**
     * A junction is a directory the JDK also flags "other". The flag alone is not enough: cloud-sync folders
     * (OneDrive) have it too, and skipping them would leave a deleted chapter's pages on disk, where nothing
     * ever cleans them up. So it counts only when its real path is not where it sits; when unsure, it is not
     * followed.
     */
    private static boolean leadsElsewhere(Path folder, BasicFileAttributes attrs)
    {
        if (!attrs.isOther())
        {
            return false;
        }
        try
        {
            Path absolute = folder.toAbsolutePath();
            Path parent = absolute.getParent();
            return parent == null
                    || !absolute.toRealPath().equals(parent.toRealPath().resolve(absolute.getFileName()));
        }
        catch (IOException e)
        {
            return true;
        }
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored)
        {
            // best-effort cleanup; a folder that is not empty refuses, which is what leaves it as far as it got
        }
    }

    /**
     * The answer can change between calls (RAM disk free space, Settings), so a download asks once and
     * passes the folder to every staging call of that run.
     *
     * @param compressed whether the download's pages go through Image Compression
     */
    public Path stagingDir(int chapterId, boolean compressed)
    {
        return scratchSpace.downloadStaging(compressed).chapter(chapterId);
    }

    /** An unrecognized extension becomes {@code jpg} rather than a file the app would refuse to show. */
    public Path stagePage(Path stagingDir, int pageNumber, String extension, byte[] bytes) throws IOException
    {
        Files.createDirectories(stagingDir);
        Path file = stagingDir.resolve(pageNumber + "." + storedExtension(extension));
        Files.write(file, bytes);
        return file;
    }

    /**
     * Moves a page a tool wrote (gallery-dl) into staging under its page number. Moved, not copied: the tool's
     * folder is inside staging, so this is a rename, and a page is either staged whole or not at all. Never
     * overwrites a staged page.
     *
     * @throws IOException also for a file that is no image the app shows: renaming it would hide what it is
     */
    public Path stageFile(Path stagingDir, int pageNumber, Path source) throws IOException
    {
        String name = deliveredPageName(pageNumber, source);
        Files.createDirectories(stagingDir);
        Path file = stagingDir.resolve(name);
        Files.move(source, file);
        return file;
    }

    /**
     * Writes a downloaded page straight into the chapter's folder, so it is kept whatever happens to the rest of
     * the download. Like every page the pipeline adds, it appears whole or not at all ({@link #publishPage}). An
     * unrecognized extension becomes {@code jpg}, as when staging.
     */
    public Path landPage(int chapterId, int pageNumber, String extension, byte[] bytes) throws IOException
    {
        Path target = createChapterDir(chapterId).resolve(pageNumber + "." + storedExtension(extension));
        Path part = partFileOf(target);
        try
        {
            writeDurably(part, bytes);
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            deleteQuietly(part);
            throw e;
        }
        finally
        {
            directoryCache.evict(chapterId);
        }
        return target;
    }

    /**
     * Moves a page a tool wrote (gallery-dl) into the chapter's folder under its page number.
     *
     * @throws IOException also for a file that is no image the app shows: renaming it would hide what it is
     */
    public Path landFile(int chapterId, int pageNumber, Path source) throws IOException
    {
        return land(chapterId, source, deliveredPageName(pageNumber, source));
    }

    /** Moves a staged page into the chapter's folder under its own name, once Image Compression is done with it. */
    public Path landStagedPage(int chapterId, Path staged) throws IOException
    {
        return land(chapterId, staged, staged.getFileName().toString());
    }

    private Path land(int chapterId, Path source, String name) throws IOException
    {
        Path target = createChapterDir(chapterId).resolve(name);
        try
        {
            publishPage(source, target);
        }
        finally
        {
            directoryCache.evict(chapterId);
        }
        return target;
    }

    /**
     * Each staged page also replaces the chapter's other images with its base name ({@code 3.jpg} removes
     * {@code 3.jxl}, not {@code 03.png}).
     * <p>
     * <b>The new page goes in before the old one comes out.</b> A crash in between leaves both, which the
     * listing counts once and the still-queued re-download repeats. Deleting first would leave the page
     * absent for good, since a re-download replaces only pages the chapter has.
     *
     * @return how many pages were published
     * @throws IOException when an old version cannot be removed; the compressed page would still be what
     *                     the listing shows, so the re-download fails and is retried
     */
    public int publishReplacingPages(int chapterId, Path staging) throws IOException
    {
        if (!Files.isDirectory(staging))
        {
            return 0;
        }
        Path target = createChapterDir(chapterId);
        // Listed once, not per page: a gallery is hundreds of pages.
        Map<String, List<String>> imagesByBase = imagesByBaseName(target);
        int published = 0;
        try (Stream<Path> files = Files.list(staging))
        {
            for (Path file : files.filter(Files::isRegularFile).toList())
            {
                String name = file.getFileName().toString();
                publishPage(file, target.resolve(name));
                published++;
                for (String old : imagesByBase.getOrDefault(ImageDirectory.baseName(name), List.of()))
                {
                    if (!old.equals(name))
                    {
                        Files.deleteIfExists(target.resolve(old));
                    }
                }
            }
        }
        finally
        {
            directoryCache.evict(chapterId);
            // The whole chapter at once: each per-page eviction lists the cache folders, and a re-download
            // replaces nearly every page.
            if (published > 0)
            {
                evictDerived(chapterId);
            }
        }
        discardStagedPages(chapterId);
        return published;
    }

    private Path createChapterDir(int chapterId) throws IOException
    {
        return Files.createDirectories(imageDirectory.chapterDir(chapterId));
    }

    private static String storedExtension(String extension)
    {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT).strip();
        return ImageDirectory.isImage("x." + ext) ? ext : "jpg";
    }

    private static String deliveredPageName(int pageNumber, Path source) throws IOException
    {
        String ext = PageDownloader.extensionOf(source.getFileName().toString()).toLowerCase(Locale.ROOT);
        if (!ImageDirectory.isImage("x." + ext))
        {
            throw new IOException(source + " is no image the app can show");
        }
        return pageNumber + "." + ext;
    }

    private static Map<String, List<String>> imagesByBaseName(Path dir) throws IOException
    {
        try (Stream<Path> files = Files.list(dir))
        {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(ImageDirectory::isImage)
                    .collect(Collectors.groupingBy(ImageDirectory::baseName));
        }
    }

    /**
     * A page must appear whole or not at all, and stay whole after a power cut. The pipeline re-fetches by which
     * page <i>numbers</i> exist, so a half-written file would read as complete for ever; Image Compression
     * deletes the original right after, so an unwritten replacement would be the only copy left. Nothing that
     * writes a page before it gets here (staging, gallery-dl, an encoder) syncs it, hence the {@link #force}.
     */
    public static void publishPage(Path file, Path target) throws IOException
    {
        force(file);
        moveInto(file, target);
    }

    /**
     * Atomic on the same filesystem or across one (a RAM disk). A plain {@code Files.move} across filesystems
     * would copy straight onto the final name: the torn page this avoids.
     */
    public static void moveInto(Path source, Path target) throws IOException
    {
        try
        {
            // ATOMIC_MOVE replaces an existing target, and fails rather than silently copying across stores.
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException crossFilesystem)
        {
            copyThenRename(source, target);
        }
    }

    static void copyThenRename(Path staged, Path target) throws IOException
    {
        Path part = partFileOf(target);
        Files.copy(staged, part, StandardCopyOption.REPLACE_EXISTING);
        force(part);
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(staged);
    }

    private static Path partFileOf(Path target)
    {
        return target.resolveSibling(target.getFileName() + PART_SUFFIX);
    }

    private static void writeDurably(Path file, byte[] bytes) throws IOException
    {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
        {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining())
            {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    /**
     * Before the rename that makes a file a page, as much as the rename itself: without it a power cut can leave
     * the file renamed but unwritten. Opened for writing, which Windows needs to flush a file.
     */
    private static void force(Path file) throws IOException
    {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE))
        {
            channel.force(true);
        }
    }

    /** Before staging is trusted, so a page a killed run left half-written is never published. */
    public void discardStagedPages(int chapterId)
    {
        // Every folder staging could have used: an earlier run may have chosen differently.
        // Compression intermediates are left alone: a sweep may be encoding this chapter's published pages
        // right now (downloads take no run lock). A killed process's leftovers are cleared at startup.
        deleteEveryChapterFolder(ScratchArea.DOWNLOAD_STAGING, chapterId);
    }

    private void deleteEveryChapterFolder(ScratchArea area, int chapterId)
    {
        scratchSpace.everyRoot(area).forEach(root -> deleteRecursively(ScratchSpace.chapterFolder(root, chapterId)));
    }

    private int nextPageNumber(int chapterId)
    {
        // No MAX_VALUE guard: the listing already drops non-numeric names.
        return directoryCache.filenames(chapterId).stream()
                .mapToInt(ImageDirectory::pageNumber)
                .max()
                .orElse(0) + 1;
    }
}

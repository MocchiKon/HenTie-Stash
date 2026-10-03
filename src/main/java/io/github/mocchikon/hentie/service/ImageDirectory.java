package io.github.mocchikon.hentie.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.github.mocchikon.hentie.config.AppProperties;

/**
 * Every disk read of a chapter's image directory, with <b>no cache</b>; {@link ImageDirectoryCache} is the
 * one caller allowed to memoize {@link #list(int)}. {@link #stats(int)} and {@link #pageNumbers(int)} must
 * never go through it.
 */
@Component
public class ImageDirectory
{
    // jxl because Image Compression writes it: a page the app wrote must count as a page. Browsers that
    // cannot show it get a PNG, which is a delivery concern, not a question of what is a page.
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "jxl");

    private static final String ACCEPT_ATTRIBUTE =
            IMAGE_EXTENSIONS.stream().map(ext -> "." + ext).sorted().collect(Collectors.joining(","));

    /** The formats Image Compression <b>writes</b>. */
    private static final Set<String> ENCODED_EXTENSIONS = Set.of("jxl", "avif");

    /**
     * The encoder-output rank before the filename lets {@link #partition} meet a replacement before what it
     * superseded, in one pass. Filename order alone would not: {@code 3.gif} sorts before {@code 3.jxl}.
     */
    private static final Comparator<String> PAGE_ORDER =
            Comparator.comparingInt(ImageDirectory::pageNumber)
                    .thenComparingInt(ImageDirectory::variantRank)
                    .thenComparing(Comparator.naturalOrder());

    private record PageFiles(List<String> pages, List<String> superseded) {}

    private final AppProperties appProperties;

    public ImageDirectory(AppProperties appProperties)
    {
        this.appProperties = appProperties;
    }

    public Path chapterDir(int chapterId)
    {
        return Paths.get(appProperties.getDataDir()).toAbsolutePath().normalize().resolve(String.valueOf(chapterId));
    }

    /**
     * The only guard between a page name from a request and the disk: empty unless the name is the bare name
     * of a regular file directly in the chapter's folder. It covers more than {@code ..}: an empty name
     * resolves to the folder itself, a root-only name has no file name, a name the platform rejects throws
     * {@code InvalidPathException} (not an {@code IOException}), and {@code sub/3.png} is not a page here.
     * Requiring a <b>regular file</b> keeps a directory out of reach whatever the name resolves to.
     */
    public Optional<Path> pageFile(int chapterId, String filename)
    {
        if (StringUtils.isBlank(filename))
        {
            return Optional.empty();
        }
        Path name;
        try
        {
            name = Paths.get(filename).getFileName();
        }
        catch (InvalidPathException e)
        {
            return Optional.empty();
        }
        if (name == null || !name.toString().equals(filename))
        {
            return Optional.empty();
        }
        Path dir = chapterDir(chapterId);
        Path file = dir.resolve(filename).normalize();
        return file.startsWith(dir) && !file.equals(dir) && Files.isRegularFile(file)
                ? Optional.of(file) : Optional.empty();
    }

    /**
     * Numerically-named image files, in reading order; empty when the directory is missing. Rendering goes
     * through {@link ImageDirectoryCache#filenames(int)} instead.
     */
    public List<String> list(int chapterId)
    {
        Path dir = chapterDir(chapterId);
        if (!Files.isDirectory(dir))
        {
            return List.of();
        }
        try
        {
            return partition(sortedPageNames(dir)).pages();
        }
        catch (IOException e)
        {
            return List.of();
        }
    }

    /**
     * For the explicit repairs to delete: leaving a superseded source out on read does not remove it, and
     * the orphan would stay on disk for ever.
     *
     * <p><b>Superseded means replaced by an encoder output with the same base name, never merely sharing a
     * page number</b>, because whatever this names is deleted.
     */
    public List<String> supersededVariants(int chapterId)
    {
        Path dir = chapterDir(chapterId);
        if (!Files.isDirectory(dir))
        {
            return List.of();
        }
        try
        {
            return partition(sortedPageNames(dir)).superseded();
        }
        catch (IOException e)
        {
            // Unlike stats(), no wrong number gets written from this, so empty is safe.
            return List.of();
        }
    }

    /**
     * <b>Never memoize this.</b> It backs every stats repair, which exists for images the app did not write,
     * so a cache invalidated only by the app's own writes would defeat it.
     * <p>
     * A <b>missing</b> directory is {@link PageStats#EMPTY}. An <b>unreadable</b> one throws: "0 pages" would
     * be written onto {@code page_num}/{@code disk_size} and drop the chapter out of every page filter.
     */
    public PageStats stats(int chapterId)
    {
        Path dir = chapterDir(chapterId);
        var sizes = new HashMap<String, Long>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir))
        {
            for (Path entry : entries)
            {
                String name = entry.getFileName().toString();
                if (!isPageName(name))
                {
                    continue;
                }
                try
                {
                    var attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    if (attrs.isRegularFile())
                    {
                        sizes.put(name, attrs.size());
                    }
                }
                catch (IOException ignored)
                {
                    // vanished or unreadable between listing and stat: not a page we can count
                }
            }
        }
        catch (NoSuchFileException | NotDirectoryException absent)
        {
            return PageStats.EMPTY;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Could not read the image directory of chapter " + chapterId, e);
        }
        // Must match list(), or page_num is permanently at odds with what the chapter shows.
        List<String> pages = partition(sizes.keySet().stream().sorted(PAGE_ORDER).toList()).pages();
        return new PageStats(pages.size(), pages.stream().mapToLong(sizes::get).sum());
    }

    /**
     * <b>Never memoize this either:</b> the download pipeline decides from it which pages are missing, and a
     * stale answer would skip a missing page or re-fetch one that is there.
     */
    public Set<Integer> pageNumbers(int chapterId)
    {
        return pageNumbersIn(chapterDir(chapterId));
    }

    /**
     * Any page directory, staging included. A missing directory is an empty set; an <b>unreadable</b> one
     * throws, because the download pipeline reads "no pages" as permission to fetch and publish the whole
     * gallery over the user's pages.
     */
    public Set<Integer> pageNumbersIn(Path dir)
    {
        return pageNumbersIn(dir, name -> true);
    }

    /**
     * Pages named exactly as the download pipeline names them ({@code 3.jpg}, never {@code 03.jpg}): the only
     * ones a full-quality re-download may replace, or {@code 03.png} would end up beside a second page 3.
     */
    public Set<Integer> canonicalPageNumbers(int chapterId)
    {
        return pageNumbersIn(chapterDir(chapterId), ImageDirectory::isCanonicalPageName);
    }

    public record PageNumbers(Set<Integer> all, Set<Integer> canonical) {}

    /** One uncached read for both sets: a gallery is hundreds of pages. */
    public PageNumbers pageNumbersWithCanonical(int chapterId)
    {
        List<String> names = pageNamesIn(chapterDir(chapterId));
        return new PageNumbers(numbersOf(names, name -> true), numbersOf(names, ImageDirectory::isCanonicalPageName));
    }

    /**
     * Whether an encoder output would survive a re-download replacing {@code replaced}. Only an encoder
     * output can be a compressed page; page numbers alone cannot tell, since {@code 03.jxl} shares 3 with
     * {@code 3.jpg} but is not replaced.
     */
    public boolean encodedPageSurvives(int chapterId, Collection<Integer> replaced)
    {
        return !pageNumbersIn(chapterDir(chapterId), name -> isEncoderOutput(name)
                && !(isCanonicalPageName(name) && replaced.contains(pageNumber(name)))).isEmpty();
    }

    private static boolean isCanonicalPageName(String name)
    {
        return baseName(name).equals(Integer.toString(pageNumber(name)));
    }

    private static Set<Integer> pageNumbersIn(Path dir, Predicate<String> nameFilter)
    {
        return numbersOf(pageNamesIn(dir), nameFilter);
    }

    private static Set<Integer> numbersOf(List<String> pageNames, Predicate<String> nameFilter)
    {
        return pageNames.stream().filter(nameFilter).map(ImageDirectory::pageNumber).collect(Collectors.toSet());
    }

    private static List<String> pageNamesIn(Path dir)
    {
        var names = new ArrayList<String>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir))
        {
            for (Path entry : entries)
            {
                String name = entry.getFileName().toString();
                if (isImage(name) && pageNumber(name) != Integer.MAX_VALUE && Files.isRegularFile(entry))
                {
                    names.add(name);
                }
            }
        }
        catch (NoSuchFileException | NotDirectoryException absent)
        {
            return List.of();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Could not read the page directory " + dir, e);
        }
        return names;
    }


    /** Null when the directory is missing. Only {@link ImageDirectoryCache} needs it. */
    FileTime lastModified(int chapterId)
    {
        try
        {
            var attrs = Files.readAttributes(chapterDir(chapterId), BasicFileAttributes.class);
            return attrs.isDirectory() ? attrs.lastModifiedTime() : null;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** Shared by {@link #list} and {@link #supersededVariants} so they cannot disagree about which file is the page. */
    private static List<String> sortedPageNames(Path dir) throws IOException
    {
        try (Stream<Path> stream = Files.list(dir))
        {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(ImageDirectory::isPageName)
                    .sorted(PAGE_ORDER)
                    .toList();
        }
    }

    /**
     * The compressor writes the encoder output before deleting the source, so a killed run leaves both;
     * counting both would corrupt {@code page_num}. A file is superseded when an <b>encoder output with the
     * same base name</b> is already a page.
     *
     * <p>Nothing else is collapsed ({@code 3.jpg} beside {@code 03.jpg} or {@code 3.png}): nothing says which
     * is unwanted, and the repairs delete what this calls superseded.
     */
    private static PageFiles partition(List<String> sortedNames)
    {
        var firstByBase = new HashMap<String, String>();
        var pages = new ArrayList<String>();
        var superseded = new ArrayList<String>();
        for (String name : sortedNames)
        {
            // Sorted, so an encoder output is met before every other file sharing its base name.
            String first = firstByBase.putIfAbsent(baseName(name), name);
            if (first != null && isEncoderOutput(first))
            {
                superseded.add(name);
            }
            else
            {
                pages.add(name);
            }
        }
        return new PageFiles(List.copyOf(pages), List.copyOf(superseded));
    }

    private static int variantRank(String filename)
    {
        return isEncoderOutput(filename) ? 0 : 1;
    }

    public static boolean isEncoderOutput(String filename)
    {
        // Locale.ROOT for the same reason as isImage().
        return ENCODED_EXTENSIONS.contains(extension(filename).toLowerCase(Locale.ROOT));
    }

    /** Numeric names only, so a scraper's {@code thumbnail.jpg} is never a page or the cover. */
    private static boolean isPageName(String filename)
    {
        return isImage(filename) && pageNumber(filename) != Integer.MAX_VALUE;
    }

    public static String baseName(String filename)
    {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    public static boolean isImage(String filename)
    {
        // Locale.ROOT: under a Turkish locale "GIF" folds to "gıf" (dotless i) and would stop being a page.
        return IMAGE_EXTENSIONS.contains(extension(filename).toLowerCase(Locale.ROOT));
    }

    /** The file-input {@code accept} value, so the picker offers only what the server will list. */
    public static String acceptAttribute()
    {
        return ACCEPT_ATTRIBUTE;
    }

    public static String extension(String filename)
    {
        if (filename == null)
        {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1);
    }

    /** Numeric value of the filename without extension; non-numeric names sort last. */
    public static int pageNumber(String filename)
    {
        String base = filename;
        int dot = filename.lastIndexOf('.');
        if (dot >= 0)
        {
            base = filename.substring(0, dot);
        }
        try
        {
            return Integer.parseInt(base.trim());
        }
        catch (NumberFormatException e)
        {
            return Integer.MAX_VALUE;
        }
    }
}

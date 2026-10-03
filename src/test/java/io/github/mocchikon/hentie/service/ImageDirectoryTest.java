package io.github.mocchikon.hentie.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mocchikon.hentie.config.AppProperties;

import static org.assertj.core.api.Assertions.*;

class ImageDirectoryTest
{
    @Test
    void shouldParseLeadingNumberAndPushNonNumericLastWhenComputingPageNumber()
    {
        // WHEN + THEN
        assertThat(ImageDirectory.pageNumber("1.jpg")).isEqualTo(1);
        assertThat(ImageDirectory.pageNumber("10.png")).isEqualTo(10);
        assertThat(ImageDirectory.pageNumber("2.webp")).isEqualTo(2);
        assertThat(ImageDirectory.pageNumber("cover.jpg")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void shouldRecogniseImageExtensionsWhenCheckingFileNames()
    {
        // WHEN + THEN
        assertThat(ImageDirectory.isImage("3.JPG")).isTrue();
        assertThat(ImageDirectory.isImage("3.webp")).isTrue();
        assertThat(ImageDirectory.isImage("3.gif")).isTrue();
        assertThat(ImageDirectory.isImage("notes.txt")).isFalse();
        assertThat(ImageDirectory.isImage("3")).isFalse();
    }

    /**
     * A Turkish default locale lower-cases 'I' to the dotless 'ı', so a locale-dependent fold would stop a
     * {@code .GIF} or {@code .AVIF} page being a page: dropped from the listing and the stats, and re-fetched.
     */
    @Test
    void shouldRecogniseUpperCaseExtensionsWhenTheDefaultLocaleFoldsTheLetterIDifferently()
    {
        // GIVEN a default locale whose lower-casing of 'I' is not the ASCII 'i'.
        var original = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
        try
        {
            // WHEN + THEN the extensions that contain an 'I' are still recognised.
            assertThat(ImageDirectory.isImage("3.GIF")).isTrue();
            assertThat(ImageDirectory.isImage("3.AVIF")).isTrue();
            assertThat(ImageDirectory.isImage("notes.TXT")).isFalse();
        }
        finally
        {
            Locale.setDefault(original);
        }
    }

    @Test
    void shouldSortNumericallyWhenFileNamesHaveNumericPrefixes()
    {
        // GIVEN
        List<String> files = List.of("10.jpg", "2.jpg", "1.jpg", "11.jpg");

        // WHEN
        List<String> sorted = files.stream()
                .sorted(Comparator.comparingInt(ImageDirectory::pageNumber)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        // THEN
        assertThat(sorted).containsExactly("1.jpg", "2.jpg", "10.jpg", "11.jpg");
    }

    @Test
    void shouldExcludeNonNumericFilesWhenFilteringPages()
    {
        // GIVEN
        List<String> files = List.of("1.jpg", "thumbnail.jpg", "2.jpg", "cover.png");

        // WHEN
        List<String> pages = files.stream()
                .filter(ImageDirectory::isImage)
                .filter(name -> ImageDirectory.pageNumber(name) != Integer.MAX_VALUE)
                .sorted(Comparator.comparingInt(ImageDirectory::pageNumber))
                .toList();

        // THEN
        assertThat(pages).containsExactly("1.jpg", "2.jpg");
    }

    /**
     * A re-download publishes page 2 as {@code 2.<ext>} and removes the other images named {@code 2}, so
     * replacing a {@code 02.png} would leave it beside a second page 2.
     */
    @Test
    void shouldCountOnlyPagesNamedTheWayThePipelineNamesThemAsCanonical(@TempDir Path dataDir) throws IOException
    {
        // GIVEN a chapter folder mixing canonical names with ones that merely parse to a page number.
        var properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        var directory = new ImageDirectory(properties);
        Path chapter = Files.createDirectories(directory.chapterDir(7));
        for (String name : List.of("1.jxl", "02.png", "3.jpg", "+4.png", "5.avif", "cover.jpg", "6.txt"))
        {
            Files.writeString(chapter.resolve(name), "x");
        }

        // WHEN + THEN
        assertThat(directory.canonicalPageNumbers(7)).containsExactlyInAnyOrder(1, 3, 5);
        // ...while every page number stays a page.
        assertThat(directory.pageNumbers(7)).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        // ...and a chapter with no folder has none.
        assertThat(directory.canonicalPageNumbers(8)).isEmpty();
        // ...and the one-read variant answers both the same way.
        var both = directory.pageNumbersWithCanonical(7);
        assertThat(both.all()).containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        assertThat(both.canonical()).containsExactlyInAnyOrder(1, 3, 5);
    }

    /** A user's own {@code 02.jpg} is no compressed page; a {@code 02.jxl} beside the replaced {@code 2.jxl} is. */
    @Test
    void shouldCountOnlyAnEncoderOutputLeftUnreplacedAsASurvivingCompressedPage(@TempDir Path dataDir)
            throws IOException
    {
        // GIVEN compressed pages 1 and 2, a user's JPEG kept as 02.jpg, and an original past them.
        var properties = new AppProperties();
        properties.setDataDir(dataDir.toString());
        var directory = new ImageDirectory(properties);
        Path chapter = Files.createDirectories(directory.chapterDir(7));
        for (String name : List.of("1.jxl", "2.avif", "02.jpg", "3.png"))
        {
            Files.writeString(chapter.resolve(name), "x");
        }

        // WHEN + THEN - replacing 1 and 2 leaves no compressed page, whatever else stays...
        assertThat(directory.encodedPageSurvives(7, List.of(1, 2))).isFalse();
        // ...leaving 2 out leaves 2.avif...
        assertThat(directory.encodedPageSurvives(7, List.of(1))).isTrue();
        // ...and a 02.jxl is not replaced by page 2 arriving, though it shares its number.
        Files.writeString(chapter.resolve("02.jxl"), "x");
        assertThat(directory.encodedPageSurvives(7, List.of(1, 2))).isTrue();
    }

    @Test
    void shouldListOnlyRecognisedImageExtensionsWhenBuildingAcceptAttribute()
    {
        // Otherwise the file picker would offer a type the server silently ignores as a page.
        // WHEN
        String[] tokens = ImageDirectory.acceptAttribute().split(",");

        // THEN
        assertThat(tokens).isNotEmpty();
        for (String token : tokens)
        {
            assertThat(token).startsWith(".");
            assertThat(ImageDirectory.isImage("page" + token)).isTrue();
        }
    }
}

package io.github.mocchikon.hentie.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against the test data directory; each test uses a distinct chapter id and cleans up. */
@SpringBootTest
class ImageServiceIT
{
    @Autowired ImageService imageService;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ImageDirectoryCache directoryCache;

    // Chapter ids well outside anything other suites create, to avoid directory collisions.
    private static final int CH_A = 900_101;
    private static final int CH_B = 900_102;
    private static final int CH_C = 900_103;
    private static final int CH_D = 900_104;
    private static final int CH_E = 900_105;
    private static final int CH_F = 900_106;
    private static final int CH_G = 900_107;
    private static final int CH_H = 900_108;
    private static final int CH_I = 900_109;
    private static final int CH_J = 900_110;
    private static final int CH_K = 900_111;
    private static final int CH_L = 900_112;
    private static final int CH_M = 900_113;
    private static final int CH_N = 900_114;
    private static final int CH_O = 900_115;
    private static final int CH_P = 900_116;

    /** Captured before any test runs, so a test that switches the default locale cannot leak it. */
    private static final Locale DEFAULT_LOCALE = Locale.getDefault();

    @AfterEach
    void cleanUp()
    {
        Locale.setDefault(DEFAULT_LOCALE);
        for (int id : new int[]{CH_A, CH_B, CH_C, CH_D, CH_E, CH_F, CH_G, CH_H, CH_I, CH_J, CH_K, CH_L, CH_M, CH_N,
                CH_O, CH_P})
        {
            imageService.deleteAll(id);
            imageService.discardStagedPages(id);
        }
    }

    private MockMultipartFile img(String filename)
    {
        return new MockMultipartFile("files", filename, "image/jpeg", ("data-" + filename).getBytes(StandardCharsets.UTF_8));
    }

    /** Page files written straight into the folder, each holding text that names it. */
    private void writePages(int chapterId, String... pages) throws IOException
    {
        Path dir = imageDirectory.chapterDir(chapterId);
        Files.createDirectories(dir);
        for (String page : pages)
        {
            Files.writeString(dir.resolve(page), "page " + page, StandardCharsets.UTF_8);
        }
    }

    private String pageText(int chapterId, String page) throws IOException
    {
        return Files.readString(imageDirectory.chapterDir(chapterId).resolve(page), StandardCharsets.UTF_8);
    }

    /**
     * The scraper writes pages straight into the folder and nothing evicts the cached listing, so the listing
     * must notice the directory's changed modification time.
     */
    @Test
    void shouldSeeAPageThatAppearedOnDiskWithNothingEvictingTheListing() throws Exception
    {
        // GIVEN a cached listing old enough that an UNCHANGED directory timestamp would be trusted
        // (see ImageDirectoryCache.CLOCK_GRANULARITY_MS).
        imageService.saveImages(CH_K, List.of(img("first.jpg")));
        assertThat(imageService.pageCount(CH_K)).isEqualTo(1);
        Thread.sleep(ImageDirectoryCache.CLOCK_GRANULARITY_MS + 100);
        assertThat(imageService.pageCount(CH_K)).isEqualTo(1);   // re-read: now a settled listing is cached

        // WHEN a page appears the way the scraper adds one - written into the folder, nothing evicted.
        Path dir = imageDirectory.chapterDir(CH_K);
        Files.writeString(dir.resolve("2.jpg"), "second", StandardCharsets.UTF_8);

        // THEN the next read sees it in the listing, the page count and the byte total.
        assertThat(imageService.pageCount(CH_K)).isEqualTo(2);
        assertThat(imageService.pageUrls(CH_K)).containsExactly("/data/" + CH_K + "/1.jpg", "/data/" + CH_K + "/2.jpg");
        assertThat(imageService.diskSize(CH_K)).isEqualTo(20L);   // "data-first.jpg" (14) + "second" (6)
    }

    @Test
    void shouldNumberSequentiallyAndListInReadingOrderWhenSavingImages() throws IOException
    {
        // WHEN
        imageService.saveImages(CH_A, List.of(img("first.jpg"), img("second.png")));

        // THEN
        assertThat(imageService.pageCount(CH_A)).isEqualTo(2);
        assertThat(imageService.pageUrls(CH_A))
                .containsExactly("/data/" + CH_A + "/1.jpg", "/data/" + CH_A + "/2.png");
        assertThat(imageService.thumbnailUrl(CH_A)).isEqualTo("/data/" + CH_A + "/1.jpg");
    }

    @Test
    void shouldContinueNumberingFromExistingPagesWhenSavingMoreImages() throws IOException
    {
        // GIVEN
        imageService.saveImages(CH_B, List.of(img("a.jpg")));

        // WHEN
        imageService.saveImages(CH_B, List.of(img("b.jpg")));

        // THEN
        assertThat(imageService.pageUrls(CH_B))
                .containsExactly("/data/" + CH_B + "/1.jpg", "/data/" + CH_B + "/2.jpg");
    }

    @Test
    void shouldReturnPlaceholderThumbnailWhenChapterHasNoImages()
    {
        // WHEN + THEN
        assertThat(imageService.pageCount(CH_C)).isZero();
        assertThat(imageService.thumbnailUrl(CH_C)).isEqualTo(ImageService.PLACEHOLDER);
    }

    @Test
    void shouldIgnoreNullAndEmptyInputWhenSavingImages() throws IOException
    {
        // WHEN
        imageService.saveImages(CH_C, null);
        imageService.saveImages(CH_C, List.of());
        imageService.saveImages(CH_C, List.of(
                new MockMultipartFile("files", "empty.jpg", "image/jpeg", new byte[0])));

        // THEN
        assertThat(imageService.pageCount(CH_C)).isZero();
    }

    @Test
    void shouldRemoveTheFileWhenDeletingAPage() throws IOException
    {
        // GIVEN
        imageService.saveImages(CH_D, List.of(img("1.jpg"), img("2.jpg")));

        // WHEN
        imageService.deletePage(CH_D, "1.jpg");

        // THEN
        assertThat(imageService.pageUrls(CH_D)).containsExactly("/data/" + CH_D + "/2.jpg");
    }

    @Test
    void shouldGuardAgainstPathTraversalWhenDeletingAPage() throws IOException
    {
        // GIVEN
        imageService.saveImages(CH_D, List.of(img("1.jpg")));

        // A sentinel in the data-dir root (the chapter directory's parent) must survive a traversal attempt.
        Path chapterDir = imageDirectory.chapterDir(CH_D);
        Path sentinel = chapterDir.getParent().resolve("sentinel-should-survive.txt");
        Files.writeString(sentinel, "keep me", StandardCharsets.UTF_8);

        // WHEN + THEN
        try
        {
            imageService.deletePage(CH_D, "../sentinel-should-survive.txt");
            assertThat(Files.exists(sentinel)).isTrue();
        }
        finally
        {
            Files.deleteIfExists(sentinel);
        }
    }

    /**
     * A compression run interrupted between writing the new file and deleting the old one leaves both.
     * Counted twice, the page would show twice and {@code page_num} would disagree with the disk for good,
     * since every rescan counts the same way.
     */
    @Test
    void shouldCountASupersededSourceAndItsReplacementAsOnePageWhenListing() throws IOException
    {
        // GIVEN page 1 as a leftover JPEG and as the JPEG XL that replaced it, plus a second page.
        Path dir = imageDirectory.chapterDir(CH_N);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1.jpg"), "old page one");
        Files.writeString(dir.resolve("1.jxl"), "new page one");
        Files.writeString(dir.resolve("2.jpg"), "page two");
        directoryCache.evict(CH_N);

        // WHEN
        List<String> urls = imageService.pageUrls(CH_N);

        // THEN two pages, and the encoded file wins every time.
        assertThat(urls).containsExactly("/data/" + CH_N + "/1.jxl", "/data/" + CH_N + "/2.jpg");
        assertThat(imageService.pageCount(CH_N)).isEqualTo(2);
        assertThat(imageService.thumbnailUrl(CH_N)).isEqualTo("/data/" + CH_N + "/1.jxl");
    }

    /** Plain filename order would pick the source here, and nothing would ever repair it. */
    @Test
    void shouldPreferTheEncodedFileOverALeftoverSourceThatSortsBeforeIt() throws IOException
    {
        // GIVEN a GIF replaced by a JPEG XL, the GIF left behind. "1.gif" < "1.jxl" lexicographically.
        Path dir = imageDirectory.chapterDir(CH_N);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1.gif"), "the original");
        Files.writeString(dir.resolve("1.jxl"), "what replaced it");
        directoryCache.evict(CH_N);

        // WHEN / THEN the listing and the uncached stats scan both answer with the JPEG XL.
        assertThat(imageService.pageUrls(CH_N)).containsExactly("/data/" + CH_N + "/1.jxl");
        assertThat(imageService.scanStats(CH_N).pageCount()).isEqualTo(1);
        assertThat(imageService.scanStats(CH_N).diskSize()).isEqualTo("what replaced it".length());
    }

    /**
     * Left on disk, the source takes space {@code disk_size} cannot report, and {@code ImageCompressor} may
     * never re-encode that page to remove it.
     */
    @Test
    void shouldDeleteTheSupersededSourceOnAnExplicitRescan() throws IOException
    {
        // GIVEN page 1 present twice, and a second page that is not duplicated.
        Path dir = imageDirectory.chapterDir(CH_N);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1.gif"), "the leftover original");
        Files.writeString(dir.resolve("1.jxl"), "what replaced it");
        Files.writeString(dir.resolve("2.jpg"), "page two");
        directoryCache.evict(CH_N);

        // WHEN
        int removed = imageService.removeSupersededPages(CH_N);

        // THEN only the superseded source is gone.
        assertThat(removed).isEqualTo(1);
        assertThat(Files.exists(dir.resolve("1.gif"))).isFalse();
        assertThat(Files.exists(dir.resolve("1.jxl"))).isTrue();
        assertThat(Files.exists(dir.resolve("2.jpg"))).isTrue();
        // ...and the numbers now describe what is really on disk.
        assertThat(imageService.scanStats(CH_N).pageCount()).isEqualTo(2);
        assertThat(imageService.scanStats(CH_N).diskSize())
                .isEqualTo("what replaced it".length() + "page two".length());
    }

    @Test
    void shouldRemoveNothingWhenNoPageHasBeenSuperseded() throws IOException
    {
        // GIVEN
        Path dir = imageDirectory.chapterDir(CH_N);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1.jxl"), "page one");
        Files.writeString(dir.resolve("2.jpg"), "page two");
        directoryCache.evict(CH_N);

        // WHEN / THEN
        assertThat(imageService.removeSupersededPages(CH_N)).isZero();
        assertThat(imageService.pageCount(CH_N)).isEqualTo(2);
        assertThat(Files.exists(dir.resolve("1.jxl"))).isTrue();
        assertThat(Files.exists(dir.resolve("2.jpg"))).isTrue();
    }

    /**
     * {@code 3.jpg} beside {@code 03.jpg}, or {@code 1.jpg} beside {@code 1.png}, can be legitimate, and what
     * the repairs call superseded is deleted for good. Only an encoder output with the same base name counts.
     */
    @Test
    void shouldKeepBothFilesWhenTwoPagesMerelyShareAPageNumber() throws IOException
    {
        // GIVEN two pairs that share a page number but were never a compression run's source and output.
        Path dir = imageDirectory.chapterDir(CH_N);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1.jpg"), "a");
        Files.writeString(dir.resolve("1.png"), "bb");
        Files.writeString(dir.resolve("3.jpg"), "ccc");
        Files.writeString(dir.resolve("03.jpg"), "dddd");
        directoryCache.evict(CH_N);

        // WHEN
        int removed = imageService.removeSupersededPages(CH_N);

        // THEN nothing is deleted...
        assertThat(removed).isZero();
        assertThat(dir.resolve("1.jpg")).exists();
        assertThat(dir.resolve("1.png")).exists();
        assertThat(dir.resolve("3.jpg")).exists();
        assertThat(dir.resolve("03.jpg")).exists();
        // ...every file is still a page, and the listing and the stats scan agree on that.
        assertThat(imageService.pageUrls(CH_N)).hasSize(4);
        assertThat(imageService.scanStats(CH_N).pageCount()).isEqualTo(4);
        assertThat(imageService.scanStats(CH_N).diskSize()).isEqualTo(10L);
    }

    @Test
    void shouldRemoveOnlyTheSourceWithTheSameBaseNameAsTheEncodedPage() throws IOException
    {
        // GIVEN an interrupted run's pair (1.jpg -> 1.jxl) beside an unrelated 01.jpg.
        Path dir = imageDirectory.chapterDir(CH_N);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1.jpg"), "the leftover original");
        Files.writeString(dir.resolve("1.jxl"), "what replaced it");
        Files.writeString(dir.resolve("01.jpg"), "a page of its own");
        directoryCache.evict(CH_N);

        // WHEN
        int removed = imageService.removeSupersededPages(CH_N);

        // THEN only 1.jpg goes; the listing had already left it out and keeps 01.jpg.
        assertThat(removed).isEqualTo(1);
        assertThat(dir.resolve("1.jpg")).doesNotExist();
        assertThat(dir.resolve("1.jxl")).exists();
        assertThat(dir.resolve("01.jpg")).exists();
        assertThat(imageService.pageUrls(CH_N))
                .containsExactly("/data/" + CH_N + "/1.jxl", "/data/" + CH_N + "/01.jpg");
        assertThat(imageService.scanStats(CH_N).pageCount()).isEqualTo(2);
    }

    /** Otherwise {@code page_num} and {@code disk_size} would describe a chapter the app never shows. */
    @Test
    void shouldCountASupersededSourceAndItsReplacementAsOnePageWhenScanningStats() throws IOException
    {
        // GIVEN the same directory as above.
        Path dir = imageDirectory.chapterDir(CH_N);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1.jpg"), "12345");        // 5 bytes - the leftover duplicate
        Files.writeString(dir.resolve("1.jxl"), "1234567890");   // 10 bytes - the encoded one, which wins
        Files.writeString(dir.resolve("2.jpg"), "123");          // 3 bytes
        directoryCache.evict(CH_N);

        // WHEN
        PageStats stats = imageService.scanStats(CH_N);

        // THEN the duplicate adds neither a page nor its bytes, and the file kept is the listing's.
        assertThat(stats.pageCount()).isEqualTo(2);
        assertThat(stats.diskSize()).isEqualTo(13L);
        assertThat(stats.diskSize()).isEqualTo(imageService.diskSize(CH_N));
    }

    @Test
    void shouldOrderFilenamesNumericallyWhenNamesAreDoubleDigit() throws IOException
    {
        // GIVEN
        // Out of order and double-digit, so a lexicographic sort ("1", "10", "2") would fail.
        Path dir = imageDirectory.chapterDir(CH_F);
        Files.createDirectories(dir);
        for (String name : List.of("10.jpg", "2.jpg", "1.jpg"))
        {
            Files.writeString(dir.resolve(name), "data");
        }
        directoryCache.evict(CH_F);

        // WHEN
        List<String> urls = imageService.pageUrls(CH_F);

        // THEN
        assertThat(urls).containsExactly(
                "/data/" + CH_F + "/1.jpg", "/data/" + CH_F + "/2.jpg", "/data/" + CH_F + "/10.jpg");
    }

    @Test
    void shouldExcludeNonNumericFilesFromPagesAndThumbnailWhenListing() throws IOException
    {
        // GIVEN
        Path dir = imageDirectory.chapterDir(CH_G);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("thumbnail.jpg"), "cover");
        Files.writeString(dir.resolve("1.jpg"), "page1");
        Files.writeString(dir.resolve("2.jpg"), "page2");
        directoryCache.evict(CH_G);

        // WHEN + THEN
        assertThat(imageService.pageCount(CH_G)).isEqualTo(2);
        assertThat(imageService.pageUrls(CH_G))
                .containsExactly("/data/" + CH_G + "/1.jpg", "/data/" + CH_G + "/2.jpg");
        assertThat(imageService.thumbnailUrl(CH_G)).isEqualTo("/data/" + CH_G + "/1.jpg");
    }

    @Test
    void shouldSkipPastGapsLeftByADeletedPageWhenComputingNextPageNumber() throws IOException
    {
        // GIVEN
        imageService.saveImages(CH_H, List.of(img("a.jpg"), img("b.jpg")));   // -> 1.jpg, 2.jpg
        imageService.deletePage(CH_H, "1.jpg");                              // leaves only 2.jpg

        // WHEN
        imageService.saveImages(CH_H, List.of(img("c.jpg")));

        // THEN
        // Must continue from the highest surviving number (3), not refill the gap left at 1.
        assertThat(imageService.pageUrls(CH_H))
                .containsExactly("/data/" + CH_H + "/2.jpg", "/data/" + CH_H + "/3.jpg");
    }

    @Test
    void shouldLowercaseTheStoredExtensionWhenSavingImages() throws IOException
    {
        // GIVEN
        MockMultipartFile upper = new MockMultipartFile(
                "files", "PHOTO.JPG", "image/jpeg", "bytes".getBytes(StandardCharsets.UTF_8));

        // WHEN
        imageService.saveImages(CH_I, List.of(upper));

        // THEN
        assertThat(imageService.pageUrls(CH_I)).containsExactly("/data/" + CH_I + "/1.jpg");
    }

    /**
     * A Turkish locale folds "GIF" to "gıf" (dotless i), which would store the page under a name
     * {@link ImageDirectory#isImage} refuses: on disk, but invisible everywhere.
     */
    @Test
    void shouldStoreAListablePageWhenUploadingAnUpperCaseGifUnderATurkishLocale() throws IOException
    {
        // GIVEN a default locale whose lower-casing of 'I' is not the ASCII 'i'.
        Locale.setDefault(Locale.of("tr", "TR"));
        var upper = new MockMultipartFile(
                "files", "PHOTO.GIF", "image/gif", "bytes".getBytes(StandardCharsets.UTF_8));

        // WHEN
        imageService.saveImages(CH_L, List.of(upper));

        // THEN it is stored under the ASCII extension and counts as a page.
        assertThat(imageService.pageUrls(CH_L)).containsExactly("/data/" + CH_L + "/1.gif");
        assertThat(imageService.pageCount(CH_L)).isEqualTo(1);
        assertThat(imageService.thumbnailUrl(CH_L)).isEqualTo("/data/" + CH_L + "/1.gif");
    }

    /** An unrecognised extension falls back to {@code jpg}, so a locale-dependent fold would rename a good page. */
    @Test
    void shouldKeepTheSourceExtensionWhenStagingAnUpperCaseGifUnderATurkishLocale() throws IOException
    {
        // GIVEN a default locale whose lower-casing of 'I' is not the ASCII 'i'.
        Locale.setDefault(Locale.of("tr", "TR"));

        // WHEN a page the source serves as .GIF is staged.
        imageService.stagePage(imageService.stagingDir(CH_M, false), 1, "GIF",
                "bytes".getBytes(StandardCharsets.UTF_8));

        // THEN it keeps its own extension rather than taking the jpg fallback.
        try (var staged = Files.list(imageService.stagingDir(CH_M, false)))
        {
            assertThat(staged.map(p -> p.getFileName().toString())).containsExactly("1.gif");
        }
    }

    @Test
    void shouldSumOnlyNumericPageFilesWhenComputingDiskSize() throws IOException
    {
        // GIVEN two page images (100 + 250 bytes) plus a non-numeric cover.
        Path dir = imageDirectory.chapterDir(CH_J);
        Files.createDirectories(dir);
        Files.write(dir.resolve("1.jpg"), new byte[100]);
        Files.write(dir.resolve("2.jpg"), new byte[250]);
        Files.write(dir.resolve("thumbnail.jpg"), new byte[9_999]);   // non-numeric -> not a page, not counted
        directoryCache.evict(CH_J);

        // WHEN + THEN only the two numbered pages contribute to both count and size.
        assertThat(imageService.pageCount(CH_J)).isEqualTo(2);
        assertThat(imageService.diskSize(CH_J)).isEqualTo(350L);
    }

    @Test
    void shouldReturnZeroDiskSizeWhenChapterHasNoImages()
    {
        // WHEN + THEN a chapter with no directory/images has zero disk size (never null).
        assertThat(imageService.diskSize(CH_C)).isZero();
    }

    @Test
    void shouldRemoveTheChapterDirectoryWhenDeletingAll() throws IOException
    {
        // GIVEN
        imageService.saveImages(CH_E, List.of(img("1.jpg"), img("2.jpg")));
        assertThat(Files.isDirectory(imageDirectory.chapterDir(CH_E))).isTrue();

        // WHEN
        imageService.deleteAll(CH_E);

        // THEN
        assertThat(Files.exists(imageDirectory.chapterDir(CH_E))).isFalse();
        assertThat(imageService.pageCount(CH_E)).isZero();
    }

    @Test
    void shouldMovePagesIntoAnotherChapterNumberedFromOneKeepingTheirFormats() throws IOException
    {
        // GIVEN a chapter of four pages in three formats, its listing cached.
        writePages(CH_O, "1.jpg", "2.jpg", "3.png", "4.jxl");
        assertThat(imageService.pageNames(CH_O)).hasSize(4);

        // WHEN its last two move to a chapter that has no pages yet.
        List<String> moved = imageService.movePages(CH_O, List.of("3.png", "4.jxl"), CH_P);

        // THEN they are that chapter's pages 1 and 2, in the order given, bytes and format unchanged...
        assertThat(moved).containsExactly("1.png", "2.jxl");
        assertThat(pageText(CH_P, "1.png")).isEqualTo("page 3.png");
        assertThat(pageText(CH_P, "2.jxl")).isEqualTo("page 4.jxl");
        // ...and both listings say so straight away, the cached one included.
        assertThat(imageService.pageNames(CH_O)).containsExactly("1.jpg", "2.jpg");
        assertThat(imageService.pageNames(CH_P)).containsExactly("1.png", "2.jxl");
    }

    @Test
    void shouldNumberMovedPagesAfterThePagesTheTargetAlreadyHas() throws IOException
    {
        // GIVEN a target folder that already holds a page, which must never be overwritten.
        writePages(CH_O, "1.jpg", "2.jpg");
        writePages(CH_P, "1.jpg");

        // WHEN
        List<String> moved = imageService.movePages(CH_O, List.of("2.jpg"), CH_P);

        // THEN the moved page comes after it.
        assertThat(moved).containsExactly("2.jpg");
        assertThat(pageText(CH_P, "1.jpg")).isEqualTo("page 1.jpg");
        assertThat(pageText(CH_P, "2.jpg")).isEqualTo("page 2.jpg");
        assertThat(imageService.pageNames(CH_O)).containsExactly("1.jpg");
    }

    @Test
    void shouldMoveEveryPageBackWhenOneOfThemCannotBeMoved() throws IOException
    {
        // GIVEN two pages that can move, followed by one that is not there (deleted in another tab, say).
        writePages(CH_O, "1.jpg", "2.jpg", "3.jpg");

        // WHEN the move reaches it, it fails...
        assertThatThrownBy(() -> imageService.movePages(CH_O, List.of("2.jpg", "3.jpg", "4.jpg"), CH_P))
                .isInstanceOf(NoSuchFileException.class);

        // ...and leaves no half-moved chapter: the moved pages are back under their own names.
        assertThat(imageService.pageNames(CH_O)).containsExactly("1.jpg", "2.jpg", "3.jpg");
        assertThat(pageText(CH_O, "2.jpg")).isEqualTo("page 2.jpg");
        assertThat(pageText(CH_O, "3.jpg")).isEqualTo("page 3.jpg");
        assertThat(imageService.pageNames(CH_P)).isEmpty();
    }

    @Test
    void shouldRemoveTheStagingDirectoryTooWhenDeletingAll() throws IOException
    {
        // GIVEN a chapter with published pages and a staging directory an interrupted download left.
        imageService.saveImages(CH_E, List.of(img("1.jpg")));
        imageService.stagePage(imageService.stagingDir(CH_E, false), 2, "jpg",
                "staged".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.isDirectory(imageService.stagingDir(CH_E, false))).isTrue();

        // WHEN the chapter is deleted.
        imageService.deleteAll(CH_E);

        // THEN both folders are gone; nothing else would ever come back for that staging.
        assertThat(Files.exists(imageDirectory.chapterDir(CH_E))).isFalse();
        assertThat(Files.exists(imageService.stagingDir(CH_E, false))).isFalse();
    }
}


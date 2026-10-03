package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.TestImages;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.dto.JxlDelivery;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.compress.ImageCompressor;
import io.github.mocchikon.hentie.service.compress.ImageToolLocator;
import io.github.mocchikon.hentie.service.compress.JxlTranscoder;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The URL never changes; the bytes are chosen per client, so most tests request one URL with different client
 * state. Without the real {@code cjxl}/{@code djxl} the encoding tests skip, while the decision tests still run.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JxlTranscodeIT
{
    /** Its own chapter id, far from anything another suite uses; nothing is in the database for it. */
    private static final int CHAPTER = 91_001;

    private static final int SIZE = 200;

    /** A finished cache entry of page 1: {@code 1.jxl.<source mtime>-<source size>.png}. */
    private static final String CACHE_ENTRY = "1\\.jxl\\.\\d+-\\d+\\.png";

    @Autowired MockMvc mvc;
    @Autowired SettingsService settingsService;
    @Autowired AppProperties appProperties;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ImageCompressor compressor;
    @Autowired ImageToolLocator toolLocator;
    @Autowired JxlTranscoder transcoder;
    @Autowired ImageService imageService;

    private Path chapterDir;
    private String originalBinDir;

    @BeforeEach
    void setUp() throws IOException
    {
        chapterDir = imageDirectory.chapterDir(CHAPTER);
        deleteRecursively(chapterDir);
        Files.createDirectories(chapterDir);
        transcoder.evict(CHAPTER);
        originalBinDir = appProperties.getImageCompression().getBinDir();
        settingsService.setJxlDelivery(JxlDelivery.AUTO);
    }

    @AfterEach
    void tearDown()
    {
        // Shared singletons: put back the binaries and the delivery mode.
        appProperties.getImageCompression().setBinDir(originalBinDir);
        settingsService.setJxlDelivery(JxlDelivery.AUTO);
        // The test profile requires login; restore it so a later suite is not handed an open app.
        settingsService.setLoginRequired(true);
        deleteRecursively(chapterDir);
        transcoder.evict(CHAPTER);
    }

    /** Guessing the other way would show a first-time visitor a page of broken thumbnails. */
    @Test
    void shouldSendPngWhenTheBrowserHasNotSaidWhetherItSupportsJpegXl() throws Exception
    {
        assumeTools();
        // GIVEN a stored JPEG XL page and no cookie.
        writeJxlPage();

        // WHEN
        MockHttpServletResponse response = mvc.perform(get(url()).with(user("user")))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // THEN PNG comes back, and it is marked as varying by client so no shared cache mixes them up.
        assertThat(response.getContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(isPng(response.getContentAsByteArray())).isTrue();
        assertThat(response.getHeader(HttpHeaders.VARY)).isEqualTo(HttpHeaders.COOKIE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("private");
    }

    @Test
    void shouldSendTheStoredJpegXlWhenTheBrowserSaysItSupportsIt() throws Exception
    {
        assumeTools();
        // GIVEN
        byte[] stored = writeJxlPage();

        // WHEN the detection cookie says "supported".
        MockHttpServletResponse response = mvc.perform(get(url()).with(user("user")).cookie(supportCookie("1")))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // THEN the bytes are the file on disk, byte for byte - nothing was decoded.
        assertThat(response.getContentAsByteArray()).isEqualTo(stored);
        assertThat(response.getContentType()).isNotEqualTo(MediaType.IMAGE_PNG_VALUE);
    }

    /**
     * Without {@code Vary}, a browser update that drops JPEG XL support would leave every cached page broken
     * until it expired. {@code private} comes from the resource-handler registration, because
     * {@code WebContentGenerator} overwrites an interceptor's {@code Cache-Control}.
     */
    @Test
    void shouldMarkTheStoredJpegXlAsVaryingByCookieToo() throws Exception
    {
        assumeTools();
        // GIVEN a browser that gets the stored file rather than a decode.
        writeJxlPage();

        // WHEN
        MockHttpServletResponse response = mvc.perform(get(url()).with(user("user")).cookie(supportCookie("1")))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // THEN it says so, exactly as the decoded variant does.
        assertThat(response.getHeader(HttpHeaders.VARY)).isEqualTo(HttpHeaders.COOKIE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("private");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).doesNotContain("public");
    }

    @Test
    void shouldSendPngWhenTheBrowserSaysItCannotDisplayJpegXl() throws Exception
    {
        assumeTools();
        // GIVEN
        writeJxlPage();

        // WHEN
        MockHttpServletResponse response = mvc.perform(get(url()).with(user("user")).cookie(supportCookie("0")))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // THEN
        assertThat(response.getContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(isPng(response.getContentAsByteArray())).isTrue();
    }

    @Test
    void shouldFollowTheSettingsOverrideRatherThanTheBrowserWhenOneIsSet() throws Exception
    {
        assumeTools();
        // GIVEN a stored page and a browser that says it can display JPEG XL.
        byte[] stored = writeJxlPage();
        Cookie supported = supportCookie("1");

        // WHEN "Always send PNG" is set.
        settingsService.setJxlDelivery(JxlDelivery.ALWAYS);
        MockHttpServletResponse always = mvc.perform(get(url()).with(user("user")).cookie(supported))
                .andExpect(status().isOk()).andReturn().getResponse();

        // THEN it is decoded even though the browser did not need it.
        assertThat(always.getContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);

        // WHEN "Never" is set and the browser says it cannot.
        settingsService.setJxlDelivery(JxlDelivery.NEVER);
        MockHttpServletResponse never = mvc.perform(get(url()).with(user("user")).cookie(supportCookie("0")))
                .andExpect(status().isOk()).andReturn().getResponse();

        // THEN the stored file is sent anyway - the user asked for that.
        assertThat(never.getContentAsByteArray()).isEqualTo(stored);
    }

    @Test
    void shouldLeaveNonJpegXlPagesAlone() throws Exception
    {
        // GIVEN an ordinary PNG page, and the mode most likely to over-reach.
        settingsService.setJxlDelivery(JxlDelivery.ALWAYS);
        byte[] stored = TestImages.png(SIZE, SIZE);
        Files.write(chapterDir.resolve("1.png"), stored);

        // WHEN
        MockHttpServletResponse response = mvc.perform(get("/data/" + CHAPTER + "/1.png").with(user("user")))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // THEN the file is served as it is, and nothing was cached for it.
        assertThat(response.getContentAsByteArray()).isEqualTo(stored);
        assertThat(cacheFiles()).isEmpty();
    }

    /** Not an optimization: a results page asks for up to 36 thumbnails at once, each a full-size decode. */
    @Test
    void shouldCacheTheDecodedPngAndReuseItOnTheNextRequest() throws Exception
    {
        assumeTools();
        // GIVEN a first request that had to decode.
        writeJxlPage();
        byte[] first = mvc.perform(get(url()).with(user("user"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        // ...cached under a name recording the exact version of the page it was decoded from.
        assertThat(cacheFiles()).singleElement().asString().matches(CACHE_ENTRY);
        long cachedAt = Files.getLastModifiedTime(cacheFile()).toMillis();

        // WHEN the same page is requested again.
        byte[] second = mvc.perform(get(url()).with(user("user"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // THEN the same bytes come back and the cached file was not rewritten.
        assertThat(second).isEqualTo(first);
        assertThat(Files.getLastModifiedTime(cacheFile()).toMillis()).isEqualTo(cachedAt);
    }

    /**
     * A page replaced outside the app can carry an older timestamp than the decode, so a "cache newer than
     * page" test would serve the stale decode for ever.
     */
    @Test
    void shouldDecodeAgainWhenThePageIsReplacedEvenByAFileStampedOlderThanTheCachedCopy() throws Exception
    {
        assumeTools();
        // GIVEN a cached decode of the current page.
        writeJxlPage();
        byte[] first = mvc.perform(get(url()).with(user("user"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String firstEntry = cacheFiles().getFirst();

        // WHEN the page is replaced by a different image carrying a timestamp from yesterday.
        writeJxlPage(SIZE * 2);
        Files.setLastModifiedTime(chapterDir.resolve("1.jxl"),
                FileTime.fromMillis(System.currentTimeMillis() - 24L * 60 * 60 * 1000));

        // THEN the next request reflects the new page rather than the cached copy...
        byte[] second = mvc.perform(get(url()).with(user("user"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(second).isNotEqualTo(first);
        // ...and only the new version's decode is left in the cache.
        assertThat(cacheFiles()).singleElement().asString().matches(CACHE_ENTRY).isNotEqualTo(firstEntry);
    }

    /** Never into a chapter folder, where a numbered PNG would be taken for a page. */
    @Test
    void shouldKeepTheDecodedCopiesInTheDataFolderWhenThereIsNoRamDisk() throws Exception
    {
        assumeTools();
        // GIVEN
        writeJxlPage();

        // WHEN
        mvc.perform(get(url()).with(user("user"))).andExpect(status().isOk());

        // THEN
        assertThat(transcoder.cacheDir()).isEqualTo(chapterDir.getParent().resolve(".transcoded"));
        assertThat(cacheFiles()).hasSize(1);
    }

    /** The cache folder can be one the user chose, so housekeeping deletes only what the cache itself writes. */
    @Test
    void shouldClearOnlyCacheEntriesFromACacheFolder() throws IOException
    {
        // GIVEN a cache folder holding one entry, one decode in progress, and three files that are not ours.
        Path root = chapterDir.getParent().resolve("test-foreign-cache");
        deleteRecursively(root);
        Path chapter = Files.createDirectories(root.resolve("7"));
        Path entry = Files.writeString(chapter.resolve("1.jxl.1700000000000-1234.png"), "decoded");
        Path oldPart = Files.writeString(chapter.resolve("2.jxl.1700000000000-99.png.0f1e2d3c.part.png"), "x");
        Files.setLastModifiedTime(oldPart, FileTime.fromMillis(0));   // abandoned long ago, so prunable
        Path foreignInChapter = Files.writeString(chapter.resolve("notes.txt"), "mine");
        Path foreignPng = Files.writeString(chapter.resolve("holiday.png"), "mine");
        Path foreignTop = Files.writeString(root.resolve("1.jxl.1700000000000-1234.png"), "not in a chapter folder");
        try
        {
            // WHEN
            transcoder.clear(root);

            // THEN
            assertThat(entry).doesNotExist();
            assertThat(oldPart).doesNotExist();
            assertThat(foreignInChapter).exists();
            assertThat(foreignPng).exists();
            assertThat(foreignTop).exists();
        }
        finally
        {
            deleteRecursively(root);
        }
    }

    /** A recursive delete of {@code <cache>/<id>} would take whatever else is in it. */
    @Test
    void shouldEvictOnlyCacheEntriesOfAChapter() throws IOException
    {
        // GIVEN the chapter's cache folder holding an entry, a decode in progress and a file that is not ours.
        Path cached = Files.createDirectories(transcoder.cacheDir().resolve(String.valueOf(CHAPTER)));
        Path entry = Files.writeString(cached.resolve("1.jxl.1700000000000-1234.png"), "decoded");
        Path part = Files.writeString(cached.resolve("2.jxl.1700000000000-99.png.0f1e2d3c.part.png"), "x");
        Path foreign = Files.writeString(cached.resolve("notes.txt"), "mine");

        // WHEN
        transcoder.evict(CHAPTER);

        // THEN the folder stays for the file that is not ours...
        assertThat(entry).doesNotExist();
        assertThat(part).doesNotExist();
        assertThat(foreign).hasContent("mine");

        // ...and goes once nothing but entries were left in it.
        Files.delete(foreign);
        Files.writeString(cached.resolve("1.jxl.1700000000001-1234.png"), "decoded again");
        transcoder.evict(CHAPTER);
        assertThat(cached).doesNotExist();
    }

    /**
     * Concurrent requests (prefetch racing a jump, two tabs) sharing one temporary file could cache a
     * truncated PNG as the page.
     */
    @Test
    void shouldHandOutOneWholeDecodeWhenAPageIsRequestedConcurrently() throws Exception
    {
        assumeTools();
        // GIVEN
        writeJxlPage();
        int callers = 8;
        var start = new CountDownLatch(1);

        // WHEN several threads ask for the page at once.
        try (ExecutorService pool = Executors.newFixedThreadPool(callers))
        {
            List<Future<Optional<Path>>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++)
            {
                results.add(pool.submit(() ->
                {
                    start.await();
                    return transcoder.pngFor(CHAPTER, "1.jxl");
                }));
            }
            start.countDown();

            // THEN every one of them got the same cached file...
            Set<Path> paths = new HashSet<>();
            for (Future<Optional<Path>> result : results)
            {
                paths.add(result.get(60, TimeUnit.SECONDS).orElseThrow());
            }
            assertThat(paths).hasSize(1);
            // ...which is a complete PNG, with no temporary decode left over.
            assertThat(isPng(Files.readAllBytes(paths.iterator().next()))).isTrue();
            assertThat(cacheFiles()).singleElement().asString().matches(CACHE_ENTRY);
        }
    }

    /** Left to the size-based prune, dead full-size PNGs would stay while there is room, and for ever with pruning off. */
    @Test
    void shouldDropTheDecodedCopiesWhenThePageOrItsChapterIsDeleted() throws Exception
    {
        assumeTools();
        // GIVEN a decoded page.
        writeJxlPage();
        mvc.perform(get(url()).with(user("user"))).andExpect(status().isOk());
        assertThat(cacheFiles()).hasSize(1);

        // WHEN the page is deleted, THEN its decode goes too.
        imageService.deletePage(CHAPTER, "1.jxl");
        assertThat(cacheFiles()).isEmpty();

        // GIVEN it decoded again.
        writeJxlPage();
        mvc.perform(get(url()).with(user("user"))).andExpect(status().isOk());
        assertThat(cacheFiles()).hasSize(1);

        // WHEN the whole chapter's images are deleted, THEN the chapter's cache folder goes too.
        imageService.deleteAll(CHAPTER);
        assertThat(Files.exists(transcoder.cacheDir().resolve(String.valueOf(CHAPTER)))).isFalse();
    }

    /** So a reader paging back and forth re-downloads nothing. */
    @Test
    void shouldAnswerNotModifiedWhenTheBrowserAlreadyHasTheDecodedPage() throws Exception
    {
        assumeTools();
        // GIVEN a first request that hands the browser the decoded variant's ETag.
        writeJxlPage();
        MockHttpServletResponse first = mvc.perform(get(url()).with(user("user"))).andExpect(status().isOk())
                .andReturn().getResponse();
        String etag = first.getHeader(HttpHeaders.ETAG);
        assertThat(etag).matches("W/\"" + CACHE_ENTRY + "\"");

        // WHEN the browser asks again with it.
        mvc.perform(get(url()).with(user("user")).header(HttpHeaders.IF_NONE_MATCH, etag))
                // THEN
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.COOKIE));
    }

    /**
     * A {@code Last-Modified} would be the decode time, later than the page's own, so after a switch to Never
     * the resource handler would answer 304 and the browser would keep the PNG for ever.
     */
    @Test
    void shouldNotGiveTheDecodedPageALastModifiedTheStoredPageCouldValidate() throws Exception
    {
        assumeTools();
        // GIVEN
        writeJxlPage();

        // WHEN
        MockHttpServletResponse response = mvc.perform(get(url()).with(user("user")))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // THEN it is revalidated by ETag only.
        assertThat(response.getContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(response.getHeader(HttpHeaders.ETAG)).isNotBlank();
        assertThat(response.getHeader(HttpHeaders.LAST_MODIFIED)).isNull();
    }

    /** The same cookie means {@code Vary} still matches, so only the missing {@code Last-Modified} prevents a 304. */
    @Test
    void shouldSendTheStoredJpegXlWhenACachedPngIsRevalidatedAfterDeliveryIsSwitchedToNever() throws Exception
    {
        assumeTools();
        // GIVEN a browser that was sent the PNG, and then the setting switched to Never.
        byte[] stored = writeJxlPage();
        String etag = mvc.perform(get(url()).with(user("user"))).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        settingsService.setJxlDelivery(JxlDelivery.NEVER);

        // WHEN it revalidates what it holds.
        MockHttpServletResponse response = mvc.perform(get(url()).with(user("user"))
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // THEN it gets the stored file in full.
        assertThat(response.getContentAsByteArray()).isEqualTo(stored);
        assertThat(response.getContentType()).isNotEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(response.getHeader(HttpHeaders.VARY)).isEqualTo(HttpHeaders.COOKIE);
    }

    /** The ETag names the page version, known from a {@code stat}, so a 304 needs no decode even after a prune. */
    @Test
    void shouldAnswerNotModifiedWithoutDecodingWhenTheCacheHasBeenPruned() throws Exception
    {
        assumeTools();
        // GIVEN a browser holding the PNG, and a server cache emptied since.
        writeJxlPage();
        String etag = mvc.perform(get(url()).with(user("user"))).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        transcoder.evict(CHAPTER);

        // WHEN
        mvc.perform(get(url()).with(user("user")).header(HttpHeaders.IF_NONE_MATCH, etag))
                // THEN
                .andExpect(status().isNotModified());
        assertThat(cacheFiles()).isEmpty();
    }

    /** On a cold cache a decode is far too much to pay for headers. */
    @Test
    void shouldAnswerAHeadRequestWithoutDecoding() throws Exception
    {
        assumeTools();
        // GIVEN a page nothing has decoded yet.
        writeJxlPage();

        // WHEN
        MockHttpServletResponse response = mvc.perform(head(url()).with(user("user")))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // THEN it is described as the PNG it would be, and nothing was decoded.
        assertThat(response.getContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(response.getHeader(HttpHeaders.ETAG)).matches("W/\"" + CACHE_ENTRY + "\"");
        assertThat(response.getHeader(HttpHeaders.VARY)).isEqualTo(HttpHeaders.COOKIE);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(cacheFiles()).isEmpty();
    }

    /** The stored file is served and the log says why; it must never be a failed request. */
    @Test
    void shouldServeTheStoredFileWhenThereIsNoDecoderToTranscodeWith() throws Exception
    {
        assumeTools();
        // GIVEN a stored page, and no binaries anywhere.
        byte[] stored = writeJxlPage();
        appProperties.getImageCompression().setBinDir("./target/test-compress/no-binaries");

        // WHEN
        MockHttpServletResponse response = mvc.perform(get(url()).with(user("user")))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // THEN
        assertThat(response.getContentAsByteArray()).isEqualTo(stored);
        assertThat(response.getContentType()).isNotEqualTo(MediaType.IMAGE_PNG_VALUE);
    }

    @Test
    void shouldReturnNotFoundForAJpegXlPageThatDoesNotExist() throws Exception
    {
        // WHEN + THEN
        mvc.perform(get("/data/" + CHAPTER + "/404.jxl").with(user("user")))
                .andExpect(status().isNotFound());
    }

    /**
     * An unauthenticated request must never reach a decoder. Login is turned on explicitly because an earlier
     * suite may have left the shared setting off.
     */
    @Test
    void shouldRequireAuthenticationForAJpegXlPage() throws Exception
    {
        assumeTools();
        // GIVEN
        settingsService.setLoginRequired(true);
        writeJxlPage();

        // WHEN + THEN
        mvc.perform(get(url())).andExpect(status().is3xxRedirection());
    }

    // ---- helpers -------------------------------------------------------------

    private void assumeTools()
    {
        Assumptions.assumeTrue(toolLocator.find("cjxl").isPresent() && toolLocator.find("djxl").isPresent(),
                "No bundled cjxl/djxl for this platform - skipping");
    }

    private String url()
    {
        return "/data/" + CHAPTER + "/1.jxl";
    }

    private static Cookie supportCookie(String value)
    {
        return new Cookie(JxlTranscodeInterceptor.SUPPORT_COOKIE, value);
    }

    private byte[] writeJxlPage() throws IOException
    {
        return writeJxlPage(SIZE);
    }

    private byte[] writeJxlPage(int size) throws IOException
    {
        Files.deleteIfExists(chapterDir.resolve("1.jxl"));
        Path png = chapterDir.resolve("1.png");
        Files.write(png, TestImages.png(size, size));
        var profile = new CompressionProfile("test", "Test", ImageEncoder.JXL,
                CompressionProfile.splitArgs("-q 40 -e 1"), List.of(), 0, 0, Set.of());
        assertThat(compressor.compress(png, profile, chapterDir.resolve(".work")).replaced()).isTrue();
        return Files.readAllBytes(chapterDir.resolve("1.jxl"));
    }

    /** Callers have just checked there is exactly one. */
    private Path cacheFile() throws IOException
    {
        return transcoder.cacheDir().resolve(String.valueOf(CHAPTER)).resolve(cacheFiles().getFirst());
    }

    private List<String> cacheFiles() throws IOException
    {
        Path dir = transcoder.cacheDir().resolve(String.valueOf(CHAPTER));
        if (!Files.isDirectory(dir))
        {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir))
        {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static boolean isPng(byte[] bytes)
    {
        return bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    private static void deleteRecursively(Path root)
    {
        if (!Files.isDirectory(root))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p ->
            {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException ignored)
                {
                    // best-effort cleanup
                }
            });
        }
        catch (IOException ignored)
        {
            // best-effort cleanup
        }
    }
}

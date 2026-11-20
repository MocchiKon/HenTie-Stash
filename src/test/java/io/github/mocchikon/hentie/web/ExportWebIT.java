package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.FakeComfyUi;
import io.github.mocchikon.hentie.TestImages;
import io.github.mocchikon.hentie.TestWorkflows;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.SeriesService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService;
import io.github.mocchikon.hentie.service.comfy.WorkflowCatalog;
import io.github.mocchikon.hentie.service.compress.ImageToolLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Commits to the shared DB, so titles are unique to this suite; everything it makes is deleted afterwards. */
@SpringBootTest
@AutoConfigureMockMvc
class ExportWebIT
{
    @Autowired MockMvc mvc;
    @Autowired ChapterService chapterService;
    @Autowired SeriesService seriesService;
    @Autowired MetadataService metadataService;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ImageToolLocator toolLocator;
    @Autowired SettingsService settingsService;
    @Autowired WorkflowCatalog catalog;
    @Autowired PageProcessingService processing;

    private final List<Integer> chapters = new ArrayList<>();
    private FakeComfyUi comfy;

    @AfterEach
    void tearDown() throws Exception
    {
        if (comfy != null)
        {
            try
            {
                processing.forgetAll();
            }
            finally
            {
                comfy.close();
                settingsService.setComfyUiUrl("");
                catalog.invalidate();
            }
        }
        for (int id : chapters)
        {
            chapterService.delete(id);
        }
    }

    // ---- a chapter -------------------------------------------------------------

    @Test
    void shouldExportPagesInReadingOrderWithComicInfo() throws Exception
    {
        // GIVEN a numbered chapter with PNG and JPEG pages, an artist and a language.
        int artist = metadataService.resolveOrCreate(MetadataType.ARTIST, List.of("export-it artist")).getFirst();
        int id = chapter("[Circle] Export Chapter & More", "English", List.of(artist));
        Path dir = imageDirectory.chapterDir(id);
        TestImages.writePng(dir.resolve("1.png"), 10, 10);
        Files.write(dir.resolve("2.jpeg"), jpeg());
        TestImages.writePng(dir.resolve("10.png"), 12, 12);
        byte[] firstPage = Files.readAllBytes(dir.resolve("1.png"));

        // WHEN
        var response = mvc.perform(get("/export/chapter/" + id).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, ExportController.CBZ_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(".cbz")))
                .andReturn().getResponse();

        // THEN pages are named in reading order, kept as they are, and described by ComicInfo.
        Map<String, byte[]> entries = unzip(response.getContentAsByteArray());
        assertThat(entries.keySet()).containsExactly("ComicInfo.xml", "001.png", "002.jpg", "003.png");
        assertThat(entries.get("001.png")).isEqualTo(firstPage);
        String info = new String(entries.get("ComicInfo.xml"), StandardCharsets.UTF_8);
        assertThat(info)
                .contains("<Title>Export Chapter &amp; More</Title>")
                .contains("<Writer>export-it artist</Writer>")
                .contains("<LanguageISO>en</LanguageISO>")
                .contains("<PageCount>3</PageCount>")
                .doesNotContain("<Number>");
    }

    @Test
    void shouldConvertPagesReadersMayNotShowToPng() throws Exception
    {
        Assumptions.assumeTrue(toolLocator.find(ImageToolLocator.MAGICK).isPresent(),
                "No bundled ImageMagick for this platform - skipping");
        // GIVEN a GIF and a BMP page.
        int id = chapter("Export Convert", "English", List.of());
        Path dir = imageDirectory.chapterDir(id);
        Files.createDirectories(dir);
        ImageIO.write(image(), "gif", dir.resolve("1.gif").toFile());
        ImageIO.write(image(), "bmp", dir.resolve("2.bmp").toFile());

        // WHEN
        byte[] body = mvc.perform(get("/export/chapter/" + id).with(user("user")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        // THEN both arrive as real PNGs, and nothing is left in the work folder.
        Map<String, byte[]> entries = unzip(body);
        assertThat(entries.keySet()).containsExactly("ComicInfo.xml", "001.png", "002.png");
        assertThat(ImageIO.read(new ByteArrayInputStream(entries.get("001.png")))).isNotNull();
        assertThat(Arrays.copyOf(entries.get("002.png"), 4)).isEqualTo(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    }

    @Test
    void shouldAnswerTheOutline() throws Exception
    {
        int id = chapter("Export: Outline?", "English", List.of());
        TestImages.writePng(imageDirectory.chapterDir(id).resolve("1.png"), 10, 10);

        mvc.perform(get("/export/chapter/" + id + "/outline").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cbzName").value("Export_ Outline_.cbz"))
                .andExpect(jsonPath("$.chapters[0].id").value(id))
                .andExpect(jsonPath("$.chapters[0].pages[0]").value("1.png"));
    }

    @Test
    void shouldAnswer404ForAMissingChapter() throws Exception
    {
        mvc.perform(get("/export/chapter/987654321").with(user("user"))).andExpect(status().isNotFound());
    }

    @Test
    void shouldOfferExportOnPagesWithSomethingToExport() throws Exception
    {
        int series = series("Export Button Series", 1);
        int chapter = chapters.getLast();

        mvc.perform(get("/chapter/" + chapter).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-export-open")))
                .andExpect(content().string(containsString("/export/chapter/" + chapter + "/outline")));
        mvc.perform(get("/series/" + series).param("lang", "English").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/export/series/" + series + "/outline?lang=English")))
                .andExpect(content().string(containsString("Only the English chapters")));
    }

    // ---- a series --------------------------------------------------------------

    @Test
    void shouldExportASeriesAsOneCbzPerChapter() throws Exception
    {
        // GIVEN a series of two numbered chapters.
        int series = series("Export Series Zip", 2);
        var ids = chapters.subList(chapters.size() - 2, chapters.size());

        // WHEN
        byte[] body = mvc.perform(get("/export/series/" + series).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"))
                .andReturn().getResponse().getContentAsByteArray();

        // THEN one CBZ per chapter, each numbered within the series.
        Map<String, byte[]> files = unzip(body);
        assertThat(files.keySet()).containsExactly("1 - Export Series Zip 1.cbz", "2 - Export Series Zip 2.cbz");
        Map<String, byte[]> second = unzip(files.get("2 - Export Series Zip 2.cbz"));
        assertThat(second.keySet()).containsExactly("ComicInfo.xml", "001.png", "002.png");
        assertThat(new String(second.get("ComicInfo.xml"), StandardCharsets.UTF_8))
                .contains("<Series>Export Series Zip</Series>")
                .contains("<Number>2</Number>")
                .contains("<Count>2</Count>");
        assertThat(ids).hasSize(2);
    }

    @Test
    void shouldExportASeriesAsOneCbz() throws Exception
    {
        int series = series("Export Series One", 2);

        byte[] body = mvc.perform(get("/export/series/" + series).param("layout", "ONE_FILE").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, ExportController.CBZ_TYPE))
                .andReturn().getResponse().getContentAsByteArray();

        Map<String, byte[]> entries = unzip(body);
        assertThat(entries.keySet())
                .containsExactly("ComicInfo.xml", "001-001.png", "001-002.png", "002-001.png", "002-002.png");
        assertThat(new String(entries.get("ComicInfo.xml"), StandardCharsets.UTF_8))
                .contains("<Title>Export Series One</Title>")
                .contains("<PageCount>4</PageCount>");
    }

    @Test
    void shouldExportOnlyTheLanguagePickedOnTheSeriesPage() throws Exception
    {
        // GIVEN a series with an English and a Japanese chapter.
        int series = series("Export Series Lang", 1);
        int japanese = chapter("Export Series Lang JP", "Japanese", List.of());
        TestImages.writePng(imageDirectory.chapterDir(japanese).resolve("1.png"), 9, 9);
        seriesService.addChapters(series, List.of(japanese));

        // WHEN + THEN
        mvc.perform(get("/export/series/" + series + "/outline").param("lang", "Japanese").with(user("user")))
                .andExpect(jsonPath("$.chapters.length()").value(1))
                .andExpect(jsonPath("$.chapters[0].id").value(japanese));
        mvc.perform(get("/export/series/" + series + "/outline").param("lang", "").with(user("user")))
                .andExpect(jsonPath("$.chapters.length()").value(2));
    }

    // ---- a workflow ------------------------------------------------------------

    @Test
    void shouldExportPagesAsTheWorkflowMakesThem() throws Exception
    {
        // GIVEN a workflow that answers with its own image.
        byte[] processed = TestImages.png(20, 20);
        startComfy().onPrompt = prompt -> new FakeComfyUi.Succeed(processed);
        int id = chapter("Export Workflow", "English", List.of());
        Path dir = imageDirectory.chapterDir(id);
        TestImages.writePng(dir.resolve("1.png"), 10, 10);
        Files.write(dir.resolve("2.jpg"), jpeg());

        // WHEN
        byte[] body = mvc.perform(get("/export/chapter/" + id).param("workflow", "upscale").with(user("user")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        // THEN every page is the processed PNG.
        Map<String, byte[]> entries = unzip(body);
        assertThat(entries.keySet()).containsExactly("ComicInfo.xml", "001.png", "002.png");
        assertThat(entries.get("001.png")).isEqualTo(processed);
        assertThat(entries.get("002.png")).isEqualTo(processed);
    }

    @Test
    void shouldRefuseAnUnknownWorkflowBeforeSending() throws Exception
    {
        startComfy();
        int id = chapter("Export Unknown Workflow", "English", List.of());
        TestImages.writePng(imageDirectory.chapterDir(id).resolve("1.png"), 10, 10);

        mvc.perform(get("/export/chapter/" + id).param("workflow", "nope").with(user("user")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("nope")));
    }

    @Test
    void shouldAnswerWhyAPageCouldNotBeProcessed() throws Exception
    {
        // GIVEN a workflow ComfyUI fails on.
        startComfy().onPrompt = prompt -> new FakeComfyUi.Fail("2", "Upscale", "out of memory");
        int id = chapter("Export Failing Workflow", "English", List.of());
        TestImages.writePng(imageDirectory.chapterDir(id).resolve("1.png"), 10, 10);

        // WHEN + THEN the reason, not a broken file.
        mvc.perform(get("/export/chapter/" + id).param("workflow", "upscale").with(user("user")))
                .andExpect(status().isBadGateway())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(jsonPath("$.error").value(containsString("out of memory")));
    }

    // ---- progress ----------------------------------------------------------------

    @Test
    void shouldReportHowFarAnExportGot() throws Exception
    {
        int id = chapter("Export Progress", "English", List.of());
        TestImages.writePng(imageDirectory.chapterDir(id).resolve("1.png"), 10, 10);
        TestImages.writePng(imageDirectory.chapterDir(id).resolve("2.png"), 10, 10);

        mvc.perform(get("/export/chapter/" + id).param("export", "export-it-progress").with(user("user")))
                .andExpect(status().isOk());

        mvc.perform(get("/export/progress/export-it-progress").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DONE"))
                .andExpect(jsonPath("$.done").value(2))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/export/progress/export-it-unknown").with(user("user"))).andExpect(status().isNotFound());
    }

    /** A browser saving the file itself shows no reason for a failed download; the popup hears it here. */
    @Test
    void shouldReportARefusalToo() throws Exception
    {
        startComfy();
        int id = chapter("Export Progress Refused", "English", List.of());
        TestImages.writePng(imageDirectory.chapterDir(id).resolve("1.png"), 10, 10);

        mvc.perform(get("/export/chapter/" + id).param("workflow", "nope").param("export", "export-it-refused")
                        .with(user("user")))
                .andExpect(status().isNotFound());

        mvc.perform(get("/export/progress/export-it-refused").with(user("user")))
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("nope")));
    }

    @Test
    void shouldStopTheRunningPageWhenCancelled() throws Exception
    {
        // GIVEN an export waiting for a page ComfyUI holds.
        var release = new CountDownLatch(1);
        startComfy().onPrompt = prompt -> new FakeComfyUi.Hold(release, TestImages.png(8, 8));
        int id = chapter("Export Cancel", "English", List.of());
        TestImages.writePng(imageDirectory.chapterDir(id).resolve("1.png"), 10, 10);
        var export = CompletableFuture.supplyAsync(() -> {
            try
            {
                return mvc.perform(get("/export/chapter/" + id).param("workflow", "upscale")
                        .param("export", "export-it-cancel").with(user("user"))).andReturn().getResponse().getStatus();
            }
            catch (Exception e)
            {
                throw new IllegalStateException(e);
            }
        });
        try
        {
            await(() -> comfy.holding().size() == 1);
            String running = comfy.holding().getFirst();
            mvc.perform(get("/export/progress/export-it-cancel").with(user("user")))
                    .andExpect(jsonPath("$.state").value("RUNNING"))
                    .andExpect(jsonPath("$.total").value(1));

            // WHEN
            mvc.perform(post("/export/progress/export-it-cancel/cancel").with(user("user")).with(csrf()))
                    .andExpect(status().isNoContent());

            // THEN ComfyUI stops the page, and the export ends without a file.
            await(() -> comfy.interrupted().contains(running));
            assertThat(export.get(15, TimeUnit.SECONDS)).isEqualTo(409);
            mvc.perform(get("/export/progress/export-it-cancel").with(user("user")))
                    .andExpect(jsonPath("$.state").value("CANCELLED"));
        }
        finally
        {
            release.countDown();
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private FakeComfyUi startComfy() throws IOException
    {
        comfy = new FakeComfyUi();
        comfy.putUserFile("api_workflows/upscale.json", TestWorkflows.UPSCALE);
        settingsService.setComfyUiUrl(comfy.url());
        catalog.invalidate();
        return comfy;
    }

    private int chapter(String titleFull, String language, List<Integer> artistIds) throws IOException
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage(language);
        form.setArtistIds(new ArrayList<>(artistIds));
        int id = chapterService.create(form);
        chapters.add(id);
        Path dir = imageDirectory.chapterDir(id);
        ImageService.deleteRecursively(dir);
        Files.createDirectories(dir);
        return id;
    }

    /** Chapters numbered 1..count, two pages each. */
    private int series(String title, int count) throws IOException
    {
        var form = new SeriesForm();
        form.setTitleFull(title);
        form.setStatus(Status.REVIEWED);
        int series = seriesService.create(form);
        for (int n = 1; n <= count; n++)
        {
            int id = chapter(title + " " + n, "English", List.of());
            TestImages.writePng(imageDirectory.chapterDir(id).resolve("1.png"), 10, 10);
            TestImages.writePng(imageDirectory.chapterDir(id).resolve("2.png"), 11, 11);
            seriesService.addChapters(series, List.of(id));
        }
        return series;
    }

    @FunctionalInterface
    private interface Check
    {
        boolean test() throws Exception;
    }

    private static void await(Check condition) throws Exception
    {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.test())
        {
            assertThat(System.currentTimeMillis()).as("condition met within 15 s").isLessThan(deadline);
            Thread.sleep(25);
        }
    }

    private static BufferedImage image()
    {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 3, 0xff0000);
        return image;
    }

    private static byte[] jpeg() throws IOException
    {
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image(), "jpg", out);
        return out.toByteArray();
    }

    /** In archive order, which is what a reader that does not sort sees. */
    private static Map<String, byte[]> unzip(byte[] archive) throws IOException
    {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive)))
        {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; )
            {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}

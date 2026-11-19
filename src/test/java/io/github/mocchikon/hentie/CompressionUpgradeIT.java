package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.CompressionRuleForm;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionRuleService;
import io.github.mocchikon.hentie.service.compress.ImageToolLocator;
import io.github.mocchikon.hentie.service.download.DownloadChoices;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Downloads queued "By rules", and upgrades: a chapter already in the library downloaded again in a better mode,
 * which a rule or the paste allows. Compression that happened earlier is simulated by swapping each {@code n.png}
 * for an {@code n.jxl} and recording a mode; a test that has pages re-encoded needs the real {@code cjxl}.
 */
@SpringBootTest
@Transactional
class CompressionUpgradeIT
{
    /** Matches {@code app.download.mock-dir} in the test profile. */
    private static final Path MOCK_DIR = Paths.get("./target/test-mock-server");

    private static final byte[] COMPRESSED = "a page as Image Compression left it".getBytes(StandardCharsets.UTF_8);

    private static final String NONE = BuiltInCompressionMode.NONE.getKey();
    private static final String LOSSLESS = BuiltInCompressionMode.LOSSLESS.getKey();
    private static final String VERY_HIGH = BuiltInCompressionMode.VERY_HIGH_REDUCTION.getKey();
    private static final String RULES = BuiltInCompressionMode.RULES_KEY;

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired ChapterRepository chapterRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired ImageService imageService;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ChapterService chapterService;
    @Autowired ImageCompressionModeService modeService;
    @Autowired ImageCompressionRuleService ruleService;
    @Autowired SettingsService settingsService;
    @Autowired ImageToolLocator toolLocator;
    @PersistenceContext EntityManager em;

    private final List<Integer> createdChapters = new ArrayList<>();

    @BeforeEach
    void resetMockSource()
    {
        deleteRecursively(MOCK_DIR);
        worker.setPaused(false);
    }

    @AfterEach
    void cleanUp()
    {
        createdChapters.forEach(imageService::deleteAll);
        createdChapters.clear();
        // Settings keep an in-memory copy the test's rollback does not reach.
        settingsService.setImageCompressionMode(NONE);
    }

    // ---- a new gallery ------------------------------------------------------------------------------------------

    /** The rules decide once the gallery is in the library, from the metadata it was imported with. */
    @Test
    void shouldGiveANewGalleryTheModeOfTheRuleItMatches() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a rule for an artist, and a gallery of theirs queued By rules
        String key = modeService.save(jxlMode("Upgrade test JXL", "-q 40 -e 1", 5));
        rule(0, key, false, "artist", "cu-new-artist");
        writeGallery("8600", 2, "cu-new-artist");

        // WHEN
        queue("8600", RULES, false);
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN its pages went through the rule's mode, and the chapter says so
        Chapter chapter = chapter("8600");
        assertThat(pageFiles(chapter.getId())).containsExactly("1.jxl", "2.jxl");
        assertThat(chapter.getCompressionMode()).isEqualTo(key);
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
        // The row keeps asking the rules; nothing rewrote it into a mode of its own before it completed.
        assertThat(queueRepository.findByLink("mock:8600")).isEmpty();
    }

    @Test
    void shouldGiveAGalleryNoRuleMatchesTheSettingsMode() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a rule for somebody else, and Settings' mode
        String key = modeService.save(jxlMode("Settings test JXL", "-q 40 -e 1", 5));
        settingsService.setImageCompressionMode(key);
        rule(0, LOSSLESS, true, "artist", "cu-somebody-else");
        writeGallery("8601", 2, "cu-nobody");

        // WHEN
        queue("8601", RULES, false);
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN
        Chapter chapter = chapter("8601");
        assertThat(pageFiles(chapter.getId())).containsExactly("1.jxl", "2.jxl");
        assertThat(chapter.getCompressionMode()).isEqualTo(key);
    }

    // ---- a chapter in the library -------------------------------------------------------------------------------

    @Test
    void shouldDownloadAChapterAgainInFullQualityWhenARuleAllowsTheUpgrade() throws IOException
    {
        // GIVEN a chapter compressed with Very high reduction, and a rule for its artist allowing upgrades to None
        int chapterId = downloadedAndCompressed("8602", 2, "cu-favourite", VERY_HIGH);
        rule(0, NONE, true, "artist", "cu-favourite");

        // WHEN its link is pasted By rules
        queue("8602", RULES, false);
        assertThat(worker.processNext()).isTrue();
        em.flush();
        em.clear();

        // THEN its pages are the source's again, and nothing says they are compressed
        assertThat(pageFiles(chapterId)).containsExactly("1.png", "2.png");
        Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
        assertThat(chapter.getCompressionMode()).isNull();
        assertThat(chapter.getPageNum()).isEqualTo(2);
        assertThat(chapter.getDiskSize()).isEqualTo(imageService.diskSize(chapterId));
        assertThat(queueRepository.findByLink("mock:8602")).isEmpty();
    }

    /** The replaced pages go through the better mode, and only once every one has arrived. */
    @Test
    void shouldReEncodeTheReplacedPagesInTheBetterMode() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a chapter compressed with Very high reduction, and a rule allowing upgrades to a better JXL mode
        int chapterId = downloadedAndCompressed("8603", 2, "cu-reencoded", VERY_HIGH);
        String key = modeService.save(jxlMode("Better JXL", "-q 90 -e 1", 10));
        rule(0, key, true, "artist", "cu-reencoded");

        // WHEN
        queue("8603", RULES, false);
        assertThat(worker.processNext()).isTrue();
        em.flush();
        em.clear();

        // THEN the pages are new JPEG XL files, the chapter names the better mode, and staging is empty
        assertThat(pageFiles(chapterId)).containsExactly("1.jxl", "2.jxl");
        Path dir = imageDirectory.chapterDir(chapterId);
        byte[] page = Files.readAllBytes(dir.resolve("1.jxl"));
        assertThat(page).isNotEqualTo(COMPRESSED);
        assertThat(ImageEncoder.JXL.producedValidFile(page)).isTrue();
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCompressionMode()).isEqualTo(key);
        assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();
    }

    @Test
    void shouldLeaveAChapterAloneWhenTheRuleDoesNotAllowUpgrading() throws IOException
    {
        // GIVEN
        int chapterId = downloadedAndCompressed("8604", 2, "cu-not-allowed", VERY_HIGH);
        rule(0, NONE, false, "artist", "cu-not-allowed");

        // WHEN
        queue("8604", RULES, false);
        assertThat(worker.processNext()).isTrue();
        em.flush();
        em.clear();

        // THEN
        assertThat(pageFiles(chapterId)).containsExactly("1.jxl", "2.jxl");
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCompressionMode()).isEqualTo(VERY_HIGH);
    }

    /** Lossless keeps the source's pixels: downloading it again as None would swap equivalent files. */
    @Test
    void shouldLeaveAChapterAloneWhenTheModeIsNoBetter() throws IOException
    {
        // GIVEN
        int chapterId = downloadedAndCompressed("8605", 2, "cu-equal", LOSSLESS);
        rule(0, NONE, true, "artist", "cu-equal");

        // WHEN
        queue("8605", RULES, false);
        assertThat(worker.processNext()).isTrue();
        em.flush();
        em.clear();

        // THEN
        assertThat(pageFiles(chapterId)).containsExactly("1.jxl", "2.jxl");
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCompressionMode()).isEqualTo(LOSSLESS);
    }

    /** A mode picked by hand upgrades a chapter only when the paste allows it; the rules are not asked. */
    @Test
    void shouldUpgradeWithAModePickedByHandOnlyWhenThePasteAllowsIt() throws IOException
    {
        // GIVEN a compressed chapter, and a rule that would never upgrade it
        int chapterId = downloadedAndCompressed("8606", 2, "cu-by-hand", VERY_HIGH);
        rule(0, VERY_HIGH, false, "artist", "cu-by-hand");

        // WHEN it is pasted with None, not allowing upgrades
        queue("8606", NONE, false);
        assertThat(worker.processNext()).isTrue();
        em.flush();
        em.clear();

        // THEN nothing changed
        assertThat(pageFiles(chapterId)).containsExactly("1.jxl", "2.jxl");

        // WHEN it is pasted again, allowing upgrades
        queue("8606", NONE, true);
        assertThat(queueRepository.findByLink("mock:8606").orElseThrow().isAllowUpgrade()).isTrue();
        assertThat(worker.processNext()).isTrue();
        em.flush();
        em.clear();

        // THEN its pages are the source's again
        assertThat(pageFiles(chapterId)).containsExactly("1.png", "2.png");
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCompressionMode()).isNull();
    }

    /** "By rules" leaves upgrading to the rules, so the row never asks for it on its own. */
    @Test
    void shouldNotStoreAllowUpgradeOnARowQueuedByRules()
    {
        // WHEN
        queue("8607", RULES, true);

        // THEN
        DownloadQueueItem row = queueRepository.findByLink("mock:8607").orElseThrow();
        assertThat(row.getCompressionMode()).isEqualTo(RULES);
        assertThat(row.isAllowUpgrade()).isFalse();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private void queue(String id, String mode, boolean allowUpgrade)
    {
        queueService.enqueue(List.of("mock:" + id),
                new DownloadChoices(mode, allowUpgrade, false, new GalleryDlOptions(false, "0")));
    }

    private Chapter chapter(String id)
    {
        Chapter chapter = chapterRepository.findByGalleryId("mock:" + id).orElseThrow();
        createdChapters.add(chapter.getId());
        return chapter;
    }

    /** Downloaded as it came, then "compressed": each page swapped for an encoder's output, the mode recorded. */
    private int downloadedAndCompressed(String id, int pages, String artist, String mode) throws IOException
    {
        writeGallery(id, pages, artist);
        queue(id, NONE, false);
        assertThat(worker.processNext()).isTrue();
        int chapterId = chapter(id).getId();
        Path dir = imageDirectory.chapterDir(chapterId);
        for (int page = 1; page <= pages; page++)
        {
            Files.write(dir.resolve(page + ".jxl"), COMPRESSED);
            Files.delete(dir.resolve(page + ".png"));
        }
        imageService.invalidateListing(chapterId);
        chapterService.syncImageStats(chapterId);
        chapterService.setCompressionMode(chapterId, mode);
        em.flush();
        return chapterId;
    }

    /** @param conditions kind key and name, in pairs */
    private void rule(int priority, String mode, boolean allowUpgrade, String... conditions)
    {
        var form = new CompressionRuleForm();
        form.setPriority(priority);
        form.setCompressionMode(mode);
        form.setAllowUpgrade(allowUpgrade);
        for (int i = 0; i < conditions.length; i += 2)
        {
            form.getConditionTypes().add(conditions[i]);
            form.getConditionValues().add(conditions[i + 1]);
        }
        ruleService.save(form);
        em.flush();
    }

    private void assumeTool(String tool)
    {
        Assumptions.assumeTrue(toolLocator.find(tool).isPresent(),
                () -> "No bundled " + tool + " for this platform - skipping");
    }

    private static ImageCompressionMode jxlMode(String name, String encoderArgs, int quality)
    {
        var mode = new ImageCompressionMode();
        mode.setName(name);
        mode.setEncoder(ImageEncoder.JXL);
        mode.setEncoderArgs(encoderArgs);
        mode.setQuality(quality);
        return mode;
    }

    /** In listing order: numeric. */
    private List<String> pageFiles(int chapterId)
    {
        return imageDirectory.list(chapterId);
    }

    /** A mock gallery by {@code artist} whose pages are real PNGs, big enough for a lossy re-encode to win. */
    private static void writeGallery(String id, int pages, String artist) throws IOException
    {
        Path gallery = MOCK_DIR.resolve("galleries").resolve(id);
        Files.createDirectories(gallery);
        var pageJson = new StringBuilder();
        for (int page = 1; page <= pages; page++)
        {
            pageJson.append(page > 1 ? "," : "")
                    .append("{\"number\":").append(page)
                    .append(",\"path\":\"galleries/").append(id).append('/').append(page).append(".png\"}");
            Files.write(gallery.resolve(page + ".png"), TestImages.png(400, 400));
        }
        String json = """
                {
                  "id": %s,
                  "media_id": "%s",
                  "title": {
                    "english": "[Test] Upgrade %s",
                    "japanese": "%s in Japanese",
                    "pretty": "Upgrade %s"
                  },
                  "tags": [
                    {"type": "language", "name": "japanese"},
                    {"type": "artist", "name": "%s"}
                  ],
                  "pages": [%s]
                }
                """.formatted(id, id, id, id, id, artist, pageJson);
        Files.writeString(MOCK_DIR.resolve(id + ".json"), json, StandardCharsets.UTF_8);
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

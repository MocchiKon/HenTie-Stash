package io.github.mocchikon.hentie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.entity.ImageCompressionMode;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.compress.ImageToolLocator;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import static org.assertj.core.api.Assertions.*;

/**
 * The paste form's mode rides the queue row, so most tests here check that it survives re-queueing, retries
 * and the mode being deleted.
 *
 * <p>The mock pages are <b>real PNGs</b> ({@link TestImages}): an encoder decodes its input, so text named
 * {@code 1.webp} would only exercise the failure path.
 */
@SpringBootTest
@Transactional
class DownloadCompressionIT
{
    /** Matches {@code app.download.mock-dir} in the test profile. */
    private static final Path MOCK_DIR = Paths.get("./target/test-mock-server");

    /** Big enough for a lossy re-encode to be an unambiguous win. */
    private static final int SIZE = 400;

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired ChapterRepository chapterRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired ImageService imageService;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ChapterService chapterService;
    @Autowired ImageCompressionModeService modeService;
    @Autowired ImageToolLocator toolLocator;
    @PersistenceContext EntityManager em;

    private Integer createdChapterId;

    @BeforeEach
    void resetMockSource() throws IOException
    {
        deleteRecursively(MOCK_DIR);
        writeGallery("8100", 2);
        worker.setPaused(false);
    }

    @AfterEach
    void cleanUp()
    {
        if (createdChapterId != null)
        {
            imageService.deleteAll(createdChapterId);
            createdChapterId = null;
        }
    }

    @Test
    void shouldPublishReEncodedPagesWhenTheLinkWasQueuedWithACompressionMode()
    {
        assumeTool("cjxl");
        // GIVEN a user-defined lossy JPEG XL mode, and a link queued with it.
        String key = modeService.save(mode("Download JXL", "-q 40 -e 1"));
        assertThat(queueService.enqueue(List.of("mock:8100"), key, false).accepted()).isEqualTo(1);

        // WHEN the worker takes it.
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the chapter's pages are JPEG XL, numbered exactly as the source numbered them...
        Chapter chapter = chapterRepository.findByGalleryId("mock:8100").orElseThrow();
        createdChapterId = chapter.getId();
        assertThat(imageService.pageUrls(createdChapterId))
                .containsExactly("/data/" + createdChapterId + "/1.jxl", "/data/" + createdChapterId + "/2.jxl");
        // ...the download is recorded as finished...
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
        // ...and the stats come from the encoded files, not the downloaded ones.
        assertThat(chapter.getPageNum()).isEqualTo(2);
        assertThat(chapter.getDiskSize()).isEqualTo(imageService.diskSize(createdChapterId));
        assertThat(chapter.getDiskSize()).isPositive();
        // ...the chapter names the mode its pages went through...
        assertThat(chapter.getCompressionMode()).isEqualTo(key);
        // AND the queue row is gone, as for any completed item.
        assertThat(queueRepository.findByLink("mock:8100")).isEmpty();
    }

    @Test
    void shouldPublishTheDownloadedPagesUntouchedWhenTheModeIsNone()
    {
        // GIVEN
        assertThat(queueService.enqueue(List.of("mock:8100"), BuiltInCompressionMode.NONE.getKey(), false)
                .accepted()).isEqualTo(1);

        // WHEN
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the extensions are the source's own.
        Chapter chapter = chapterRepository.findByGalleryId("mock:8100").orElseThrow();
        createdChapterId = chapter.getId();
        assertThat(imageService.pageUrls(createdChapterId))
                .containsExactly("/data/" + createdChapterId + "/1.png", "/data/" + createdChapterId + "/2.png");
        assertThat(chapter.getPageNum()).isEqualTo(2);
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
        assertThat(chapter.getCompressionMode()).isNull();
    }

    @Test
    void shouldStoreTheChosenModeOnTheQueueRowWhenLinksAreQueued()
    {
        // GIVEN + WHEN
        var result = queueService.enqueue(List.of("mock:8100"), BuiltInCompressionMode.LOSSLESS.getKey(), false);

        // THEN
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.requeued()).isZero();
        assertThat(result.alreadyQueued()).isZero();
        assertThat(result.rejected()).isZero();
        assertThat(queueRepository.findByLink("mock:8100").orElseThrow().getCompressionMode())
                .isEqualTo(BuiltInCompressionMode.LOSSLESS.getKey());
    }

    /** The paste form carries a mode, so a re-paste's choice wins, just as it clears {@code ignoreImageErrors}. */
    @Test
    void shouldReplaceTheModeOnAFailedRowWhenTheLinkIsPastedAgain()
    {
        // GIVEN a row that failed after being queued with one mode.
        queueService.enqueue(List.of("mock:8100"), BuiltInCompressionMode.LOSSLESS.getKey(), false);
        var item = queueRepository.findByLink("mock:8100").orElseThrow();
        item.setError("boom");
        item.setIgnoreImageErrors(true);
        queueRepository.save(item);
        em.flush();

        // WHEN it is pasted again, this time with a different mode.
        var result = queueService.enqueue(List.of("mock:8100"),
                BuiltInCompressionMode.VERY_HIGH_REDUCTION.getKey(), false);

        // THEN the one row is revived, carrying the new mode and back in the strict image mode.
        assertThat(result.requeued()).isEqualTo(1);
        assertThat(result.accepted()).isZero();
        assertThat(result.alreadyQueued()).isZero();
        assertThat(result.rejected()).isZero();
        var revived = queueRepository.findByLink("mock:8100").orElseThrow();
        assertThat(revived.getCompressionMode()).isEqualTo(BuiltInCompressionMode.VERY_HIGH_REDUCTION.getKey());
        assertThat(revived.getError()).isNull();
        assertThat(revived.getAttempts()).isZero();
        assertThat(revived.isIgnoreImageErrors()).isFalse();
    }

    /** The form says the choice is remembered on the queued link, so keeping the old one would ignore the user. */
    @Test
    void shouldReplaceTheModeOnAPendingRowWhenTheLinkIsPastedAgain()
    {
        // GIVEN a link still waiting, queued with one mode.
        queueService.enqueue(List.of("mock:8100"), BuiltInCompressionMode.LOSSLESS.getKey(), false);
        em.flush();

        // WHEN it is pasted again with a different mode.
        var result = queueService.enqueue(List.of("mock:8100"),
                BuiltInCompressionMode.HIGH_REDUCTION.getKey(), false);

        // THEN it is still the one waiting row, now carrying the new mode.
        assertThat(result.alreadyQueued()).isEqualTo(1);
        assertThat(result.accepted()).isZero();
        assertThat(result.requeued()).isZero();
        assertThat(result.rejected()).isZero();
        assertThat(queueRepository.findAll()).hasSize(1);
        assertThat(queueRepository.findByLink("mock:8100").orElseThrow().getCompressionMode())
                .isEqualTo(BuiltInCompressionMode.HIGH_REDUCTION.getKey());
    }

    /**
     * Inherited staged pages are not compressed again, so reusing them after a mode change would publish a
     * chapter half in each mode.
     */
    @Test
    void shouldProcessEveryPageWithTheNewModeWhenTheModeChangedBetweenTwoAttempts() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a three-page gallery whose last page is missing, queued uncompressed...
        writeGallery("8101", 3, 2);
        queueService.enqueue(List.of("mock:8101"), BuiltInCompressionMode.NONE.getKey(), false);
        // ...and a first attempt that stages pages 1-2 as they are and then fails on page 3.
        assertThat(worker.processNext()).isTrue();
        em.flush();
        createdChapterId = chapterRepository.findByGalleryId("mock:8101").orElseThrow().getId();
        assertThat(imageService.stagedPageNumbers(imageService.stagingDir(createdChapterId, false)))
                .containsExactlyInAnyOrder(1, 2);

        // WHEN the link is pasted again with a JPEG XL mode, the missing page turns up, and it is retried.
        queueService.enqueue(List.of("mock:8101"), modeService.save(mode("Changed mode", "-q 40 -e 1")), false);
        Files.write(MOCK_DIR.resolve("galleries/8101/3.png"), TestImages.png(SIZE, SIZE));
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN every page was encoded with the new mode - including the two staged under the old one.
        assertThat(imageService.pageUrls(createdChapterId)).containsExactly(
                "/data/" + createdChapterId + "/1.jxl",
                "/data/" + createdChapterId + "/2.jxl",
                "/data/" + createdChapterId + "/3.jxl");
        assertThat(chapterRepository.findById(createdChapterId).orElseThrow().getDownloadStatus())
                .isEqualTo(DownloadStatus.SUCCESSFUL);
    }

    /**
     * Otherwise a chapter compressed only by a failed attempt would be recorded as full quality. The retry
     * itself compresses nothing: its new page is a JPEG the PNG-only mode skips.
     */
    @Test
    void shouldRecordTheModeWhenOnlyAnEarlierAttemptCompressedThePages() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a three-page gallery whose last page is missing, queued with a PNG-only JPEG XL mode...
        writeGallery("8102", 3, 2);
        var pngOnly = mode("PNG only", "-q 40 -e 1");
        pngOnly.setFormats("PNG");
        String key = modeService.save(pngOnly);
        queueService.enqueue(List.of("mock:8102"), key, false);
        // ...and a first attempt that compresses pages 1-2 in staging and then fails on page 3.
        assertThat(worker.processNext()).isTrue();
        em.flush();
        createdChapterId = chapterRepository.findByGalleryId("mock:8102").orElseThrow().getId();

        // WHEN page 3 turns up as a JPEG, and the item is retried.
        Path json = MOCK_DIR.resolve("8102.json");
        Files.writeString(json, Files.readString(json).replace("galleries/8102/3.png", "galleries/8102/3.jpg"));
        Files.write(MOCK_DIR.resolve("galleries/8102/3.jpg"), TestImages.png(SIZE, SIZE));
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the inherited pages are the compressed ones, the new page is untouched...
        assertThat(imageService.pageUrls(createdChapterId)).containsExactly(
                "/data/" + createdChapterId + "/1.jxl",
                "/data/" + createdChapterId + "/2.jxl",
                "/data/" + createdChapterId + "/3.jpg");
        // ...and the chapter records the mode all the same.
        Chapter chapter = chapterRepository.findById(createdChapterId).orElseThrow();
        assertThat(chapter.getCompressionMode()).isEqualTo(key);
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
    }

    /**
     * Nothing rebuilds the mode from the files, and a rerun with nothing left to compress would never record
     * it, so it must be on the chapter before the first page lands.
     */
    @Test
    void shouldRecordTheModeBeforeTheCompressedPagesArePublished() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a three-page gallery whose last page is missing, queued with a JPEG XL mode, and a first
        // attempt that compresses pages 1-2 in staging and then fails on page 3...
        writeGallery("8103", 3, 2);
        String key = modeService.save(mode("Before publishing", "-q 40 -e 1"));
        queueService.enqueue(List.of("mock:8103"), key, false);
        assertThat(worker.processNext()).isTrue();
        em.flush();
        createdChapterId = chapterRepository.findByGalleryId("mock:8103").orElseThrow().getId();
        // ...then page 3 turns up, and a folder squats on page 2's published name, so the next publish fails
        // part-way, as a crash would stop it.
        Files.write(MOCK_DIR.resolve("galleries/8103/3.png"), TestImages.png(SIZE, SIZE));
        Path squatter = Files.createDirectories(imageDirectory.chapterDir(createdChapterId).resolve("2.jxl"));
        Files.writeString(squatter.resolve("occupied.txt"), "not a page");

        // WHEN the item is retried.
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the publish did not finish, and the item waits for another attempt...
        Chapter chapter = chapterRepository.findById(createdChapterId).orElseThrow();
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.PENDING);
        assertThat(queueRepository.findByLink("mock:8103").orElseThrow().getError()).isNull();
        // ...but the chapter already names the mode its pages went through.
        assertThat(chapter.getCompressionMode()).isEqualTo(key);
    }

    /** Like every compression failure, a missing mode means "keep the original", never a failed download. */
    @Test
    void shouldDownloadWithoutCompressingWhenTheQueuedModeHasBeenDeleted()
    {
        // GIVEN a link queued with a mode that is then deleted.
        String key = modeService.save(mode("Doomed", "-q 40 -e 1"));
        queueService.enqueue(List.of("mock:8100"), key, false);
        modeService.delete(ImageCompressionModeService.customId(key));
        em.flush();

        // WHEN the worker takes it.
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the chapter is complete, with the source's own page files.
        Chapter chapter = chapterRepository.findByGalleryId("mock:8100").orElseThrow();
        createdChapterId = chapter.getId();
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
        assertThat(chapter.getPageNum()).isEqualTo(2);
        assertThat(imageService.pageUrls(createdChapterId))
                .containsExactly("/data/" + createdChapterId + "/1.png", "/data/" + createdChapterId + "/2.png");
    }

    /** Anything the encoder left in staging could be picked up by publishing. */
    @Test
    void shouldLeaveNothingInStagingWhenACompressedDownloadCompletes()
    {
        assumeTool("cjxl");
        // GIVEN
        String key = modeService.save(mode("Staging check", "-q 40 -e 1"));
        queueService.enqueue(List.of("mock:8100"), key, false);

        // WHEN
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the published pages are the encoded ones and the staging folder is gone entirely.
        Chapter chapter = chapterRepository.findByGalleryId("mock:8100").orElseThrow();
        createdChapterId = chapter.getId();
        assertThat(imageService.pageUrls(createdChapterId)).hasSize(2)
                .allMatch(url -> url.endsWith(".jxl"));
        assertThat(Files.exists(imageService.stagingDir(createdChapterId, true))).isFalse();
    }

    // ---- helpers -------------------------------------------------------------

    private void assumeTool(String tool)
    {
        Assumptions.assumeTrue(toolLocator.find(tool).isPresent(),
                () -> "No bundled " + tool + " for this platform - skipping");
    }

    private static ImageCompressionMode mode(String name, String encoderArgs)
    {
        var mode = new ImageCompressionMode();
        mode.setName(name);
        mode.setEncoder(ImageEncoder.JXL);
        mode.setEncoderArgs(encoderArgs);
        return mode;
    }

    private static void writeGallery(String id, int pages) throws IOException
    {
        writeGallery(id, pages, pages);
    }

    /** Only the first {@code presentPages} exist, so an attempt stages those and fails on the next. */
    private static void writeGallery(String id, int declaredPages, int presentPages) throws IOException
    {
        Path gallery = MOCK_DIR.resolve("galleries").resolve(id);
        Files.createDirectories(gallery);

        var pageJson = new StringBuilder();
        for (int page = 1; page <= declaredPages; page++)
        {
            pageJson.append(page > 1 ? "," : "")
                    .append("{\"number\":").append(page)
                    .append(",\"path\":\"galleries/").append(id).append('/').append(page).append(".png\"}");
            if (page <= presentPages)
            {
                Files.write(gallery.resolve(page + ".png"), TestImages.png(SIZE, SIZE));
            }
        }
        String json = """
                {
                  "id": %s,
                  "media_id": "%s",
                  "title": {
                    "english": "[Test] Gallery %s",
                    "japanese": "%s in Japanese",
                    "pretty": "Gallery %s"
                  },
                  "tags": [
                    {"type": "language", "name": "japanese"},
                    {"type": "artist", "name": "testartist"}
                  ],
                  "pages": [%s]
                }
                """.formatted(id, id, id, id, id, pageJson);
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

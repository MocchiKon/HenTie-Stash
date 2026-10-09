package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.ResourceLink;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.compress.ImageToolLocator;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import io.github.mocchikon.hentie.service.download.GalleryImportService;
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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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
    @Autowired DataDownloaderRegistry registry;
    @Autowired GalleryImportService importService;
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
        assertThat(queueService.enqueue(List.of("mock:8100"), TestDownloads.choices(key, false)).accepted()).isEqualTo(1);

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
        assertThat(queueService.enqueue(List.of("mock:8100"), TestDownloads.choices(BuiltInCompressionMode.NONE.getKey(), false))
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
        var result = queueService.enqueue(List.of("mock:8100"), TestDownloads.choices(BuiltInCompressionMode.LOSSLESS.getKey(), false));

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
        queueService.enqueue(List.of("mock:8100"), TestDownloads.choices(BuiltInCompressionMode.LOSSLESS.getKey(), false));
        var item = queueRepository.findByLink("mock:8100").orElseThrow();
        item.setError("boom");
        item.setIgnoreImageErrors(true);
        queueRepository.save(item);
        em.flush();

        // WHEN it is pasted again, this time with a different mode.
        var result = queueService.enqueue(List.of("mock:8100"), TestDownloads.choices(BuiltInCompressionMode.VERY_HIGH_REDUCTION.getKey(), false));

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
        queueService.enqueue(List.of("mock:8100"), TestDownloads.choices(BuiltInCompressionMode.LOSSLESS.getKey(), false));
        em.flush();

        // WHEN it is pasted again with a different mode.
        var result = queueService.enqueue(List.of("mock:8100"), TestDownloads.choices(BuiltInCompressionMode.HIGH_REDUCTION.getKey(), false));

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
     * The pages an earlier attempt saved are the chapter's as they are: a mode chosen afterwards applies to the
     * pages still missing.
     */
    @Test
    void shouldKeepThePagesAnEarlierAttemptSavedWhenTheModeChangedBetweenTwoAttempts() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a three-page gallery whose last page is missing, queued uncompressed...
        writeGallery("8101", 3, 2);
        queueService.enqueue(List.of("mock:8101"), TestDownloads.choices(BuiltInCompressionMode.NONE.getKey(), false));
        // ...and a first attempt that saves pages 1-2 as they are and then fails on page 3.
        assertThat(worker.processNext()).isTrue();
        em.flush();
        createdChapterId = chapterRepository.findByGalleryId("mock:8101").orElseThrow().getId();
        assertThat(imageService.pageNames(createdChapterId)).containsExactly("1.png", "2.png");

        // WHEN the link is pasted again with a JPEG XL mode, the missing page turns up, and it is retried.
        String key = modeService.save(mode("Changed mode", "-q 40 -e 1"));
        queueService.enqueue(List.of("mock:8101"), TestDownloads.choices(key, false));
        Files.write(MOCK_DIR.resolve("galleries/8101/3.png"), TestImages.png(SIZE, SIZE));
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN only the page that was still missing went through the new mode...
        assertThat(imageService.pageUrls(createdChapterId)).containsExactly(
                "/data/" + createdChapterId + "/1.png",
                "/data/" + createdChapterId + "/2.png",
                "/data/" + createdChapterId + "/3.jxl");
        // ...which the chapter names, since one of its pages is compressed.
        Chapter chapter = chapterRepository.findById(createdChapterId).orElseThrow();
        assertThat(chapter.getCompressionMode()).isEqualTo(key);
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
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
        queueService.enqueue(List.of("mock:8102"), TestDownloads.choices(key, false));
        // ...and a first attempt that compresses and saves pages 1-2 and then fails on page 3.
        assertThat(worker.processNext()).isTrue();
        em.flush();
        createdChapterId = chapterRepository.findByGalleryId("mock:8102").orElseThrow().getId();
        assertThat(imageService.pageNames(createdChapterId)).containsExactly("1.jxl", "2.jxl");

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
     * A page that could not be saved after its encoder is never skipped, even in lenient mode: a full disk would
     * skip every page and still mark the chapter {@code SUCCESSFUL}. Nothing rebuilds the mode from the files, so the
     * chapter must name it for the page that was saved, though its attempt failed.
     */
    @Test
    void shouldFailTheAttemptButKeepTheModeWhenACompressedPageCannotBeSaved() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a chapter with its gallery's metadata, and a folder squatting on page 2's name in its folder...
        writeGallery("8103", 2);
        ResourceLink link = registry.parse("mock:8103").orElseThrow();
        createdChapterId = importService.importChapter(
                link.downloader().downloadGalleryInfo(link.resourceId()), link.galleryId());
        Path squatter = Files.createDirectories(imageDirectory.chapterDir(createdChapterId).resolve("2.jxl"));
        Files.writeString(squatter.resolve("occupied.txt"), "not a page");
        // ...queued with a JPEG XL mode, in the one mode allowed to skip pages.
        String key = modeService.save(mode("Cannot save", "-q 40 -e 1"));
        queueService.enqueue(List.of("mock:8103"), TestDownloads.choices(key, false));
        int id = queueRepository.findByLink("mock:8103").orElseThrow().getId();
        assertThat(queueService.retry(id, true, false)).isTrue();
        em.flush();

        // WHEN the worker takes it.
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the attempt failed rather than finishing without page 2...
        Chapter chapter = chapterRepository.findById(createdChapterId).orElseThrow();
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.PENDING);
        DownloadQueueItem item = queueRepository.findById(id).orElseThrow();
        assertThat(item.getAttempts()).isEqualTo(1);
        // ...while page 1 is saved compressed, and the chapter names the mode it went through.
        assertThat(imageService.pageNames(createdChapterId)).containsExactly("1.jxl");
        assertThat(chapter.getCompressionMode()).isEqualTo(key);

        // WHEN the folder is gone and the item runs again.
        ImageService.deleteRecursively(squatter);
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the chapter is complete.
        assertThat(imageService.pageNames(createdChapterId)).containsExactly("1.jxl", "2.jxl");
        assertThat(chapterRepository.findById(createdChapterId).orElseThrow().getDownloadStatus())
                .isEqualTo(DownloadStatus.SUCCESSFUL);
    }

    /** The mode is recorded before any page could be re-encoded, so it must be taken back when none was. */
    @Test
    void shouldRecordNoModeWhenTheModeReEncodedNoPage() throws IOException
    {
        // GIVEN a gallery of JPEGs, queued with a mode that takes only PNGs.
        writeGallery("8104", 2, 2, "jpg");
        var pngOnly = mode("PNG only, JPEG gallery", "-q 40 -e 1");
        pngOnly.setFormats("PNG");
        queueService.enqueue(List.of("mock:8104"), TestDownloads.choices(modeService.save(pngOnly), false));

        // WHEN the worker takes it.
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the pages are the source's own, and the chapter says so.
        Chapter chapter = chapterRepository.findByGalleryId("mock:8104").orElseThrow();
        createdChapterId = chapter.getId();
        assertThat(imageService.pageNames(createdChapterId)).containsExactly("1.jpg", "2.jpg");
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
        assertThat(chapter.getCompressionMode()).isNull();
    }

    /** Likewise for an attempt that fails before re-encoding anything, which no later attempt would put right. */
    @Test
    void shouldTakeTheModeBackWhenAnAttemptFailedBeforeReEncodingAnyPage() throws IOException
    {
        // GIVEN a gallery whose pages the source does not have (yet), queued with a JPEG XL mode.
        writeGallery("8105", 2, 0);
        queueService.enqueue(List.of("mock:8105"), TestDownloads.choices(
                modeService.save(mode("Not yet", "-q 40 -e 1")), false));

        // WHEN its first attempt fails.
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the chapter records no mode.
        Chapter chapter = chapterRepository.findByGalleryId("mock:8105").orElseThrow();
        createdChapterId = chapter.getId();
        assertThat(queueRepository.findByLink("mock:8105").orElseThrow().getAttempts()).isEqualTo(1);
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.PENDING);
        assertThat(chapter.getCompressionMode()).isNull();
    }

    /** Like every compression failure, a missing mode means "keep the original", never a failed download. */
    @Test
    void shouldDownloadWithoutCompressingWhenTheQueuedModeHasBeenDeleted()
    {
        // GIVEN a link queued with a mode that is then deleted.
        String key = modeService.save(mode("Doomed", "-q 40 -e 1"));
        queueService.enqueue(List.of("mock:8100"), TestDownloads.choices(key, false));
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
        queueService.enqueue(List.of("mock:8100"), TestDownloads.choices(key, false));

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

    private static void writeGallery(String id, int declaredPages, int presentPages) throws IOException
    {
        writeGallery(id, declaredPages, presentPages, "png");
    }

    /**
     * Only the first {@code presentPages} exist, so an attempt saves those and fails on the next. Every page is
     * a PNG, whatever {@code extension} names it.
     */
    private static void writeGallery(String id, int declaredPages, int presentPages, String extension)
            throws IOException
    {
        Path gallery = MOCK_DIR.resolve("galleries").resolve(id);
        Files.createDirectories(gallery);

        var pageJson = new StringBuilder();
        for (int page = 1; page <= declaredPages; page++)
        {
            pageJson.append(page > 1 ? "," : "")
                    .append("{\"number\":").append(page)
                    .append(",\"path\":\"galleries/").append(id).append('/').append(page).append('.')
                    .append(extension).append("\"}");
            if (page <= presentPages)
            {
                Files.write(gallery.resolve(page + "." + extension), TestImages.png(SIZE, SIZE));
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

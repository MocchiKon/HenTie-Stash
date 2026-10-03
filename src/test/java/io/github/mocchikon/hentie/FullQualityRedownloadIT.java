package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.DownloadService;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Re-download in full quality": the one download path that overwrites pages, so most of this pins what it
 * must still leave alone. Compression is simulated by swapping each {@code n.png} for an {@code n.jxl}, so no
 * encoder is needed.
 */
@SpringBootTest
@Transactional
class FullQualityRedownloadIT
{
    /** Matches {@code app.download.mock-dir} in the test profile. */
    private static final Path MOCK_DIR = Paths.get("./target/test-mock-server");

    private static final byte[] COMPRESSED = "a page as Image Compression left it".getBytes(StandardCharsets.UTF_8);

    private static final String LOSSLESS = BuiltInCompressionMode.LOSSLESS.getKey();

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadService downloadService;
    @Autowired DownloadWorker worker;
    @Autowired ChapterService chapterService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired ImageService imageService;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ImageCompressionService compressionService;
    @PersistenceContext EntityManager em;

    private Integer createdChapterId;

    @BeforeEach
    void resetMockSource()
    {
        deleteRecursively(MOCK_DIR);
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

    // ---- the re-download ------------------------------------------------------

    @Test
    void shouldReplaceTheCompressedPagesWithTheSourcesOriginals() throws IOException
    {
        // GIVEN a downloaded chapter whose two pages were compressed afterwards.
        int chapterId = downloadedAndCompressed("8400", 2);
        assertThat(pageFiles(chapterId)).containsExactly("1.jxl", "2.jxl");

        // WHEN a full-quality re-download is queued, and the worker runs it.
        assertThat(downloadService.queueFullQuality(chapterService.get(chapterId))).isTrue();
        assertThat(worker.processNext()).isTrue();

        // THEN the pages are the source's own again, and the compressed ones are gone...
        assertThat(pageFiles(chapterId)).containsExactly("1.png", "2.png");
        em.flush();
        em.clear();
        Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
        // ...the chapter no longer says it is compressed...
        assertThat(chapter.getCompressionMode()).isNull();
        // ...its searchable stats follow the new files...
        assertThat(chapter.getPageNum()).isEqualTo(2);
        assertThat(chapter.getDiskSize()).isEqualTo(imageService.diskSize(chapterId));
        assertThat(chapter.getDiskSize()).isGreaterThan(2L * COMPRESSED.length);
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
        // ...and the queue row is gone, as for any completed item.
        assertThat(queueRepository.findByLink("mock:8400")).isEmpty();
    }

    /** A finished download's pages are the user's, so a page they deleted stays deleted. */
    @Test
    void shouldReplaceOnlyThePagesTheChapterHas() throws IOException
    {
        // GIVEN a compressed three-page chapter whose page 2 the user deleted.
        int chapterId = downloadedAndCompressed("8401", 3);
        chapterService.deletePage(chapterId, "2.jxl");

        // WHEN
        downloadService.queueFullQuality(chapterService.get(chapterId));
        assertThat(worker.processNext()).isTrue();

        // THEN pages 1 and 3 are originals again, and page 2 did not come back.
        assertThat(pageFiles(chapterId)).containsExactly("1.png", "3.png");
        em.flush();
        em.clear();
        Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
        assertThat(chapter.getPageNum()).isEqualTo(2);
        assertThat(chapter.getCompressionMode()).isNull();
    }

    /** Fetching page 2 for a page kept as {@code 02.jxl} would put a second page 2 beside it as {@code 2.png}. */
    @Test
    void shouldLeaveAPageNamedDifferentlyFromTheSourcesNumbering() throws IOException
    {
        // GIVEN a compressed chapter whose page 2 is stored as 02.jxl.
        int chapterId = downloadedAndCompressed("8402", 3);
        Path dir = imageDirectory.chapterDir(chapterId);
        Files.move(dir.resolve("2.jxl"), dir.resolve("02.jxl"));
        imageService.invalidateListing(chapterId);

        // WHEN
        downloadService.queueFullQuality(chapterService.get(chapterId));
        assertThat(worker.processNext()).isTrue();

        // THEN the canonically named pages were replaced and 02.jxl is exactly as it was.
        assertThat(pageFiles(chapterId)).containsExactly("1.png", "02.jxl", "3.png");
        assertThat(Files.readAllBytes(dir.resolve("02.jxl"))).isEqualTo(COMPRESSED);
    }

    /** The page numbers say every page was replaced, yet {@code 02.jxl} is still compressed. */
    @Test
    void shouldKeepTheModeWhileAPageKeptUnderAnotherNameIsStillCompressed() throws IOException
    {
        // GIVEN a compressed chapter holding both 2.jxl and 02.jxl.
        int chapterId = downloadedAndCompressed("8409", 2);
        Path dir = imageDirectory.chapterDir(chapterId);
        Files.write(dir.resolve("02.jxl"), COMPRESSED);
        imageService.invalidateListing(chapterId);

        // WHEN
        downloadService.queueFullQuality(chapterService.get(chapterId));
        assertThat(worker.processNext()).isTrue();

        // THEN the canonical pages are originals, 02.jxl is untouched, and the chapter still says it is compressed.
        assertThat(pageFiles(chapterId)).containsExactlyInAnyOrder("1.png", "2.png", "02.jxl");
        assertThat(Files.readAllBytes(dir.resolve("02.jxl"))).isEqualTo(COMPRESSED);
        em.flush();
        em.clear();
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCompressionMode()).isEqualTo(LOSSLESS);
    }

    /** A non-canonical page that is no encoder output ({@code 02.jpg}) does not keep the mode. */
    @Test
    void shouldClearTheModeWhenOnlyAnUncompressedPageKeptUnderAnotherNameRemains() throws IOException
    {
        // GIVEN a compressed chapter holding a user's own 02.jpg beside 2.jxl.
        int chapterId = downloadedAndCompressed("8412", 2);
        Path dir = imageDirectory.chapterDir(chapterId);
        Files.write(dir.resolve("02.jpg"), COMPRESSED);
        imageService.invalidateListing(chapterId);

        // WHEN
        downloadService.queueFullQuality(chapterService.get(chapterId));
        assertThat(worker.processNext()).isTrue();

        // THEN 02.jpg is untouched, and the chapter no longer says it is compressed.
        assertThat(pageFiles(chapterId)).containsExactlyInAnyOrder("1.png", "2.png", "02.jpg");
        assertThat(Files.readAllBytes(dir.resolve("02.jpg"))).isEqualTo(COMPRESSED);
        em.flush();
        em.clear();
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCompressionMode()).isNull();
    }

    /** Refused before fetching, since giving up after fetching would discard everything staged. */
    @Test
    void shouldRefuseARedownloadBeforeFetchingWhileACompressionRunIsGoing() throws Exception
    {
        // GIVEN a compressed chapter, and a run holding the lock on another thread.
        int chapterId = downloadedAndCompressed("8411", 2);
        downloadService.queueFullQuality(chapterService.get(chapterId));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread run = new Thread(() -> compressionService.exclusively(() ->
        {
            held.countDown();
            try
            {
                return release.await(30, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }));
        run.start();
        try
        {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            // WHEN the worker reaches the re-download.
            assertThat(worker.processNext()).isTrue();

            // THEN it failed transiently, having fetched nothing and touched no page.
            DownloadQueueItem row = queueRepository.findByLink("mock:8411").orElseThrow();
            assertThat(row.getError()).isNull();
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(imageService.stagedPageNumbers(imageService.stagingDir(chapterId, false))).isEmpty();
            assertThat(pageFiles(chapterId)).containsExactly("1.jxl", "2.jxl");
        }
        finally
        {
            release.countDown();
            run.join(10_000);
        }

        // WHEN the run is over, THEN the next attempt replaces the pages.
        assertThat(worker.processNext()).isTrue();
        assertThat(pageFiles(chapterId)).containsExactly("1.png", "2.png");
    }

    /** Nothing is replaced before every original has arrived. */
    @Test
    void shouldKeepTheCompressedPagesWhenTheSourceNoLongerHasOne() throws IOException
    {
        // GIVEN a compressed two-page chapter whose source has since lost page 2.
        int chapterId = downloadedAndCompressed("8403", 2);
        Files.delete(MOCK_DIR.resolve("galleries/8403/2.png"));

        // WHEN the re-download runs out of attempts.
        downloadService.queueFullQuality(chapterService.get(chapterId));
        int attempts = 0;
        while (worker.processNext())
        {
            attempts++;
        }

        // THEN it gave up and says why...
        assertThat(attempts).isPositive();
        DownloadQueueItem failed = queueRepository.findByLink("mock:8403").orElseThrow();
        assertThat(failed.getError()).contains("page 2");
        assertThat(failed.isReplacePages()).isTrue();
        // ...no page was touched, and the chapter still says it is compressed.
        assertThat(pageFiles(chapterId)).containsExactly("1.jxl", "2.jxl");
        em.flush();
        em.clear();
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCompressionMode()).isEqualTo(LOSSLESS);
    }

    /** The label stays, since one page is still compressed. */
    @Test
    void shouldKeepTheModeWhenARetryIgnoringImageErrorsSkippedAPage() throws IOException
    {
        // GIVEN a failed re-download of a chapter whose source lost page 2.
        int chapterId = downloadedAndCompressed("8404", 2);
        Files.delete(MOCK_DIR.resolve("galleries/8404/2.png"));
        downloadService.queueFullQuality(chapterService.get(chapterId));
        while (worker.processNext())
        {
            // run out of attempts
        }
        DownloadQueueItem failed = queueRepository.findByLink("mock:8404").orElseThrow();

        // WHEN it is retried ignoring image errors.
        assertThat(queueService.retry(failed.getId(), true, false)).isTrue();
        assertThat(worker.processNext()).isTrue();

        // THEN page 1 is the original, page 2 is still the compressed one...
        assertThat(pageFiles(chapterId)).containsExactly("1.png", "2.jxl");
        em.flush();
        em.clear();
        // ...so the chapter still says it is compressed, and the item is done.
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCompressionMode()).isEqualTo(LOSSLESS);
        assertThat(queueRepository.findByLink("mock:8404")).isEmpty();
    }

    @Test
    void shouldFailRatherThanImportTheGalleryWhenItsChapterIsGone() throws IOException
    {
        // GIVEN a re-download queued for a gallery no chapter holds.
        writeGallery("8405", 1);
        queueService.enqueueFullQuality("mock:8405", "mock:8405");

        // WHEN
        assertThat(worker.processNext()).isTrue();

        // THEN it failed permanently, at once, and created nothing.
        DownloadQueueItem failed = queueRepository.findByLink("mock:8405").orElseThrow();
        assertThat(failed.getError()).contains("no longer exists");
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(chapterRepository.findByGalleryId("mock:8405")).isEmpty();
    }

    // ---- the queue row --------------------------------------------------------

    @Test
    void shouldTurnTheGallerysExistingRowIntoAnUncompressedStrictRedownload()
    {
        // GIVEN a row for the gallery that failed under a compressing, lenient, title-checking paste.
        queueService.enqueue(List.of("mock:8406"), LOSSLESS, true);
        DownloadQueueItem row = queueRepository.findByLink("mock:8406").orElseThrow();
        queueService.recordFailure(row, "boom", null, true, 3);
        queueService.retry(row.getId(), true, false);
        queueService.recordFailure(row, "boom again", null, true, 3);

        // WHEN a full-quality re-download is queued for it.
        queueService.enqueueFullQuality("mock:8406", "mock:8406");

        // THEN it is that same row, pending again, with every choice reset.
        assertThat(queueRepository.findAll()).filteredOn(item -> "mock:8406".equals(item.getGalleryId())).hasSize(1);
        em.flush();
        em.clear();
        DownloadQueueItem item = queueRepository.findByLink("mock:8406").orElseThrow();
        assertThat(item.getError()).isNull();
        assertThat(item.getAttempts()).isZero();
        assertThat(item.isReplacePages()).isTrue();
        assertThat(item.getCompressionMode()).isEqualTo(BuiltInCompressionMode.NONE.getKey());
        assertThat(item.isIgnoreImageErrors()).isFalse();
        assertThat(item.isAvoidDuplicateTitles()).isFalse();
    }

    /** The running attempt's outcome belongs to the old request, so it neither deletes nor fails the row. */
    @Test
    void shouldKeepARowTurnedIntoARedownloadWhileItsAttemptWasRunning()
    {
        // GIVEN the row an ordinary attempt was started from, and a re-download queued meanwhile.
        queueService.enqueue(List.of("mock:8410"), BuiltInCompressionMode.NONE.getKey(), false);
        em.flush();
        DownloadQueueItem attempted = queueRepository.findByLink("mock:8410").orElseThrow();
        em.detach(attempted);
        queueService.enqueueFullQuality("mock:8410", "mock:8410");

        // WHEN + THEN - the attempt failing records nothing on the row...
        assertThat(queueService.recordFailure(attempted, "boom", null, true, 3))
                .isEqualTo(DownloadQueueService.FailureOutcome.SUPERSEDED);
        // ...and the attempt finishing does not delete it.
        assertThat(queueService.complete(attempted)).isFalse();
        em.flush();
        em.clear();
        DownloadQueueItem row = queueRepository.findByLink("mock:8410").orElseThrow();
        assertThat(row.isReplacePages()).isTrue();
        assertThat(row.getError()).isNull();
        assertThat(row.getAttempts()).isZero();
    }

    /** Likewise for a re-paste that changes the choices; one repeating them asks for nothing new. */
    @Test
    void shouldKeepARowWhoseChoicesAPasteChangedWhileItsAttemptWasRunning()
    {
        // GIVEN the row an uncompressed attempt was started from, re-pasted meanwhile with a compressing mode.
        queueService.enqueue(List.of("mock:8413"), BuiltInCompressionMode.NONE.getKey(), false);
        em.flush();
        DownloadQueueItem attempted = queueRepository.findByLink("mock:8413").orElseThrow();
        em.detach(attempted);
        queueService.enqueue(List.of("mock:8413"), LOSSLESS, false);

        // WHEN + THEN - neither the failure nor the success of that attempt touches the row...
        assertThat(queueService.recordFailure(attempted, "boom", null, false, 3))
                .isEqualTo(DownloadQueueService.FailureOutcome.SUPERSEDED);
        assertThat(queueService.complete(attempted)).isFalse();
        em.flush();
        em.clear();
        DownloadQueueItem row = queueRepository.findByLink("mock:8413").orElseThrow();
        assertThat(row.getCompressionMode()).isEqualTo(LOSSLESS);
        assertThat(row.getAttempts()).isZero();

        // ...while re-pasting the very same choices leaves an attempt of them free to finish the row.
        em.detach(row);
        queueService.enqueue(List.of("mock:8413"), LOSSLESS, false);
        assertThat(queueService.complete(row)).isTrue();
        em.flush();
        em.clear();
        assertThat(queueRepository.findByLink("mock:8413")).isEmpty();
    }

    @Test
    void shouldTurnAWaitingRedownloadBackIntoAnOrdinaryDownloadWhenTheLinkIsPasted()
    {
        // GIVEN
        queueService.enqueueFullQuality("mock:8407", "mock:8407");

        // WHEN
        var result = queueService.enqueue(List.of("mock:8407"), BuiltInCompressionMode.NONE.getKey(), false);

        // THEN
        assertThat(result.alreadyQueued()).isEqualTo(1);
        assertThat(result.accepted()).isZero();
        assertThat(result.requeued()).isZero();
        assertThat(result.rejected()).isZero();
        em.flush();
        em.clear();
        assertThat(queueRepository.findByLink("mock:8407").orElseThrow().isReplacePages()).isFalse();
    }

    // ---- who is offered it ----------------------------------------------------

    @Test
    void shouldOfferARedownloadOnlyForACompressedChapterFromASourceThisAppHas()
    {
        // GIVEN
        int fromSource = newChapter("From the mock source", "mock:8408");
        int byHand = newChapter("Added by hand", null);
        int fromElsewhere = newChapter("Imported from a source with no downloader", "elsewhere:8408");

        // WHEN + THEN - never compressed: nothing to offer, whatever the source.
        assertThat(downloadService.fullQualityLink(chapterService.get(fromSource))).isEmpty();

        // WHEN + THEN - compressed: offered for the mock source only.
        chapterService.setCompressionMode(fromSource, LOSSLESS);
        chapterService.setCompressionMode(byHand, LOSSLESS);
        chapterService.setCompressionMode(fromElsewhere, LOSSLESS);
        assertThat(downloadService.fullQualityLink(chapterService.get(fromSource))).contains("mock:8408");
        assertThat(downloadService.fullQualityLink(chapterService.get(byHand))).isEmpty();
        assertThat(downloadService.fullQualityLink(chapterService.get(fromElsewhere))).isEmpty();
        // ...and queueing one it is not offered for queues nothing.
        assertThat(downloadService.queueFullQuality(chapterService.get(byHand))).isFalse();
        assertThat(queueRepository.findAll()).noneMatch(DownloadQueueItem::isReplacePages);
    }

    // ---- helpers -------------------------------------------------------------

    /** Downloaded, then each page swapped for an {@code n.jxl}, with stats and recorded mode to match. */
    private int downloadedAndCompressed(String id, int pages) throws IOException
    {
        writeGallery(id, pages);
        queueService.enqueue(List.of("mock:" + id), BuiltInCompressionMode.NONE.getKey(), false);
        assertThat(worker.processNext()).isTrue();
        int chapterId = chapterRepository.findByGalleryId("mock:" + id).orElseThrow().getId();
        createdChapterId = chapterId;
        Path dir = imageDirectory.chapterDir(chapterId);
        for (int page = 1; page <= pages; page++)
        {
            Files.write(dir.resolve(page + ".jxl"), COMPRESSED);
            Files.delete(dir.resolve(page + ".png"));
        }
        imageService.invalidateListing(chapterId);
        chapterService.syncImageStats(chapterId);
        chapterService.setCompressionMode(chapterId, LOSSLESS);
        return chapterId;
    }

    private int newChapter(String titleFull, String galleryId)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        form.setGalleryId(galleryId);
        return chapterService.create(form);
    }

    /** In listing order: numeric, so 02.jxl is page 2. */
    private List<String> pageFiles(int chapterId)
    {
        return imageDirectory.list(chapterId);
    }

    /** A mock gallery whose pages are real PNGs. */
    private static void writeGallery(String id, int pages) throws IOException
    {
        Path gallery = MOCK_DIR.resolve("galleries").resolve(id);
        Files.createDirectories(gallery);
        var pageJson = new StringBuilder();
        for (int page = 1; page <= pages; page++)
        {
            pageJson.append(page > 1 ? "," : "")
                    .append("{\"number\":").append(page)
                    .append(",\"path\":\"galleries/").append(id).append('/').append(page).append(".png\"}");
            Files.write(gallery.resolve(page + ".png"), TestImages.png(64, 64));
        }
        String json = """
                {
                  "id": %s,
                  "media_id": "%s",
                  "title": {
                    "english": "[Test] Full quality %s",
                    "japanese": "%s in Japanese",
                    "pretty": "Full quality %s"
                  },
                  "tags": [
                    {"type": "language", "name": "japanese"}
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

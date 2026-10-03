package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.DownloadedGalleryRepository;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.scrapper.ResourceLink;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.download.ChapterDownloadService;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import io.github.mocchikon.hentie.service.download.GalleryImportService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * The download pipeline end to end, against the "mock" source. The crash tests matter most: each sets up
 * the exact state a crash leaves and runs the worker again. Recovery depends on the queue row surviving
 * until the end, and on {@code chapter.download_status} saying whether the images may be touched.
 */
@SpringBootTest
@Transactional
class DownloadQueueIT
{
    private static final String NO_COMPRESSION = BuiltInCompressionMode.NONE.getKey();

    /** Matches {@code app.download.mock-dir} in the test profile. */
    private static final Path MOCK_DIR = Paths.get("./target/test-mock-server");

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired GalleryImportService importService;
    @Autowired DataDownloaderRegistry registry;
    @Autowired ChapterRepository chapterRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired DownloadedGalleryRepository downloadedGalleryRepository;
    @Autowired ImageService imageService;
    @Autowired ChapterService chapterService;
    @Autowired ChapterDownloadService chapterDownloadService;
    @Autowired SettingsService settingsService;
    @Autowired AppProperties appProperties;
    @Autowired WriteGate writeGate;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void resetMockSource() throws IOException
    {
        deleteRecursively(MOCK_DIR);
        // 900: complete. 901/902: list two pages but have one, which makes an item fail.
        writeGallery("900", 2, 2);
        writeGallery("901", 2, 1);
        writeGallery("902", 2, 1);
        worker.setPaused(false);
    }

    @Test
    void shouldCreateTheChapterWithItsMetadataAndImagesWhenProcessingAQueuedLink()
    {
        // GIVEN one queued link.
        assertThat(queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false).accepted()).isEqualTo(1);

        Integer chapterId = null;
        try
        {
            // WHEN the worker takes it.
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the chapter carries what the source said, under the namespaced gallery id.
            Chapter chapter = chapterRepository.findByGalleryId("mock:900").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getTitleFull()).isEqualTo("[Test] Gallery 900");
            assertThat(chapter.getTitle()).isEqualTo("Gallery 900");
            assertThat(chapter.getNativeTitle()).isEqualTo("900 in Japanese");
            // Canonical spelling, or "japanese" would be a facet of its own.
            assertThat(chapter.getLanguage()).isEqualTo("Japanese");
            // Stored folded: the source says "Test tag".
            assertThat(chapter.getTags()).extracting("name").containsExactly("test tag");
            assertThat(chapter.getArtists()).extracting("name").containsExactly("testartist");
            assertThat(chapter.getGroups()).extracting("name").containsExactly("test group");
            assertThat(chapter.getParodies()).extracting("name").containsExactly("original");

            // AND the images are on disk, with matching stats.
            assertThat(imageService.pageUrls(chapterId)).hasSize(2);
            assertThat(chapter.getPageNum()).isEqualTo(2);
            assertThat(chapter.getDiskSize()).isPositive();

            // AND the queue row is gone while the permanent "downloaded" record stays.
            assertThat(queueRepository.findByLink("mock:900")).isEmpty();
            assertThat(downloadedGalleryRepository.findById("mock:900")).isPresent();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** Makes re-running an item safe, and protects the user's edits from being overwritten. */
    @Test
    void shouldNotTouchTheDatabaseOrTheImagesWhenTheGalleryIsAlreadyStored() throws IOException
    {
        // GIVEN a downloaded gallery, renamed by the user, with page 1 given recognizable content.
        queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false);
        worker.processNext();
        em.flush();
        Chapter first = chapterRepository.findByGalleryId("mock:900").orElseThrow();
        Integer chapterId = first.getId();
        try
        {
            first.setTitle("Renamed by the user");
            chapterRepository.save(first);
            em.flush();
            Path page1 = chapterDir(chapterId).resolve("1.webp");
            Files.writeString(page1, "edited-in-place");

            // WHEN the same link is queued and processed again.
            queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false);
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN there is still one chapter, still named as the user named it...
            assertThat(chapterRepository.findAll().stream()
                    .filter(c -> "mock:900".equals(c.getGalleryId()))).hasSize(1);
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getTitle())
                    .isEqualTo("Renamed by the user");
            // ...and the images were not fetched again.
            assertThat(Files.readString(page1)).isEqualTo("edited-in-place");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** The re-run must recognize its own chapter rather than create a second one. */
    @Test
    void shouldResumeAndDownloadTheImagesWhenTheAppDiedAfterSavingTheMetadata()
    {
        // GIVEN the row pending, chapter and "downloaded" record written, nothing on disk.
        queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false);
        ResourceLink link = registry.parse("mock:900").orElseThrow();
        GalleryData data = link.downloader().downloadGalleryInfo(link.resourceId());
        int chapterId = importService.importChapter(data, link.galleryId());
        importService.recordDownloaded(link.galleryId(), chapterId);
        em.flush();
        try
        {
            assertThat(imageService.scanStats(chapterId).pageCount()).isZero();

            // WHEN the app comes back up and the worker picks the still-pending item.
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN no second chapter was created, and the images are now complete.
            assertThat(chapterRepository.findAll().stream()
                    .filter(c -> "mock:900".equals(c.getGalleryId()))).hasSize(1);
            assertThat(imageService.pageUrls(chapterId)).hasSize(2);
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isEqualTo(2);
            assertThat(queueRepository.findByLink("mock:900")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** A killed run's staging may hold a half-written page, so a new run must not trust it. */
    @Test
    void shouldFetchEveryPageAgainWhenTheAppDiedPartWayThroughDownloadingThem() throws IOException
    {
        // GIVEN nothing published, and staging left by the killed run (with a bogus page 9).
        queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false);
        ResourceLink link = registry.parse("mock:900").orElseThrow();
        int chapterId = importService.importChapter(
                link.downloader().downloadGalleryInfo(link.resourceId()), link.galleryId());
        importService.recordDownloaded(link.galleryId(), chapterId);
        em.flush();
        try
        {
            Path staging = imageService.stagingDir(chapterId, false);
            Files.createDirectories(staging);
            Files.writeString(staging.resolve("1.webp"), "half-downloaded");
            Files.writeString(staging.resolve("9.webp"), "leftover from the killed run");

            // WHEN the queue is worked again.
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN exactly the two real pages; the stale staged files were discarded, not published.
            assertThat(imageService.pageUrls(chapterId))
                    .containsExactly("/data/" + chapterId + "/1.webp", "/data/" + chapterId + "/2.webp");
            assertThat(Files.readString(chapterDir(chapterId).resolve("1.webp"))).isEqualTo("page 1 of 900");
            assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isEqualTo(2);
            assertThat(queueRepository.findByLink("mock:900")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** The power-cut case {@code download_status} exists for: only absent pages are fetched. */
    @Test
    void shouldFetchOnlyTheMissingPagesWhenAPendingChapterWasLeftHalfDownloaded() throws IOException
    {
        // GIVEN a chapter whose download published page 1 and then died - PENDING, one page of two.
        queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false);
        ResourceLink link = registry.parse("mock:900").orElseThrow();
        int chapterId = importService.importChapter(
                link.downloader().downloadGalleryInfo(link.resourceId()), link.galleryId());
        importService.recordDownloaded(link.galleryId(), chapterId);
        em.flush();
        try
        {
            Path chapterDir = chapterDir(chapterId);
            Files.createDirectories(chapterDir);
            Files.writeString(chapterDir.resolve("1.webp"), "published before the power cut");
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getDownloadStatus())
                    .isEqualTo(DownloadStatus.PENDING);

            // WHEN the app comes back up and works the still-queued item.
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN page 2 was fetched and page 1 was not re-downloaded (its content is untouched)...
            assertThat(imageService.pageUrls(chapterId))
                    .containsExactly("/data/" + chapterId + "/1.webp", "/data/" + chapterId + "/2.webp");
            assertThat(Files.readString(chapterDir.resolve("1.webp"))).isEqualTo("published before the power cut");
            assertThat(Files.readString(chapterDir.resolve("2.webp"))).isEqualTo("page 2 of 900");
            // ...and the chapter is now off limits, with its stats matching the files.
            Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(chapter.getPageNum()).isEqualTo(2);
            assertThat(queueRepository.findByLink("mock:900")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** Once finished, the pages are the user's; a "does the count match?" check would undo their deletes. */
    @Test
    void shouldNotPutBackAPageTheUserDeletedFromASuccessfulChapter() throws IOException
    {
        // GIVEN a fully downloaded chapter (SUCCESSFUL) that the user has since stripped down to one page.
        queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false);
        worker.processNext();
        em.flush();
        Chapter downloaded = chapterRepository.findByGalleryId("mock:900").orElseThrow();
        int chapterId = downloaded.getId();
        try
        {
            assertThat(downloaded.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            imageService.deletePage(chapterId, "2.webp");

            // WHEN the same link is queued and processed again.
            queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false);
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN the deleted page is not restored, and the stats follow what is actually there.
            assertThat(imageService.pageUrls(chapterId)).containsExactly("/data/" + chapterId + "/1.webp");
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isEqualTo(1);
            assertThat(queueRepository.findByLink("mock:900")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** Every imported row is {@code NONE}, so this case applies to millions of chapters. */
    @Test
    void shouldLeaveTheImagesAloneWhenTheChapterDidNotComeFromADownload() throws IOException
    {
        // GIVEN a hand-added chapter carrying the gallery id, holding one page of the two the source lists.
        queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false);
        var form = new ChapterForm();
        form.setTitleFull("dq-hand-added-900");
        form.setLanguage("English");
        form.setGalleryId("mock:900");
        int chapterId = chapterService.create(form);
        em.flush();
        try
        {
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getDownloadStatus())
                    .isEqualTo(DownloadStatus.NONE);
            Path chapterDir = chapterDir(chapterId);
            Files.createDirectories(chapterDir);
            Files.writeString(chapterDir.resolve("1.webp"), "the page the user already has");

            // WHEN the item is processed.
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN nothing was downloaded and nothing was overwritten...
            assertThat(imageService.pageUrls(chapterId)).containsExactly("/data/" + chapterId + "/1.webp");
            assertThat(Files.readString(chapterDir.resolve("1.webp"))).isEqualTo("the page the user already has");
            // ...the chapter is not promoted to SUCCESSFUL...
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getDownloadStatus())
                    .isEqualTo(DownloadStatus.NONE);
            // ...its stats are still synced...
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isEqualTo(1);
            // ...and the item counts as done rather than failing over the page it did not fetch.
            assertThat(queueRepository.findByLink("mock:900")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /**
     * Only a {@code PENDING} chapter gets its absent pages fetched, so a chapter filled from empty must be
     * {@code PENDING} before its first page lands, or a publish stopped part-way would leave it half-filled.
     */
    @Test
    void shouldFinishFillingAHandAddedChapterWhenItsPublishStoppedPartWay() throws IOException
    {
        // GIVEN a hand-added chapter carrying the gallery id, with no pages...
        queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false);
        var form = new ChapterForm();
        form.setTitleFull("dq-filled-from-empty-900");
        form.setLanguage("English");
        form.setGalleryId("mock:900");
        int chapterId = chapterService.create(form);
        em.flush();
        try
        {
            // ...and a folder squatting on page 2's name, so the publish fails part-way, as a crash would stop it.
            Path squatter = Files.createDirectories(chapterDir(chapterId).resolve("2.webp"));
            Files.writeString(squatter.resolve("occupied.txt"), "not a page");
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getDownloadStatus())
                    .isEqualTo(DownloadStatus.PENDING);
            assertThat(queueRepository.findByLink("mock:900").orElseThrow().getError()).isNull();

            // WHEN the folder is gone and the still-queued item runs again.
            ImageService.deleteRecursively(squatter);
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN both pages are in place and the chapter is finished.
            assertThat(imageService.pageUrls(chapterId))
                    .containsExactly("/data/" + chapterId + "/1.webp", "/data/" + chapterId + "/2.webp");
            Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(chapter.getPageNum()).isEqualTo(2);
            assertThat(queueRepository.findByLink("mock:900")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** Publishing half a chapter would make it look complete, and the missing pages would never be fetched. */
    @Test
    void shouldGiveUpAfterTheConfiguredAttemptsAndPublishNothingWhenAPageCannotBeFetched()
    {
        // GIVEN a gallery whose second page is missing from the source.
        queueService.enqueue(List.of("mock:901"), NO_COMPRESSION, false);
        Integer chapterId = null;
        try
        {
            // WHEN the worker keeps at it.
            for (int attempt = 1; attempt <= 3; attempt++)
            {
                assertThat(worker.processNext()).isTrue();
            }
            em.flush();

            // THEN the item is failed, with the attempts recorded and the reason kept for the user.
            DownloadQueueItem item = queueRepository.findByLink("mock:901").orElseThrow();
            assertThat(item.getAttempts()).isEqualTo(3);
            assertThat(item.getError()).contains("page 2");
            assertThat(queueService.failedCount()).isPositive();
            assertThat(queueService.nextPending()).isEmpty();   // out of the worker's way

            // AND the metadata is there, but nothing was published or left staged.
            Chapter chapter = chapterRepository.findByGalleryId("mock:901").orElseThrow();
            chapterId = chapter.getId();
            assertThat(item.getChapterId()).isEqualTo(chapterId);
            assertThat(imageService.pageUrls(chapterId)).isEmpty();
            assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /**
     * After {@code stop()} the pools and the database close under an item still running, so its failure says
     * nothing about the link, and counting it could use up the link's last attempt.
     */
    @Test
    void shouldCountNoAttemptWhenAnItemFailsAfterTheWorkerWasToldToStop()
    {
        // GIVEN a gallery whose second page is missing from the source, and a worker told to stop - one of its
        // own, so the context's worker is not left stopping for later suites.
        queueService.enqueue(List.of("mock:901"), NO_COMPRESSION, false);
        var stopped = new DownloadWorker(queueService, chapterDownloadService, importService, settingsService,
                appProperties, writeGate);
        stopped.stop();
        Integer chapterId = null;
        try
        {
            // WHEN it works the item, which fails on page 2.
            assertThat(stopped.processNext()).isTrue();
            em.flush();
            chapterId = chapterRepository.findByGalleryId("mock:901").map(Chapter::getId).orElse(null);

            // THEN the row is as it was queued: no attempt counted, no error, no chapter noted.
            DownloadQueueItem item = queueRepository.findByLink("mock:901").orElseThrow();
            assertThat(item.getAttempts()).isZero();
            assertThat(item.getError()).isNull();
            assertThat(item.getChapterId()).isNull();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /**
     * An in-process retry has no torn file to guard against, and discarding staging each attempt would re-fetch
     * a whole gallery per attempt. Pages 1-2 are deleted from the source in between, so they can only come
     * from staging.
     */
    @Test
    void shouldKeepThePagesAlreadyStagedWhenTheSameProcessRetriesAFailedItem() throws IOException
    {
        // GIVEN a three-page gallery whose last page the source does not have (yet).
        writeGallery("903", 3, 2);
        queueService.enqueue(List.of("mock:903"), NO_COMPRESSION, false);
        Integer chapterId = null;
        try
        {
            // ...and one attempt, which stages pages 1-2 and then fails on page 3.
            assertThat(worker.processNext()).isTrue();
            em.flush();
            chapterId = chapterRepository.findByGalleryId("mock:903").orElseThrow().getId();
            assertThat(imageService.stagedPageNumbers(imageService.stagingDir(chapterId, false)))
                    .containsExactlyInAnyOrder(1, 2);
            assertThat(queueRepository.findByLink("mock:903").orElseThrow().getError()).isNull();

            // WHEN the missing page turns up, and the fetched pages leave the source.
            Path gallery = MOCK_DIR.resolve("galleries/903");
            Files.delete(gallery.resolve("1.webp"));
            Files.delete(gallery.resolve("2.webp"));
            Files.writeString(gallery.resolve("3.webp"), "page 3 of 903");
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN the item finished, with all three pages published...
            assertThat(queueRepository.findByLink("mock:903")).isEmpty();
            assertThat(imageService.pageUrls(chapterId)).containsExactly(
                    "/data/" + chapterId + "/1.webp",
                    "/data/" + chapterId + "/2.webp",
                    "/data/" + chapterId + "/3.webp");
            // ...and pages 1-2 carry the bytes attempt 1 staged.
            assertThat(Files.readString(chapterDir(chapterId).resolve("1.webp"))).isEqualTo("page 1 of 903");
            assertThat(Files.readString(chapterDir(chapterId).resolve("3.webp"))).isEqualTo("page 3 of 903");
            // ...staging is empty again, and the stats match the files.
            assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isEqualTo(3);
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getDownloadStatus())
                    .isEqualTo(DownloadStatus.SUCCESSFUL);
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** Nothing else comes back for that folder, which on a RAM disk would hold memory until a reboot. */
    @Test
    void shouldDiscardTheStagedPagesWhenAQueueItemIsRemoved()
    {
        // GIVEN an item that failed one attempt part-way, so it is still pending with pages staged.
        queueService.enqueue(List.of("mock:901"), NO_COMPRESSION, false);
        Integer chapterId = null;
        try
        {
            assertThat(worker.processNext()).isTrue();
            em.flush();
            chapterId = chapterRepository.findByGalleryId("mock:901").orElseThrow().getId();
            DownloadQueueItem item = queueRepository.findByLink("mock:901").orElseThrow();
            assertThat(item.getError()).isNull();               // still pending, more attempts to come
            assertThat(item.getChapterId()).isEqualTo(chapterId);
            assertThat(imageService.stagedPageNumbers(imageService.stagingDir(chapterId, false))).containsExactly(1);

            // WHEN the user removes it (through the worker, which owns the staging folder it is running).
            worker.remove(item.getId());
            em.flush();

            // THEN the row is gone and so is everything it had staged.
            assertThat(queueRepository.findByLink("mock:901")).isEmpty();
            assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /**
     * {@code chapter_id} is written only by {@code recordFailure}, so the chapter must be found by gallery id,
     * or Remove would miss the row most likely to have bytes in staging.
     */
    @Test
    void shouldDiscardTheStagedPagesOfAnItemThatHasNeverFailed() throws IOException
    {
        // GIVEN a pending, never-failed row whose chapter has pages in staging.
        writeGallery("904", 2, 2);
        queueService.enqueue(List.of("mock:904"), NO_COMPRESSION, false);
        Integer chapterId = null;
        try
        {
            assertThat(worker.processNext()).isTrue();
            em.flush();
            chapterId = chapterRepository.findByGalleryId("mock:904").orElseThrow().getId();

            queueService.enqueue(List.of("mock:904"), NO_COMPRESSION, false);
            em.flush();
            DownloadQueueItem item = queueRepository.findByLink("mock:904").orElseThrow();
            assertThat(item.getError()).isNull();
            assertThat(item.getChapterId()).isNull();          // never failed, so never written
            imageService.stagePage(imageService.stagingDir(chapterId, false), 1, "webp",
                    "half a page".getBytes(StandardCharsets.UTF_8));
            assertThat(imageService.stagedPageNumbers(imageService.stagingDir(chapterId, false))).containsExactly(1);

            // WHEN the user removes it.
            worker.remove(item.getId());
            em.flush();

            // THEN the staged bytes go with the row, the chapter having been found by its gallery id.
            assertThat(queueRepository.findByLink("mock:904")).isEmpty();
            assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /**
     * Otherwise the worker would publish into the folder of a deleted row, where nothing ever cleans the pages
     * up, or re-import the gallery the user just deleted.
     */
    @Test
    void shouldCancelTheDownloadAndDiscardItsStagingWhenTheChapterIsDeleted()
    {
        // GIVEN a pending item with a page staged, and a second item behind it
        queueService.enqueue(List.of("mock:901", "mock:902"), NO_COMPRESSION, false);
        Integer chapterId = null;
        try
        {
            assertThat(worker.processNext()).isTrue();
            em.flush();
            chapterId = chapterRepository.findByGalleryId("mock:901").orElseThrow().getId();
            assertThat(imageService.stagedPageNumbers(imageService.stagingDir(chapterId, false))).containsExactly(1);

            // WHEN the user deletes the chapter
            chapterService.delete(chapterId);
            em.flush();

            // THEN its queue row and staged page went with it, and the item behind it is next in line
            assertThat(queueRepository.findByLink("mock:901")).isEmpty();
            assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();
            assertThat(queueService.nextPending()).get().extracting(DownloadQueueItem::getLink).isEqualTo("mock:902");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /**
     * "Nothing to fetch" and "everything fetched" look the same; as {@code SUCCESSFUL}, an empty chapter
     * could never be repaired by re-queueing.
     */
    @Test
    void shouldNotMarkAChapterSuccessfulWhenTheSourceListsNoPages() throws IOException
    {
        writeGallery("905", 0, 0);
        queueService.enqueue(List.of("mock:905"), NO_COMPRESSION, false);
        Integer chapterId = null;
        try
        {
            failCompletely("mock:905");
            em.flush();

            Chapter chapter = chapterRepository.findByGalleryId("mock:905").orElseThrow();
            chapterId = chapter.getId();
            // Still PENDING, so re-queueing can come back for it.
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.PENDING);
            assertThat(queueRepository.findByLink("mock:905").orElseThrow().getError())
                    .contains("lists no pages");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /**
     * The registry reads these three as one gallery, but {@code link} is UNIQUE under BINARY collation, so
     * de-duplicating on it would fetch the gallery once per spelling.
     */
    @Test
    void shouldQueueOneRowPerGalleryWhateverTheLinkSpelling()
    {
        var result = queueService.enqueue(List.of("mock:906", "MOCK:906", "mock: 906"), NO_COMPRESSION, false);
        em.flush();

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.alreadyQueued()).isEqualTo(2);
        assertThat(result.rejected()).isZero();
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("mock:906")).isPresent();
        // Stored trimmed, so the row's link is the one the registry actually parsed.
        assertThat(queueRepository.findByLink("mock:906")).isPresent();
    }

    /**
     * Only {@code app.js} keeps the "CUSTOM" sentinel out of a submission, and SQLite enforces no
     * {@code VARCHAR(n)}, so enqueue must not trust the value.
     */
    @Test
    void shouldStoreNoneWhenThePastedCompressionModeIsNotARealMode()
    {
        // WHEN three links are queued with values that are not modes.
        var sentinel = queueService.enqueue(List.of("mock:907"), "CUSTOM", false);
        var overlong = queueService.enqueue(List.of("mock:908"), "x".repeat(5_000), false);
        var deleted = queueService.enqueue(List.of("mock:909"), "custom:999999", false);
        em.flush();

        // THEN each link was queued: a bad mode is corrected, never rejected.
        assertThat(sentinel.accepted()).isEqualTo(1);
        assertThat(sentinel.rejected()).isZero();
        assertThat(overlong.accepted()).isEqualTo(1);
        assertThat(overlong.rejected()).isZero();
        assertThat(deleted.accepted()).isEqualTo(1);
        assertThat(deleted.rejected()).isZero();

        // ...and every row carries NONE.
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("mock:907").orElseThrow()
                .getCompressionMode()).isEqualTo("NONE");
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("mock:908").orElseThrow()
                .getCompressionMode()).isEqualTo("NONE");
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("mock:909").orElseThrow()
                .getCompressionMode()).isEqualTo("NONE");
    }

    /** So the guard above cannot pass by ignoring the mode altogether. */
    @Test
    void shouldStoreARealCompressionModeOnTheQueuedRow()
    {
        // WHEN
        var result = queueService.enqueue(List.of("mock:910"), "LOSSLESS", false);
        em.flush();

        // THEN
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.rejected()).isZero();
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("mock:910").orElseThrow()
                .getCompressionMode()).isEqualTo("LOSSLESS");
    }

    @Test
    void shouldDiscardTheStagedPagesWhenAllFailedItemsAreDeleted() throws IOException
    {
        // GIVEN a failed item, and staging as a run killed after its last attempt leaves it.
        queueService.enqueue(List.of("mock:902"), NO_COMPRESSION, false);
        Integer chapterId = null;
        try
        {
            failCompletely("mock:902");
            chapterId = chapterRepository.findByGalleryId("mock:902").orElseThrow().getId();
            assertThat(queueRepository.findByLink("mock:902").orElseThrow().getChapterId()).isEqualTo(chapterId);
            Path staging = imageService.stagingDir(chapterId, false);
            Files.createDirectories(staging);
            Files.writeString(staging.resolve("1.webp"), "left over");

            // WHEN
            assertThat(queueService.deleteFailed()).isEqualTo(1);
            em.flush();

            // THEN
            assertThat(queueRepository.findByLink("mock:902")).isEmpty();
            assertThat(Files.exists(staging)).isFalse();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldMakeFailedItemsPendingAgainWhenRetryingThem()

    {
        // GIVEN a failed item.
        queueService.enqueue(List.of("mock:901"), NO_COMPRESSION, false);
        failCompletely("mock:901");
        Integer chapterId = chapterRepository.findByGalleryId("mock:901").map(Chapter::getId).orElse(null);
        try
        {
            // WHEN "Retry all failed" is used.
            assertThat(queueService.retryFailed(false)).isEqualTo(1);
            em.flush();
            em.clear();

            // THEN it is pending again with a clean slate, so it gets its full set of attempts.
            DownloadQueueItem item = queueRepository.findByLink("mock:901").orElseThrow();
            assertThat(item.getError()).isNull();
            assertThat(item.getAttempts()).isZero();
            assertThat(item.isIgnoreImageErrors()).isFalse();

            // AND deleting the failures removes them for good.
            failCompletely("mock:901");
            assertThat(queueService.deleteFailed()).isEqualTo(1);
            em.flush();
            assertThat(queueRepository.findByLink("mock:901")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldRetryOnlyTheChosenItemWhenRetryingASingleFailure()
    {
        // GIVEN two failed items.
        queueService.enqueue(List.of("mock:901", "mock:902"), NO_COMPRESSION, false);
        failCompletely("mock:901");
        failCompletely("mock:902");
        Integer chapterId = chapterRepository.findByGalleryId("mock:901").map(Chapter::getId).orElse(null);
        Integer otherChapterId = chapterRepository.findByGalleryId("mock:902").map(Chapter::getId).orElse(null);
        try
        {
            int id = queueRepository.findByLink("mock:901").orElseThrow().getId();

            // WHEN only the first is retried.
            assertThat(queueService.retry(id, false, false)).isTrue();
            em.flush();
            em.clear();

            // THEN it is pending with a clean slate...
            DownloadQueueItem retried = queueRepository.findByLink("mock:901").orElseThrow();
            assertThat(retried.getError()).isNull();
            assertThat(retried.getAttempts()).isZero();
            assertThat(retried.isIgnoreImageErrors()).isFalse();
            // ...and the other is untouched, error and attempts included.
            DownloadQueueItem untouched = queueRepository.findByLink("mock:902").orElseThrow();
            assertThat(untouched.getError()).isNotNull();
            assertThat(untouched.getAttempts()).isEqualTo(3);
            assertThat(queueService.pendingCount()).isEqualTo(1);
            assertThat(queueService.failedCount()).isEqualTo(1);
        }
        finally
        {
            cleanUp(chapterId);
            cleanUp(otherChapterId);
        }
    }

    /** A skipped page keeps its number, so a page that turns up later drops into its slot. */
    @Test
    void shouldPublishTheAvailablePagesAndFinishWhenRetryingWhileIgnoringImageErrors()
    {
        // GIVEN an item that failed because the source has only one of its two pages.
        queueService.enqueue(List.of("mock:901"), NO_COMPRESSION, false);
        failCompletely("mock:901");
        int id = queueRepository.findByLink("mock:901").orElseThrow().getId();
        Integer chapterId = chapterRepository.findByGalleryId("mock:901").map(Chapter::getId).orElse(null);
        try
        {
            // WHEN it is retried in the lenient mode.
            assertThat(queueService.retry(id, true, false)).isTrue();
            em.flush();
            assertThat(queueRepository.findById(id).orElseThrow().isIgnoreImageErrors()).isTrue();
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN the queue row is gone rather than failed again...
            assertThat(queueRepository.findByLink("mock:901")).isEmpty();
            assertThat(queueService.failedCount()).isZero();
            // ...only the page that exists is published...
            assertThat(imageService.pageUrls(chapterId)).containsExactly("/data/" + chapterId + "/1.webp");
            // ...and the stats say one page.
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isEqualTo(1);
            assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();
            // AND it is SUCCESSFUL despite the gap, so re-queueing is a no-op, not the same failure again.
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getDownloadStatus())
                    .isEqualTo(DownloadStatus.SUCCESSFUL);
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** Otherwise a page that was only briefly unreachable would be dropped for ever. */
    @Test
    void shouldReturnAnItemToStrictModeWhenPlainlyRetriedAfterALenientRetry()
    {
        // GIVEN an item that has been retried in the lenient mode (and failed again for some other reason).
        queueService.enqueue(List.of("mock:901"), NO_COMPRESSION, false);
        failCompletely("mock:901");
        int id = queueRepository.findByLink("mock:901").orElseThrow().getId();
        Integer chapterId = chapterRepository.findByGalleryId("mock:901").map(Chapter::getId).orElse(null);
        try
        {
            queueService.retry(id, true, false);
            queueService.recordFailure(queueRepository.findById(id).orElseThrow(), "something else", null, true, 3);
            em.flush();
            em.clear();
            assertThat(queueRepository.findById(id).orElseThrow().isIgnoreImageErrors()).isTrue();

            // WHEN it is retried plainly, in bulk.
            assertThat(queueService.retryFailed(false)).isEqualTo(1);
            em.flush();
            em.clear();

            // THEN the flag is cleared along with the error and the attempts.
            DownloadQueueItem item = queueRepository.findById(id).orElseThrow();
            assertThat(item.isIgnoreImageErrors()).isFalse();
            assertThat(item.getError()).isNull();
            assertThat(item.getAttempts()).isZero();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldNotQueueLinksNoSourceRecognizes()
    {
        // WHEN a mix of a known link shape and junk is pasted.
        DownloadQueueService.EnqueueResult result =
                queueService.enqueue(List.of("mock:900", "https://elsewhere.example/g/1", "mock:"), NO_COMPRESSION, false);

        // THEN only the recognized one is queued; the rest are reported rather than left to fail later.
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(2);
        assertThat(queueService.pendingCount()).isEqualTo(1);
    }

    @Test
    void shouldReQueueAFailedLinkRatherThanDuplicatingItWhenPastedAgain()
    {
        // GIVEN a link that has already failed.
        queueService.enqueue(List.of("mock:901"), NO_COMPRESSION, false);
        failCompletely("mock:901");
        Integer chapterId = chapterRepository.findByGalleryId("mock:901").map(Chapter::getId).orElse(null);
        try
        {
            // WHEN the user pastes it again.
            DownloadQueueService.EnqueueResult result = queueService.enqueue(List.of("mock:901"), NO_COMPRESSION, false);
            em.flush();

            // THEN the same row is revived instead of a second one appearing.
            assertThat(result.accepted()).isZero();
            assertThat(result.requeued()).isEqualTo(1);
            assertThat(queueRepository.findAll().stream().filter(i -> "mock:901".equals(i.getLink()))).hasSize(1);
            assertThat(queueService.pendingCount()).isEqualTo(1);
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldDoNothingWhenThePauseFlagIsSet()
    {
        // GIVEN a pending item and a paused queue.
        queueService.enqueue(List.of("mock:900"), NO_COMPRESSION, false);
        worker.setPaused(true);
        try
        {
            // WHEN + THEN the worker declines to work, and the item is untouched.
            assertThat(worker.processNext()).isFalse();
            assertThat(queueRepository.findByLink("mock:900")).isPresent();
            assertThat(worker.progress().paused()).isTrue();
        }
        finally
        {
            worker.setPaused(false);
        }
    }

    // ---- fixture -----------------------------------------------------------

    /** Only the first {@code presentPages} of the listed pages exist on disk. */
    private static void writeGallery(String id, int declaredPages, int presentPages) throws IOException
    {
        Path gallery = MOCK_DIR.resolve("galleries").resolve(id);
        Files.createDirectories(gallery);

        var pages = new StringBuilder();
        for (int page = 1; page <= declaredPages; page++)
        {
            pages.append(page > 1 ? "," : "")
                    .append("{\"number\":").append(page)
                    .append(",\"path\":\"galleries/").append(id).append('/').append(page).append(".webp\"}");
            if (page <= presentPages)
            {
                Files.writeString(gallery.resolve(page + ".webp"), "page " + page + " of " + id);
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
                    {"type": "parody", "name": "original"},
                    {"type": "tag", "name": "Test tag"},
                    {"type": "artist", "name": "testartist"},
                    {"type": "group", "name": "test group"}
                  ],
                  "pages": [%s]
                }
                """.formatted(id, id, id, id, id, pages);
        Files.writeString(MOCK_DIR.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }

    private Path chapterDir(int chapterId)
    {
        return Paths.get("./target/test-data").toAbsolutePath().normalize().resolve(String.valueOf(chapterId));
    }

    /** Through the worker, not by writing an error onto the row, so the failed state is the real one. */
    private void failCompletely(String link)
    {
        for (int attempt = 0; attempt < 5; attempt++)
        {
            if (queueRepository.findByLink(link).orElseThrow().getError() != null)
            {
                return;
            }
            worker.processNext();
            em.flush();
        }
        throw new IllegalStateException(link + " did not fail within its attempts");
    }

    /** Files are not rolled back with the transaction. */
    private void cleanUp(Integer chapterId)
    {
        if (chapterId != null)
        {
            imageService.discardStagedPages(chapterId);
            imageService.deleteAll(chapterId);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException
    {
        if (!Files.isDirectory(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }
}

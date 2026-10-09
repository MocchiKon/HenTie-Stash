package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.scrapper.nhentai.NhentaiProperties;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.download.DownloadChoices;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Abort current download" and "Clear all" on the queue page, and deleting the chapter of a download in progress,
 * each done on another thread than the download's. Not {@code @Transactional}: the other thread could not see
 * uncommitted rows. It removes what it made.
 */
@SpringBootTest
class DownloadAbortIT
{
    private static final String LINK = "https://hitomi.la/doujinshi/abort-me-english-9101.html";
    private static final String GALLERY_ID = "hitomi:9101";
    private static final String FAILED_LINK = "mock:abort-failed";
    private static final String NHENTAI_GALLERY_ID = "nhentai:9102";

    /** A gallery that takes 20 s to fetch, so it is surely still running when aborted. */
    private static final String HITOMI_JSON = """
            [[2, {"category": "hitomi", "count": 5, "gallery_id": 9101, "title": "Abort Me", "title_jpn": "",
              "type": "Doujinshi", "language": "English", "tags": [], "artist": [], "group": [], "parody": [],
              "characters": []}]]""";

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired ChapterRepository chapterRepository;
    @Autowired ChapterService chapterService;
    @Autowired ImageService imageService;
    @Autowired ImageDirectory imageDirectory;
    @Autowired NhentaiProperties nhentaiProperties;

    @BeforeEach
    void setUp() throws IOException
    {
        FakeGalleryDl.reset();
        FakeGalleryDl.set("json", HITOMI_JSON);
        FakeGalleryDl.set("pages", "5");
        FakeGalleryDl.set("delayMillis", "4000");
        worker.setPaused(false);
    }

    @AfterEach
    void cleanUp()
    {
        queueRepository.findByLink(LINK).ifPresent(queueRepository::delete);
        queueRepository.findByLink(FAILED_LINK).ifPresent(queueRepository::delete);
        queueRepository.findByLink(NHENTAI_GALLERY_ID).ifPresent(queueRepository::delete);
        for (String galleryId : List.of(GALLERY_ID, NHENTAI_GALLERY_ID))
        {
            chapterRepository.findByGalleryId(galleryId).map(Chapter::getId).ifPresent(id ->
            {
                imageService.discardStagedPages(id);
                chapterService.delete(id);
            });
        }
    }

    private static DownloadChoices choices()
    {
        return new DownloadChoices(BuiltInCompressionMode.NONE.getKey(), false, TestDownloads.PLAIN_GALLERY_DL);
    }

    @Test
    void shouldStopTheRunningDownloadAndMoveItToTheFailedList() throws Exception
    {
        // GIVEN a download fetching its pages
        queueService.enqueue(List.of(LINK), choices());
        CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(worker::processNext);
        awaitUntil(() -> calls() == 2);

        // WHEN
        long abortedAt = System.nanoTime();
        assertThat(worker.abortCurrent()).contains(LINK);

        // THEN it stops long before its pages would have arrived...
        assertThat(running.get(15, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - abortedAt)).isLessThan(Duration.ofSeconds(10));

        // ...and waits on the Failed list, its chapter still to be finished, nothing published or left staged.
        DownloadQueueItem item = queueRepository.findByLink(LINK).orElseThrow();
        assertThat(item.getError()).isEqualTo("Aborted on request.");
        Chapter chapter = chapterRepository.findByGalleryId(GALLERY_ID).orElseThrow();
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.PENDING);
        assertThat(imageService.pageUrls(chapter.getId())).isEmpty();
        assertThat(Files.exists(imageService.stagingDir(chapter.getId(), false))).isFalse();

        // AND the worker goes on: the next item is not interrupted.
        assertThat(worker.progress().currentLink()).isNull();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    /**
     * The page on its way when the chapter is deleted lands in a folder the delete already removed, where nothing but
     * the worker could ever clean it up; the pages after it are not fetched at all.
     */
    @Test
    void shouldStopAndRemoveWhatLandedWhenTheChapterIsDeletedDuringTheDownload() throws Exception
    {
        // GIVEN a three-page gallery whose chapter the user deletes while page 2 is on its way
        String baseUrlBefore = nhentaiProperties.getBaseUrl();
        var deleted = new AtomicInteger();
        try (FakeNhentai site = FakeNhentai.start())
        {
            nhentaiProperties.setBaseUrl(site.baseUrl());
            site.simpleGallery("9102", 3).beforeEachImage(path ->
            {
                if (path.endsWith("/2.jpg"))
                {
                    int chapterId = chapterRepository.findByGalleryId(NHENTAI_GALLERY_ID).orElseThrow().getId();
                    chapterService.delete(chapterId);
                    deleted.set(chapterId);
                }
            });
            queueService.enqueue(List.of(NHENTAI_GALLERY_ID), choices());

            // WHEN the worker takes it
            assertThat(worker.processNext()).isTrue();

            // THEN it stopped before page 3, and left no trace: no row, no chapter, no folder.
            assertThat(deleted.get()).isPositive();
            assertThat(site.requestsFor("/galleries/m9102/3.jpg")).isZero();
            assertThat(queueRepository.findByLink(NHENTAI_GALLERY_ID)).isEmpty();
            assertThat(chapterRepository.findById(deleted.get())).isEmpty();
            assertThat(imageDirectory.chapterDir(deleted.get())).doesNotExist();
        }
        finally
        {
            nhentaiProperties.setBaseUrl(baseUrlBefore);
        }
    }

    @Test
    void shouldSayNothingRanWhenNothingRuns()
    {
        // WHEN + THEN
        assertThat(worker.abortCurrent()).isEmpty();
    }

    @Test
    void shouldRemoveTheWaitingItemsAndStopTheRunningDownloadButKeepTheFailedOnesWhenClearingAll() throws Exception
    {
        // GIVEN a failed item, and a download fetching its pages
        DownloadQueueItem failed = TestDownloads.queueItem(FAILED_LINK, FAILED_LINK);
        failed.setError("It failed");
        queueRepository.save(failed);
        queueService.enqueue(List.of(LINK), choices());
        CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(worker::processNext);
        awaitUntil(() -> calls() == 2);

        // WHEN
        assertThat(worker.clearAll()).isGreaterThanOrEqualTo(1);

        // THEN the download stops, and its row stays gone rather than landing on the Failed list...
        assertThat(running.get(15, TimeUnit.SECONDS)).isTrue();
        assertThat(queueRepository.findByLink(LINK)).isEmpty();
        assertThat(queueService.pendingCount()).isZero();
        // ...where the failed item is still waiting for the user.
        assertThat(queueRepository.findByLink(FAILED_LINK)).hasValueSatisfying(
                item -> assertThat(item.getError()).isEqualTo("It failed"));
        Chapter chapter = chapterRepository.findByGalleryId(GALLERY_ID).orElseThrow();
        assertThat(imageService.pageUrls(chapter.getId())).isEmpty();
        assertThat(Files.exists(imageService.stagingDir(chapter.getId(), false))).isFalse();
    }

    private static int calls()
    {
        try
        {
            return FakeGalleryDl.calls().size();
        }
        catch (IOException e)
        {
            return 0;
        }
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean())
        {
            assertThat(System.nanoTime()).as("waited 30 s").isLessThan(deadline);
            Thread.sleep(50);
        }
    }
}

package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.download.ChapterDownloadService;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import io.github.mocchikon.hentie.service.download.GalleryImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The download worker in a busy library: its writes wait instead of failing, so waiting never uses up an attempt
 * or puts a link in the Failed list. The worker thread is off in the tests, which call
 * {@link DownloadWorker#processNext()} from threads of their own, as it would. Not {@code @Transactional}: the
 * holders are other threads and connections.
 */
@SpringBootTest
class DownloadWriteGateIT
{
    private static final String NO_COMPRESSION = BuiltInCompressionMode.NONE.getKey();

    /** Matches {@code app.download.mock-dir} in the test profile. */
    private static final Path MOCK_DIR = Paths.get("./target/test-mock-server");

    @Autowired DownloadWorker worker;
    @Autowired DownloadQueueService queueService;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired ChapterRepository chapterRepository;
    @Autowired ChapterService chapterService;
    @Autowired GalleryImportService importService;
    @Autowired SettingsService settingsService;
    @Autowired AppProperties appProperties;
    @Autowired WriteGate writeGate;
    @Autowired PlatformTransactionManager transactionManager;
    @Value("${spring.datasource.url}") String datasourceUrl;

    private long budget;

    @BeforeEach
    void setUp()
    {
        budget = appProperties.getWrites().getRequestWaitMillis();
        // The worker takes the oldest pending row, so none left by another suite may come first.
        queueRepository.deleteAll();
        worker.setPaused(false);
    }

    @AfterEach
    void removeChapters()
    {
        for (String galleryId : List.of("mock:9701", "mock:9702"))
        {
            chapterRepository.findByGalleryId(galleryId).ifPresent(chapter -> chapterService.delete(chapter.getId()));
        }
        queueRepository.deleteAll();
    }

    @Test
    void shouldFinishADownloadThatWaitedForTheWriteGateWithoutUsingAnAttempt() throws Exception
    {
        // GIVEN a queued two-page gallery, and a sweep holding the gate for longer than a request would wait.
        writeGallery("9701", 2);
        queueService.enqueue(List.of("mock:9701"), NO_COMPRESSION, false);
        CompletableFuture<Boolean> worked;
        try (GateHolder ignored = GateHolder.hold(transactionManager, writeGate, "the test's sweep"))
        {
            // WHEN the worker takes the item meanwhile.
            worked = CompletableFuture.supplyAsync(worker::processNext);

            // THEN it waits instead of failing: no attempt is counted.
            Thread.sleep(3 * budget);
            assertThat(worked).isNotDone();
            assertThat(queueRepository.findByLink("mock:9701").orElseThrow().getAttempts()).isZero();
        }

        // AND once the gate is free, the item completes.
        assertDownloaded("mock:9701", worked);
    }

    @Test
    void shouldFinishADownloadThatWaitedForAProgramOutsideTheApp() throws Exception
    {
        // GIVEN a queued two-page gallery, and a program outside the app holding SQLite's write lock.
        writeGallery("9702", 2);
        queueService.enqueue(List.of("mock:9702"), NO_COMPRESSION, false);
        CompletableFuture<Boolean> worked;
        try (SqliteLockHolder ignored = SqliteLockHolder.hold(datasourceUrl))
        {
            // WHEN the worker takes the item meanwhile.
            worked = CompletableFuture.supplyAsync(worker::processNext);

            // THEN it waits instead of failing: no attempt is counted.
            Thread.sleep(3 * budget);
            assertThat(worked).isNotDone();
            assertThat(queueRepository.findByLink("mock:9702").orElseThrow().getAttempts()).isZero();
        }

        // AND once the lock is free, the item completes.
        assertDownloaded("mock:9702", worked);
    }

    /** The gate leaves a lock error only to writers outside it, and that says nothing about the link. */
    @Test
    void shouldCountNoAttemptWhenALockErrorStillReachesTheWorker()
    {
        // GIVEN a queued link whose download meets SQLite's lock error.
        queueService.enqueue(List.of("mock:9703"), NO_COMPRESSION, false);
        var downloads = mock(ChapterDownloadService.class);
        when(downloads.download(any(), any())).thenThrow(new PessimisticLockingFailureException("locked",
                new SQLException("[SQLITE_BUSY] The database file is locked (database is locked)", null, 5)));
        var busyWorker = new DownloadWorker(queueService, downloads, importService, settingsService, appProperties,
                writeGate);

        // WHEN a worker takes it.
        assertThat(busyWorker.processNext()).isTrue();

        // THEN the row is as it was queued: no attempt counted, no error, no chapter noted, still pending.
        DownloadQueueItem item = queueRepository.findByLink("mock:9703").orElseThrow();
        assertThat(item.getAttempts()).isZero();
        assertThat(item.getError()).isNull();
        assertThat(item.getChapterId()).isNull();
        assertThat(queueService.nextPending()).map(DownloadQueueItem::getId).contains(item.getId());
    }

    private void assertDownloaded(String galleryId, CompletableFuture<Boolean> worked) throws Exception
    {
        assertThat(worked.get(20, TimeUnit.SECONDS)).isTrue();
        assertThat(queueRepository.findByLink(galleryId)).isEmpty();
        Chapter chapter = chapterRepository.findByGalleryId(galleryId).orElseThrow();
        assertThat(chapter.getPageNum()).isEqualTo(2);
        assertThat(chapter.getDiskSize()).isPositive();
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
    }

    /**
     * The mock source's format: a JSON file per gallery and its pages under {@code galleries/<id>/}. No metadata but
     * the language, so the downloads leave no rows that other suites' name searches would find.
     */
    private static void writeGallery(String id, int pages) throws IOException
    {
        Path gallery = Files.createDirectories(MOCK_DIR.resolve("galleries").resolve(id));
        var pageList = new StringBuilder();
        for (int page = 1; page <= pages; page++)
        {
            pageList.append(page > 1 ? "," : "")
                    .append("{\"number\":").append(page)
                    .append(",\"path\":\"galleries/").append(id).append('/').append(page).append(".webp\"}");
            Files.writeString(gallery.resolve(page + ".webp"), "page " + page + " of " + id);
        }
        String json = """
                {
                  "id": %s,
                  "media_id": "%s",
                  "title": {
                    "english": "[Write Gate] Gallery %s",
                    "japanese": "%s in Japanese",
                    "pretty": "Write Gate Gallery %s"
                  },
                  "tags": [
                    {"type": "language", "name": "japanese"}
                  ],
                  "pages": [%s]
                }
                """.formatted(id, id, id, id, id, pageList);
        Files.writeString(MOCK_DIR.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }
}

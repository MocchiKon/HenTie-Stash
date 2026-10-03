package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.DownloadedGalleryRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageService;
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
 * The rule is narrow on purpose (a new gallery, an exact title, a different source), so most tests here are
 * cases that must <i>not</i> be refused. The clashing chapter uses an unregistered prefix ({@code other:...}),
 * since only the stored prefix matters to the check.
 */
@SpringBootTest
@Transactional
class DownloadDuplicateTitleIT
{
    private static final String NO_COMPRESSION = BuiltInCompressionMode.NONE.getKey();

    /** Matches {@code app.download.mock-dir} in the test profile. */
    private static final Path MOCK_DIR = Paths.get("./target/test-mock-server");

    private static final String TITLE = "[Dup] Duplicate Title 9500";

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired GalleryImportService importService;
    @Autowired DataDownloaderRegistry registry;
    @Autowired ChapterService chapterService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired DownloadedGalleryRepository downloadedGalleryRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ImageService imageService;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void resetMockSource() throws IOException
    {
        deleteRecursively(MOCK_DIR);
        writeGallery("9500", TITLE);
        worker.setPaused(false);
    }

    /**
     * A refusal must leave nothing behind. The gallery's unique tag proves the check ran before metadata
     * resolution, which creates new names as rows.
     */
    @Test
    void shouldRefuseANewGalleryWhoseTitleAChapterFromAnotherSourceHolds()
    {
        // GIVEN a chapter from another source holding the title, and the link queued with the box ticked.
        int holder = chapter(TITLE, "other:1");
        queueService.enqueue(List.of("mock:9500"), TestDownloads.choices(NO_COMPRESSION, true));

        // WHEN the worker takes it - once, since a refusal is permanent.
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the item is failed after a single attempt, naming the chapter it clashed with.
        DownloadQueueItem item = queueRepository.findByLink("mock:9500").orElseThrow();
        assertThat(item.getAttempts()).isEqualTo(1);
        assertThat(item.getError()).contains("Chapter " + holder, "other:1", TITLE);
        assertThat(queueService.nextPending()).isEmpty();
        // AND nothing was written for the refused gallery: no chapter, no downloaded record, no new tag.
        assertThat(chapterRepository.findByGalleryId("mock:9500")).isEmpty();
        assertThat(downloadedGalleryRepository.findById("mock:9500")).isEmpty();
        assertThat(tagRepository.existsByNameIgnoreCase("dup-only-tag-9500")).isFalse();
    }

    /** A hand-added or imported chapter has no prefix, which is not this source. */
    @Test
    void shouldRefuseTheGalleryWhenTheChapterHoldingTheTitleHasNoGalleryId()
    {
        // GIVEN
        int holder = chapter(TITLE, null);
        queueService.enqueue(List.of("mock:9500"), TestDownloads.choices(NO_COMPRESSION, true));

        // WHEN
        worker.processNext();
        em.flush();

        // THEN
        assertThat(queueRepository.findByLink("mock:9500").orElseThrow().getError())
                .contains("Chapter " + holder, "without a gallery id");
        assertThat(chapterRepository.findByGalleryId("mock:9500")).isEmpty();
    }

    /** The prefix is compared with its separator, so {@code mockx} is not {@code mock}. */
    @Test
    void shouldTreatASourceWhoseNameMerelyStartsTheSameAsADifferentSource()
    {
        // GIVEN
        chapter(TITLE, "mockx:1");
        queueService.enqueue(List.of("mock:9500"), TestDownloads.choices(NO_COMPRESSION, true));

        // WHEN
        worker.processNext();
        em.flush();

        // THEN
        assertThat(queueRepository.findByLink("mock:9500").orElseThrow().getError()).contains("mockx:1");
        assertThat(chapterRepository.findByGalleryId("mock:9500")).isEmpty();
    }

    /** A source can hold two galleries with one title; its ids tell them apart. */
    @Test
    void shouldDownloadTheGalleryWhenTheChapterHoldingTheTitleIsFromTheSameSource()
    {
        // GIVEN a chapter from the mock source itself holding the title.
        chapter(TITLE, "mock:9599");
        queueService.enqueue(List.of("mock:9500"), TestDownloads.choices(NO_COMPRESSION, true));
        Integer chapterId = null;
        try
        {
            // WHEN
            worker.processNext();
            em.flush();

            // THEN
            chapterId = downloaded("mock:9500");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldDownloadTheGalleryWhenTheBoxWasNotTicked()
    {
        // GIVEN
        chapter(TITLE, "other:1");
        queueService.enqueue(List.of("mock:9500"), TestDownloads.choices(NO_COMPRESSION, false));
        Integer chapterId = null;
        try
        {
            // WHEN
            worker.processNext();
            em.flush();

            // THEN
            chapterId = downloaded("mock:9500");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldDownloadTheGalleryWhenTheOtherTitleDiffersOnlyInCase()
    {
        // GIVEN
        chapter(TITLE.toLowerCase(), "other:1");
        queueService.enqueue(List.of("mock:9500"), TestDownloads.choices(NO_COMPRESSION, true));
        Integer chapterId = null;
        try
        {
            // WHEN
            worker.processNext();
            em.flush();

            // THEN
            chapterId = downloaded("mock:9500");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** A gallery already in the library is not checked, so a download cut short by a crash can always finish. */
    @Test
    void shouldFinishAnInterruptedDownloadWhoseTitleAnotherSourceNowHolds()
    {
        // GIVEN the state a crash after saving the metadata leaves, plus another source's chapter.
        var link = registry.parse("mock:9500").orElseThrow();
        int chapterId = importService.importChapter(link.downloader().downloadGalleryInfo(link.resourceId()),
                link.galleryId());
        chapter(TITLE, "other:1");
        queueService.enqueue(List.of("mock:9500"), TestDownloads.choices(NO_COMPRESSION, true));
        try
        {
            // WHEN
            worker.processNext();
            em.flush();
            em.clear();

            // THEN the chapter was completed, not refused.
            assertThat(downloaded("mock:9500")).isEqualTo(chapterId);
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** Retry means "the same again"; a re-paste is a new choice. */
    @Test
    void shouldKeepTheChoiceOnRetryAndReplaceItWhenTheLinkIsPastedAgain()
    {
        // GIVEN a link refused as a duplicate.
        chapter(TITLE, "other:1");
        queueService.enqueue(List.of("mock:9500"), TestDownloads.choices(NO_COMPRESSION, true));
        worker.processNext();
        em.flush();
        DownloadQueueItem item = queueRepository.findByLink("mock:9500").orElseThrow();
        assertThat(item.getError()).isNotNull();
        Integer chapterId = null;
        try
        {
            // WHEN it is retried, singly and in bulk.
            assertThat(queueService.retry(item.getId(), false, false)).isTrue();
            em.flush();
            em.clear();
            assertThat(queueRepository.findById(item.getId()).orElseThrow().isAvoidDuplicateTitles()).isTrue();
            worker.processNext();
            em.flush();
            assertThat(queueService.retryFailed(false)).isEqualTo(1);
            em.clear();

            // THEN the flag survived both retries...
            assertThat(queueRepository.findById(item.getId()).orElseThrow().isAvoidDuplicateTitles()).isTrue();

            // WHEN it is pasted again with the box cleared.
            assertThat(queueService.enqueue(List.of("mock:9500"), TestDownloads.choices(NO_COMPRESSION, false)).alreadyQueued())
                    .isEqualTo(1);
            em.flush();
            em.clear();
            assertThat(queueRepository.findById(item.getId()).orElseThrow().isAvoidDuplicateTitles()).isFalse();
            worker.processNext();
            em.flush();

            // THEN it downloads.
            chapterId = downloaded("mock:9500");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** Without it, every button beside a refusal could only be refused again. */
    @Test
    void shouldDownloadARefusedDuplicateWhenRetriedAllowingTheDuplicateTitle()
    {
        // GIVEN a link refused as a duplicate.
        chapter(TITLE, "other:1");
        queueService.enqueue(List.of("mock:9500"), TestDownloads.choices(NO_COMPRESSION, true));
        worker.processNext();
        em.flush();
        DownloadQueueItem item = queueRepository.findByLink("mock:9500").orElseThrow();
        assertThat(item.getError()).isNotNull();
        Integer chapterId = null;
        try
        {
            // WHEN it is retried allowing the duplicate title.
            assertThat(queueService.retry(item.getId(), false, true)).isTrue();
            em.flush();
            em.clear();

            // THEN the row is pending again, strict about images, with the check off.
            DownloadQueueItem retried = queueRepository.findById(item.getId()).orElseThrow();
            assertThat(retried.getError()).isNull();
            assertThat(retried.getAttempts()).isZero();
            assertThat(retried.isIgnoreImageErrors()).isFalse();
            assertThat(retried.isAvoidDuplicateTitles()).isFalse();

            // WHEN the worker takes it again.
            worker.processNext();
            em.flush();

            // THEN it downloads.
            chapterId = downloaded("mock:9500");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldStoreThePastedChoiceOnNewAndRevivedRows()
    {
        // GIVEN + WHEN a new row with the box ticked.
        queueService.enqueue(List.of("mock:9501"), TestDownloads.choices(NO_COMPRESSION, true));
        em.flush();
        DownloadQueueItem item = queueRepository.findByLink("mock:9501").orElseThrow();
        assertThat(item.isAvoidDuplicateTitles()).isTrue();
        // AND it fails (no such gallery - a permanent failure) and is pasted again unticked.
        worker.processNext();
        em.flush();
        assertThat(queueRepository.findById(item.getId()).orElseThrow().getError()).isNotNull();
        var result = queueService.enqueue(List.of("mock:9501"), TestDownloads.choices(NO_COMPRESSION, false));
        em.flush();
        em.clear();

        // THEN the same row is revived, carrying the new choice.
        assertThat(result.requeued()).isEqualTo(1);
        assertThat(queueRepository.findById(item.getId()).orElseThrow().isAvoidDuplicateTitles()).isFalse();
    }

    // ---- fixture -----------------------------------------------------------

    /** @param galleryId null for a hand-added chapter */
    private int chapter(String titleFull, String galleryId)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        form.setGalleryId(galleryId);
        int id = chapterService.create(form);
        em.flush();
        return id;
    }

    private int downloaded(String galleryId)
    {
        Chapter chapter = chapterRepository.findByGalleryId(galleryId).orElseThrow();
        assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
        assertThat(imageService.pageUrls(chapter.getId())).hasSize(1);
        assertThat(queueRepository.findByLink(galleryId)).isEmpty();
        return chapter.getId();
    }

    /** Carries a tag no other fixture uses, so a refusal can be shown to create no metadata. */
    private static void writeGallery(String id, String title) throws IOException
    {
        Path gallery = MOCK_DIR.resolve("galleries").resolve(id);
        Files.createDirectories(gallery);
        Files.writeString(gallery.resolve("1.webp"), "page 1 of " + id);
        String json = """
                {
                  "id": %s,
                  "media_id": "%s",
                  "title": {
                    "english": "%s",
                    "japanese": "",
                    "pretty": ""
                  },
                  "tags": [
                    {"type": "language", "name": "english"},
                    {"type": "tag", "name": "dup-only-tag-%s"}
                  ],
                  "pages": [{"number": 1, "path": "galleries/%s/1.webp"}]
                }
                """.formatted(id, id, title, id, id);
        Files.writeString(MOCK_DIR.resolve(id + ".json"), json, StandardCharsets.UTF_8);
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

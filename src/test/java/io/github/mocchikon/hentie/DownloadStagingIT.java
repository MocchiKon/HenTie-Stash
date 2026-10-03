package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import io.github.mocchikon.hentie.service.scratch.ScratchArea;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staging set in Settings outside the data folder, standing in for a RAM disk.
 * <p>
 * {@code ./target} is on the same volume as the data folder, so the cross-filesystem copy-and-rename branch
 * of {@code ImageService.publishPage} is not reached here; {@code ImageServiceUnitTest} covers it directly.
 */
@SpringBootTest
@Transactional
class DownloadStagingIT
{
    private static final String NO_COMPRESSION = BuiltInCompressionMode.NONE.getKey();

    private static final Path MOCK_DIR = Paths.get("./target/test-mock-server");
    private static final Path STAGING_ROOT = Paths.get("./target/test-staging").toAbsolutePath().normalize();

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired ChapterRepository chapterRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired ImageService imageService;
    @Autowired ScratchSpace scratchSpace;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void writeMockGallery() throws IOException
    {
        // The unwritable-staging test makes the root a file; a killed run would leave it behind.
        if (Files.isRegularFile(STAGING_ROOT))
        {
            Files.delete(STAGING_ROOT);
        }
        Path gallery = MOCK_DIR.resolve("galleries/910");
        Files.createDirectories(gallery);
        Files.writeString(gallery.resolve("1.webp"), "page 1 of 910");
        Files.writeString(gallery.resolve("2.webp"), "page 2 of 910");
        Files.writeString(MOCK_DIR.resolve("910.json"), """
                {
                  "title": {"english": "[Test] Gallery 910", "pretty": "Gallery 910"},
                  "tags": [{"type": "language", "name": "english"}],
                  "pages": [
                    {"number": 1, "path": "galleries/910/1.webp"},
                    {"number": 2, "path": "galleries/910/2.webp"}
                  ]
                }
                """, StandardCharsets.UTF_8);
        worker.setPaused(false);
        assertThat(scratchSpace.update(Map.of(ScratchArea.DOWNLOAD_STAGING, STAGING_ROOT.toString()))).isEmpty();
    }

    /** Shared singleton: a folder left set here would move every later suite's staging. */
    @AfterEach
    void restoreAutomaticStaging()
    {
        assertThat(scratchSpace.update(Map.of(ScratchArea.DOWNLOAD_STAGING, ""))).isEmpty();
    }

    @Test
    void shouldStageOutsideTheDataDirectoryAndStillPublishIntoIt() throws IOException
    {
        // GIVEN a queued link, with staging configured away from the data directory.
        queueService.enqueue(List.of("mock:910"), TestDownloads.choices(NO_COMPRESSION, false));
        Integer chapterId = null;
        try
        {
            // WHEN the worker takes it.
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the staging directory really was the configured one...
            Chapter chapter = chapterRepository.findByGalleryId("mock:910").orElseThrow();
            chapterId = chapter.getId();
            // (as a string: AssertJ's Path startsWith resolves the real path, and the folder is gone by now.)
            assertThat(imageService.stagingDir(chapterId, false).toString()).startsWith(STAGING_ROOT.toString());

            // ...the pages ended up in the data directory all the same, with their real content...
            assertThat(imageService.pageUrls(chapterId))
                    .containsExactly("/data/" + chapterId + "/1.webp", "/data/" + chapterId + "/2.webp");
            Path chapterDir = Paths.get("./target/test-data").toAbsolutePath().normalize()
                    .resolve(String.valueOf(chapterId));
            assertThat(Files.readString(chapterDir.resolve("2.webp"))).isEqualTo("page 2 of 910");

            // ...nothing was left staged, and no half-published .part file survives either...
            assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();
            try (var entries = Files.list(chapterDir))
            {
                assertThat(entries.map(p -> p.getFileName().toString()))
                        .allSatisfy(name -> assertThat(name).doesNotEndWith(".part"));
            }
            // ...and the searchable stats match the files.
            assertThat(chapter.getPageNum()).isEqualTo(2);
        }
        finally
        {
            if (chapterId != null)
            {
                imageService.discardStagedPages(chapterId);
                imageService.deleteAll(chapterId);
            }
        }
    }

    /**
     * Lenient mode may skip a page the source cannot give, never one we could not write. Otherwise a full RAM
     * disk would skip every page and mark the chapter {@code SUCCESSFUL}, which makes re-queueing a no-op.
     */
    @Test
    void shouldFailTheItemRatherThanSkipEveryPageWhenStagingCannotBeWritten() throws IOException
    {
        // GIVEN a staging root that is a file, and a complete source, so only the write can fail.
        deleteRecursively(STAGING_ROOT);
        Files.writeString(STAGING_ROOT, "not a directory");
        Integer chapterId = null;
        try
        {
            queueService.enqueue(List.of("mock:910"), TestDownloads.choices(NO_COMPRESSION, false));
            int id = queueRepository.findByLink("mock:910").orElseThrow().getId();
            // ...and the item in the one mode allowed to skip pages.
            assertThat(queueService.retry(id, true, false)).isTrue();
            em.flush();

            // WHEN the worker works it until it gives up or wrongly "finishes" it.
            for (int attempt = 0; attempt < 5; attempt++)
            {
                var current = queueRepository.findById(id);
                if (current.isEmpty() || current.get().getError() != null)
                {
                    break;
                }
                worker.processNext();
                em.flush();
            }
            chapterId = chapterRepository.findByGalleryId("mock:910").map(Chapter::getId).orElse(null);

            // THEN the item is failed, not deleted as done...
            assertThat(queueRepository.findById(id))
                    .as("a page that could not be written must fail the item, so its queue row survives "
                            + "instead of being deleted as done")
                    .isPresent();
            DownloadQueueItem item = queueRepository.findById(id).orElseThrow();
            assertThat(item.getError()).contains("Could not stage page 1");
            assertThat(item.getChapterId()).isEqualTo(chapterId);
            // ...with the lenient flag still on...
            assertThat(item.isIgnoreImageErrors()).isTrue();
            // ...nothing was published...
            assertThat(chapterId).isNotNull();
            assertThat(imageService.pageUrls(chapterId)).isEmpty();
            // ...and the chapter is still PENDING, which lets the pipeline back in.
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getDownloadStatus())
                    .isEqualTo(DownloadStatus.PENDING);
        }
        finally
        {
            Files.deleteIfExists(STAGING_ROOT);
            if (chapterId != null)
            {
                imageService.deleteAll(chapterId);
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException
    {
        if (!Files.exists(dir))
        {
            return;
        }
        try (var walk = Files.walk(dir))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.delete(path);
            }
        }
    }
}

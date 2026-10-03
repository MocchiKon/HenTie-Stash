package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.scrapper.chaika.ChaikaProperties;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
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
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A chaika archive through the whole pipeline, against {@link FakeChaika}: pages read out of the remote zip. */
@SpringBootTest
@Transactional
class ChaikaDownloadIT
{
    private static final String JSON = """
            {"category": "Manga", "filecount": 3, "title": "Chaika Archive 31", "title_jpn": "",
             "tags": ["female:big_breasts", "other:full_color", "artist:chaika_artist"]}""";

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired ChapterRepository chapterRepository;
    @Autowired ImageService imageService;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ChaikaProperties chaikaProperties;
    @PersistenceContext EntityManager em;

    private FakeChaika site;
    private String baseUrlBefore;

    @BeforeEach
    void start() throws IOException
    {
        site = FakeChaika.start();
        baseUrlBefore = chaikaProperties.getBaseUrl();
        chaikaProperties.setBaseUrl(site.baseUrl());
        worker.setPaused(false);
    }

    @AfterEach
    void restore()
    {
        chaikaProperties.setBaseUrl(baseUrlBefore);
        site.close();
    }

    @Test
    void shouldStoreTheArchivesPagesInReadingOrderUnderTheirOwnFormats() throws IOException
    {
        // GIVEN an archive with gaps in its numbering, in no order, holding a PNG among JPEGs.
        var files = new LinkedHashMap<String, byte[]>();
        files.put("236.jpg", "third".getBytes(StandardCharsets.UTF_8));
        files.put("010.png", "second".getBytes(StandardCharsets.UTF_8));
        files.put("9.jpg", "first".getBytes(StandardCharsets.UTF_8));
        files.put("info.txt", "no page".getBytes(StandardCharsets.UTF_8));
        site.archive("31", JSON, files, false);
        queueService.enqueue(List.of("https://panda.chaika.moe/archive/31/"),
                TestDownloads.choices(BuiltInCompressionMode.NONE.getKey(), false));
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN the pages are numbered in reading order, each with its own format.
            Chapter chapter = chapterRepository.findByGalleryId("chaika:31").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(chapter.getLanguage()).isEqualTo("Japanese");
            assertThat(chapter.getTags()).extracting("name").containsExactlyInAnyOrder("big breasts ♀", "big breasts",
                    "full color");
            assertThat(chapter.getArtists()).extracting("name").containsExactly("chaika artist");
            var dir = imageDirectory.chapterDir(chapterId);
            assertThat(Files.readString(dir.resolve("1.jpg"))).isEqualTo("first");
            assertThat(Files.readString(dir.resolve("2.png"))).isEqualTo("second");
            assertThat(Files.readString(dir.resolve("3.jpg"))).isEqualTo("third");
            // AND nothing asked for the whole archive.
            assertThat(site.ranges()).allMatch(range -> range.startsWith("bytes="));
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
}

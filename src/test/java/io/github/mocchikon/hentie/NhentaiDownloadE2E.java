package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.DownloadedGalleryRepository;
import io.github.mocchikon.hentie.scrapper.nhentai.NhentaiProperties;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One real gallery from nhentai.net through the whole pipeline, with nhentai's real rate limits: what
 * {@code NhentaiDownloadIT} checks against a fake, checked against the site itself. Needs the internet, so it runs
 * only in the {@code e2e} profile:
 * <pre>
 * ./mvnw test -Pe2e
 * </pre>
 * Gallery 111 is small (20 pages) and lists "translated" before its language, as many galleries do. All of its
 * metadata is checked, so an edit on the site fails this test; the expectations then follow the site.
 */
@SpringBootTest
@Transactional
class NhentaiDownloadE2E
{
    private static final String LINK = "https://nhentai.net/g/111/";
    private static final String GALLERY_ID = "nhentai:111";
    private static final int PAGES = 20;

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired ChapterRepository chapterRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired DownloadedGalleryRepository downloadedGalleryRepository;
    @Autowired ImageService imageService;
    @Autowired NhentaiProperties nhentaiProperties;
    @Autowired AppProperties appProperties;
    @PersistenceContext EntityManager em;

    /** The test profile points the source nowhere and lifts its limits; this suite wants the real thing. */
    private final NhentaiProperties testProfile = new NhentaiProperties();

    @BeforeEach
    void useTheRealSite()
    {
        copy(nhentaiProperties, testProfile);
        copy(new NhentaiProperties(), nhentaiProperties);
        worker.setPaused(false);
    }

    @AfterEach
    void restore()
    {
        copy(testProfile, nhentaiProperties);
    }

    @Test
    void shouldSaveTheMetadataAndDownloadEveryPageWhenDownloadingARealGallery() throws IOException
    {
        // GIVEN
        assertThat(queueService.enqueue(List.of(LINK), TestDownloads.choices(BuiltInCompressionMode.NONE.getKey(), false)).accepted())
                .isEqualTo(1);
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN the item finished rather than waiting for another attempt.
            assertThat(queueRepository.findByLink(LINK)).isEmpty();
            assertThat(downloadedGalleryRepository.findById(GALLERY_ID)).isPresent();

            // AND the metadata is what nhentai lists.
            Chapter chapter = chapterRepository.findByGalleryId(GALLERY_ID).orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getTitleFull()).isEqualTo("(C60) [Nakayohi Mogudan (Mogudan)] Ayanami 2 Hokenshitsu Hen "
                    + "[One Student Compilation 2] (Neon Genesis Evangelion) [English]");
            assertThat(chapter.getTitle()).isEqualTo("Ayanami 2 Hokenshitsu Hen");
            // nhentai sends an empty Japanese title for it.
            assertThat(chapter.getNativeTitle()).isNull();
            assertThat(chapter.getLanguage()).isEqualTo("English");
            assertThat(chapter.getArtists()).extracting("name").containsExactly("mogudan");
            assertThat(chapter.getGroups()).extracting("name").containsExactly("nakayohi mogudan");
            assertThat(chapter.getParodies()).extracting("name").containsExactly("neon genesis evangelion");
            assertThat(chapter.getCharacters()).extracting("name").containsExactly("rei ayanami");
            assertThat(chapter.getCategories()).extracting("name").containsExactly("doujinshi");
            assertThat(chapter.getTags()).extracting("name").containsExactlyInAnyOrder("big breasts",
                    "sole female", "anal", "blowjob", "full color", "mosaic censorship", "bloomers", "gymshorts");
            assertThat(chapter.getGalleryId()).isEqualTo(GALLERY_ID);

            // AND what the import sets itself: new, unrated, today, stored as it arrived. No series, since the
            // test profile turns auto-linking off.
            assertThat(chapter.getStatus()).isEqualTo(Status.NEW);
            assertThat(chapter.getScore()).isNull();
            assertThat(chapter.getChapterNum()).isNull();
            assertThat(chapter.getSeries()).isNull();
            assertThat(chapter.getUploadDate()).isEqualTo(LocalDate.now());
            assertThat(chapter.getCompressionMode()).isNull();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(chapter.getPageNum()).isEqualTo(PAGES);
            assertThat(chapter.getDiskSize()).isPositive();

            // AND every page is on disk, numbered from 1, and is a JPEG rather than an error page.
            Path folder = Paths.get(appProperties.getDataDir()).toAbsolutePath().normalize()
                    .resolve(String.valueOf(chapterId));
            assertThat(imageService.pageUrls(chapterId)).hasSize(PAGES);
            for (int page = 1; page <= PAGES; page++)
            {
                byte[] bytes = Files.readAllBytes(folder.resolve(page + ".jpg"));
                assertThat(Arrays.copyOf(bytes, 3)).as("page %d", page)
                        .containsExactly((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
            }
            // Only pages: no cover or thumbnail was stored beside them.
            try (var files = Files.list(folder))
            {
                assertThat(files.map(file -> file.getFileName().toString()))
                        .hasSize(PAGES)
                        .allMatch(name -> name.matches("\\d+\\.jpg"));
            }
        }
        finally
        {
            // Files are not rolled back with the transaction.
            if (chapterId != null)
            {
                imageService.discardStagedPages(chapterId);
                imageService.deleteAll(chapterId);
            }
        }
    }

    private static void copy(NhentaiProperties from, NhentaiProperties to)
    {
        to.setBaseUrl(from.getBaseUrl());
        to.setUserAgent(from.getUserAgent());
        to.setGalleryRequestsPerMinute(from.getGalleryRequestsPerMinute());
        to.setGalleryRequestsPerMinuteWithKey(from.getGalleryRequestsPerMinuteWithKey());
        to.setFavouritesRequestsPerMinute(from.getFavouritesRequestsPerMinute());
        to.setImageRequestIntervalMillis(from.getImageRequestIntervalMillis());
        to.setRequestTimeoutSeconds(from.getRequestTimeoutSeconds());
    }
}

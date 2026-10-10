package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.MetadataRuleRepository;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An nhentai link through the whole pipeline, against {@link FakeNhentai}: what the source reads ends up on the
 * chapter, and a page that fails is fetched again before it costs the item an attempt.
 */
@SpringBootTest
@Transactional
class NhentaiDownloadIT
{
    private static final String NO_COMPRESSION = BuiltInCompressionMode.NONE.getKey();

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired DataDownloaderRegistry registry;
    @Autowired ChapterRepository chapterRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired MetadataRuleRepository ruleRepository;
    @Autowired ImageService imageService;
    @Autowired NhentaiProperties nhentaiProperties;
    @Autowired AppProperties appProperties;
    @PersistenceContext EntityManager em;

    private FakeNhentai site;
    private String baseUrlBefore;

    @BeforeEach
    void pointTheSourceAtAFake() throws IOException
    {
        site = FakeNhentai.start();
        // A shared singleton: restored afterwards, or later suites would talk to a closed fake.
        baseUrlBefore = nhentaiProperties.getBaseUrl();
        nhentaiProperties.setBaseUrl(site.baseUrl());
        worker.setPaused(false);
    }

    @AfterEach
    void restore()
    {
        nhentaiProperties.setBaseUrl(baseUrlBefore);
        site.close();
    }

    @Test
    void shouldStoreTheGalleryWithItsArtistsFirstNameAndOnlyItsPagesWhenDownloadingAnNhentaiLink()
    {
        // GIVEN
        site.simpleGallery("177013", 3);
        assertThat(queueService.enqueue(List.of("https://nhentai.net/g/177013/1/"), TestDownloads.choices(NO_COMPRESSION, false)).accepted())
                .isEqualTo(1);
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the chapter holds what nhentai said, under the namespaced gallery id.
            Chapter chapter = chapterRepository.findByGalleryId("nhentai:177013").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getTitleFull()).isEqualTo("[Circle (Alpha | Beta)] Gallery 177013 [English]");
            assertThat(chapter.getTitle()).isEqualTo("Gallery 177013");
            assertThat(chapter.getNativeTitle()).isNull();
            assertThat(chapter.getLanguage()).isEqualTo("English");
            // nhentai's "alpha | beta" is e-hentai's name and alias: the alias is ruled to the name.
            assertThat(chapter.getArtists()).extracting("name").containsExactly("alpha");
            assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.ARTIST, "beta").orElseThrow().getTargetId())
                    .isEqualTo(chapter.getArtists().getFirst().getId());
            assertThat(chapter.getGroups()).extracting("name").containsExactly("circle");
            assertThat(chapter.getCategories()).extracting("name").containsExactly("doujinshi");
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(imageService.pageUrls(chapterId)).hasSize(3);
            assertThat(chapter.getPageNum()).isEqualTo(3);

            // AND no cover or thumbnail was ever asked for.
            assertThat(site.paths()).noneMatch(path -> path.endsWith("t.jpg") || path.contains("cover")
                    || path.contains("thumb"));
            assertThat(queueRepository.findAll()).noneMatch(item -> "nhentai:177013".equals(item.getGalleryId()));

            // AND a full-quality re-download can find its way back to the gallery.
            assertThat(registry.linkFor("nhentai:177013")).contains("https://nhentai.net/g/177013/");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldFetchAFailedPageAgainRatherThanFailTheItem()
    {
        // GIVEN a page that fails three times before it arrives.
        site.simpleGallery("178", 2).failNext("/galleries/m178/2.jpg", 500, 503, 502);
        queueService.enqueue(List.of("nhentai:178"), TestDownloads.choices(NO_COMPRESSION, false));
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN one attempt was enough.
            Chapter chapter = chapterRepository.findByGalleryId("nhentai:178").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(imageService.pageUrls(chapterId)).hasSize(2);
            assertThat(site.requestsFor("/galleries/m178/2.jpg")).isEqualTo(4);
            assertThat(queueRepository.findByLink("nhentai:178")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldFailTheAttemptOnlyOnceAPageFailedEveryRetry()
    {
        // GIVEN a page that fails once more than it is retried.
        int tries = appProperties.getDownload().getPageRetries() + 1;
        Integer[] failures = new Integer[tries];
        Arrays.fill(failures, 500);
        site.simpleGallery("179", 2).failNext("/galleries/m179/2.jpg", failures);
        queueService.enqueue(List.of("nhentai:179"), TestDownloads.choices(NO_COMPRESSION, false));
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the attempt failed after the retries, and the item waits for its next one with page 1 saved.
            DownloadQueueItem item = queueRepository.findByLink("nhentai:179").orElseThrow();
            assertThat(item.getAttempts()).isEqualTo(1);
            assertThat(item.getError()).isNull();
            assertThat(site.requestsFor("/galleries/m179/2.jpg")).isEqualTo(tries);
            Chapter chapter = chapterRepository.findByGalleryId("nhentai:179").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.PENDING);
            assertThat(imageService.pageNumbersOnDisk(chapterId)).containsExactly(1);

            // WHEN the next attempt runs, the page arrives.
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN the chapter is complete, and page 1 was not fetched from the site again.
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getDownloadStatus())
                    .isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(imageService.pageUrls(chapterId)).hasSize(2);
            assertThat(site.requestsFor("/galleries/m179/1.jpg")).isEqualTo(1);
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** A download never fails over a language the app cannot map or a name with pipes of its own. */
    @Test
    void shouldIgnoreALanguageTheAppCannotMapAndKeepANameWithPipesOfItsOwn()
    {
        // GIVEN a pair of languages nhentai really sends, and an artist whose pipes belong to the name.
        site.simpleGallery("180", 1, List.of("language:japanese | definition revised please read the wiki",
                "language:japanese", "artist:|joe||"));
        queueService.enqueue(List.of("nhentai:180"), TestDownloads.choices(NO_COMPRESSION, false));
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the gallery is downloaded in the language the app could map, with the artist's name whole.
            Chapter chapter = chapterRepository.findByGalleryId("nhentai:180").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(chapter.getLanguage()).isEqualTo("Japanese");
            assertThat(chapter.getArtists()).extracting("name").containsExactly("|joe||");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldSaveAGalleryWithNoLanguageTheAppKnowsAsJapanese()
    {
        // GIVEN a gallery nhentai files only as speechless.
        site.simpleGallery("181", 1, List.of("language:speechless", "tag:full color"));
        queueService.enqueue(List.of("nhentai:181"), TestDownloads.choices(NO_COMPRESSION, false));
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN it is downloaded all the same, in the language a gallery without one is taken for.
            Chapter chapter = chapterRepository.findByGalleryId("nhentai:181").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(chapter.getLanguage()).isEqualTo("Japanese");
            assertThat(queueRepository.findByLink("nhentai:181")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
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
}

package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.entity.DownloadedGallery;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.DownloadedGalleryRepository;
import io.github.mocchikon.hentie.scrapper.nhentai.NhentaiProperties;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.FavouritesDownloadService;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** "Download all favourites" against {@link FakeNhentai}: what gets queued, what is left out, and the button. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FavouritesDownloadIT
{
    private static final String NO_COMPRESSION = BuiltInCompressionMode.NONE.getKey();

    @Autowired FavouritesDownloadService favouritesService;
    @Autowired DownloadQueueService queueService;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired DownloadedGalleryRepository downloadedGalleryRepository;
    @Autowired ChapterService chapterService;
    @Autowired SettingsService settingsService;
    @Autowired NhentaiProperties nhentaiProperties;
    @Autowired MockMvc mvc;

    private FakeNhentai site;
    private String baseUrlBefore;

    @BeforeEach
    void pointTheSourceAtAFake() throws IOException
    {
        site = FakeNhentai.start().acceptKey("secret");
        // Shared singletons, restored afterwards: the rollback reaches neither.
        baseUrlBefore = nhentaiProperties.getBaseUrl();
        nhentaiProperties.setBaseUrl(site.baseUrl());
        settingsService.setNhentaiApiKey("secret");
    }

    @AfterEach
    void restore()
    {
        settingsService.setNhentaiApiKey("");
        nhentaiProperties.setBaseUrl(baseUrlBefore);
        site.close();
    }

    @Test
    void shouldQueueOnlyTheFavouritesTheLibraryHasNotGot()
    {
        // GIVEN five favourites over two pages: one in the library, one whose download never finished, one
        // downloaded and deleted since, one that failed before, and one new.
        site.favourites(List.of(101, 102, 103), List.of(104, 105));
        chapter("nhentai:101", DownloadStatus.SUCCESSFUL);
        chapter("nhentai:102", DownloadStatus.PENDING);
        downloadedAndDeleted("nhentai:103");
        failedRow("https://nhentai.net/g/104/");

        // WHEN
        FavouritesDownloadService.Outcome outcome = favouritesService.queueAll("nhentai", NO_COMPRESSION, true);

        // THEN
        assertThat(outcome.isRefused()).isFalse();
        assertThat(outcome.isStopped()).isFalse();
        assertThat(outcome.listed()).isEqualTo(5);
        assertThat(outcome.inLibrary()).isEqualTo(1);
        assertThat(outcome.deletedSince()).isEqualTo(1);
        assertThat(outcome.enqueued()).isEqualTo(new DownloadQueueService.EnqueueResult(2, 1, 0, 0));
        assertThat(outcome.summary()).isEqualTo("Listed 5 nhentai favourite(s): queued 2, queued again 1 that had "
                + "failed, 1 already in the library, 1 left out because they were downloaded before and deleted "
                + "since - paste their links to download them again.");

        // AND the queued rows carry the form's choices, like pasted links.
        assertThat(queueRepository.findAll())
                .filteredOn(item -> StringUtils.startsWith(item.getGalleryId(), "nhentai:"))
                .extracting(DownloadQueueItem::getGalleryId, DownloadQueueItem::getError,
                        DownloadQueueItem::isAvoidDuplicateTitles)
                .containsExactlyInAnyOrder(tuple("nhentai:102", null, true), tuple("nhentai:104", null, true),
                        tuple("nhentai:105", null, true));

        // AND nothing but the list was asked for: the galleries left out cost no request.
        assertThat(site.requests()).extracting(FakeNhentai.Request::path).containsOnly("/api/v2/favorites");
        assertThat(site.requests()).extracting(FakeNhentai.Request::query).containsExactly("page=1", "page=2");
    }

    @Test
    void shouldKeepWhatWasQueuedAndSayWhereItStoppedWhenAPageCannotBeListed()
    {
        // GIVEN the second page fails.
        site.favourites(List.of(201), List.of(202)).failNext("/api/v2/favorites?page=2", 500);

        // WHEN
        FavouritesDownloadService.Outcome outcome = favouritesService.queueAll("nhentai", NO_COMPRESSION, false);

        // THEN
        assertThat(outcome.isStopped()).isTrue();
        assertThat(outcome.enqueued().accepted()).isEqualTo(1);
        assertThat(outcome.summary()).startsWith("Listing your nhentai favourites stopped at page 2 of 2: ")
                .contains("HTTP 500")
                .endsWith("Of the 1 listed before that: queued 1.");
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("nhentai:201")).isPresent();
    }

    @Test
    void shouldRefuseWithoutAnApiKeyAndAskTheSiteNothing()
    {
        // GIVEN
        settingsService.setNhentaiApiKey("");

        // WHEN
        FavouritesDownloadService.Outcome outcome = favouritesService.queueAll("nhentai", NO_COMPRESSION, false);

        // THEN
        assertThat(outcome.isRefused()).isTrue();
        assertThat(outcome.summary()).contains("API key").contains("Settings");
        assertThat(site.requests()).isEmpty();
    }

    @Test
    void shouldRefuseASourceThatHasNoFavourites()
    {
        // WHEN + THEN - the mock source has no accounts.
        assertThat(favouritesService.queueAll("mock", NO_COMPRESSION, false).summary())
                .isEqualTo("No source here can list favourites from \"mock\".");
        assertThat(favouritesService.queueAll(null, NO_COMPRESSION, false).isRefused()).isTrue();
    }

    @Test
    void shouldSayTheListIsEmptyWhenThereAreNoFavourites()
    {
        // GIVEN
        site.favourites();

        // WHEN + THEN
        assertThat(favouritesService.queueAll("nhentai", NO_COMPRESSION, false).summary())
                .isEqualTo("Your nhentai favourites list is empty.");
    }

    // ---- the page ----------------------------------------------------------

    @Test
    void shouldOfferTheButtonOnlyWhileTheSourceHasAnApiKey() throws Exception
    {
        // WHEN + THEN - ready.
        mvc.perform(get("/chapter/download").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("favouritesReady", true))
                .andExpect(content().string(containsString("formaction=\"/chapter/download/favourites\"")))
                .andExpect(content().string(containsString("<option value=\"nhentai\">nhentai</option>")));

        // GIVEN
        settingsService.setNhentaiApiKey("");

        // WHEN + THEN - the button is there, disabled, and the page says why.
        mvc.perform(get("/chapter/download").with(user("user")))
                .andExpect(model().attribute("favouritesReady", false))
                .andExpect(content().string(containsString("nhentai (needs its API key)")))
                .andExpect(content().string(containsString("set the site's key in")));
    }

    @Test
    void shouldGoToTheQueueWithWhatWasFoundWhenDownloadingAllFavourites() throws Exception
    {
        // GIVEN
        site.favourites(List.of(301));

        // WHEN + THEN
        mvc.perform(post("/chapter/download/favourites").with(user("user")).with(csrf())
                        .param("source", "nhentai")
                        .param("compressionMode", NO_COMPRESSION)
                        .param("avoidDuplicateTitles", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/queue"))
                .andExpect(flash().attributeExists("favourites"));
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("nhentai:301")).get()
                .extracting(DownloadQueueItem::isAvoidDuplicateTitles).isEqualTo(true);
    }

    @Test
    void shouldGoBackToTheButtonWhenTheListingIsRefused() throws Exception
    {
        // GIVEN
        settingsService.setNhentaiApiKey("");

        // WHEN + THEN - nothing was queued, so the queue has nothing to show.
        mvc.perform(post("/chapter/download/favourites").with(user("user")).with(csrf()).param("source", "nhentai"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/download"))
                .andExpect(flash().attribute("favouritesError", containsString("API key")));
    }

    // ---- fixture -----------------------------------------------------------

    private void chapter(String galleryId, DownloadStatus status)
    {
        var form = new ChapterForm();
        form.setTitleFull("Favourite " + galleryId);
        form.setLanguage("English");
        form.setGalleryId(galleryId);
        chapterService.setDownloadStatus(chapterService.create(form), status);
    }

    private void downloadedAndDeleted(String galleryId)
    {
        var downloaded = new DownloadedGallery();
        downloaded.setGalleryId(galleryId);
        downloaded.setChapterId(Integer.MAX_VALUE);
        downloaded.setDownloadedAt(LocalDateTime.now());
        downloadedGalleryRepository.save(downloaded);
    }

    /** As {@code recordFailure} leaves a row that used up its attempts. */
    private void failedRow(String link)
    {
        queueService.enqueue(List.of(link), NO_COMPRESSION, false);
        DownloadQueueItem item = queueRepository.findByLink(link).orElseThrow();
        item.setAttempts(3);
        item.setError("failed before");
        queueRepository.save(item);
    }
}

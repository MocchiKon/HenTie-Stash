package io.github.mocchikon.hentie.scrapper.nhentai;

import io.github.mocchikon.hentie.FakeNhentai;
import io.github.mocchikon.hentie.scrapper.DataDownloader;
import io.github.mocchikon.hentie.scrapper.FavouritesSource;
import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.scrapper.GalleryNotFoundException;
import io.github.mocchikon.hentie.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Against {@link FakeNhentai}, so the requests themselves (paths, headers, 429s) are what is checked. */
class NhentaiDownloaderTest
{
    private final NhentaiProperties properties = new NhentaiProperties();
    private final SettingsService settingsService = mock(SettingsService.class);
    private FakeNhentai site;
    private NhentaiDownloader downloader;

    @BeforeEach
    void setUp() throws IOException
    {
        site = FakeNhentai.start();
        properties.setBaseUrl(site.baseUrl());
        properties.setGalleryRequestsPerMinute(0);
        properties.setGalleryRequestsPerMinuteWithKey(0);
        properties.setFavouritesRequestsPerMinute(0);
        properties.setImageRequestIntervalMillis(0);
        when(settingsService.getNhentaiApiKey()).thenReturn("");
        downloader = new NhentaiDownloader(properties, settingsService);
    }

    @AfterEach
    void tearDown()
    {
        downloader.close();
        site.close();
    }

    // ---- links -------------------------------------------------------------

    /** People paste whatever their browser shows, often a page of the gallery rather than the gallery. */
    @Test
    void shouldAcceptTheLinksPeoplePasteAndNameTheGalleryInThem()
    {
        for (String link : List.of("https://nhentai.net/g/123456/", "https://nhentai.net/g/123456",
                "http://www.nhentai.net/g/123456/7/", "nhentai.net/g/123456?ref=x", "HTTPS://NHENTAI.NET/G/123456/",
                "nhentai:123456", "NHENTAI: 123456", "https://nhentai.net/g/0123456/"))
        {
            assertThat(downloader.accepts(link)).as(link).isTrue();
            // Leading zeros dropped, or one gallery would get two gallery ids.
            assertThat(downloader.resourceId(link)).as(link).isEqualTo("123456");
        }
        assertThat(downloader.galleryId("123456")).isEqualTo("nhentai:123456");
    }

    @Test
    void shouldRefuseLinksOfOtherSitesAndLinksWithoutAGallery()
    {
        for (String link : List.of("https://nhentai.to/g/1/", "https://fakenhentai.net/g/1/",
                "https://nhentai.net.example.com/g/1/", "https://example.com/nhentai.net/g/1/",
                "https://nhentai.net/g/", "https://nhentai.net/g/12ab/", "https://nhentai.net/tag/english/",
                "nhentai:", "nhentai:abc", "mock:12", "123456"))
        {
            assertThat(downloader.accepts(link)).as(link).isFalse();
        }
    }

    /** A full-quality re-download has only the gallery id; the link must be the site's, not the API's address. */
    @Test
    void shouldBuildALinkItAcceptsForAResourceId()
    {
        // WHEN
        String link = downloader.link("42");

        // THEN
        assertThat(link).isEqualTo("https://nhentai.net/g/42/");
        assertThat(downloader.accepts(link)).isTrue();
        assertThat(downloader.resourceId(link)).isEqualTo("42");
    }

    /** The chapter page links its gallery id to the gallery's page on the site. */
    @Test
    void shouldOfferTheGalleryPageOnTheSiteAsTheLinkForAPerson()
    {
        // WHEN + THEN
        assertThat(downloader.pageLinkTemplate().replace(DataDownloader.RESOURCE_ID, "42"))
                .isEqualTo("https://nhentai.net/g/42/");
    }

    // ---- a gallery ---------------------------------------------------------

    @Test
    void shouldSortEachTagIntoItsFacetAndListOnlyThePagesInTheirOrder() throws IOException
    {
        // GIVEN a gallery as nhentai sends it, pages listed out of order.
        site.gallery("9", """
                {
                  "id": 9, "media_id": "900",
                  "title": {"english": "[Grp (Name1 | Name2)] Work [English]", "japanese": "作品", "pretty": "Work"},
                  "cover": {"path": "galleries/900/cover.jpg", "width": 350, "height": 500},
                  "thumbnail": {"path": "galleries/900/thumb.jpg", "width": 250, "height": 350},
                  "tags": [
                    {"type": "language", "name": "translated"},
                    {"type": "language", "name": "english"},
                    {"type": "artist", "name": "name1 | name2"},
                    {"type": "artist", "name": "|||naka|||"},
                    {"type": "group", "name": "grp"},
                    {"type": "parody", "name": "original"},
                    {"type": "character", "name": "a character"},
                    {"type": "tag", "name": "one"}, {"type": "tag", "name": "two"},
                    {"type": "category", "name": "doujinshi"}
                  ],
                  "num_pages": 2,
                  "pages": [
                    {"number": 2, "path": "galleries/900/2.png", "thumbnail": "galleries/900/2t.png"},
                    {"number": 1, "path": "/galleries/900/1.jpg", "thumbnail": "galleries/900/1t.jpg"}
                  ]
                }
                """);

        // WHEN
        GalleryData data = downloader.downloadGalleryInfo("9");

        // THEN
        assertThat(data.getId()).isEqualTo("9");
        assertThat(data.getFullTitle()).isEqualTo("[Grp (Name1 | Name2)] Work [English]");
        assertThat(data.getPrettyTitle()).isEqualTo("Work");
        assertThat(data.getJapaneseTitle()).isEqualTo("作品");
        // "translated" is filed as a language too; the real one wins.
        assertThat(data.getLanguage()).isEqualTo("english");
        assertThat(data.getArtists()).containsExactly("name1", "name2", "|||naka|||");
        assertThat(data.getGroups()).containsExactly("grp");
        assertThat(data.getParodies()).containsExactly("original");
        assertThat(data.getCharacters()).containsExactly("a character");
        assertThat(data.getTags()).containsExactly("one", "two");
        assertThat(data.getCategories()).containsExactly("doujinshi");
        // The pages on the listed image server, by nhentai's numbering - no cover, no thumbnails.
        assertThat(data.getPageUrls()).containsExactly(
                URI.create(site.baseUrl() + "/galleries/900/1.jpg"),
                URI.create(site.baseUrl() + "/galleries/900/2.png"));
    }

    /** nhentai bans clients that ask for paths it never gave; thumbnails are no pages either. */
    @Test
    void shouldAskForNothingButTheGalleryTheServerListAndThePages() throws IOException
    {
        // GIVEN
        site.simpleGallery("77", 2);

        // WHEN the gallery and each of its pages are downloaded.
        GalleryData data = downloader.downloadGalleryInfo("77");
        for (URI page : data.getPageUrls())
        {
            assertThat(new String(downloader.downloadPage(page), StandardCharsets.UTF_8)).startsWith("page ");
        }

        // THEN
        assertThat(site.paths()).containsExactly("/api/v2/galleries/77", "/api/v2/cdn",
                "/galleries/m77/1.jpg", "/galleries/m77/2.jpg");
        // nhentai asks every client to say who it is.
        assertThat(site.requests()).allSatisfy(request -> assertThat(request.userAgent()).startsWith("HenTie/"));
    }

    @Test
    void shouldReportAGalleryTheSiteDoesNotHaveAsNotFound()
    {
        // WHEN + THEN - permanent, so the queue does not spend its attempts on it.
        assertThatExceptionOfType(GalleryNotFoundException.class).isThrownBy(() -> downloader.downloadGalleryInfo("5"));
        // Only a number reaches the API's path.
        assertThatExceptionOfType(GalleryNotFoundException.class)
                .isThrownBy(() -> downloader.downloadGalleryInfo("5/../../user"));
        assertThat(site.paths()).containsExactly("/api/v2/galleries/5");
    }

    @Test
    void shouldReportAServerErrorAsTransient()
    {
        // GIVEN
        site.simpleGallery("6", 1).failNext("/api/v2/galleries/6", 502);

        // WHEN + THEN - retried by the queue, and the next try works.
        assertThatExceptionOfType(UncheckedIOException.class).isThrownBy(() -> downloader.downloadGalleryInfo("6"))
                .withMessageContaining("HTTP 502");
        assertThat(downloader.downloadGalleryInfo("6").getPageUrls()).hasSize(1);
    }

    /** The key raises the API's limits; the image servers come from the API's answers and get no key. */
    @Test
    void shouldSendTheApiKeyToTheApiButNeverToTheImageServers() throws IOException
    {
        // GIVEN
        when(settingsService.getNhentaiApiKey()).thenReturn("secret");
        site.simpleGallery("8", 1);

        // WHEN
        GalleryData data = downloader.downloadGalleryInfo("8");
        downloader.downloadPage(data.getPageUrls().getFirst());

        // THEN
        assertThat(site.requests()).filteredOn(request -> request.path().equals("/api/v2/galleries/8"))
                .singleElement().extracting(FakeNhentai.Request::authorization).isEqualTo("Key secret");
        assertThat(site.requests()).filteredOn(request -> !request.path().startsWith("/api/v2/galleries/"))
                .extracting(FakeNhentai.Request::authorization).containsOnlyNulls();
    }

    /** nhentai: "Treat 429 as a backoff signal". */
    @Test
    void shouldWaitAndAskAgainWhenTheSiteSaysTooManyRequests() throws IOException
    {
        // GIVEN two refusals before each answer.
        site.simpleGallery("10", 1)
                .failNext("/api/v2/galleries/10", 429, 429)
                .failNext("/galleries/m10/1.jpg", 429);

        // WHEN
        GalleryData data = downloader.downloadGalleryInfo("10");
        byte[] page = downloader.downloadPage(data.getPageUrls().getFirst());

        // THEN
        assertThat(page).isNotEmpty();
        assertThat(site.requestsFor("/api/v2/galleries/10")).isEqualTo(3);
        assertThat(site.requestsFor("/galleries/m10/1.jpg")).isEqualTo(2);
    }

    @Test
    void shouldGiveUpWhenTheSiteKeepsSayingTooManyRequests()
    {
        // GIVEN more refusals than one request sits out.
        Integer[] refusals = new Integer[NhentaiApi.MAX_RATE_LIMIT_WAITS + 1];
        Arrays.fill(refusals, 429);
        site.simpleGallery("11", 1).failNext("/api/v2/galleries/11", refusals);

        // WHEN + THEN - transient: the queue tries the item again later.
        assertThatExceptionOfType(UncheckedIOException.class).isThrownBy(() -> downloader.downloadGalleryInfo("11"))
                .withMessageContaining("429");
        assertThat(site.requestsFor("/api/v2/galleries/11")).isEqualTo(NhentaiApi.MAX_RATE_LIMIT_WAITS + 1);
    }

    /** Stored, either would be a page that shows nothing, and the chapter would count as complete. */
    @Test
    void shouldFailAPageThatIsEmptyOrNoImage()
    {
        // GIVEN
        site.image("galleries/1/1.jpg", new byte[0])
                .image("galleries/1/2.jpg", "<html>blocked</html>".getBytes(), "text/html");

        // WHEN + THEN
        assertThatIOException().isThrownBy(() -> downloader.downloadPage(page("galleries/1/1.jpg")));
        assertThatIOException().isThrownBy(() -> downloader.downloadPage(page("galleries/1/2.jpg")))
                .withMessageContaining("text/html");
        assertThatIOException().isThrownBy(() -> downloader.downloadPage(page("galleries/1/3.jpg")))
                .withMessageContaining("HTTP 404");
    }

    private URI page(String path)
    {
        return URI.create(site.baseUrl() + "/" + path);
    }

    // ---- favourites --------------------------------------------------------

    @Test
    void shouldListTheFavouritesOfTheKeysOwnerPageByPage()
    {
        // GIVEN
        when(settingsService.getNhentaiApiKey()).thenReturn("secret");
        site.acceptKey("secret").favourites(List.of(3, 2), List.of(1));

        // WHEN
        FavouritesSource.FavouritesPage first = downloader.favourites(1);
        FavouritesSource.FavouritesPage second = downloader.favourites(2);

        // THEN
        assertThat(downloader.canListFavourites()).isTrue();
        assertThat(first.resourceIds()).containsExactly("3", "2");
        assertThat(first.pageCount()).isEqualTo(2);
        assertThat(second.resourceIds()).containsExactly("1");
        assertThat(site.requests()).extracting(FakeNhentai.Request::query).containsExactly("page=1", "page=2");
        assertThat(site.requests()).extracting(FakeNhentai.Request::authorization).containsOnly("Key secret");
    }

    @Test
    void shouldNeedAKeyTheSiteAcceptsToListFavourites()
    {
        // GIVEN no key: nothing to ask with.
        assertThat(downloader.canListFavourites()).isFalse();
        assertThatExceptionOfType(UncheckedIOException.class).isThrownBy(() -> downloader.favourites(1))
                .withMessageContaining("Settings");
        assertThat(site.requests()).isEmpty();

        // GIVEN a key the site refuses.
        when(settingsService.getNhentaiApiKey()).thenReturn("wrong");
        site.acceptKey("secret").favourites(List.of(1));

        // WHEN + THEN - worded for the user, who has to fix it in Settings.
        assertThatExceptionOfType(UncheckedIOException.class).isThrownBy(() -> downloader.favourites(1))
                .withMessageContaining("did not accept the API key")
                .withMessageContaining("Settings");
    }

    // ---- nhentai's data quirks --------------------------------------------

    /** nhentai joins artists as "name1 | name2", but a pipe can also be part of a name. */
    @Test
    void shouldSplitJoinedArtistsButKeepNamesThatHavePipesOfTheirOwn()
    {
        assertThat(NhentaiDownloader.splitArtists(List.of("name1 | name2"))).containsExactly("name1", "name2");
        assertThat(NhentaiDownloader.splitArtists(List.of("a | b | c"))).containsExactly("a", "b", "c");
        // A duplicate of an artist the gallery lists anyway counts once.
        assertThat(NhentaiDownloader.splitArtists(List.of("a | b", "b"))).containsExactly("a", "b");

        for (String name : List.of("|||naka|||", "a|b", "a |b", "a| b", "| naka |", "naka | ", "a || b", "x | | y"))
        {
            assertThat(NhentaiDownloader.splitArtists(List.of(name))).as(name).containsExactly(name);
        }
    }

    @Test
    void shouldTakeTheFirstRealLanguageAndElseNameTheFirstTag()
    {
        assertThat(NhentaiDownloader.language(List.of("translated", "english"))).isEqualTo("english");
        assertThat(NhentaiDownloader.language(List.of("japanese"))).isEqualTo("japanese");
        // Kept, so the refusal to import names what the gallery has.
        assertThat(NhentaiDownloader.language(List.of("speechless"))).isEqualTo("speechless");
        assertThat(NhentaiDownloader.language(Set.of())).isNull();
    }
}

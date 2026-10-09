package io.github.mocchikon.hentie.scrapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What counts as one source's link is that source's business, so the stubs accept whatever the tests need. */
class DataDownloaderRegistryTest
{
    private final DataDownloaderRegistry registry = new DataDownloaderRegistry(
            List.of(new StubDownloader("mock", "mock:"),
                    new StubDownloader("other", "https://other.example/", "https://other.example/view/{id}")));

    @Test
    void shouldPickTheDownloaderThatAcceptsTheLink()
    {
        // WHEN
        ResourceLink link = registry.parse("https://other.example/42").orElseThrow();

        // THEN the gallery id is namespaced, which keeps two sources numbering from 1 apart.
        assertThat(link.downloader().sourcePrefix()).isEqualTo("other");
        assertThat(link.resourceId()).isEqualTo("42");
        assertThat(link.galleryId()).isEqualTo("other:42");
    }

    @Test
    void shouldPassTheLinkToTheDownloaderWithoutSurroundingWhitespace()
    {
        // Otherwise every source would have to trim for itself.
        // WHEN + THEN
        assertThat(registry.parse("  mock:7  ").orElseThrow().galleryId()).isEqualTo("mock:7");
    }

    @Test
    void shouldReturnEmptyWhenNoSourceRecognizesTheLink()
    {
        // Not queued at all rather than queued to fail later.
        assertThat(registry.parse("https://elsewhere.example/g/1")).isEmpty();
        assertThat(registry.parse(null)).isEmpty();
        assertThat(registry.parse("   ")).isEmpty();
        assertThat(registry.isSupported("mock:1")).isTrue();
        assertThat(registry.isSupported("nope")).isFalse();
    }

    @Test
    void shouldRefuseToStartWhenTwoSourcesShareAPrefix()
    {
        // They would collide on gallery ids, silently mixing up their galleries.
        List<DataDownloader> clashing = List.of(new StubDownloader("mock", "mock:"), new StubDownloader("mock", "m:"));

        assertThatThrownBy(() -> new DataDownloaderRegistry(clashing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mock");
    }

    /** A full-quality re-download has only the stored gallery id to rebuild the link from. */
    @Test
    void shouldBuildALinkForAStoredGalleryIdThatParsesBackToIt()
    {
        // WHEN
        String link = registry.linkFor("other:42").orElseThrow();

        // THEN
        assertThat(link).isEqualTo("https://other.example/42");
        assertThat(registry.parse(link).orElseThrow().galleryId()).isEqualTo("other:42");
    }

    @Test
    void shouldBuildNoLinkForAGalleryIdNoSourceHereCanFetch()
    {
        // WHEN + THEN - an unknown source, and ids that are no gallery id at all.
        assertThat(registry.linkFor("elsewhere:42")).isEmpty();
        assertThat(registry.linkFor("other:")).isEmpty();
        assertThat(registry.linkFor(null)).isEmpty();
        assertThat(registry.linkFor("  ")).isEmpty();
    }

    @Test
    void shouldBuildAPageLinkFromTheTemplateOfTheGalleryIdsSource()
    {
        // WHEN + THEN
        assertThat(registry.pageLinkFor("other:42", null)).contains("https://other.example/view/42");
    }

    @Test
    void shouldBuildNoPageLinkForASourceWithoutAWebsiteOrAnUnknownGalleryId()
    {
        // WHEN + THEN - the mock source has no template; the rest name no source here.
        assertThat(registry.pageLinkFor("mock:42", null)).isEmpty();
        assertThat(registry.pageLinkFor("elsewhere:42", null)).isEmpty();
        assertThat(registry.pageLinkFor("other:", null)).isEmpty();
        assertThat(registry.pageLinkFor(null, null)).isEmpty();
    }

    /** The Download page offers the sources that have favourites, and only those. */
    @Test
    void shouldOfferOnlyTheSourcesThatCanListFavourites()
    {
        // GIVEN
        var withFavourites = new StubFavouritesSource("site");
        var registry = new DataDownloaderRegistry(List.of(new StubDownloader("mock", "mock:"), withFavourites));

        // WHEN + THEN
        assertThat(registry.favouritesSources()).containsExactly(withFavourites);
        assertThat(registry.favouritesSource("site")).contains(withFavourites);
        assertThat(registry.favouritesSource("mock")).isEmpty();
        assertThat(registry.favouritesSource(null)).isEmpty();
    }

    /** A subscription stores its site's key, so two sites with one key would have it search the wrong site. */
    @Test
    void shouldRefuseToStartWhenTwoSearchSitesShareAKey()
    {
        List<DataDownloader> clashing = List.of(new StubSearchSource("a", null, "site"),
                new StubSearchSource("b", null, "site"));

        assertThatThrownBy(() -> new DataDownloaderRegistry(clashing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'site'");
    }

    @Test
    void shouldFindTheSourceOfEverySearchSite()
    {
        // GIVEN a source with two sites, and one with none
        var twoSites = new StubSearchSource("eh", null, "e-hentai", "exhentai");
        var registry = new DataDownloaderRegistry(List.of(new StubDownloader("mock", "mock:"), twoSites));

        // WHEN + THEN
        assertThat(registry.searchSources()).containsExactly(twoSites);
        assertThat(registry.searchSites()).extracting(SearchSource.SearchSite::key)
                .containsExactly("e-hentai", "exhentai");
        assertThat(registry.searchSource("exhentai")).contains(twoSites);
        assertThat(registry.searchSource("mock")).isEmpty();
        assertThat(registry.searchSource(null)).isEmpty();
    }

    /** The worker leaves a subscription's rows of a source waiting while that source refuses every download. */
    @Test
    void shouldTellUntilWhenTheSourceOfAGalleryRefusesDownloads()
    {
        // GIVEN
        Instant until = Instant.parse("2026-10-04T15:00:00Z");
        var registry = new DataDownloaderRegistry(List.of(new StubDownloader("mock", "mock:"),
                new StubSearchSource("eh", until, "e-hentai")));

        // WHEN + THEN
        assertThat(registry.refusingUntil("eh:1/abc")).contains(until);
        assertThat(registry.refusingUntil("mock:1")).isEmpty();
        assertThat(registry.refusingUntil("elsewhere:1")).isEmpty();
        assertThat(registry.refusingUntil(null)).isEmpty();
        assertThat(registry.refusals()).containsExactly("eh:");
    }

    /** @param refusing until when it refuses every download; null while it does not */
    private record StubSearchSource(String prefix, Instant refusing, List<String> siteKeys)
            implements SearchSource, PageDownloader
    {
        StubSearchSource(String prefix, Instant refusing, String... siteKeys)
        {
            this(prefix, refusing, List.of(siteKeys));
        }

        @Override
        public String sourcePrefix()
        {
            return prefix;
        }

        @Override
        public Optional<Instant> refusingUntil()
        {
            return Optional.ofNullable(refusing);
        }

        @Override
        public boolean accepts(String link)
        {
            return false;
        }

        @Override
        public String resourceId(String link)
        {
            return link;
        }

        @Override
        public String link(String resourceId)
        {
            return resourceId;
        }

        @Override
        public GalleryData downloadGalleryInfo(String id)
        {
            return GalleryData.builder().id(id).build();
        }

        @Override
        public byte[] downloadPage(URI url) throws IOException
        {
            throw new IOException("not used");
        }

        @Override
        public List<SearchSite> searchSites()
        {
            return siteKeys.stream().map(key -> new SearchSite(key, key, "")).toList();
        }

        @Override
        public String normalizedQuery(String site, String query)
        {
            return query;
        }

        @Override
        public Optional<String> notReady(String site)
        {
            return Optional.empty();
        }

        @Override
        public SearchPage search(String site, String query, String after)
        {
            return SearchPage.end();
        }

        @Override
        public String cursorAfter(String site, String after, List<String> resourceIds)
        {
            return resourceIds.getLast();
        }

        @Override
        public long position(String resourceId)
        {
            return 0;
        }

        @Override
        public String searchPageUrl(String site, String query)
        {
            return "";
        }
    }

    private record StubFavouritesSource(String prefix) implements FavouritesSource, PageDownloader
    {
        @Override
        public String sourcePrefix()
        {
            return prefix;
        }

        @Override
        public boolean accepts(String link)
        {
            return false;
        }

        @Override
        public String resourceId(String link)
        {
            return link;
        }

        @Override
        public String link(String resourceId)
        {
            return resourceId;
        }

        @Override
        public GalleryData downloadGalleryInfo(String id)
        {
            return GalleryData.builder().id(id).build();
        }

        @Override
        public byte[] downloadPage(URI url) throws IOException
        {
            throw new IOException("not used");
        }

        @Override
        public boolean canListFavourites()
        {
            return true;
        }

        @Override
        public FavouritesPage favourites(int page)
        {
            return new FavouritesPage(List.of(), 0);
        }
    }

    private record StubDownloader(String prefix, String marker, String pageLinkTemplate) implements PageDownloader
    {
        StubDownloader(String prefix, String marker)
        {
            this(prefix, marker, null);
        }

        @Override
        public String sourcePrefix()
        {
            return prefix;
        }

        @Override
        public boolean accepts(String link)
        {
            return link.startsWith(marker) && link.length() > marker.length();
        }

        @Override
        public String resourceId(String link)
        {
            return link.substring(marker.length());
        }

        @Override
        public String link(String resourceId)
        {
            return marker + resourceId;
        }

        @Override
        public GalleryData downloadGalleryInfo(String id)
        {
            return GalleryData.builder().id(id).build();
        }

        @Override
        public byte[] downloadPage(URI url) throws IOException
        {
            throw new IOException("not used");
        }
    }
}

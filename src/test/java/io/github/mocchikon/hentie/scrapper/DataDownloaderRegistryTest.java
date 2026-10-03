package io.github.mocchikon.hentie.scrapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;

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
        assertThat(registry.pageLinkFor("other:42")).contains("https://other.example/view/42");
    }

    @Test
    void shouldBuildNoPageLinkForASourceWithoutAWebsiteOrAnUnknownGalleryId()
    {
        // WHEN + THEN - the mock source has no template; the rest name no source here.
        assertThat(registry.pageLinkFor("mock:42")).isEmpty();
        assertThat(registry.pageLinkFor("elsewhere:42")).isEmpty();
        assertThat(registry.pageLinkFor("other:")).isEmpty();
        assertThat(registry.pageLinkFor(null)).isEmpty();
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

    private record StubFavouritesSource(String prefix) implements FavouritesSource
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

    private record StubDownloader(String prefix, String marker, String pageLinkTemplate) implements DataDownloader
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

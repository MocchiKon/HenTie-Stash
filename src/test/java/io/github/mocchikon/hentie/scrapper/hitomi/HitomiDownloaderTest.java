package io.github.mocchikon.hentie.scrapper.hitomi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.service.download.PermanentDownloadException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HitomiDownloaderTest
{
    private final HitomiDownloader downloader = new HitomiDownloader(null);

    @Test
    void shouldReadTheIdFromEveryKindOfGalleryLink()
    {
        // WHEN + THEN
        assertThat(downloader.resourceId("https://hitomi.la/doujinshi/neechan-no-musuko-english-670237.html"))
                .isEqualTo("670237");
        assertThat(downloader.resourceId("https://hitomi.la/galleries/1000000.html")).isEqualTo("1000000");
        assertThat(downloader.resourceId("hitomi.la/reader/1000000.html#4")).isEqualTo("1000000");
        assertThat(downloader.resourceId("https://HITOMI.la/gamecg/title-2-japanese-0123.html?x=1")).isEqualTo("123");
        assertThat(downloader.resourceId("hitomi:00042")).isEqualTo("42");
        assertThat(downloader.resourceId(downloader.link("42"))).isEqualTo("42");
        assertThat(downloader.galleryId("42")).isEqualTo("hitomi:42");
        assertThat(downloader.accepts("https://hitomi.la/tag/female%3Abig%20breasts-all.html")).isFalse();
        assertThat(downloader.accepts("https://nothitomi.la.example.com/galleries/1.html")).isFalse();
    }

    @Test
    void shouldTurnGalleryDlsMetadataIntoAChaptersMetadata() throws IOException
    {
        // GIVEN what gallery-dl printed for a real gallery, with a language added (that one has none).
        JsonNode gallery = directoryOf("/gallery-dl/hitomi-j-1000000.json");
        ((com.fasterxml.jackson.databind.node.ObjectNode) gallery).put("language", "English");

        // WHEN
        GalleryData data = HitomiDownloader.galleryData("1000000", gallery);

        // THEN
        assertThat(data.getFullTitle()).isEqualTo("Zanmataisei Demonbane (uncensored)");
        assertThat(data.getJapaneseTitle()).isNull();
        assertThat(data.getLanguage()).isEqualTo("English");
        assertThat(data.getArtists()).containsExactly("ni theta");
        assertThat(data.getGroups()).containsExactly("nitroplus");
        assertThat(data.getParodies()).containsExactly("demonbane");
        assertThat(data.getCharacters()).contains("al azif", "kurou daijuuji");
        assertThat(data.getTags()).contains("Big Breasts ♀", "Big Penis ♂", "Uncensored");
        assertThat(data.getCategories()).containsExactly("game cg");
        assertThat(data.getPageCount()).isEqualTo(1758);
    }

    @Test
    void shouldTakeAGalleryWithoutALanguageForJapanese() throws IOException
    {
        // GIVEN what gallery-dl printed for a real gallery without a language
        JsonNode gallery = directoryOf("/gallery-dl/hitomi-j-1000000.json");

        // WHEN
        GalleryData data = HitomiDownloader.galleryData("1000000", gallery);

        // THEN
        assertThat(data.getLanguage()).isEqualTo("Japanese");
    }

    @Test
    void shouldRefuseAnAnimeGallery()
    {
        // GIVEN
        var gallery = new ObjectMapper().createObjectNode().put("type", "Anime").put("title", "x").put("count", 0);

        // WHEN + THEN
        assertThatThrownBy(() -> HitomiDownloader.galleryData("5", gallery))
                .isInstanceOf(PermanentDownloadException.class).hasMessageContaining("video");
    }

    static JsonNode directoryOf(String resource) throws IOException
    {
        try (InputStream in = HitomiDownloaderTest.class.getResourceAsStream(resource))
        {
            return new ObjectMapper().readTree(in).get(0).get(1);
        }
    }
}

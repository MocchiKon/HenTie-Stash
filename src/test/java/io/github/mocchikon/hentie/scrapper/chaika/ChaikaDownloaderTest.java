package io.github.mocchikon.hentie.scrapper.chaika;

import io.github.mocchikon.hentie.FakeChaika;
import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.scrapper.GalleryNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** chaika's archives, read page by page out of the remote zip against {@link FakeChaika}. */
class ChaikaDownloaderTest
{
    /** Shortened from a real answer of {@code /api?archive=63715}. */
    static final String ARCHIVE_JSON = """
            {"category": "Doujinshi", "filecount": 3, "gallery": 73729, "tags": ["mixed:multiple_pairings",
            "female:nipple_stimulation", "other:full_color", "artist:kurosu_gatari", "group:doll_play",
            "parody:original", "male:dark_skin", "language:english", "language:translated"],
            "title": "[DOLL PLAY (Kurosu Gatari)] Wild-shiki Nihonjin Tsuma no Netorikata Sono San",
            "title_jpn": "[DOLL PLAY (\\u9ed2\\u5de3\\u30ac\\u30bf\\u30ea)] \\u5176\\u30ce\\u4e09"}""";

    private FakeChaika site;
    private ChaikaDownloader downloader;

    @BeforeEach
    void start() throws IOException
    {
        site = FakeChaika.start();
        var properties = new ChaikaProperties();
        properties.setBaseUrl(site.baseUrl());
        properties.setApiRequestIntervalMillis(0);
        properties.setPageRequestIntervalMillis(0);
        downloader = new ChaikaDownloader(properties);
    }

    @AfterEach
    void stop()
    {
        downloader.close();
        site.close();
    }

    @Test
    void shouldReadArchiveLinksOnly()
    {
        assertThat(downloader.resourceId("https://panda.chaika.moe/archive/63715/")).isEqualTo("63715");
        assertThat(downloader.resourceId("panda.chaika.moe/archive/063715/download/?original=1")).isEqualTo("63715");
        assertThat(downloader.resourceId("chaika:63715")).isEqualTo("63715");
        assertThat(downloader.resourceId(downloader.link("63715"))).isEqualTo("63715");
        assertThat(downloader.accepts("https://panda.chaika.moe/gallery/73729/")).isFalse();
    }

    @Test
    void shouldListTheImagesInReadingOrderFromTheIndexAlone() throws IOException
    {
        // GIVEN an archive with gaps in its numbering, out of order, with a file that is no page.
        var files = new LinkedHashMap<String, byte[]>();
        files.put("236.jpg", bytes("page 236"));
        files.put("110.png", bytes("page 110"));
        files.put("info.txt", bytes("not a page"));
        files.put("9.jpg", bytes("page 9"));
        site.archive("63715", ARCHIVE_JSON, files, false);

        // WHEN
        GalleryData data = downloader.downloadGalleryInfo("63715");

        // THEN the metadata is e-hentai's, spaces put back, and the pages are the images in natural order.
        assertThat(data.getFullTitle()).startsWith("[DOLL PLAY (Kurosu Gatari)]");
        assertThat(data.getJapaneseTitle()).startsWith("[DOLL PLAY (黒巣");
        assertThat(data.getLanguage()).isEqualTo("english");
        assertThat(data.getArtists()).containsExactly("kurosu gatari");
        assertThat(data.getGroups()).containsExactly("doll play");
        assertThat(data.getParodies()).containsExactly("original");
        assertThat(data.getTags()).containsExactly("mixed:multiple pairings", "female:nipple stimulation",
                "other:full color", "male:dark skin");
        assertThat(data.getCategories()).containsExactly("Doujinshi");
        List<URI> pages = List.copyOf(data.getPageUrls());
        assertThat(pages).hasSize(3);
        assertThat(pages).extracting(downloader::pageExtension).containsExactly("jpg", "png", "jpg");

        // AND each page reads back on its own, without the whole archive ever being asked for.
        assertThat(new String(downloader.downloadPage(pages.get(0)), StandardCharsets.UTF_8)).isEqualTo("page 9");
        assertThat(new String(downloader.downloadPage(pages.get(1)), StandardCharsets.UTF_8)).isEqualTo("page 110");
        assertThat(new String(downloader.downloadPage(pages.get(2)), StandardCharsets.UTF_8)).isEqualTo("page 236");
        assertThat(site.ranges()).allMatch(range -> range.startsWith("bytes="));
    }

    @Test
    void shouldReadADeflatedArchiveToo() throws IOException
    {
        // GIVEN
        var files = new LinkedHashMap<String, byte[]>();
        files.put("1.jpg", bytes("one ".repeat(1000)));
        files.put("2.jpg", bytes("two ".repeat(1000)));
        site.archive("5", ARCHIVE_JSON, files, true);

        // WHEN
        List<URI> pages = List.copyOf(downloader.downloadGalleryInfo("5").getPageUrls());

        // THEN
        assertThat(downloader.downloadPage(pages.get(1))).isEqualTo(bytes("two ".repeat(1000)));
    }

    @Test
    void shouldRefuseAServerThatIgnoresByteRanges() throws IOException
    {
        // GIVEN a server answering every range with the whole archive
        site.archive("6", ARCHIVE_JSON, java.util.Map.of("1.jpg", bytes("one")), false).ignoreRanges();

        // WHEN + THEN the gallery fails as a transient failure, instead of downloading archives page by page.
        assertThatThrownBy(() -> downloader.downloadGalleryInfo("6"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("ignored the byte range");
    }

    @Test
    void shouldTreatAMissingArchiveAsPermanentlyMissing()
    {
        assertThatThrownBy(() -> downloader.downloadGalleryInfo("404"))
                .isInstanceOf(GalleryNotFoundException.class);
    }

    @Test
    void shouldRefuseAPageAddressThatIsNotOne()
    {
        assertThatIOException().isThrownBy(() -> downloader.downloadPage(URI.create("https://panda.chaika.moe/archive/1/")));
    }

    private static byte[] bytes(String text)
    {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

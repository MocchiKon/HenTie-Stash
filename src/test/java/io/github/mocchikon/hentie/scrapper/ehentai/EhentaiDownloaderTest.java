package io.github.mocchikon.hentie.scrapper.ehentai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.scrapper.GalleryNotFoundException;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDl;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlTool;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.download.PermanentDownloadException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** e-hentai's links, its JSON API and what its answers become, against a fake API. */
class EhentaiDownloaderTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Shortened from a real answer of the API (gallery 618395). */
    static final String GDATA = """
            {"gmetadata":[{"gid":618395,"token":"0439fa3666","title":"(Kouroumu 8) [Handful\\u2606Happiness! (Fuyuki Nanahara)] TOUHOU GUNMANIA A2 &amp; more (Touhou Project)",
            "title_jpn":"(\\u7d05\\u697c\\u59228) TOUHOU GUNMANIA A2","category":"Non-H","posted":"1376110208","filecount":"20",
            "filesize":51210504,"expunged":false,"rating":"4.55","tags":["parody:touhou project","character:hong meiling",
            "group:handful happiness","artist:nanahara fuyuki","female:big breasts","other:artbook","other:full color"]}]}""";

    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();

    @AfterEach
    void stop()
    {
        if (server != null)
        {
            server.stop(0);
        }
    }

    private EhentaiDownloader downloaderAnswering(int status, String answer) throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api.php", exchange ->
        {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var properties = new EhentaiProperties();
        properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api.php");
        properties.setApiRequestIntervalMillis(0);
        // A gallery-dl never updated: metadata asks whether one is.
        var appProperties = new AppProperties();
        return new EhentaiDownloader(properties, new GalleryDl(new GalleryDlTool(appProperties, null), appProperties),
                mock(SettingsService.class));
    }

    @Test
    void shouldNameOneGalleryWhicheverDomainOrSpellingTheLinkUses()
    {
        var downloader = new EhentaiDownloader(new EhentaiProperties(), null, mock(SettingsService.class));

        // WHEN + THEN
        assertThat(downloader.resourceId("https://e-hentai.org/g/618395/0439fa3666/")).isEqualTo("618395/0439fa3666");
        assertThat(downloader.resourceId("https://exhentai.org/g/618395/0439FA3666/?p=2")).isEqualTo("618395/0439fa3666");
        assertThat(downloader.resourceId("http://E-HENTAI.org/mpv/0618395/0439fa3666/#page3")).isEqualTo("618395/0439fa3666");
        assertThat(downloader.resourceId("ehentai: 618395 / 0439fa3666")).isEqualTo("618395/0439fa3666");
        assertThat(downloader.galleryId("618395/0439fa3666")).isEqualTo("ehentai:618395/0439fa3666");
        assertThat(downloader.resourceId(downloader.link("618395/0439fa3666"))).isEqualTo("618395/0439fa3666");
        assertThat(downloader.pageLinkTemplate()).isEqualTo("https://e-hentai.org/g/{id}/");
        assertThat(downloader.pageLinkTemplate(null)).isEqualTo("https://e-hentai.org/g/{id}/");
        assertThat(downloader.pageLinkTemplate("exhentai")).isEqualTo("https://exhentai.org/g/{id}/");
    }

    @Test
    void shouldMarkEveryKindOfGalleryLink()
    {
        var downloader = new EhentaiDownloader(new EhentaiProperties(), null, mock(SettingsService.class));
        var links = List.of("https://e-hentai.org/g/618395/0439fa3666/", "https://exhentai.org/g/618395/0439FA3666/?p=2",
                "http://E-HENTAI.org/mpv/0618395/0439fa3666/#page3", "EHENTAI: 618395 / 0439fa3666",
                downloader.link("618395/0439fa3666"));

        // WHEN + THEN the Download page shows gallery-dl's choices for each.
        assertThat(links).allSatisfy(link -> assertThat(downloader.accepts(link)).isTrue())
                .allSatisfy(link -> assertThat(link.toLowerCase(Locale.ROOT))
                        .containsAnyOf(downloader.linkMarkers().toArray(String[]::new)));
    }

    @Test
    void shouldRefuseLinksThatNeedTheNetworkToBeUnderstood()
    {
        var downloader = new EhentaiDownloader(new EhentaiProperties(), null, mock(SettingsService.class));

        // WHEN + THEN a page link's gallery token is unknown, and a link without a token names nothing fetchable.
        assertThat(downloader.accepts("https://e-hentai.org/s/0123456789/618395-3")).isFalse();
        assertThat(downloader.accepts("https://e-hentai.org/g/618395/")).isFalse();
        assertThat(downloader.accepts("https://e-hentai.org.evil.com/g/618395/0439fa3666/")).isFalse();
        assertThat(downloader.accepts("ehentai:618395")).isFalse();
    }

    @Test
    void shouldAskTheApiForOneGalleryWithNamespacedTags() throws Exception
    {
        // GIVEN
        var downloader = downloaderAnswering(200, GDATA);

        // WHEN
        GalleryData data = downloader.downloadGalleryInfo("618395/0439fa3666");

        // THEN the request is the documented one...
        JsonNode body = JSON.readTree(bodies.getFirst());
        assertThat(body.path("method").asText()).isEqualTo("gdata");
        assertThat(body.path("gidlist").get(0).get(0).asLong()).isEqualTo(618395);
        assertThat(body.path("gidlist").get(0).get(1).asText()).isEqualTo("0439fa3666");
        assertThat(body.path("namespace").asInt()).isEqualTo(1);
        // ...and the answer becomes a chapter's metadata: entities decoded, no language tag means Japanese.
        assertThat(data.getFullTitle()).isEqualTo(
                "(Kouroumu 8) [Handful☆Happiness! (Fuyuki Nanahara)] TOUHOU GUNMANIA A2 & more (Touhou Project)");
        assertThat(data.getJapaneseTitle()).startsWith("(紅楼夢");
        assertThat(data.getLanguage()).isEqualTo("Japanese");
        assertThat(data.getCategories()).containsExactly("Non-H");
        assertThat(data.getArtists()).containsExactly("nanahara fuyuki");
        assertThat(data.getGroups()).containsExactly("handful happiness");
        assertThat(data.getParodies()).containsExactly("touhou project");
        assertThat(data.getCharacters()).containsExactly("hong meiling");
        assertThat(data.getTags()).containsExactly("female:big breasts", "other:artbook", "other:full color");
        assertThat(data.getPageCount()).isEqualTo(20);
        assertThat(data.pages()).isEqualTo(20);
    }

    @Test
    void shouldTreatAnUnknownGalleryAsPermanentlyMissing() throws Exception
    {
        // GIVEN the API's answer for a wrong token.
        var downloader = downloaderAnswering(200,
                "{\"gmetadata\":[{\"gid\":618395,\"error\":\"Key missing, or incorrect key provided.\"}]}");

        // WHEN + THEN
        assertThatThrownBy(() -> downloader.downloadGalleryInfo("618395/0439fa3666"))
                .isInstanceOf(GalleryNotFoundException.class)
                .hasMessageContaining("incorrect key");
    }

    @Test
    void shouldStopAskingWhenTheSiteBannedTheAddress() throws Exception
    {
        // GIVEN e-hentai's ban page instead of JSON.
        var downloader = downloaderAnswering(200, "Your IP address has been temporarily banned for excessive "
                + "pageloads which indicates that you are using automated mirroring/harvesting software.");

        // WHEN the first item finds out
        assertThatThrownBy(() -> downloader.downloadGalleryInfo("618395/0439fa3666"))
                .isInstanceOf(PermanentDownloadException.class)
                .hasMessageContaining("temporarily banned")
                .hasMessageContaining("not attempted until");

        // THEN the next one fails without asking the site at all.
        assertThatThrownBy(() -> downloader.downloadGalleryInfo("1/0123456789"))
                .isInstanceOf(PermanentDownloadException.class)
                .hasMessageContaining("not asked again until");
        assertThat(bodies).hasSize(1);
    }
}

package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.scrapper.chaika.ChaikaDownloader;
import io.github.mocchikon.hentie.scrapper.ehentai.EhentaiDownloader;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDl;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlTool;
import io.github.mocchikon.hentie.scrapper.hitomi.HitomiDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * hitomi, e-hentai and chaika themselves, with the gallery-dl bundled in {@code bin/}: what the fakes stand in for,
 * checked against the real sites. Needs the internet, so it runs only in the {@code e2e} profile:
 * <pre>
 * ./mvnw test -Pe2e
 * </pre>
 * Only metadata and a page or two of each gallery are fetched, at the sites' real pace. Every field read is
 * checked, so an edit on a site fails this test; the expectations then follow the site.
 */
@SpringBootTest(properties = {
        "app.gallery-dl.command=",
        "app.download.ehentai.api-url=https://api.e-hentai.org/api.php",
        "app.download.ehentai.api-request-interval-millis=1250",
        "app.download.chaika.base-url=https://panda.chaika.moe",
        "app.download.chaika.api-request-interval-millis=1000",
        "app.download.chaika.page-request-interval-millis=250"})
class GalleryDlSourcesE2E
{
    private static final GalleryDlOptions NO_COOKIES = new GalleryDlOptions(null, false, "0.4-0.65");

    @Autowired GalleryDlTool tool;
    @Autowired HitomiDownloader hitomi;
    @Autowired EhentaiDownloader ehentai;
    @Autowired ChaikaDownloader chaika;

    @TempDir Path folder;

    @Test
    void shouldReadAHitomiGalleryAndItsFirstPages() throws IOException
    {
        assumeBundledGalleryDl();

        // WHEN
        GalleryData data = hitomi.downloadGalleryInfo("1000000");
        List<Integer> received = new ArrayList<>();
        GalleryDl.Outcome outcome = hitomi.downloadPages("1000000", NO_COOKIES, new TreeSet<>(List.of(1, 2)),
                folder.resolve("run"), (page, file) -> received.add(page));

        // THEN
        assertThat(data.getFullTitle()).isEqualTo("Zanmataisei Demonbane (uncensored)");
        assertThat(data.getArtists()).containsExactly("ni theta");
        assertThat(data.getGroups()).containsExactly("nitroplus");
        assertThat(data.getParodies()).containsExactly("demonbane");
        assertThat(data.getCategories()).containsExactly("game cg");
        assertThat(data.getTags()).contains("Big Breasts ♀", "Uncensored");
        assertThat(data.getPageCount()).isEqualTo(1758);
        assertThat(received).containsExactlyInAnyOrder(1, 2);
        assertThat(outcome.received()).containsExactlyInAnyOrder(1, 2);
        assertThat(folder.resolve("run/1.webp")).isNotEmptyFile();
    }

    @Test
    void shouldReadAnEhentaiGalleryFromTheApiAndItsFirstPagesThroughGalleryDl() throws IOException
    {
        assumeBundledGalleryDl();

        // WHEN
        GalleryData data = ehentai.downloadGalleryInfo("618395/0439fa3666");
        GalleryDl.Outcome outcome = ehentai.downloadPages("618395/0439fa3666", NO_COOKIES, new TreeSet<>(List.of(1, 2)),
                folder.resolve("run"), (page, file) -> { });

        // THEN
        assertThat(data.getFullTitle()).isEqualTo(
                "(Kouroumu 8) [Handful☆Happiness! (Fuyuki Nanahara)] TOUHOU GUNMANIA A2 (Touhou Project)");
        assertThat(data.getLanguage()).isEqualTo("Japanese");
        assertThat(data.getCategories()).containsExactly("Non-H");
        assertThat(data.getArtists()).containsExactly("nanahara fuyuki");
        assertThat(data.getGroups()).containsExactly("handful happiness");
        assertThat(data.getParodies()).containsExactly("touhou project");
        assertThat(data.getCharacters()).contains("hong meiling", "reimu hakurei");
        assertThat(data.getTags()).contains("other:artbook", "other:full color");
        assertThat(data.getPageCount()).isEqualTo(20);
        assertThat(outcome.received()).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void shouldReadAChaikaArchivesIndexAndItsFirstPageThroughByteRanges() throws IOException
    {
        // WHEN
        GalleryData data = chaika.downloadGalleryInfo("63715");
        URI first = data.getPageUrls().getFirst();
        byte[] page = chaika.downloadPage(first);

        // THEN
        assertThat(data.getFullTitle()).isEqualTo("[DOLL PLAY (Kurosu Gatari)] Wild-shiki Nihonjin Tsuma no Netorikata Sono San");
        assertThat(data.getArtists()).containsExactly("kurosu gatari");
        assertThat(data.getGroups()).containsExactly("doll play");
        assertThat(data.getCategories()).containsExactly("Doujinshi");
        assertThat(data.getPageUrls()).hasSize(120);
        assertThat(chaika.pageExtension(first)).isEqualTo("jpg");
        // A JPEG, whole: its CRC was checked on the way.
        assertThat(page).startsWith((byte) 0xFF, (byte) 0xD8);
    }

    /** macOS has no bundled copy. */
    private void assumeBundledGalleryDl()
    {
        GalleryDlTool.Origin origin;
        try
        {
            origin = tool.executable().origin();
        }
        catch (IOException e)
        {
            origin = null;
        }
        assumeThat(origin).as("gallery-dl is bundled for this system").isEqualTo(GalleryDlTool.Origin.BUNDLED);
    }
}

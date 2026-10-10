package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.scrapper.chaika.ChaikaDownloader;
import io.github.mocchikon.hentie.scrapper.ehentai.EhentaiDownloader;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDl;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlTool;
import io.github.mocchikon.hentie.scrapper.hitomi.HitomiDownloader;
import io.github.mocchikon.hentie.service.download.GalleryImportService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

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
 * checked, so an edit on a site fails this test; the expectations then follow the site. The metadata is then saved as
 * a download saves it, and the chapter checked too: names in their canonical spelling, with the plain tag beside each
 * gendered one. Rolled back afterwards.
 */
@SpringBootTest(properties = {
        "app.gallery-dl.command=",
        "app.download.ehentai.api-url=https://api.e-hentai.org/api.php",
        "app.download.ehentai.api-request-interval-millis=1250",
        "app.download.chaika.base-url=https://panda.chaika.moe",
        "app.download.chaika.api-request-interval-millis=1000",
        "app.download.chaika.page-request-interval-millis=250"})
@Transactional
class GalleryDlSourcesE2E
{
    private static final GalleryDlOptions NO_COOKIES = new GalleryDlOptions(null, false, "0.4-0.65");

    @Autowired GalleryDlTool tool;
    @Autowired HitomiDownloader hitomi;
    @Autowired EhentaiDownloader ehentai;
    @Autowired ChaikaDownloader chaika;
    @Autowired GalleryImportService importService;
    @Autowired ChapterRepository chapterRepository;
    @PersistenceContext EntityManager em;

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

        // ...and saved with the plain tag beside the gendered one, so a search for either finds it.
        Chapter chapter = saved(data, hitomi.galleryId("1000000"));
        assertThat(chapter.getTitleFull()).isEqualTo("Zanmataisei Demonbane (uncensored)");
        assertThat(chapter.getTags()).extracting("name").contains("big breasts ♀", "big breasts", "uncensored")
                .doesNotContain("Big Breasts ♀");
        assertThat(chapter.getArtists()).extracting("name").containsExactly("ni theta");
        assertThat(chapter.getGroups()).extracting("name").containsExactly("nitroplus");
        assertThat(chapter.getParodies()).extracting("name").containsExactly("demonbane");
        assertThat(chapter.getCategories()).extracting("name").containsExactly("game cg");
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
        // Without cookies from e-hentai.org, so the chapter would link there.
        assertThat(outcome.site()).isNull();

        // ...and saved without e-hentai's namespaces.
        Chapter chapter = saved(data, ehentai.galleryId("618395/0439fa3666"));
        assertThat(chapter.getTitleFull()).isEqualTo(
                "(Kouroumu 8) [Handful☆Happiness! (Fuyuki Nanahara)] TOUHOU GUNMANIA A2 (Touhou Project)");
        assertThat(chapter.getLanguage()).isEqualTo("Japanese");
        assertThat(chapter.getCategories()).extracting("name").containsExactly("non-h");
        assertThat(chapter.getArtists()).extracting("name").containsExactly("nanahara fuyuki");
        assertThat(chapter.getGroups()).extracting("name").containsExactly("handful happiness");
        assertThat(chapter.getParodies()).extracting("name").containsExactly("touhou project");
        assertThat(chapter.getCharacters()).extracting("name").contains("hong meiling", "reimu hakurei");
        assertThat(chapter.getTags()).extracting("name").contains("artbook", "full color")
                .doesNotContain("other:artbook", "other:full color");
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

        // ...and saved.
        Chapter chapter = saved(data, chaika.galleryId("63715"));
        assertThat(chapter.getTitleFull()).isEqualTo(
                "[DOLL PLAY (Kurosu Gatari)] Wild-shiki Nihonjin Tsuma no Netorikata Sono San");
        assertThat(chapter.getArtists()).extracting("name").containsExactly("kurosu gatari");
        assertThat(chapter.getGroups()).extracting("name").containsExactly("doll play");
        assertThat(chapter.getCategories()).extracting("name").containsExactly("doujinshi");
    }

    /** As the download pipeline saves a new gallery, read back from the database. */
    private Chapter saved(GalleryData data, String galleryId)
    {
        int id = importService.importChapter(data, galleryId);
        em.flush();
        em.clear();
        return chapterRepository.findById(id).orElseThrow();
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

package io.github.mocchikon.hentie.scrapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockDataDownloaderTest
{
    @TempDir Path mockDir;

    private MockDataDownloader downloader;

    @BeforeEach
    void setUp()
    {
        downloader = new MockDataDownloader(mockDir.toString());
    }

    /** Each source parses its own links; there is no shared {@code <prefix>:<id>} handling. */
    @Test
    void shouldClaimItsOwnLinksAndNameTheResourceInThem()
    {
        // A prefix is a name rather than a case-sensitive token...
        assertThat(downloader.accepts("mock:12")).isTrue();
        assertThat(downloader.accepts("MOCK:12")).isTrue();
        assertThat(downloader.resourceId("MOCK:12")).isEqualTo("12");
        assertThat(downloader.galleryId(downloader.resourceId("mock:12"))).isEqualTo("mock:12");

        // ...a prefix that only starts the same is another source, and a prefix with no id is no resource.
        assertThat(downloader.accepts("mockery:1")).isFalse();
        assertThat(downloader.accepts("mock:")).isFalse();
        assertThat(downloader.accepts("mock:   ")).isFalse();
        assertThat(downloader.accepts("https://somesite.org/gallery/12")).isFalse();
    }

    /** A full-quality re-download rebuilds the link from the stored id. */
    @Test
    void shouldBuildALinkItAcceptsForAResourceId()
    {
        // WHEN
        String link = downloader.link("12");

        // THEN
        assertThat(link).isEqualTo("mock:12");
        assertThat(downloader.accepts(link)).isTrue();
        assertThat(downloader.resourceId(link)).isEqualTo("12");
    }

    @Test
    void shouldSortEachTagIntoItsFacetAndOrderPagesBySourceNumberWhenReadingAGallery() throws IOException
    {
        // GIVEN pages listed out of order; the source's own "number" decides.
        writeJson("12", """
                {
                  "title": {"english": "[Grp] Work", "japanese": "\\u4f5c\\u54c1", "pretty": "Work"},
                  "tags": [
                    {"type": "language", "name": "english"},
                    {"type": "tag", "name": "one"}, {"type": "tag", "name": "two"},
                    {"type": "artist", "name": "an artist"},
                    {"type": "group", "name": "a group"},
                    {"type": "parody", "name": "a parody"},
                    {"type": "character", "name": "a character"},
                    {"type": "category", "name": "manga"}
                  ],
                  "pages": [
                    {"number": 2, "path": "galleries/12/2.webp"},
                    {"number": 1, "path": "galleries/12/1.webp"}
                  ]
                }
                """);

        // WHEN
        GalleryData data = downloader.downloadGalleryInfo("12");

        // THEN every facet is filled from the one flat tags array...
        assertThat(data.getFullTitle()).isEqualTo("[Grp] Work");
        assertThat(data.getPrettyTitle()).isEqualTo("Work");
        assertThat(data.getJapaneseTitle()).isEqualTo("作品");
        assertThat(data.getLanguage()).isEqualTo("english");
        assertThat(data.getTags()).containsExactly("one", "two");
        assertThat(data.getArtists()).containsExactly("an artist");
        assertThat(data.getGroups()).containsExactly("a group");
        assertThat(data.getParodies()).containsExactly("a parody");
        assertThat(data.getCharacters()).containsExactly("a character");
        assertThat(data.getCategories()).containsExactly("manga");
        // ...and the pages come back in reading order, not array order.
        assertThat(List.copyOf(data.getPageUrls()))
                .extracting(url -> url.getPath().substring(url.getPath().lastIndexOf('/') + 1))
                .containsExactly("1.webp", "2.webp");
    }

    @Test
    void shouldTreatTheLiteralWordNullAsNoValueWhenReadingATitle() throws IOException
    {
        // The source writes the string "null" for no Japanese title; kept, it would show as a real title.
        writeJson("13", """
                {"title": {"english": "Only English", "japanese": "null", "pretty": ""}, "pages": []}
                """);

        // WHEN
        GalleryData data = downloader.downloadGalleryInfo("13");

        // THEN
        assertThat(data.getJapaneseTitle()).isNull();
        assertThat(data.getPrettyTitle()).isNull();
        assertThat(data.getFullTitle()).isEqualTo("Only English");
    }

    @Test
    void shouldFailPermanentlyWhenTheGalleryDoesNotExistOrTheIdIsNotAnId()
    {
        // A missing gallery is not worth retrying, and "../.." must not escape the mock directory.
        assertThatThrownBy(() -> downloader.downloadGalleryInfo("404"))
                .isInstanceOf(GalleryNotFoundException.class);
        assertThatThrownBy(() -> downloader.downloadGalleryInfo("../secrets"))
                .isInstanceOf(GalleryNotFoundException.class);
    }

    @Test
    void shouldReadPageBytesAndRefuseAnythingOutsideTheMockDirectory() throws IOException
    {
        // GIVEN one real page file.
        Path pages = Files.createDirectories(mockDir.resolve("galleries/14"));
        Files.writeString(pages.resolve("1.webp"), "bytes");

        // WHEN + THEN a page inside the source is read...
        assertThat(downloader.downloadPage(pages.resolve("1.webp").toUri()))
                .isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));
        // ...a page the JSON promised but the source lacks fails, so the queue retries...
        assertThatThrownBy(() -> downloader.downloadPage(pages.resolve("2.webp").toUri()))
                .isInstanceOf(NoSuchFileException.class);
        // ...and a URL pointing anywhere else is refused outright.
        URI outside = mockDir.getParent().resolve("elsewhere.txt").toUri();
        assertThatThrownBy(() -> downloader.downloadPage(outside))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside");
    }

    private void writeJson(String id, String json) throws IOException
    {
        Files.writeString(mockDir.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }
}

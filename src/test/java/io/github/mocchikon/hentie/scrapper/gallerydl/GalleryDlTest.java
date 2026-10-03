package io.github.mocchikon.hentie.scrapper.gallerydl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What gallery-dl's output and exit status mean. It reports failures in no other way. */
class GalleryDlTest
{
    @Test
    void shouldWriteRunsOfPagesAsRangesWhenBuildingTheRangeOption()
    {
        // WHEN + THEN
        assertThat(GalleryDl.rangeSpec(List.of(1, 2, 3, 5, 7, 8, 10))).isEqualTo("1-3,5,7-8,10");
        assertThat(GalleryDl.rangeSpec(List.of(4))).isEqualTo("4");
        assertThat(GalleryDl.rangeSpec(List.of(9, 3, 2, 3))).isEqualTo("2-3,9");
        assertThat(GalleryDl.rangeSpec(List.of())).isEmpty();
    }

    @Test
    void shouldReadTheGalleryFromARealMetadataRun() throws IOException
    {
        // GIVEN what gallery-dl 1.32.14 printed for `-j --range 1` on a hitomi gallery.
        String stdout;
        try (InputStream in = getClass().getResourceAsStream("/gallery-dl/hitomi-j-1000000.json"))
        {
            stdout = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // WHEN
        JsonNode gallery = GalleryDl.directoryIn("u", new GalleryDlTool.Result(0, List.of(), stdout));

        // THEN the Directory message, not the first file's.
        assertThat(gallery.path("title").asText()).isEqualTo("Zanmataisei Demonbane (uncensored)");
        assertThat(gallery.path("count").asInt()).isEqualTo(1758);
        assertThat(gallery.has("extension")).isFalse();
    }

    @Test
    void shouldReportTheErrorGalleryDlPutInItsJson()
    {
        // GIVEN a metadata run for a gallery the site does not have: -j exits 0 and says so in the JSON.
        String stdout = "[[-1, {\"error\": \"NotFoundError\", \"message\": \"Requested gallery could not be found\"}]]";

        // WHEN + THEN
        assertThatThrownBy(() -> GalleryDl.directoryIn("u", new GalleryDlTool.Result(0, List.of(), stdout)))
                .isInstanceOfSatisfying(GalleryDlException.class,
                        e -> assertThat(e.kind()).isEqualTo(GalleryDlException.Kind.NOT_FOUND))
                .hasMessageContaining("could not be found");
    }

    @Test
    void shouldSaySoWhenAMetadataRunReturnsNothing()
    {
        assertThatThrownBy(() -> GalleryDl.directoryIn("u", new GalleryDlTool.Result(0, List.of(), "[]")))
                .isInstanceOfSatisfying(GalleryDlException.class,
                        e -> assertThat(e.kind()).isEqualTo(GalleryDlException.Kind.OTHER));
    }

    @Test
    void shouldTellFailuresApartByTheirWordsBeforeTheirExitBits()
    {
        // WHEN + THEN a ban and a refused account share the "not authorized" bit; the words tell them apart.
        assertThat(kind(16, "[exhentai][error] AuthorizationError: Temporarily Banned"))
                .isEqualTo(GalleryDlException.Kind.BANNED);
        assertThat(kind(16, "[exhentai][error] AuthorizationError: Insufficient privileges"))
                .isEqualTo(GalleryDlException.Kind.REFUSED);
        assertThat(kind(4, "[exhentai][error] Image limit exceeded!")).isEqualTo(GalleryDlException.Kind.IMAGE_LIMIT);
        assertThat(kind(4, "[exhentai][error] Not enough GP")).isEqualTo(GalleryDlException.Kind.NO_GP);
        assertThat(kind(8, "[hitomi][error] ChallengeError: Cloudflare challenge"))
                .isEqualTo(GalleryDlException.Kind.CHALLENGE);
        assertThat(kind(64, "[gallery-dl][error] Unsupported URL 'x'")).isEqualTo(GalleryDlException.Kind.UNSUPPORTED);
        assertThat(kind(128, "[download][error] Unable to download data:  OSError: [Errno 28] No space left"))
                .isEqualTo(GalleryDlException.Kind.WRITE);
        assertThat(kind(4, "[download][error] Failed to download 3.webp")).isEqualTo(GalleryDlException.Kind.OTHER);
        assertThat(kind(0xC0000135, "")).isEqualTo(GalleryDlException.Kind.NOT_RUNNABLE);
        // A kill by a signal (128 + 9) or a crash is no status of gallery-dl's, whatever its bits say.
        assertThat(kind(137, "[urllib3.connectionpool][debug] GET /x")).isEqualTo(GalleryDlException.Kind.ABORTED);
        assertThat(kind(0xC0000005, "")).isEqualTo(GalleryDlException.Kind.ABORTED);
    }

    @Test
    void shouldQuoteGalleryDlsLastErrorsWithoutTheirPrefix()
    {
        // GIVEN debug chatter, a warning and errors.
        List<String> stderr = List.of("[urllib3.connectionpool][debug] GET /x", "[downloader.http][warning] 503",
                "[download][error] Failed to download 2.webp", "[download][error] Failed to download 5.webp");

        // WHEN + THEN
        assertThat(GalleryDl.problem(stderr)).isEqualTo("Failed to download 2.webp; Failed to download 5.webp");
        assertThat(GalleryDl.problem(List.of("[downloader.http][warning] 503 Service Unavailable")))
                .isEqualTo("503 Service Unavailable");
        assertThat(GalleryDl.problem(List.of("[x][debug] nothing"))).isNull();
    }

    private static GalleryDlException.Kind kind(int exit, String line)
    {
        return GalleryDl.classify(exit, line.isEmpty() ? List.of() : List.of(line), "context").kind();
    }
}

package io.github.mocchikon.hentie.service.export;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CbzNamingTest
{
    @Test
    void shouldMakeNamesEverySystemAccepts()
    {
        assertThat(CbzExportService.fileName("a/b\\c:d*e?f\"g<h>i|j", "x")).isEqualTo("a_b_c_d_e_f_g_h_i_j");
        assertThat(CbzExportService.fileName("  Title  with   spaces. . ", "x")).isEqualTo("Title with spaces");
        assertThat(CbzExportService.fileName("...", "chapter-7")).isEqualTo("chapter-7");
        assertThat(CbzExportService.fileName(null, "chapter-7")).isEqualTo("chapter-7");
        assertThat(CbzExportService.fileName("日".repeat(300), "x")).hasSize(120);
    }

    @Test
    void shouldEscapeAndDropWhatXmlCannotCarry()
    {
        assertThat(ComicInfo.escape("a<b>&\"c\u0001d😀")).isEqualTo("a&lt;b&gt;&amp;&quot;cd😀");
    }

    @Test
    void shouldWriteOnlyValuesInSchemaOrder()
    {
        var info = new ComicInfo("T", "S", "2.10", 3, LocalDate.of(2024, 6, 1), List.of("artist"), List.of(),
                List.of("doujinshi"), List.of("tag a", "tag b"), List.of(), List.of("parody"),
                ComicInfo.isoCode("Japanese"), null, 12, 7);

        String xml = new String(info.toXml());

        assertThat(xml).contains("<Number>2.10</Number>", "<Tags>tag a, tag b</Tags>", "<LanguageISO>ja</LanguageISO>",
                        "<CommunityRating>3.5</CommunityRating>", "<SeriesGroup>parody</SeriesGroup>")
                .doesNotContain("<Publisher>", "<Web>", "<Characters>");
        assertThat(xml.indexOf("<Genre>")).isLessThan(xml.indexOf("<Tags>"));
        assertThat(xml.indexOf("<PageCount>")).isLessThan(xml.indexOf("<LanguageISO>"));
    }
}

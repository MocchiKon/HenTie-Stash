package io.github.mocchikon.hentie.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pieces must always re-join into the verbatim {@code titleFull} (never the pretty title's casing),
 * and a pretty title not contained in the full one fades nothing.
 */
class TitlePartsTest
{
    @Test
    void shouldSplitAroundThePrettyTitleWhenItIsContained()
    {
        // WHEN
        TitleParts parts = TitleParts.of("[Doujin] Isekai Yuusha (Alpha)", "Isekai Yuusha");

        // THEN
        assertThat(parts.prefix()).isEqualTo("[Doujin] ");
        assertThat(parts.match()).isEqualTo("Isekai Yuusha");
        assertThat(parts.suffix()).isEqualTo(" (Alpha)");
        assertThat(parts.isSplit()).isTrue();
    }

    @Test
    void shouldFadeOnlyTheTrailingDecorationWhenThePrettyTitleLeads()
    {
        // WHEN
        TitleParts parts = TitleParts.of("Comic Hero 2024-06 [Digital]", "Comic Hero 2024-06");

        // THEN
        assertThat(parts.prefix()).isEmpty();
        assertThat(parts.match()).isEqualTo("Comic Hero 2024-06");
        assertThat(parts.suffix()).isEqualTo(" [Digital]");
        assertThat(parts.isSplit()).isTrue();
    }

    @Test
    void shouldCutThePiecesOutOfTheFullTitleWhenCasingDiffers()
    {
        // WHEN
        TitleParts parts = TitleParts.of("[Doujin] ISEKAI YUUSHA (Alpha)", "isekai yuusha");

        // THEN
        assertThat(parts.match()).isEqualTo("ISEKAI YUUSHA");
        assertThat(parts.prefix() + parts.match() + parts.suffix())
                .isEqualTo("[Doujin] ISEKAI YUUSHA (Alpha)");
    }

    @Test
    void shouldKeepTheWholeTitleNormalWhenNothingIsShared()
    {
        // WHEN
        TitleParts renamed = TitleParts.of("[Doujin] Isekai Yuusha", "My own name for it");
        TitleParts identical = TitleParts.of("Isekai Yuusha", "Isekai Yuusha");
        TitleParts blank = TitleParts.of("Isekai Yuusha", "  ");
        TitleParts missing = TitleParts.of("Isekai Yuusha", null);

        // THEN
        assertThat(renamed.match()).isEqualTo("[Doujin] Isekai Yuusha");
        assertThat(identical.match()).isEqualTo("Isekai Yuusha");
        assertThat(blank.match()).isEqualTo("Isekai Yuusha");
        assertThat(missing.match()).isEqualTo("Isekai Yuusha");
        assertThat(renamed.isSplit()).isFalse();
        assertThat(identical.isSplit()).isFalse();
        assertThat(blank.isSplit()).isFalse();
        assertThat(missing.isSplit()).isFalse();
    }

    @Test
    void shouldTolerateAMissingFullTitle()
    {
        // WHEN
        TitleParts parts = TitleParts.of(null, "Isekai Yuusha");

        // THEN - never a null piece: the template concatenates all three.
        assertThat(parts.prefix()).isEmpty();
        assertThat(parts.match()).isEmpty();
        assertThat(parts.suffix()).isEmpty();
    }
}

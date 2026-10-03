package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.dto.MetadataType;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataServiceUnitTest
{
    @Test
    void shouldTrimAndLowerCaseWhenNormalizingAName()
    {
        // WHEN + THEN
        assertThat(MetadataService.normalize("  Big Sister ")).isEqualTo("big sister");
        assertThat(MetadataService.normalize("BIG SISTER")).isEqualTo("big sister");
        assertThat(MetadataService.normalize("big sister")).isEqualTo("big sister");
        assertThat(MetadataService.normalize(null)).isNull();
    }

    /**
     * A Turkish default locale lower-cases 'I' to the dotless 'ı', so a locale-dependent fold would make
     * {@code "BIG SISTER"} a different name from the stored {@code "big sister"} and fork a duplicate row.
     */
    @Test
    void shouldFoldTheSameWayWhenTheDefaultLocaleLowerCasesTheLetterIDifferently()
    {
        // GIVEN a default locale whose lower-casing of 'I' is not the ASCII 'i'.
        var original = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
        try
        {
            // WHEN + THEN the fold is still the ASCII one every stored name was written with.
            assertThat(MetadataService.normalize("BIG SISTER")).isEqualTo("big sister");
            assertThat(MetadataService.normalize("Big Sister")).isEqualTo("big sister");
            assertThat(MetadataService.normalize("Full Color")).isEqualTo("full color");
        }
        finally
        {
            Locale.setDefault(original);
        }
    }

    @Test
    void shouldTurnGenderNamespacesIntoASymbolWhenCanonicalizingATag()
    {
        // WHEN + THEN
        assertThat(MetadataService.canonical(MetadataType.TAG, "female:halo")).isEqualTo("halo \u2640");
        assertThat(MetadataService.canonical(MetadataType.TAG, "male:halo")).isEqualTo("halo \u2642");
        assertThat(MetadataService.canonical(MetadataType.TAG, "  Female : Big Breasts ")).isEqualTo("big breasts \u2640");
        assertThat(MetadataService.canonical(MetadataType.TAG, "MALE:Big Penis")).isEqualTo("big penis \u2642");
    }

    @Test
    void shouldDropNamespacesThatAddNothingWhenCanonicalizingATag()
    {
        // WHEN + THEN
        assertThat(MetadataService.canonical(MetadataType.TAG, "other:full color")).isEqualTo("full color");
        assertThat(MetadataService.canonical(MetadataType.TAG, "mixed:group")).isEqualTo("group");
        assertThat(MetadataService.canonical(MetadataType.TAG, "location:school")).isEqualTo("school");
        assertThat(MetadataService.canonical(MetadataType.TAG, "temp:something")).isEqualTo("something");
    }

    @Test
    void shouldBeIdempotentWhenATagAlreadyCarriesASymbol()
    {
        // WHEN + THEN a tag gallery-dl already formatted, or one canonicalized before, stays as it is.
        assertThat(MetadataService.canonical(MetadataType.TAG, "Big Breasts \u2640")).isEqualTo("big breasts \u2640");
        assertThat(MetadataService.canonical(MetadataType.TAG, "female:big breasts \u2640")).isEqualTo("big breasts \u2640");
        String once = MetadataService.canonical(MetadataType.TAG, "female:halo");
        assertThat(MetadataService.canonical(MetadataType.TAG, once)).isEqualTo(once);
    }

    @Test
    void shouldLeaveOtherNamesAloneWhenCanonicalizing()
    {
        // WHEN + THEN an unlisted prefix, a namespace without a name and every other kind only fold.
        assertThat(MetadataService.canonical(MetadataType.TAG, "Re:Zero")).isEqualTo("re:zero");
        assertThat(MetadataService.canonical(MetadataType.TAG, "female:")).isEqualTo("female:");
        assertThat(MetadataService.canonical(MetadataType.TAG, "female")).isEqualTo("female");
        assertThat(MetadataService.canonical(MetadataType.ARTIST, "female:halo")).isEqualTo("female:halo");
        assertThat(MetadataService.canonical(MetadataType.PARODY, "Other:Thing")).isEqualTo("other:thing");
        assertThat(MetadataService.canonical(MetadataType.TAG, null)).isNull();
    }

    @Test
    void shouldGiveThePlainTagOfAGenderedOne()
    {
        assertThat(MetadataService.plainTagOf("halo \u2640")).contains("halo");
        assertThat(MetadataService.plainTagOf("big penis \u2642")).contains("big penis");
        assertThat(MetadataService.plainTagOf("halo")).isEmpty();
        assertThat(MetadataService.plainTagOf("\u2640")).isEmpty();
        assertThat(MetadataService.plainTagOf(" \u2640")).isEmpty();
        assertThat(MetadataService.plainTagOf(null)).isEmpty();
    }

    @Test
    void shouldCoverAPlainTagOnlyWhenItsGenderedVersionIsBesideIt()
    {
        var covered = MetadataService.plainTagsCoveredBy(
                java.util.List.of("halo \u2640", "halo", "wings", "horns \u2642", "horns", "tail \u2640", "tail \u2642"));
        assertThat(covered).containsExactlyInAnyOrder("halo", "horns", "tail");
        assertThat(MetadataService.plainTagsCoveredBy(java.util.List.of("wings", "\u2640"))).isEmpty();
    }
}

package io.github.mocchikon.hentie.service;

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
}

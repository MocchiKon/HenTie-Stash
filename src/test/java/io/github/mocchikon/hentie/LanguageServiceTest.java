package io.github.mocchikon.hentie;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mocchikon.hentie.service.LanguageService;

import static org.assertj.core.api.Assertions.*;

class LanguageServiceTest
{
    @Test
    void shouldReturnNonEmptyListWhenAccessingLanguages()
    {
        // WHEN + THEN
        assertThat(LanguageService.LANGUAGES).isNotEmpty();
    }

    @Test
    void shouldOrderPriorityLanguagesFirstWhenAccessingLanguages()
    {
        // GIVEN
        List<String> langs = LanguageService.LANGUAGES;

        // THEN
        assertThat(langs.get(0)).isEqualTo("English");
        assertThat(langs.get(1)).isEqualTo("Japanese");
        assertThat(langs.get(2)).isEqualTo("Chinese");
    }

    @Test
    void shouldHaveNoDuplicatesWhenAccessingLanguages()
    {
        // WHEN + THEN
        assertThat(LanguageService.LANGUAGES)
                .doesNotHaveDuplicates();
    }

    @Test
    void shouldSortRestAlphabeticallyWhenAccessingLanguages()
    {
        // GIVEN
        List<String> rest = LanguageService.LANGUAGES.subList(3, LanguageService.LANGUAGES.size());

        // THEN
        assertThat(rest).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    @Test
    void shouldReturnFirstNWhenSearchQueryIsNull()
    {
        // WHEN
        List<String> result = LanguageService.search(null, 5);

        // THEN
        assertThat(result).hasSize(5);
        assertThat(result).isEqualTo(LanguageService.LANGUAGES.subList(0, 5));
    }

    @Test
    void shouldReturnFirstNWhenSearchQueryIsBlank()
    {
        // WHEN
        List<String> result = LanguageService.search("  ", 3);

        // THEN
        assertThat(result).hasSize(3);
        assertThat(result).isEqualTo(LanguageService.LANGUAGES.subList(0, 3));
    }

    @Test
    void shouldMatchCaseInsensitivelyWhenSearching()
    {
        // WHEN
        List<String> engResult = LanguageService.search("ENG", 10);
        List<String> japResult = LanguageService.search("jap", 10);

        // THEN
        assertThat(engResult).contains("English");
        assertThat(japResult).contains("Japanese");
    }

    @Test
    void shouldReturnEmptyWhenSearchHasNoMatch()
    {
        // WHEN
        List<String> result = LanguageService.search("zzzznotlanguage", 10);

        // THEN
        assertThat(result).isEmpty();
    }

    @Test
    void shouldRespectLimitWhenSearching()
    {
        // GIVEN
        List<String> allMatches = LanguageService.LANGUAGES.stream()
                .filter(l -> l.toLowerCase().contains("a")).toList();
        // With 2 or fewer matches the assertion below would pass even if the limit were ignored.
        assertThat(allMatches.size()).isGreaterThan(2);

        // WHEN
        List<String> result = LanguageService.search("a", 2);

        // THEN
        assertThat(result).hasSize(2).isEqualTo(allMatches.subList(0, 2));
    }

    @Test
    void shouldReturnTrueWhenLanguageIsExactMatch()
    {
        // WHEN
        boolean englishKnown = LanguageService.isKnown("English");
        boolean japaneseKnown = LanguageService.isKnown("Japanese");

        // THEN
        assertThat(englishKnown).isTrue();
        assertThat(japaneseKnown).isTrue();
    }

    @Test
    void shouldReturnTrueWhenLanguageDiffersInCase()
    {
        // WHEN
        boolean lowerKnown = LanguageService.isKnown("english");
        boolean upperKnown = LanguageService.isKnown("JAPANESE");

        // THEN
        assertThat(lowerKnown).isTrue();
        assertThat(upperKnown).isTrue();
    }

    @Test
    void shouldReturnTrueWhenLanguageHasSurroundingWhitespace()
    {
        // WHEN
        boolean known = LanguageService.isKnown("  English  ");

        // THEN
        assertThat(known).isTrue();
    }

    @Test
    void shouldReturnFalseWhenLanguageIsUnknown()
    {
        // WHEN
        boolean known = LanguageService.isKnown("Klingon");

        // THEN
        assertThat(known).isFalse();
    }

    @Test
    void shouldReturnFalseWhenLanguageIsNull()
    {
        // WHEN
        boolean known = LanguageService.isKnown(null);

        // THEN
        assertThat(known).isFalse();
    }

    @Test
    void shouldReturnCanonicalSpellingWhenLanguageDiffersInCase()
    {
        // WHEN + THEN
        assertThat(LanguageService.canonical("english")).isEqualTo("English");
        assertThat(LanguageService.canonical("JAPANESE")).isEqualTo("Japanese");
        assertThat(LanguageService.canonical("  chinese ")).isEqualTo("Chinese");
    }

    @Test
    void shouldReturnInputStrippedWhenLanguageIsUnknown()
    {
        // WHEN + THEN
        assertThat(LanguageService.canonical("  Klingon  ")).isEqualTo("Klingon");
        assertThat(LanguageService.canonical("")).isEmpty();
    }

    @Test
    void shouldReturnNullWhenCanonicalizingNull()
    {
        // WHEN + THEN
        assertThat(LanguageService.canonical(null)).isNull();
    }
}

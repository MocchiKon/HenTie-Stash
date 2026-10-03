package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.dto.SearchType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChapterServiceUnitTest
{
    @Test
    void shouldReturnNullWhenScoreIsNull()
    {
        // WHEN
        Short result = ChapterService.toDbScore(null);

        // THEN
        assertThat(result).isNull();
    }

    @Test
    void shouldKeepValueWhenScoreIsInRange()
    {
        // WHEN
        Short low = ChapterService.toDbScore(1);
        Short mid = ChapterService.toDbScore(5);
        Short high = ChapterService.toDbScore(10);

        // THEN
        assertThat(low).isEqualTo((short) 1);
        assertThat(mid).isEqualTo((short) 5);
        assertThat(high).isEqualTo((short) 10);
    }

    @Test
    void shouldClampToOneWhenScoreBelowOne()
    {
        // WHEN
        Short zero = ChapterService.toDbScore(0);
        Short negative = ChapterService.toDbScore(-7);

        // THEN
        assertThat(zero).isEqualTo((short) 1);
        assertThat(negative).isEqualTo((short) 1);
    }

    @Test
    void shouldClampToTenWhenScoreAboveTen()
    {
        // WHEN
        Short justAbove = ChapterService.toDbScore(11);
        Short farAbove = ChapterService.toDbScore(999);

        // THEN
        assertThat(justAbove).isEqualTo((short) 10);
        assertThat(farAbove).isEqualTo((short) 10);
    }

    @Test
    void shouldBuildResultsUrlWithTypeWhenBuildingHref()
    {
        // WHEN
        String chapterHref = ChapterService.href(SearchType.CHAPTER, "tags", "5");
        String seriesHref = ChapterService.href(SearchType.SERIES, "artists", "9");

        // THEN
        assertThat(chapterHref).isEqualTo("/search/results?type=CHAPTER&tags=5");
        assertThat(seriesHref).isEqualTo("/search/results?type=SERIES&artists=9");
    }

    @Test
    void shouldUrlEncodeValueWhenBuildingHref()
    {
        // WHEN
        String spaceHref = ChapterService.href(SearchType.CHAPTER, "languages", "a b");
        String ampHref = ChapterService.href(SearchType.CHAPTER, "languages", "a&b");

        // THEN
        assertThat(spaceHref).isEqualTo("/search/results?type=CHAPTER&languages=a+b");
        assertThat(ampHref).isEqualTo("/search/results?type=CHAPTER&languages=a%26b");
    }
}

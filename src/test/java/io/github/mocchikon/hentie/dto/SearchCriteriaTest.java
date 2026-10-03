package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.Status;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SearchCriteria#hasFilter()} decides whether "Delete all matching" is offered; without a filter that
 * is the whole library. So every filtering field must count, and nothing else - not even a normalized value
 * that looks like a filter but matches every row.
 */
class SearchCriteriaTest
{
    @Test
    void shouldHaveNoFilterWhenOnlyTypePagingAndSortAreSet()
    {
        // GIVEN
        SearchCriteria criteria = new SearchCriteria();
        criteria.setType(SearchType.SERIES);
        criteria.setPage(4);
        criteria.setSize(10);
        criteria.setSortBy(SortBy.SCORE);
        criteria.setSortDir(Sort.Direction.ASC);
        criteria.setTitle("   ");

        // WHEN / THEN - a blank title narrows nothing either
        assertThat(criteria.hasFilter()).isFalse();
    }

    @Test
    void shouldNotCountAGalleryIdAsAFilterOnASeriesSearch()
    {
        // GIVEN a gallery id typed before switching the form to Series: the series spec ignores it
        SearchCriteria criteria = new SearchCriteria();
        criteria.setType(SearchType.SERIES);
        criteria.setGalleryId("mock:1");

        // WHEN / THEN the search is the whole library, so it must not count
        assertThat(criteria.hasFilter()).isFalse();
    }

    @Test
    void shouldHaveAFilterWhenAnyFilteringFieldIsSet()
    {
        // GIVEN one setter per filtering field
        List<Consumer<SearchCriteria>> filters = List.of(
                c -> c.setTitle("abc"),
                c -> c.setGalleryId("mock:1"),
                c -> c.getTagIds().add(1),
                c -> c.getArtistIds().add(1),
                c -> c.getCharacterIds().add(1),
                c -> c.getParodyIds().add(1),
                c -> c.getGroupIds().add(1),
                c -> c.getCategoryIds().add(1),
                c -> c.getExcludedTagIds().add(1),
                c -> c.getExcludedArtistIds().add(1),
                c -> c.getExcludedCharacterIds().add(1),
                c -> c.getExcludedParodyIds().add(1),
                c -> c.getExcludedGroupIds().add(1),
                c -> c.getExcludedCategoryIds().add(1),
                c -> c.getLanguages().add("English"),
                c -> c.getStatuses().add(Status.NEW),
                c -> c.setMinScore(2),
                c -> c.setMinPages(1),
                c -> c.setMaxPages(9),
                c -> c.setUploadFrom(LocalDate.of(2024, 1, 1)),
                c -> c.setUploadTo(LocalDate.of(2024, 1, 1)));

        // WHEN each is applied to an otherwise empty search, THEN it alone makes a filter - normalized too
        for (int i = 0; i < filters.size(); i++)
        {
            SearchCriteria criteria = new SearchCriteria();
            filters.get(i).accept(criteria);
            criteria.normalize();
            assertThat(criteria.hasFilter()).as("filter #%d", i).isTrue();
        }
    }

    @Test
    void shouldHaveNoFilterAfterNormalizingValuesThatMatchEveryRow()
    {
        // GIVEN one setter per value a request can carry that looks like a filter but matches every row
        List<Consumer<SearchCriteria>> matchAll = List.of(
                c -> c.getTagIds().add(null),
                c -> c.getArtistIds().add(null),
                c -> c.getCharacterIds().add(null),
                c -> c.getParodyIds().add(null),
                c -> c.getGroupIds().add(null),
                c -> c.getCategoryIds().add(null),
                c -> c.getExcludedTagIds().add(null),
                c -> c.getExcludedArtistIds().add(null),
                c -> c.getExcludedCharacterIds().add(null),
                c -> c.getExcludedParodyIds().add(null),
                c -> c.getExcludedGroupIds().add(null),
                c -> c.getExcludedCategoryIds().add(null),
                c -> c.getLanguages().add(" "),
                c -> c.getStatuses().add(null),
                c -> c.getStatuses().addAll(List.of(Status.values())),
                c -> c.setMinScore(0),
                c -> c.setMinPages(0));

        // WHEN each is normalized on an otherwise empty search, THEN it is no filter
        for (int i = 0; i < matchAll.size(); i++)
        {
            var criteria = new SearchCriteria();
            matchAll.get(i).accept(criteria);
            criteria.normalize();
            assertThat(criteria.hasFilter()).as("value #%d", i).isFalse();
        }
    }

    @Test
    void shouldGiveOneCacheKeyToSearchesThatDifferOnlyInValuesThatMatchEveryRow()
    {
        // GIVEN a plain search, and the same one carrying values that narrow nothing
        var plain = new SearchCriteria();
        plain.getTagIds().add(3);
        var noisy = new SearchCriteria();
        noisy.getTagIds().addAll(Arrays.asList(3, null));
        noisy.setMinScore(0);
        noisy.setMinPages(0);
        noisy.getStatuses().addAll(List.of(Status.values()));

        // WHEN
        plain.normalize();
        noisy.normalize();

        // THEN both count under one key
        assertThat(noisy.filterKey()).isEqualTo(plain.filterKey());
    }

    @Test
    void shouldKeepGenuineFiltersAndDropExcludedIdsAlsoIncludedWhenNormalizing()
    {
        // GIVEN
        var criteria = new SearchCriteria();
        criteria.getTagIds().add(1);
        criteria.getExcludedTagIds().addAll(List.of(1, 2));
        criteria.getStatuses().addAll(List.of(Status.NEW, Status.REVIEWED));
        criteria.getLanguages().add("English");
        criteria.setMinScore(4);
        criteria.setMinPages(5);

        // WHEN
        criteria.normalize();

        // THEN only the included-and-excluded id goes; everything else still narrows the search
        assertThat(criteria.getExcludedTagIds()).containsExactly(2);
        assertThat(criteria.getStatuses()).containsExactly(Status.NEW, Status.REVIEWED);
        assertThat(criteria.getLanguages()).containsExactly("English");
        assertThat(criteria.getMinScore()).isEqualTo(4);
        assertThat(criteria.getMinPages()).isEqualTo(5);
    }
}

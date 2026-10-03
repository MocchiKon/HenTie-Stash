package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.CardDto;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.dto.SearchType;
import io.github.mocchikon.hentie.dto.SortBy;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.*;
import io.github.mocchikon.hentie.repository.spec.ChapterSpecifications;
import io.github.mocchikon.hentie.repository.spec.CompoundSearchQuery;
import io.github.mocchikon.hentie.repository.spec.SeriesSpecifications;
import io.github.mocchikon.hentie.service.SearchService;
import io.github.mocchikon.hentie.service.TitleSearchIndex;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The native queries must return exactly what the Criteria spec returns: the same rows in the same order and
 * the same total, on every page and for every sort. With {@code large-list-rows=1} every value counts as
 * broad, so the planner sends each search below to {@link CompoundSearchQuery}; the spec as it was before any
 * planning is the reference. Ties in every sort column and missing scores check the id tiebreak and "missing
 * last".
 */
@SpringBootTest(properties = "app.search.large-list-rows=1")
@Transactional
class CompoundSearchIT
{
    private static final int PAGE_SIZE = 4;
    private static final int PAGES = 3;

    @Autowired SearchService searchService;
    @Autowired TitleSearchIndex titleSearchIndex;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired CacheManager cacheManager;
    @PersistenceContext EntityManager em;

    private Fixture fixture;

    /** The ids the searches filter on, created per test. */
    record Fixture(int tagA, int tagB, int tagC, int tagExcluded, int artist, int category)
    {
    }

    @BeforeEach
    void seed()
    {
        Tag a = tag("cmp-a");
        Tag b = tag("cmp-b");
        Tag c = tag("cmp-c");
        Tag excluded = tag("cmp-excluded");
        Artist artist = artist("cmp-artist");
        Category category = category("cmp-category");
        for (int i = 1; i <= 40; i++)
        {
            chapter(i, a, b, c, excluded, artist, category);
        }
        for (int i = 1; i <= 20; i++)
        {
            series(i, a, b, excluded, category);
        }
        em.flush();
        // Rolled-back ids are handed out again, so a count cached by an earlier test could match a new filter.
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();
        fixture = new Fixture(a.getId(), b.getId(), c.getId(), excluded.getId(), artist.getId(), category.getId());
    }

    static Stream<Arguments> chapterSearches()
    {
        return Stream.of(
                search("one tag", (f, c) -> c.setTagIds(ids(f.tagA()))),
                search("two tags", (f, c) -> c.setTagIds(ids(f.tagA(), f.tagB()))),
                search("tag and artist", (f, c) ->
                {
                    c.setTagIds(ids(f.tagA()));
                    c.setArtistIds(ids(f.artist()));
                }),
                search("one category", (f, c) -> c.setCategoryIds(ids(f.category()))),
                search("tag and category", (f, c) ->
                {
                    c.setTagIds(ids(f.tagB()));
                    c.setCategoryIds(ids(f.category()));
                }),
                search("tag minus a category", (f, c) ->
                {
                    c.setTagIds(ids(f.tagA()));
                    c.setExcludedCategoryIds(ids(f.category()));
                }),
                search("excluded category only", (f, c) -> c.setExcludedCategoryIds(ids(f.category()))),
                search("tag minus a tag", (f, c) ->
                {
                    c.setTagIds(ids(f.tagB()));
                    c.setExcludedTagIds(ids(f.tagExcluded()));
                }),
                search("excluded tag only", (f, c) -> c.setExcludedTagIds(ids(f.tagExcluded()))),
                search("excluded tag, one status", (f, c) ->
                {
                    c.setExcludedTagIds(ids(f.tagExcluded()));
                    c.setStatuses(List.of(Status.NEW));
                }),
                search("excluded tag, minimum score", (f, c) ->
                {
                    c.setExcludedTagIds(ids(f.tagExcluded()));
                    c.setMinScore(3);
                }),
                search("tag, one status", (f, c) ->
                {
                    c.setTagIds(ids(f.tagA()));
                    c.setStatuses(List.of(Status.NEW));
                }),
                search("tag, two statuses", (f, c) ->
                {
                    c.setTagIds(ids(f.tagA()));
                    c.setStatuses(List.of(Status.NEW, Status.REVIEWED));
                }),
                search("tag, one language", (f, c) ->
                {
                    c.setTagIds(ids(f.tagB()));
                    c.setLanguages(List.of("English"));
                }),
                search("tag, two languages", (f, c) ->
                {
                    c.setTagIds(ids(f.tagB()));
                    c.setLanguages(List.of("English", "Japanese"));
                }),
                search("tag, score and page range", (f, c) ->
                {
                    c.setTagIds(ids(f.tagB()));
                    c.setMinScore(4);
                    c.setMinPages(10);
                    c.setMaxPages(30);
                }),
                search("tag, upload date range", (f, c) ->
                {
                    c.setTagIds(ids(f.tagB()));
                    c.setUploadFrom(LocalDate.of(2021, 1, 2));
                    c.setUploadTo(LocalDate.of(2021, 1, 4));
                }),
                search("tag, gallery id", (f, c) ->
                {
                    c.setTagIds(ids(f.tagA()));
                    c.setGalleryId("cmp:4");
                }),
                search("title term", (f, c) -> c.setTitle("zzkey")),
                search("title term and tag", (f, c) ->
                {
                    c.setTitle("zzkey");
                    c.setTagIds(ids(f.tagC()));
                }));
    }

    static Stream<Arguments> seriesSearches()
    {
        return Stream.of(
                search("one tag", (f, c) -> c.setTagIds(ids(f.tagA()))),
                search("two tags", (f, c) -> c.setTagIds(ids(f.tagA(), f.tagB()))),
                search("tag minus a tag", (f, c) ->
                {
                    c.setTagIds(ids(f.tagB()));
                    c.setExcludedTagIds(ids(f.tagExcluded()));
                }),
                search("excluded tag only", (f, c) -> c.setExcludedTagIds(ids(f.tagExcluded()))),
                search("one category", (f, c) -> c.setCategoryIds(ids(f.category()))),
                search("tag minus a category", (f, c) ->
                {
                    c.setTagIds(ids(f.tagB()));
                    c.setExcludedCategoryIds(ids(f.category()));
                }),
                search("one language", (f, c) -> c.setLanguages(List.of("English"))),
                search("two languages", (f, c) -> c.setLanguages(List.of("English", "Japanese"))),
                search("tag and language", (f, c) ->
                {
                    c.setTagIds(ids(f.tagA()));
                    c.setLanguages(List.of("Japanese"));
                }),
                search("tag, one status", (f, c) ->
                {
                    c.setTagIds(ids(f.tagB()));
                    c.setStatuses(List.of(Status.REVIEWED));
                }),
                search("tag, score and created date", (f, c) ->
                {
                    c.setTagIds(ids(f.tagB()));
                    c.setMinScore(5);
                    c.setUploadFrom(LocalDate.of(2021, 1, 2));
                }),
                search("title term and tag", (f, c) ->
                {
                    c.setTitle("zzkey");
                    c.setTagIds(ids(f.tagB()));
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chapterSearches")
    void shouldReturnWhatTheCriteriaSpecReturnsForEveryChapterPageAndSort(String label,
                                                                          CriteriaSetup setup)
    {
        for (SearchCriteria c : everyPageAndSort(setup, SearchType.CHAPTER))
        {
            // GIVEN
            var phrase = titleSearchIndex.phraseFor(Objects.toString(c.getTitle(), "")).orElse(null);
            Page<Chapter> expected = chapterRepository.findAll(ChapterSpecifications.from(c, phrase),
                    PageRequest.of(c.getPage(), PAGE_SIZE, sortOf(c)));

            // WHEN
            Page<CardDto> actual = searchService.search(c);

            // THEN
            assertThat(actual.getContent()).as(describe(c)).extracting(CardDto::getId)
                    .containsExactlyElementsOf(expected.getContent().stream().map(Chapter::getId).toList());
            assertThat(actual.getTotalElements()).as(describe(c)).isEqualTo(expected.getTotalElements());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("seriesSearches")
    void shouldReturnWhatTheCriteriaSpecReturnsForEverySeriesPageAndSort(String label,
                                                                         CriteriaSetup setup)
    {
        for (SearchCriteria c : everyPageAndSort(setup, SearchType.SERIES))
        {
            // GIVEN
            var phrase = titleSearchIndex.phraseFor(Objects.toString(c.getTitle(), "")).orElse(null);
            Page<Series> expected = seriesRepository.findAll(SeriesSpecifications.from(c, phrase),
                    PageRequest.of(c.getPage(), PAGE_SIZE, sortOf(c)));

            // WHEN
            Page<CardDto> actual = searchService.search(c);

            // THEN
            assertThat(actual.getContent()).as(describe(c)).extracting(CardDto::getId)
                    .containsExactlyElementsOf(expected.getContent().stream().map(Series::getId).toList());
            assertThat(actual.getTotalElements()).as(describe(c)).isEqualTo(expected.getTotalElements());
        }
    }

    /**
     * The native count also has a shape that reads a single status from its owner rows (beside a small
     * value); the planner never picks it here, so it is compared directly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("chapterSearches")
    void shouldCountWhatTheCriteriaSpecCountsWhicheverWayOwnerFiltersAreRead(String label, CriteriaSetup setup)
    {
        // GIVEN
        var c = new SearchCriteria();
        setup.apply(fixture, c);
        var phrase = titleSearchIndex.phraseFor(Objects.toString(c.getTitle(), "")).orElse(null);
        long expected = chapterRepository.count(ChapterSpecifications.from(c, phrase));

        // WHEN
        long streamed = nativeCount(CompoundSearchQuery.count(c, phrase, true));
        long filtered = nativeCount(CompoundSearchQuery.count(c, phrase, false));

        // THEN
        assertThat(streamed).isEqualTo(expected);
        assertThat(filtered).isEqualTo(expected);
    }

    @FunctionalInterface
    interface CriteriaSetup
    {
        void apply(Fixture fixture, SearchCriteria criteria);
    }

    private static Arguments search(String label, CriteriaSetup setup)
    {
        return Arguments.of(label, setup);
    }

    private List<SearchCriteria> everyPageAndSort(CriteriaSetup setup, SearchType type)
    {
        List<SearchCriteria> all = new ArrayList<>();
        for (SortBy sortBy : SortBy.values())
        {
            for (Sort.Direction direction : Sort.Direction.values())
            {
                for (int page = 0; page < PAGES; page++)
                {
                    var c = new SearchCriteria();
                    c.setType(type);
                    setup.apply(fixture, c);
                    c.setSortBy(sortBy);
                    c.setSortDir(direction);
                    c.setPage(page);
                    c.setSize(PAGE_SIZE);
                    all.add(c);
                }
            }
        }
        return all;
    }

    /** The sort the results page asks for, spelled independently of SearchService. */
    private static Sort sortOf(SearchCriteria c)
    {
        Sort.Direction dir = c.getSortDir();
        return switch (c.getSortBy())
        {
            case SCORE -> Sort.by(new Sort.Order(dir, "score", Sort.NullHandling.NULLS_LAST), new Sort.Order(dir, "id"));
            case PAGE_NUM -> Sort.by(new Sort.Order(dir, "pageNum"), new Sort.Order(dir, "id"));
            case DISK_SIZE -> Sort.by(new Sort.Order(dir, "diskSize"), new Sort.Order(dir, "id"));
            case DATE -> Sort.by(dir, "id");
        };
    }

    private static String describe(SearchCriteria c)
    {
        return c.getSortBy() + " " + c.getSortDir() + ", page " + c.getPage();
    }

    private long nativeCount(CompoundSearchQuery.Query query)
    {
        var nativeQuery = em.createNativeQuery(query.sql());
        for (int i = 0; i < query.params().size(); i++)
        {
            nativeQuery.setParameter(i + 1, query.params().get(i));
        }
        return ((Number) nativeQuery.getSingleResult()).longValue();
    }

    private static List<Integer> ids(Integer... ids)
    {
        return new ArrayList<>(List.of(ids));
    }

    private void chapter(int i, Tag a, Tag b, Tag c, Tag excluded, Artist artist, Category category)
    {
        var chapter = new Chapter();
        chapter.setTitleFull("cmp chapter " + i + (i % 3 == 0 ? " zzkey" : ""));
        chapter.setTitle(chapter.getTitleFull());
        chapter.setNativeTitle(i % 8 == 0 ? "zzkey native" : null);   // the term also matches native titles
        chapter.setUploadDate(LocalDate.of(2021, 1, 1).plusDays(i % 6));
        chapter.setLanguage(i % 2 == 0 ? "English" : "Japanese");
        chapter.setStatus(Status.values()[i % 3]);
        chapter.setScore(i % 6 == 0 ? null : (short) (i % 10 + 1));
        chapter.setPageNum(5 + (i % 4) * 10);
        chapter.setDiskSize(1000L * (i % 5));
        chapter.setGalleryId("cmp:" + i);
        chapter.setTags(tagsWhere(i % 2 == 0, a, i % 3 != 0, b, i % 5 < 3, c, i % 7 == 0, excluded));
        chapter.setArtists(new ArrayList<>(i % 4 == 0 ? List.of(artist) : List.of()));
        chapter.setCategories(new ArrayList<>(i % 3 == 1 ? List.of(category) : List.of()));
        chapterRepository.save(chapter);
    }

    private void series(int i, Tag a, Tag b, Tag excluded, Category category)
    {
        var series = new Series();
        series.setTitleFull("cmp series " + i + (i % 3 == 0 ? " zzkey" : ""));
        series.setTitle(series.getTitleFull());
        series.setCreatedDate(LocalDate.of(2021, 1, 1).plusDays(i % 5));
        series.setStatus(Status.values()[i % 3]);
        series.setScore(i % 5 == 0 ? null : (short) (i % 10 + 1));
        series.setScoreSource(i % 5 == 0 ? ScoreSource.DERIVED : ScoreSource.USER_SET);
        series.setPageNum(10 + (i % 3) * 10);
        series.setDiskSize(500L * (i % 4));
        series.setEffectiveTags(tagsWhere(i % 2 == 0, a, i % 3 != 0, b, i % 5 == 0, excluded));
        series.setEffectiveCategories(new ArrayList<>(i % 2 == 1 ? List.of(category) : List.of()));
        series.setEffectiveLanguages(new ArrayList<>(i % 3 == 0
                ? List.of("English", "Japanese")
                : List.of(i % 2 == 0 ? "English" : "Japanese")));
        seriesRepository.save(series);
    }

    /** Pairs of (condition, tag): the tags whose condition holds. */
    private static List<Tag> tagsWhere(Object... conditionsAndTags)
    {
        List<Tag> tags = new ArrayList<>();
        for (int i = 0; i < conditionsAndTags.length; i += 2)
        {
            if ((Boolean) conditionsAndTags[i])
            {
                tags.add((Tag) conditionsAndTags[i + 1]);
            }
        }
        return tags;
    }

    private Tag tag(String name)
    {
        var tag = new Tag();
        tag.setName(name);
        return tagRepository.save(tag);
    }

    private Artist artist(String name)
    {
        var artist = new Artist();
        artist.setName(name);
        return artistRepository.save(artist);
    }

    private Category category(String name)
    {
        var category = new Category();
        category.setName(name);
        return categoryRepository.save(category);
    }
}

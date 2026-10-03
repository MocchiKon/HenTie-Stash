package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.*;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.SearchService;
import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SearchServiceIT
{
    @Autowired SearchService searchService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired SettingsService settingsService;
    @Autowired CacheManager cacheManager;
    @PersistenceContext EntityManager em;

    @AfterEach
    void resetTitleMode()
    {
        // The settings cache is a shared singleton the rollback does not reach.
        settingsService.setTitleDisplayMode(TitleDisplayMode.FULL);
    }

    private Chapter chapter(String full, String pretty, String nativeTitle, Short score)
    {
        Chapter c = new Chapter();
        c.setTitleFull(full);
        c.setTitle(pretty);
        c.setNativeTitle(nativeTitle);
        c.setUploadDate(LocalDate.of(2021, 1, 1));
        c.setLanguage("English");
        c.setScore(score);
        return chapterRepository.save(c);
    }

    @Test
    void shouldReturnChapterCardsLinkingToTheChapterWhenSearching()
    {
        // GIVEN
        Chapter c = chapter("Findable Full", "Findable Pretty", "n", null);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("Findable");
        Page<CardDto> page = searchService.search(sc);

        // THEN
        assertThat(page.getContent()).anySatisfy(card ->
                assertThat(card.getHref()).isEqualTo("/chapter/" + c.getId()));
    }

    @Test
    void shouldUseTitleFullWhenTitleDisplayModeFull()
    {
        // GIVEN
        settingsService.setTitleDisplayMode(TitleDisplayMode.FULL);
        chapter("Full-X Complete", "Pretty-X", "Native-X", null);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("X");
        List<CardDto> results = searchService.search(sc).getContent();

        // THEN
        assertThat(results)
                .extracting(CardDto::getCaption).contains("Full-X Complete");
    }

    @Test
    void shouldUseTitleWithFallbackWhenTitleDisplayModePretty()
    {
        // GIVEN
        settingsService.setTitleDisplayMode(TitleDisplayMode.PRETTY);
        chapter("Full-Y", "Pretty-Y", "Native-Y", null);
        chapter("Full-Y-nopretty", "", "Native-Y", null);   // blank pretty -> fall back to full
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("Y");
        List<CardDto> results = searchService.search(sc).getContent();

        // THEN
        assertThat(results)
                .extracting(CardDto::getCaption)
                .contains("Pretty-Y", "Full-Y-nopretty");
    }

    @Test
    void shouldUseNativeTitleWhenTitleDisplayModeNative()
    {
        // GIVEN
        settingsService.setTitleDisplayMode(TitleDisplayMode.NATIVE);
        chapter("Full-Z", "Pretty-Z", "Native-Z", null);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("Z");
        List<CardDto> results = searchService.search(sc).getContent();

        // THEN
        assertThat(results)
                .extracting(CardDto::getCaption).contains("Native-Z");
    }

    @Test
    void shouldPutNullsLastWhenSortingByScoreDesc()
    {
        // GIVEN
        chapter("score-hi", "score-hi", "n", (short) 9);
        chapter("score-none", "score-none", "n", null);
        chapter("score-mid", "score-mid", "n", (short) 5);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("score-");
        sc.setSortBy(SortBy.SCORE);
        sc.setSortDir(Sort.Direction.DESC);
        List<CardDto> results = searchService.search(sc).getContent();

        // THEN
        assertThat(results)
                .extracting(CardDto::getCaption)
                .containsExactly("score-hi", "score-mid", "score-none");
    }

    @Test
    void shouldPutNullsLastWhenSortingByScoreDescForSeriesToo()
    {
        // GIVEN
        series("srt-hi", (short) 9);
        series("srt-none", null);
        series("srt-mid", (short) 5);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setTitle("srt-");
        sc.setSortBy(SortBy.SCORE);
        sc.setSortDir(Sort.Direction.DESC);
        List<CardDto> results = searchService.search(sc).getContent();

        // THEN
        assertThat(results)
                .extracting(CardDto::getCaption)
                .containsExactly("srt-hi", "srt-mid", "srt-none");
    }

    /**
     * Page count and disk size order the fixture <i>differently</i>, so sorting on the wrong column fails as
     * loudly as sorting in the wrong direction; every value is distinct, since ties would hide either bug.
     */
    @ParameterizedTest(name = "chapters by {0} {1} -> {2}")
    @CsvSource({
            "PAGE_NUM,  ASC,  'cstat-b,cstat-c,cstat-a'",
            "PAGE_NUM,  DESC, 'cstat-a,cstat-c,cstat-b'",
            "DISK_SIZE, ASC,  'cstat-a,cstat-c,cstat-b'",
            "DISK_SIZE, DESC, 'cstat-b,cstat-c,cstat-a'"
    })
    void shouldSortChaptersByTheChosenStatInTheChosenDirection(SortBy sortBy, Sort.Direction dir, String expected)
    {
        // GIVEN chapters whose page counts (30/10/20) and disk sizes (100/300/200) disagree on order.
        chapterWithStats("cstat-a", 30, 100L);
        chapterWithStats("cstat-b", 10, 300L);
        chapterWithStats("cstat-c", 20, 200L);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("cstat-");
        sc.setSortBy(sortBy);
        sc.setSortDir(dir);
        List<CardDto> results = searchService.search(sc).getContent();

        // THEN
        assertThat(results).extracting(CardDto::getCaption).containsExactly(expected.split(","));
    }

    /** The same matrix for <b>series</b>, over their materialized totals. */
    @ParameterizedTest(name = "series by {0} {1} -> {2}")
    @CsvSource({
            "PAGE_NUM,  ASC,  'sstat-b,sstat-c,sstat-a'",
            "PAGE_NUM,  DESC, 'sstat-a,sstat-c,sstat-b'",
            "DISK_SIZE, ASC,  'sstat-a,sstat-c,sstat-b'",
            "DISK_SIZE, DESC, 'sstat-b,sstat-c,sstat-a'"
    })
    void shouldSortSeriesByTheChosenStatInTheChosenDirection(SortBy sortBy, Sort.Direction dir, String expected)
    {
        // GIVEN series whose page totals (30/10/20) and disk totals (100/300/200) disagree on order.
        seriesWithStats("sstat-a", 30, 100L);
        seriesWithStats("sstat-b", 10, 300L);
        seriesWithStats("sstat-c", 20, 200L);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setTitle("sstat-");
        sc.setSortBy(sortBy);
        sc.setSortDir(dir);
        List<CardDto> results = searchService.search(sc).getContent();

        // THEN
        assertThat(results).extracting(CardDto::getCaption).containsExactly(expected.split(","));
    }

    /** The id tiebreaker must follow the sort's direction, or the order within an equal-stat group flips. */
    @ParameterizedTest(name = "ties by page count {0} -> {1}")
    @CsvSource({
            "ASC,  'ctie-a,ctie-b,ctie-c'",
            "DESC, 'ctie-c,ctie-b,ctie-a'"
    })
    void shouldBreakStatTiesByIdInTheSortDirection(Sort.Direction dir, String expected)
    {
        // GIVEN three chapters with identical (zero) stats, created oldest-first.
        chapterWithStats("ctie-a", 0, 0L);
        chapterWithStats("ctie-b", 0, 0L);
        chapterWithStats("ctie-c", 0, 0L);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("ctie-");
        sc.setSortBy(SortBy.PAGE_NUM);
        sc.setSortDir(dir);
        List<CardDto> results = searchService.search(sc).getContent();

        // THEN
        assertThat(results).extracting(CardDto::getCaption).containsExactly(expected.split(","));
    }

    @Test
    void shouldCountTagPlusPageFilterAgreeingWithMembership()
    {
        // GIVEN two chapters with the same tag but different page counts. page_num is an owner-row column,
        // so the count must filter the owner rows, not only count the tag's links.
        Tag t = tag("pgc-tag");
        Chapter small = chapterWith("pgc-small", List.of(t), List.of());
        small.setPageNum(5);
        chapterRepository.save(small);
        Chapter big = chapterWith("pgc-big", List.of(t), List.of());
        big.setPageNum(50);
        chapterRepository.save(big);
        em.flush();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTagIds(new ArrayList<>(List.of(t.getId())));
        sc.setMinPages(10);
        Page<CardDto> page = searchService.search(sc);

        // THEN only the 50-page chapter matches, and the count agrees.
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(CardDto::getHref).containsExactly("/chapter/" + big.getId());
    }

    @Test
    void shouldClampToFirstPageWhenPageNegative()
    {
        // GIVEN
        chapter("neg-page-probe", "p", "n", null);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("neg-page-probe");
        sc.setPage(-5);

        Page<CardDto> page = searchService.search(sc);

        // THEN
        assertThat(page.getNumber()).isZero();
        assertThat(page.getContent()).extracting(CardDto::getCaption).contains("neg-page-probe");
    }

    @Test
    void shouldReturnEmptyContentWhenPageBeyondLastPage()
    {
        // GIVEN
        chapter("beyond-page-probe", "p", "n", null);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("beyond-page-probe");
        sc.setPage(999);
        sc.setSize(10);

        Page<CardDto> page = searchService.search(sc);

        // THEN
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void shouldUseConfiguredDefaultPageSizeWhenSizeZero()
    {
        // GIVEN
        chapter("page-size-probe", "p", "n", null);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setSize(0);
        int size = searchService.search(sc).getSize();

        // THEN
        assertThat(size).isEqualTo(settingsService.getSearchPageSize());
    }

    @Test
    void shouldOverrideTheDefaultWhenExplicitSizeGiven()
    {
        // GIVEN
        chapter("explicit-size-probe", "p", "n", null);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setSize(7);
        int size = searchService.search(sc).getSize();

        // THEN
        assertThat(size).isEqualTo(7);
    }

    @Test
    void shouldReturnSeriesCardsWhenSeriesSearch()
    {
        // GIVEN
        Series s = new Series();
        s.setTitleFull("Searchable Series");
        s.setTitle("Searchable Series");              // title_pretty is NOT NULL
        s.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        s = seriesRepository.save(s);
        em.flush();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setTitle("Searchable Series");
        Page<CardDto> page = searchService.search(sc);

        // THEN
        final int id = s.getId();
        assertThat(page.getContent()).anySatisfy(card ->
                assertThat(card.getHref()).isEqualTo("/series/" + id));
    }

    @Test
    void shouldMatchSeriesByEffectiveLanguageAcrossContentAndCount()
    {
        // GIVEN series with effective languages. A title too short for the index leaves the search to the
        // spec with no value to start from, where the content page (correlated EXISTS) and the count
        // (non-correlated IN) take different shapes, and must agree.
        Series jp = seriesWithLanguages("elang-jp-qx", "Japanese");
        Series en = seriesWithLanguages("elang-en-qx", "English");
        Series mixed = seriesWithLanguages("elang-mixed-qx", "English", "Japanese");
        em.flush();
        // The count cache is not rolled back with the tx.
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setTitle("qx");
        sc.setLanguages(List.of("Japanese"));
        Page<CardDto> page = searchService.search(sc);

        // THEN the JP-only and mixed series match; the count query (IN) agrees with the content (EXISTS).
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(CardDto::getHref)
                .containsExactlyInAnyOrder("/series/" + jp.getId(), "/series/" + mixed.getId())
                .doesNotContain("/series/" + en.getId());
    }

    private Series seriesWithLanguages(String titleFull, String... languages)
    {
        Series s = new Series();
        s.setTitleFull(titleFull);
        s.setTitle(titleFull);                        // title_pretty is NOT NULL (falls back to titleFull)
        s.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        s.setEffectiveLanguages(new ArrayList<>(List.of(languages)));
        return seriesRepository.save(s);
    }

    @Test
    void shouldResolveIdsToLabelsInOrderWhenResolvingSelected()
    {
        // GIVEN
        Tag a = tag("resolve-a");
        Tag b = tag("resolve-b");
        em.flush();
        // The tags bypassed MetadataService, so nothing evicted the cached list.
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.METADATA)).clear();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTagIds(new ArrayList<>(List.of(b.getId(), a.getId())));
        SelectedFilters selected = searchService.resolveSelected(sc);

        // THEN
        assertThat(selected.getTags()).extracting("label").containsExactly("resolve-b", "resolve-a");
        assertThat(selected.getArtists()).isEmpty();
    }

    @Test
    void shouldCountMultiValueTagChapterSearchAgreeingWithMembership()
    {
        // GIVEN chapters with different tag combinations, searched by the tags alone and with a title matching
        // every seeded chapter. CompoundSearchIT compares the native queries with the spec.
        Tag a = tag("isect-a");
        Tag b = tag("isect-b");
        Tag c = tag("isect-c");
        Chapter cab = chapterWith("isect-c-ab", List.of(a, b), List.of());
        chapterWith("isect-c-a", List.of(a), List.of());          // only a
        chapterWith("isect-c-b", List.of(b), List.of());          // only b
        Chapter cabc = chapterWith("isect-c-abc", List.of(a, b, c), List.of());
        em.flush();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();

        // WHEN
        SearchCriteria alone = new SearchCriteria();
        alone.setTagIds(new ArrayList<>(List.of(a.getId(), b.getId())));
        Page<CardDto> aloneResult = searchService.search(alone);

        // AND the same result set with a title too
        SearchCriteria criteria = new SearchCriteria();
        criteria.setTagIds(new ArrayList<>(List.of(a.getId(), b.getId())));
        criteria.setTitle("isect-c-");
        Page<CardDto> withTitleResult = searchService.search(criteria);

        // THEN only the chapters carrying BOTH a and b match, either way.
        assertThat(aloneResult.getTotalElements()).isEqualTo(2);
        assertThat(withTitleResult.getTotalElements()).isEqualTo(2);
        assertThat(aloneResult.getContent()).extracting(CardDto::getHref)
                .containsExactlyInAnyOrder("/chapter/" + cab.getId(), "/chapter/" + cabc.getId());
    }

    @Test
    void shouldCountCrossFacetChapterSearchAgreeingWithMembership()
    {
        // GIVEN a tag AND an artist, two join tables, searched alone and with a title.
        Tag t = tag("xf-tag");
        Artist ar = artist("xf-artist");
        Chapter both = chapterWith("xf-both", List.of(t), List.of(ar));
        chapterWith("xf-tag-only", List.of(t), List.of());
        chapterWith("xf-artist-only", List.of(), List.of(ar));
        em.flush();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();

        SearchCriteria alone = new SearchCriteria();
        alone.setTagIds(new ArrayList<>(List.of(t.getId())));
        alone.setArtistIds(new ArrayList<>(List.of(ar.getId())));
        Page<CardDto> aloneResult = searchService.search(alone);

        SearchCriteria criteria = new SearchCriteria();
        criteria.setTagIds(new ArrayList<>(List.of(t.getId())));
        criteria.setArtistIds(new ArrayList<>(List.of(ar.getId())));
        criteria.setTitle("xf-");
        Page<CardDto> withTitleResult = searchService.search(criteria);

        assertThat(aloneResult.getTotalElements()).isEqualTo(1);
        assertThat(withTitleResult.getTotalElements()).isEqualTo(1);
        assertThat(aloneResult.getContent()).extracting(CardDto::getHref)
                .containsExactly("/chapter/" + both.getId());
    }

    @Test
    void shouldCountMultiValueTagSeriesSearchAgreeingWithMembership()
    {
        // GIVEN series with effective tags, searched by the tags alone and with a title.
        Tag a = tag("sisect-a");
        Tag b = tag("sisect-b");
        Tag c = tag("sisect-c");
        Series sab = seriesWithEffectiveTags("sisect-ab", a, b);
        seriesWithEffectiveTags("sisect-a", a);
        Series sabc = seriesWithEffectiveTags("sisect-abc", a, b, c);
        em.flush();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();

        SearchCriteria alone = new SearchCriteria();
        alone.setType(SearchType.SERIES);
        alone.setTagIds(new ArrayList<>(List.of(a.getId(), b.getId())));
        Page<CardDto> aloneResult = searchService.search(alone);

        SearchCriteria criteria = new SearchCriteria();
        criteria.setType(SearchType.SERIES);
        criteria.setTagIds(new ArrayList<>(List.of(a.getId(), b.getId())));
        criteria.setTitle("sisect-");
        Page<CardDto> withTitleResult = searchService.search(criteria);

        assertThat(aloneResult.getTotalElements()).isEqualTo(2);
        assertThat(withTitleResult.getTotalElements()).isEqualTo(2);
        assertThat(aloneResult.getContent()).extracting(CardDto::getHref)
                .containsExactlyInAnyOrder("/series/" + sab.getId(), "/series/" + sabc.getId());
    }

    @Test
    void shouldCountSingleValueTagChapterSearchViaBareCountAgreeingWithCriteria()
    {
        // GIVEN one tag only: the native count(distinct) path must agree with the Criteria count.
        Tag t = tag("single-a");
        Chapter c1 = chapterWith("single-c1", List.of(t), List.of());
        Chapter c2 = chapterWith("single-c2", List.of(t), List.of());
        chapterWith("single-none", List.of(), List.of());
        em.flush();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();

        SearchCriteria bare = new SearchCriteria();
        bare.setTagIds(new ArrayList<>(List.of(t.getId())));
        Page<CardDto> viaBare = searchService.search(bare);

        SearchCriteria criteria = new SearchCriteria();
        criteria.setTagIds(new ArrayList<>(List.of(t.getId())));
        criteria.setTitle("single-c");   // a second value: the Criteria count (matches both tagged chapters)
        Page<CardDto> withTitleResult = searchService.search(criteria);

        assertThat(viaBare.getTotalElements()).isEqualTo(2);
        assertThat(withTitleResult.getTotalElements()).isEqualTo(2);
        assertThat(viaBare.getContent()).extracting(CardDto::getHref)
                .containsExactlyInAnyOrder("/chapter/" + c1.getId(), "/chapter/" + c2.getId());
    }

    @Test
    void shouldCountPureLanguageSeriesSearchViaBareCountAgreeingWithCriteria()
    {
        // GIVEN a language-only series search, whose count roots on series_effective_languages.
        Series jp = seriesWithLanguages("plang-jp", "Japanese");
        seriesWithLanguages("plang-en", "English");
        Series mixed = seriesWithLanguages("plang-mixed", "English", "Japanese");
        em.flush();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();

        SearchCriteria bare = new SearchCriteria();
        bare.setType(SearchType.SERIES);
        bare.setLanguages(List.of("Japanese"));
        Page<CardDto> viaBare = searchService.search(bare);

        SearchCriteria criteria = new SearchCriteria();
        criteria.setType(SearchType.SERIES);
        criteria.setLanguages(List.of("Japanese"));
        criteria.setTitle("plang-");   // a second value: the Criteria count (same result set)
        Page<CardDto> withTitleResult = searchService.search(criteria);

        assertThat(viaBare.getTotalElements()).isEqualTo(2);
        assertThat(withTitleResult.getTotalElements()).isEqualTo(2);
        assertThat(viaBare.getContent()).extracting(CardDto::getHref)
                .containsExactlyInAnyOrder("/series/" + jp.getId(), "/series/" + mixed.getId());
    }

    @Test
    void shouldCountIncludedMinusExcludedChapterSearchAgreeingWithMembership()
    {
        // GIVEN an included tag plus an excluded tag and artist, searched alone and with a title.
        Tag wanted = tag("exc-wanted");
        Tag bannedTag = tag("exc-banned-tag");
        Artist bannedArtist = artist("exc-banned-artist");
        Chapter kept1 = chapterWith("exc-kept-1", List.of(wanted), List.of());
        Chapter kept2 = chapterWith("exc-kept-2", List.of(wanted), List.of());
        chapterWith("exc-drop-tag", List.of(wanted, bannedTag), List.of());
        chapterWith("exc-drop-artist", List.of(wanted), List.of(bannedArtist));
        chapterWith("exc-not-wanted", List.of(), List.of());
        em.flush();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();

        SearchCriteria alone = new SearchCriteria();
        alone.setTagIds(new ArrayList<>(List.of(wanted.getId())));
        alone.setExcludedTagIds(new ArrayList<>(List.of(bannedTag.getId())));
        alone.setExcludedArtistIds(new ArrayList<>(List.of(bannedArtist.getId())));

        SearchCriteria withTitle = new SearchCriteria();
        withTitle.setTagIds(new ArrayList<>(List.of(wanted.getId())));
        withTitle.setExcludedTagIds(new ArrayList<>(List.of(bannedTag.getId())));
        withTitle.setExcludedArtistIds(new ArrayList<>(List.of(bannedArtist.getId())));
        withTitle.setTitle("exc-");   // same result set

        // WHEN
        Page<CardDto> alonePage = searchService.search(alone);
        Page<CardDto> withTitlePage = searchService.search(withTitle);

        // THEN
        assertThat(alonePage.getTotalElements()).isEqualTo(2);
        assertThat(withTitlePage.getTotalElements()).isEqualTo(2);
        assertThat(alonePage.getContent()).extracting(CardDto::getHref)
                .containsExactlyInAnyOrder("/chapter/" + kept1.getId(), "/chapter/" + kept2.getId());
    }

    @Test
    void shouldCountExcludeOnlyChapterSearchAsTotalMinusDistinctExcludedOwners()
    {
        // GIVEN exclusions only, counted as all chapters minus those carrying an excluded value. One chapter
        // carries both, so a count without dedupe across facets would subtract it twice.
        Tag bannedTag = tag("xo-banned-tag");
        Artist bannedArtist = artist("xo-banned-artist");
        Chapter kept = chapterWith("xo-kept", List.of(), List.of());
        Chapter byTag = chapterWith("xo-drop-tag", List.of(bannedTag), List.of());
        Chapter byArtist = chapterWith("xo-drop-artist", List.of(), List.of(bannedArtist));
        Chapter byBoth = chapterWith("xo-drop-both", List.of(bannedTag), List.of(bannedArtist));
        em.flush();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();

        SearchCriteria everything = new SearchCriteria();
        everything.setSize(1000);
        SearchCriteria excludeOnly = new SearchCriteria();
        excludeOnly.setSize(1000);
        excludeOnly.setExcludedTagIds(new ArrayList<>(List.of(bannedTag.getId())));
        excludeOnly.setExcludedArtistIds(new ArrayList<>(List.of(bannedArtist.getId())));

        // WHEN
        Page<CardDto> all = searchService.search(everything);
        Page<CardDto> excluded = searchService.search(excludeOnly);

        // THEN exactly the three chapters carrying an excluded value are gone, from the count and the page.
        assertThat(all.getTotalElements() - excluded.getTotalElements()).isEqualTo(3);
        assertThat(excluded.getContent()).extracting(CardDto::getHref)
                .contains("/chapter/" + kept.getId())
                .doesNotContain("/chapter/" + byTag.getId(), "/chapter/" + byArtist.getId(),
                        "/chapter/" + byBoth.getId());
    }

    @Test
    void shouldCountExcludedTagSeriesSearchAgreeingAcrossEveryCountPath()
    {
        // GIVEN series with effective tags; one exclusion is counted three ways: beside an included tag,
        // alone (all series minus the excluded ones) and beside a title.
        Tag wanted = tag("sexc-wanted");
        Tag banned = tag("sexc-banned");
        Series kept = seriesWithEffectiveTags("sexc-kept", wanted);
        Series dropped = seriesWithEffectiveTags("sexc-dropped", wanted, banned);
        Series bare = seriesWithEffectiveTags("sexc-bare");
        em.flush();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();

        SearchCriteria alone = new SearchCriteria();
        alone.setType(SearchType.SERIES);
        alone.setTagIds(new ArrayList<>(List.of(wanted.getId())));
        alone.setExcludedTagIds(new ArrayList<>(List.of(banned.getId())));

        SearchCriteria withTitle = new SearchCriteria();
        withTitle.setType(SearchType.SERIES);
        withTitle.setTitle("sexc-");
        withTitle.setExcludedTagIds(new ArrayList<>(List.of(banned.getId())));

        SearchCriteria everything = new SearchCriteria();
        everything.setType(SearchType.SERIES);
        SearchCriteria excludeOnly = new SearchCriteria();
        excludeOnly.setType(SearchType.SERIES);
        excludeOnly.setExcludedTagIds(new ArrayList<>(List.of(banned.getId())));

        // WHEN
        Page<CardDto> alonePage = searchService.search(alone);
        Page<CardDto> withTitlePage = searchService.search(withTitle);
        long droppedByExclusion = searchService.search(everything).getTotalElements()
                - searchService.search(excludeOnly).getTotalElements();

        // THEN
        assertThat(alonePage.getTotalElements()).isEqualTo(1);
        assertThat(alonePage.getContent()).extracting(CardDto::getHref).containsExactly("/series/" + kept.getId());
        assertThat(withTitlePage.getTotalElements()).isEqualTo(2);
        assertThat(withTitlePage.getContent()).extracting(CardDto::getHref)
                .containsExactlyInAnyOrder("/series/" + kept.getId(), "/series/" + bare.getId())
                .doesNotContain("/series/" + dropped.getId());
        assertThat(droppedByExclusion).isEqualTo(1);
    }

    @Test
    void shouldResolveExcludedIdsToLabelsSeparatelyFromIncludedWhenResolvingSelected()
    {
        // GIVEN
        Tag included = tag("resolve-in");
        Tag excluded = tag("resolve-out");
        em.flush();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.METADATA)).clear();

        // WHEN
        SearchCriteria sc = new SearchCriteria();
        sc.setTagIds(new ArrayList<>(List.of(included.getId())));
        sc.setExcludedTagIds(new ArrayList<>(List.of(excluded.getId())));
        SelectedFilters selected = searchService.resolveSelected(sc);

        // THEN
        assertThat(selected.getTags()).extracting("label").containsExactly("resolve-in");
        assertThat(selected.getExcludedTags()).extracting("label").containsExactly("resolve-out");
        assertThat(selected.getExcludedArtists()).isEmpty();
    }

    private Chapter chapterWith(String titleFull, List<Tag> tags, List<Artist> artists)
    {
        Chapter c = new Chapter();
        c.setTitleFull(titleFull);
        c.setTitle(titleFull);
        c.setNativeTitle("n");
        c.setUploadDate(LocalDate.of(2021, 1, 1));
        c.setLanguage("English");
        c.setTags(new ArrayList<>(tags));
        c.setArtists(new ArrayList<>(artists));
        return chapterRepository.save(c);
    }

    private Series seriesWithEffectiveTags(String titleFull, Tag... tags)
    {
        Series s = new Series();
        s.setTitleFull(titleFull);
        s.setTitle(titleFull);                        // title_pretty is NOT NULL (falls back to titleFull)
        s.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        s.setEffectiveTags(new ArrayList<>(List.of(tags)));
        return seriesRepository.save(s);
    }

    private Tag tag(String name)
    {
        Tag t = new Tag();
        t.setName(name);
        return tagRepository.save(t);
    }

    private Artist artist(String name)
    {
        Artist a = new Artist();
        a.setName(name);
        return artistRepository.save(a);
    }

    private Series series(String titleFull, Short score)
    {
        Series s = new Series();
        s.setTitleFull(titleFull);
        s.setTitle(titleFull);                        // title_pretty is NOT NULL (falls back to titleFull)
        s.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        s.setScore(score);
        s.setScoreSource(score != null ? ScoreSource.USER_SET : ScoreSource.DERIVED);
        return seriesRepository.save(s);
    }

    private Chapter chapterWithStats(String full, int pageNum, long diskSize)
    {
        Chapter c = chapter(full, full, "n", null);
        c.setPageNum(pageNum);
        c.setDiskSize(diskSize);
        return chapterRepository.save(c);
    }

    private Series seriesWithStats(String titleFull, int pageNum, long diskSize)
    {
        Series s = new Series();
        s.setTitleFull(titleFull);
        s.setTitle(titleFull);                        // title_pretty is NOT NULL (falls back to titleFull)
        s.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        s.setScoreSource(ScoreSource.DERIVED);
        s.setPageNum(pageNum);
        s.setDiskSize(diskSize);
        return seriesRepository.save(s);
    }
}

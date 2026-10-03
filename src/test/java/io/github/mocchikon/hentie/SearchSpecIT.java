package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.dto.SearchType;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.repository.spec.ChapterSpecifications;
import io.github.mocchikon.hentie.repository.spec.SeriesSpecifications;
import io.github.mocchikon.hentie.service.SeriesService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SearchSpecIT
{
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired SeriesService seriesService;
    @PersistenceContext EntityManager em;

    // Series search reads the effective metadata; fixtures bypass SeriesService, so they recompute it themselves.
    private void materialize(int seriesId)
    {
        seriesService.recomputeDerived(seriesId);
        em.flush();
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

    private Chapter chapter(String titleFull, String nativeTitle, String language, List<Tag> tags)
    {
        Chapter c = new Chapter();
        c.setTitle(titleFull);
        c.setTitleFull(titleFull);
        c.setNativeTitle(nativeTitle);
        c.setUploadDate(LocalDate.of(2020, 1, 1));
        c.setLanguage(language);
        c.setTags(new ArrayList<>(tags));
        return chapterRepository.save(c);
    }

    @Test
    void shouldAndTogetherWhenMultipleTagsGiven()
    {
        // GIVEN
        Tag a = tag("alpha");
        Tag b = tag("beta");
        chapter("hasBoth", "x", "en", List.of(a, b));
        chapter("hasAlphaOnly", "x", "en", List.of(a));
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setTagIds(List.of(a.getId(), b.getId()));

        // WHEN
        List<Chapter> result = chapterRepository.findAll(ChapterSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Chapter::getTitleFull).containsExactly("hasBoth");
    }

    @Test
    void shouldMatchNativeTitleOrTitleFullViaLikeFallbackWhenSearchingByTitle()
    {
        // GIVEN
        chapter("zzz-full", "にほんご", "jp", List.of());
        em.flush();

        // WHEN
        SearchCriteria byJp = new SearchCriteria();
        byJp.setTitle("にほん");
        List<Chapter> byJpResult = chapterRepository.findAll(ChapterSpecifications.from(byJp));

        SearchCriteria byFull = new SearchCriteria();
        byFull.setTitle("zzz");
        List<Chapter> byFullResult = chapterRepository.findAll(ChapterSpecifications.from(byFull));

        // THEN
        assertThat(byJpResult).extracting(Chapter::getTitleFull).contains("zzz-full");
        assertThat(byFullResult).extracting(Chapter::getTitleFull).contains("zzz-full");
    }

    @Test
    void shouldMatchSeriesByTagDerivedFromChaptersWhenNoOverride()
    {
        // GIVEN
        Tag t = tag("derived");
        Series series = new Series();
        series.setTitleFull("Derived Series");
        series.setTitle("Derived Series");                 // title_pretty is NOT NULL
        series.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        series = seriesRepository.save(series);
        Chapter c = chapter("chap", "x", "en", List.of(t));
        c.setSeries(series);
        chapterRepository.save(c);
        em.flush();
        materialize(series.getId());

        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setTagIds(List.of(t.getId()));

        // WHEN
        List<Series> result = seriesRepository.findAll(SeriesSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Series::getTitleFull).contains("Derived Series");
    }

    @Test
    void shouldTakePrecedenceOverDerivedTagsWhenSeriesHasOverride()
    {
        // GIVEN
        Tag override = tag("ov");
        Tag chapterTag = tag("ct");
        Series series = new Series();
        series.setTitleFull("Override Series");
        series.setTitle("Override Series");                // title_pretty is NOT NULL
        series.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        series.setTags(new ArrayList<>(List.of(override)));
        series = seriesRepository.save(series);
        Chapter c = chapter("c-in-ov", "x", "en", List.of(chapterTag));
        c.setSeries(series);
        chapterRepository.save(c);
        em.flush();
        materialize(series.getId());

        // WHEN
        SearchCriteria byChapterTag = new SearchCriteria();
        byChapterTag.setType(SearchType.SERIES);
        byChapterTag.setTagIds(List.of(chapterTag.getId()));
        List<Series> byChapterTagResult = seriesRepository.findAll(SeriesSpecifications.from(byChapterTag));

        SearchCriteria byOverride = new SearchCriteria();
        byOverride.setType(SearchType.SERIES);
        byOverride.setTagIds(List.of(override.getId()));
        List<Series> byOverrideResult = seriesRepository.findAll(SeriesSpecifications.from(byOverride));

        // THEN
        // The override hides the chapter's tag.
        assertThat(byChapterTagResult).extracting(Series::getTitleFull).doesNotContain("Override Series");
        assertThat(byOverrideResult).extracting(Series::getTitleFull).contains("Override Series");
    }

    @Test
    void shouldMatchSeriesByArtistDerivedFromChaptersWhenNoOverride()
    {
        // GIVEN
        Artist a = artist("derived-artist");
        Series series = new Series();
        series.setTitleFull("Derived Artist Series");
        series.setTitle("Derived Artist Series");          // title_pretty is NOT NULL
        series.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        series = seriesRepository.save(series);
        Chapter c = chapter("art-chap", "x", "en", List.of());
        c.setArtists(new ArrayList<>(List.of(a)));
        c.setSeries(series);
        chapterRepository.save(c);
        em.flush();
        materialize(series.getId());

        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setArtistIds(List.of(a.getId()));

        // WHEN
        List<Series> result = seriesRepository.findAll(SeriesSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Series::getTitleFull).contains("Derived Artist Series");
    }

    @Test
    void shouldTakePrecedenceOverDerivedArtistsWhenSeriesHasOverride()
    {
        // GIVEN
        Artist override = artist("ov-artist");
        Artist chapterArtist = artist("ct-artist");
        Series series = new Series();
        series.setTitleFull("Override Artist Series");
        series.setTitle("Override Artist Series");         // title_pretty is NOT NULL
        series.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        series.setArtists(new ArrayList<>(List.of(override)));
        series = seriesRepository.save(series);
        Chapter c = chapter("c-in-ov-artist", "x", "en", List.of());
        c.setArtists(new ArrayList<>(List.of(chapterArtist)));
        c.setSeries(series);
        chapterRepository.save(c);
        em.flush();
        materialize(series.getId());

        // WHEN
        SearchCriteria byChapterArtist = new SearchCriteria();
        byChapterArtist.setType(SearchType.SERIES);
        byChapterArtist.setArtistIds(List.of(chapterArtist.getId()));
        List<Series> byChapterArtistResult = seriesRepository.findAll(SeriesSpecifications.from(byChapterArtist));

        SearchCriteria byOverride = new SearchCriteria();
        byOverride.setType(SearchType.SERIES);
        byOverride.setArtistIds(List.of(override.getId()));
        List<Series> byOverrideResult = seriesRepository.findAll(SeriesSpecifications.from(byOverride));

        // THEN
        // The override hides the chapter's artist.
        assertThat(byChapterArtistResult).extracting(Series::getTitleFull).doesNotContain("Override Artist Series");
        assertThat(byOverrideResult).extracting(Series::getTitleFull).contains("Override Artist Series");
    }

    // --- excluded metadata -----------------------------------------------------

    @Test
    void shouldDropChaptersCarryingAnyExcludedValueWhenExcludedTagsGiven()
    {
        // GIVEN
        Tag a = tag("exc-a");
        Tag b = tag("exc-b");
        Tag other = tag("exc-other");
        chapter("exc-has-a", "x", "en", List.of(a));
        chapter("exc-has-b", "x", "en", List.of(b, other));
        chapter("exc-has-other", "x", "en", List.of(other));
        chapter("exc-untagged", "x", "en", List.of());
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("exc-");
        sc.setExcludedTagIds(List.of(a.getId(), b.getId()));

        // WHEN
        List<Chapter> result = chapterRepository.findAll(ChapterSpecifications.from(sc));

        // THEN "none of a, b": a chapter carrying either one is dropped, untagged chapters are kept.
        assertThat(result).extracting(Chapter::getTitleFull)
                .containsExactlyInAnyOrder("exc-has-other", "exc-untagged");
    }

    @Test
    void shouldRequireIncludedAndForbidExcludedAcrossFacetsWhenBothGiven()
    {
        // GIVEN
        Tag wanted = tag("mix-wanted");
        Artist banned = artist("mix-banned");
        chapter("mix-wanted-only", "x", "en", List.of(wanted));
        Chapter withBanned = chapter("mix-wanted-banned", "x", "en", List.of(wanted));
        withBanned.setArtists(new ArrayList<>(List.of(banned)));
        chapterRepository.save(withBanned);
        chapter("mix-neither", "x", "en", List.of());
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setTagIds(List.of(wanted.getId()));
        sc.setExcludedArtistIds(List.of(banned.getId()));

        // WHEN
        List<Chapter> result = chapterRepository.findAll(ChapterSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Chapter::getTitleFull).containsExactly("mix-wanted-only");
    }

    @Test
    void shouldMatchNothingWhenOneValueIsBothIncludedAndExcluded()
    {
        // GIVEN
        Tag t = tag("both-ways");
        chapter("both-ways-chapter", "x", "en", List.of(t));
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setTagIds(List.of(t.getId()));
        sc.setExcludedTagIds(List.of(t.getId()));

        // WHEN
        List<Chapter> result = chapterRepository.findAll(ChapterSpecifications.from(sc));

        // THEN
        assertThat(result).isEmpty();
    }

    @Test
    void shouldExcludeSeriesByEffectiveTagsNotByOverriddenChapterTags()
    {
        // GIVEN a series whose tag override hides its chapter's tag, and a series deriving its tags
        Tag override = tag("exc-ov");
        Tag chapterTag = tag("exc-ct");
        Series overridden = new Series();
        overridden.setTitleFull("exc-overridden");
        overridden.setTitle("exc-overridden");               // title_pretty is NOT NULL
        overridden.setCreatedDate(LocalDate.of(2021, 1, 1)); // created_date is NOT NULL
        overridden.setTags(new ArrayList<>(List.of(override)));
        overridden = seriesRepository.save(overridden);
        Chapter inOverridden = chapter("exc-c-ov", "x", "en", List.of(chapterTag));
        inOverridden.setSeries(overridden);
        chapterRepository.save(inOverridden);

        Series derived = new Series();
        derived.setTitleFull("exc-derived");
        derived.setTitle("exc-derived");
        derived.setCreatedDate(LocalDate.of(2021, 1, 1));
        derived = seriesRepository.save(derived);
        Chapter inDerived = chapter("exc-c-derived", "x", "en", List.of(chapterTag));
        inDerived.setSeries(derived);
        chapterRepository.save(inDerived);
        em.flush();
        materialize(overridden.getId());
        materialize(derived.getId());

        // WHEN
        SearchCriteria withoutChapterTag = new SearchCriteria();
        withoutChapterTag.setType(SearchType.SERIES);
        withoutChapterTag.setTitle("exc-");
        withoutChapterTag.setExcludedTagIds(List.of(chapterTag.getId()));
        List<Series> withoutChapterTagResult =
                seriesRepository.findAll(SeriesSpecifications.from(withoutChapterTag));

        SearchCriteria withoutOverride = new SearchCriteria();
        withoutOverride.setType(SearchType.SERIES);
        withoutOverride.setTitle("exc-");
        withoutOverride.setExcludedTagIds(List.of(override.getId()));
        List<Series> withoutOverrideResult = seriesRepository.findAll(SeriesSpecifications.from(withoutOverride));

        // THEN excluding reads the same effective set including does: the override hides the chapter's tag.
        assertThat(withoutChapterTagResult).extracting(Series::getTitleFull).containsExactly("exc-overridden");
        assertThat(withoutOverrideResult).extracting(Series::getTitleFull).containsExactly("exc-derived");
    }

    // --- chapter scalar facets -------------------------------------------------

    @Test
    void shouldFilterOutChaptersBelowThresholdWhenMinScoreSet()
    {
        // GIVEN
        scored("score-8", (short) 8);
        scored("score-4", (short) 4);
        scored("score-null", null);
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("score-");
        sc.setMinScore(6);

        // WHEN
        List<Chapter> result = chapterRepository.findAll(ChapterSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Chapter::getTitleFull).containsExactly("score-8");
    }

    @Test
    void shouldMatchOnlySelectedStatusesWhenStatusFilterSet()
    {
        // GIVEN
        withStatus("st-new", Status.NEW);
        withStatus("st-fav", Status.REVIEWED_FAVOURITE);
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("st-");
        sc.setStatuses(List.of(Status.REVIEWED_FAVOURITE));

        // WHEN
        List<Chapter> result = chapterRepository.findAll(ChapterSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Chapter::getTitleFull).containsExactly("st-fav");
    }

    @Test
    void shouldMatchChapterLanguageWhenLanguageFilterSet()
    {
        // GIVEN
        chapter("lang-en", "x", "English", List.of());
        chapter("lang-jp", "x", "Japanese", List.of());
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("lang-");
        sc.setLanguages(List.of("Japanese"));

        // WHEN
        List<Chapter> result = chapterRepository.findAll(ChapterSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Chapter::getTitleFull).containsExactly("lang-jp");
    }

    @Test
    void shouldMatchExactValueWhenGalleryIdFilterSet()
    {
        // GIVEN
        Chapter c = chapter("gal-1", "x", "English", List.of());
        c.setGalleryId("EXACT-42");
        chapterRepository.save(c);
        chapter("gal-2", "x", "English", List.of());
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setGalleryId("EXACT-42");

        // WHEN
        List<Chapter> result = chapterRepository.findAll(ChapterSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Chapter::getTitleFull).containsExactly("gal-1");
    }

    @Test
    void shouldFilterChaptersWhenUploadDateRangeSet()
    {
        // GIVEN
        dated("d-2019", LocalDate.of(2019, 6, 1));
        dated("d-2021", LocalDate.of(2021, 6, 1));
        dated("d-2023", LocalDate.of(2023, 6, 1));
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("d-");
        sc.setUploadFrom(LocalDate.of(2020, 1, 1));
        sc.setUploadTo(LocalDate.of(2022, 1, 1));

        // WHEN
        List<Chapter> result = chapterRepository.findAll(ChapterSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Chapter::getTitleFull).containsExactly("d-2021");
    }

    @Test
    void shouldAndDifferentFacetsTogetherNotOrWhenMultipleFacetsSet()
    {
        // GIVEN
        Chapter both = scored("and-both", (short) 9);
        // Matches the title only, so minScore must exclude it.
        scored("and-title-only", (short) 2);
        em.flush();

        // WHEN
        SearchCriteria titleOnly = new SearchCriteria();
        titleOnly.setTitle("and-");
        List<Chapter> titleOnlyResult = chapterRepository.findAll(ChapterSpecifications.from(titleOnly));

        SearchCriteria titleAndScore = new SearchCriteria();
        titleAndScore.setTitle("and-");
        titleAndScore.setMinScore(6);
        List<Chapter> titleAndScoreResult = chapterRepository.findAll(ChapterSpecifications.from(titleAndScore));

        // THEN
        assertThat(titleOnlyResult).extracting(Chapter::getTitleFull).contains("and-both", "and-title-only");
        assertThat(titleAndScoreResult).extracting(Chapter::getTitleFull).containsExactly(both.getTitleFull());
    }

    @Test
    void shouldFilterChaptersByPageCountRangeWhenMinAndMaxPagesSet()
    {
        // GIVEN chapters with 5 / 50 / 200 pages.
        withPages("pg-5", 5);
        withPages("pg-50", 50);
        withPages("pg-200", 200);
        em.flush();

        // WHEN minPages only -> excludes the 5-page chapter.
        SearchCriteria min = new SearchCriteria();
        min.setTitle("pg-");
        min.setMinPages(10);
        List<Chapter> minResult = chapterRepository.findAll(ChapterSpecifications.from(min));

        // maxPages only -> excludes the 200-page chapter.
        SearchCriteria max = new SearchCriteria();
        max.setTitle("pg-");
        max.setMaxPages(100);
        List<Chapter> maxResult = chapterRepository.findAll(ChapterSpecifications.from(max));

        // both bounds -> only the middle 50-page chapter.
        SearchCriteria range = new SearchCriteria();
        range.setTitle("pg-");
        range.setMinPages(10);
        range.setMaxPages(100);
        List<Chapter> rangeResult = chapterRepository.findAll(ChapterSpecifications.from(range));

        // THEN
        assertThat(minResult).extracting(Chapter::getTitleFull).containsExactlyInAnyOrder("pg-50", "pg-200");
        assertThat(maxResult).extracting(Chapter::getTitleFull).containsExactlyInAnyOrder("pg-5", "pg-50");
        assertThat(rangeResult).extracting(Chapter::getTitleFull).containsExactly("pg-50");
    }

    @Test
    void shouldFilterSeriesByPageCountWhenPageRangeSet()
    {
        // GIVEN series with materialized page totals 10 / 100 (derived from chapters, sum stored on Series).
        seriesWithPages("spg-10", 10);
        seriesWithPages("spg-100", 100);
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setTitle("spg-");
        sc.setMinPages(50);

        // WHEN
        List<Series> result = seriesRepository.findAll(SeriesSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Series::getTitleFull).contains("spg-100").doesNotContain("spg-10");
    }

    @Test
    void shouldTreatAsNoFilterWhenMinScoreIsNonPositive()
    {
        // GIVEN
        scored("mnf-hi", (short) 9);
        scored("mnf-lo", (short) 1);
        scored("mnf-null", null);
        em.flush();

        // WHEN
        SearchCriteria zero = new SearchCriteria();
        zero.setTitle("mnf-");
        zero.setMinScore(0);
        List<Chapter> zeroResult = chapterRepository.findAll(ChapterSpecifications.from(zero));

        SearchCriteria negative = new SearchCriteria();
        negative.setTitle("mnf-");
        negative.setMinScore(-5);
        List<Chapter> negativeResult = chapterRepository.findAll(ChapterSpecifications.from(negative));

        // THEN
        assertThat(zeroResult).extracting(Chapter::getTitleFull).contains("mnf-hi", "mnf-lo", "mnf-null");
        assertThat(negativeResult).extracting(Chapter::getTitleFull).contains("mnf-hi", "mnf-lo", "mnf-null");
    }

    // --- series scalar facets --------------------------------------------------

    @Test
    void shouldMatchOnStoredStatusWhenSeriesStatusFilterSet()
    {
        // GIVEN
        series("srv-new", Status.NEW, null, null);
        series("srv-reviewed", Status.REVIEWED, null, null);
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setStatuses(List.of(Status.REVIEWED));

        // WHEN
        List<Series> result = seriesRepository.findAll(SeriesSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Series::getTitleFull).contains("srv-reviewed")
                .doesNotContain("srv-new");
    }

    @Test
    void shouldMatchOnMaterializedEffectiveScoreWhenSeriesMinScoreSet()
    {
        // GIVEN
        // The filter reads the effective score, whatever its source: a USER_SET 9 and a DERIVED 8 both clear 7.
        series("s-override", Status.REVIEWED, (short) 9, null);
        Series derived = new Series();
        derived.setTitleFull("s-derived");
        derived.setTitle("s-derived");                      // title_pretty is NOT NULL
        derived.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        derived.setStatus(Status.REVIEWED);
        derived.setScoreSource(ScoreSource.DERIVED);
        derived.setScore((short) 8);   // materialized rounded chapter average
        seriesRepository.save(derived);
        series("s-low", Status.REVIEWED, (short) 2, null);
        // No scored chapters: a null effective score is excluded.
        series("s-null", Status.REVIEWED, null, null);
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setTitle("s-");
        sc.setMinScore(7);

        // WHEN
        List<Series> result = seriesRepository.findAll(SeriesSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Series::getTitleFull)
                .containsExactlyInAnyOrder("s-override", "s-derived")
                .doesNotContain("s-low", "s-null");
    }

    @Test
    void shouldFilterOnCreatedDateWhenSeriesDateRangeSet()
    {
        // GIVEN
        series("sd-old", Status.REVIEWED, null, LocalDate.of(2019, 1, 1));
        series("sd-mid", Status.REVIEWED, null, LocalDate.of(2021, 1, 1));
        em.flush();

        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setUploadFrom(LocalDate.of(2020, 1, 1));

        // WHEN
        List<Series> result = seriesRepository.findAll(SeriesSpecifications.from(sc));

        // THEN
        assertThat(result).extracting(Series::getTitleFull).contains("sd-mid").doesNotContain("sd-old");
    }

    // --- helpers ---------------------------------------------------------------

    private Chapter scored(String titleFull, Short score)
    {
        Chapter c = chapter(titleFull, "x", "English", List.of());
        c.setScore(score);
        return chapterRepository.save(c);
    }

    private Chapter withStatus(String titleFull, Status status)
    {
        Chapter c = chapter(titleFull, "x", "English", List.of());
        c.setStatus(status);
        return chapterRepository.save(c);
    }

    private Chapter dated(String titleFull, LocalDate uploadDate)
    {
        Chapter c = chapter(titleFull, "x", "English", List.of());
        c.setUploadDate(uploadDate);
        return chapterRepository.save(c);
    }

    private Chapter withPages(String titleFull, int pageNum)
    {
        Chapter c = chapter(titleFull, "x", "English", List.of());
        c.setPageNum(pageNum);
        return chapterRepository.save(c);
    }

    private Series seriesWithPages(String titleFull, int pageNum)
    {
        Series s = new Series();
        s.setTitleFull(titleFull);
        s.setTitle(titleFull);                        // title_pretty is NOT NULL (falls back to titleFull)
        s.setStatus(Status.REVIEWED);
        s.setScoreSource(ScoreSource.DERIVED);
        s.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        s.setPageNum(pageNum);
        return seriesRepository.save(s);
    }

    // A non-null score is stored as a USER_SET override, null as DERIVED with no scores.
    private Series series(String titleFull, Status status, Short score, LocalDate createdDate)
    {
        Series s = new Series();
        s.setTitleFull(titleFull);
        s.setTitle(titleFull);   // title_pretty is NOT NULL (falls back to titleFull)
        s.setStatus(status);
        s.setScore(score);
        s.setScoreSource(score != null ? ScoreSource.USER_SET : ScoreSource.DERIVED);
        s.setCreatedDate(createdDate != null ? createdDate : LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        return seriesRepository.save(s);
    }
}

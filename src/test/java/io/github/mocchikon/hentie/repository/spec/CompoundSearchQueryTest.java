package io.github.mocchikon.hentie.repository.spec;

import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.dto.SearchType;
import io.github.mocchikon.hentie.dto.SortBy;
import io.github.mocchikon.hentie.entity.Status;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the SQL shapes, because a wrong one still returns the right rows on a few of them and only shows at
 * scale. The results themselves are compared against the Criteria path in {@code CompoundSearchIT}.
 */
class CompoundSearchQueryTest
{
    private static final String TAG = "select chapter_id from chapter_tags where tag_id = ?";

    @Test
    void shouldListOneOperandPerIncludedValueThenTheTitleThenTheLanguages()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setType(SearchType.SERIES);
        c.setTagIds(new ArrayList<>(List.of(1, 2)));
        c.setArtistIds(new ArrayList<>(List.of(3)));
        c.setTitle("term");
        c.setLanguages(List.of("English", "Japanese"));

        // WHEN
        var operands = CompoundSearchQuery.operands(c, "\"term\"");

        // THEN
        assertThat(operands).extracting(CompoundSearchQuery.Operand::key).containsExactly(
                OperandKey.of(MetadataType.TAG, 1), OperandKey.of(MetadataType.TAG, 2),
                OperandKey.of(MetadataType.ARTIST, 3), OperandKey.TITLE, OperandKey.LANGUAGES);
        assertThat(operands).extracting(CompoundSearchQuery.Operand::sql).containsExactly(
                "select series_id from series_effective_tags where tag_id = ?",
                "select series_id from series_effective_tags where tag_id = ?",
                "select series_id from series_effective_artists where artist_id = ?",
                "select rowid from series_fts where series_fts match ?",
                "select series_id from series_effective_languages where language in (?,?)");
        assertThat(operands).extracting(CompoundSearchQuery.Operand::unique)
                .containsExactly(false, false, false, true, false);
        assertThat(operands.get(4).params()).containsExactly("English", "Japanese");
    }

    @Test
    void shouldNotExpressATitleTooShortForTheIndex()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setTitle("ab");

        // WHEN / THEN
        assertThat(CompoundSearchQuery.expressible(c, null)).isFalse();
        assertThat(CompoundSearchQuery.expressible(new SearchCriteria(), null)).isTrue();
    }

    @Test
    void shouldCapTheProbeWithALimit()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setTagIds(new ArrayList<>(List.of(7)));
        var operand = CompoundSearchQuery.operands(c, null).getFirst();

        // WHEN
        var q = CompoundSearchQuery.probe(operand, 30_000);

        // THEN
        assertThat(q.sql()).isEqualTo("select count(*) from (" + TAG + " limit ?)");
        assertThat(q.params()).containsExactly(7, 30_000);
    }

    @Test
    void shouldCountALoneTagWithDistinctBecauseJoinTablesAreBags()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setTagIds(new ArrayList<>(List.of(4)));

        // WHEN
        var q = CompoundSearchQuery.count(c, null, true);

        // THEN
        assertThat(q.sql()).isEqualTo(
                "select count(*) from (select distinct chapter_id from chapter_tags where tag_id = ?)");
        assertThat(q.params()).containsExactly(4);
    }

    @Test
    void shouldCountALoneTitleTermWithoutDistinctBecauseRowidsAreUnique()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setTitle("term");

        // WHEN
        var q = CompoundSearchQuery.count(c, "\"term\"", true);

        // THEN
        assertThat(q.sql()).isEqualTo(
                "select count(*) from (select rowid from chapter_fts where chapter_fts match ?)");
        assertThat(q.params()).containsExactly("\"term\"");
    }

    @Test
    void shouldIntersectIncludedAndExceptEachExcludedValueWithParamsInSqlOrder()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setTagIds(new ArrayList<>(List.of(1, 2)));
        c.setExcludedTagIds(new ArrayList<>(List.of(8, 9)));

        // WHEN
        var q = CompoundSearchQuery.count(c, null, true);

        // THEN one except per excluded value: "in (?, ?)" would give the merge two runs to sort first.
        assertThat(q.sql()).isEqualTo("select count(*) from (" + TAG + " intersect " + TAG
                + " except " + TAG + " except " + TAG + ")");
        assertThat(q.params()).containsExactly(1, 2, 8, 9);
    }

    @Test
    void shouldCountExclusionsOnlyAsAllOwnersMinusTheExcludedUnion()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setExcludedTagIds(new ArrayList<>(List.of(1, 2)));
        c.setExcludedArtistIds(new ArrayList<>(List.of(3)));

        // WHEN
        var q = CompoundSearchQuery.count(c, null, true);

        // THEN
        assertThat(q.sql()).isEqualTo("select (select count(*) from chapter) - (select count(*) from ("
                + TAG + " union " + TAG + " union select chapter_id from chapter_artists where artist_id = ?))");
        assertThat(q.params()).containsExactly(1, 2, 3);
    }

    @Test
    void shouldDedupeALoneExcludedValueWhenCountingAllOwnersMinusIt()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setType(SearchType.SERIES);
        c.setExcludedTagIds(new ArrayList<>(List.of(7)));

        // WHEN
        var q = CompoundSearchQuery.count(c, null, true);

        // THEN
        assertThat(q.sql()).isEqualTo("select (select count(*) from series) - (select count(*) from ("
                + "select distinct series_id from series_effective_tags where tag_id = ?))");
        assertThat(q.params()).containsExactly(7);
    }

    @Test
    void shouldStreamASingleStatusWhenAskedAndSubtractTheExclusionsFromIt()
    {
        // GIVEN the review queue minus a tag
        var c = new SearchCriteria();
        c.setStatuses(List.of(Status.NEW));
        c.setExcludedTagIds(new ArrayList<>(List.of(5)));

        // WHEN
        var streamed = CompoundSearchQuery.count(c, null, true);
        var filtered = CompoundSearchQuery.count(c, null, false);

        // THEN
        assertThat(streamed.sql()).isEqualTo(
                "select count(*) from (select id from chapter where status = ? except " + TAG + ")");
        assertThat(streamed.params()).containsExactly(Status.NEW.ordinal(), 5);
        assertThat(filtered.sql()).isEqualTo("select count(*) from chapter o where o.id in ("
                + "select id from chapter except " + TAG + ") and o.status in (?)");
        assertThat(filtered.params()).containsExactly(5, Status.NEW.ordinal());
    }

    @Test
    void shouldWrapTheCompoundWithTheOwnerRowFiltersInSpecOrder()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setTagIds(new ArrayList<>(List.of(1)));
        c.setStatuses(List.of(Status.NEW, Status.REVIEWED));
        c.setLanguages(List.of("English", "Japanese"));
        c.setMinScore(12);
        c.setMinPages(10);
        c.setMaxPages(40);
        c.setUploadFrom(LocalDate.of(2020, 1, 1));
        c.setUploadTo(LocalDate.of(2020, 12, 31));
        c.setGalleryId(" mock:1 ");

        // WHEN
        var q = CompoundSearchQuery.count(c, null, true);

        // THEN several statuses or languages cannot be one ordered stream, so they stay filters.
        assertThat(q.sql()).isEqualTo("select count(*) from chapter o where o.id in (" + TAG + ")"
                + " and o.status in (?,?) and o.language in (?,?) and o.score >= ? and o.page_num >= ?"
                + " and o.page_num <= ? and o.upload_date >= ? and o.upload_date <= ? and o.gallery_id = ?");
        assertThat(q.params()).containsExactly(1, Status.NEW.ordinal(), Status.REVIEWED.ordinal(),
                "English", "Japanese", 10, 10, 40, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31), "mock:1");
    }

    @Test
    void shouldFilterSeriesOnTheirOwnColumnsAndNeverOnAGalleryId()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setType(SearchType.SERIES);
        c.setTagIds(new ArrayList<>(List.of(1)));
        c.setMinScore(6);
        c.setUploadFrom(LocalDate.of(2021, 1, 1));
        c.setGalleryId("mock:1");

        // WHEN
        var q = CompoundSearchQuery.count(c, null, true);

        // THEN
        assertThat(q.sql()).isEqualTo("select count(*) from series o where o.id in ("
                + "select series_id from series_effective_tags where tag_id = ?)"
                + " and o.score is not null and o.score >= ? and o.created_date >= ?");
        assertThat(q.params()).containsExactly(1, 6, LocalDate.of(2021, 1, 1));
    }

    @Test
    void shouldPageByDateStraightFromTheStreamsWhenNoRowFilterIsLeft()
    {
        // GIVEN a tag and one status: both streams, so the merge alone yields the page.
        var c = new SearchCriteria();
        c.setTagIds(new ArrayList<>(List.of(1)));
        c.setStatuses(List.of(Status.NEW));

        // WHEN
        var q = CompoundSearchQuery.pageIds(c, null, SortBy.DATE, Sort.Direction.ASC, 72, 36);

        // THEN
        assertThat(q.sql()).isEqualTo(TAG + " intersect select id from chapter where status = ?"
                + " order by 1 asc limit ? offset ?");
        assertThat(q.params()).containsExactly(1, Status.NEW.ordinal(), 36, 72L);
    }

    @Test
    void shouldPageALoneTagByDateWithDistinct()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setTagIds(new ArrayList<>(List.of(1)));

        // WHEN
        var q = CompoundSearchQuery.pageIds(c, null, SortBy.DATE, Sort.Direction.DESC, 0, 36);

        // THEN
        assertThat(q.sql()).isEqualTo(
                "select distinct chapter_id from chapter_tags where tag_id = ? order by 1 desc limit ? offset ?");
    }

    @Test
    void shouldStreamASingleChapterLanguageForTheDateOrder()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setTagIds(new ArrayList<>(List.of(1)));
        c.setLanguages(List.of("Japanese"));

        // WHEN
        var q = CompoundSearchQuery.pageIds(c, null, SortBy.DATE, Sort.Direction.DESC, 0, 36);

        // THEN
        assertThat(q.sql()).isEqualTo(TAG + " intersect select id from chapter where language = ?"
                + " order by 1 desc limit ? offset ?");
        assertThat(q.params()).containsExactly(1, "Japanese", 36, 0L);
    }

    @Test
    void shouldKeepASingleStatusAFilterWhenAnotherRowFilterIsLeft()
    {
        // GIVEN a date range beside the status: the merge must be built in full anyway, so a status stream
        // would only make it bigger
        var c = new SearchCriteria();
        c.setTagIds(new ArrayList<>(List.of(1)));
        c.setStatuses(List.of(Status.NEW));
        c.setUploadFrom(LocalDate.of(2020, 1, 1));

        // WHEN
        var q = CompoundSearchQuery.pageIds(c, null, SortBy.DATE, Sort.Direction.DESC, 0, 36);

        // THEN
        assertThat(q.sql()).isEqualTo("select o.id from chapter o where o.id in (" + TAG + ")"
                + " and o.status in (?) and o.upload_date >= ? order by o.id desc limit ? offset ?");
        assertThat(q.params()).containsExactly(1, Status.NEW.ordinal(), LocalDate.of(2020, 1, 1), 36, 0L);
    }

    @Test
    void shouldPageByDateThroughTheOwnerRowsWhenAFilterIsLeft()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setTagIds(new ArrayList<>(List.of(1, 2)));
        c.setMinPages(20);

        // WHEN
        var q = CompoundSearchQuery.pageIds(c, null, SortBy.DATE, Sort.Direction.DESC, 36, 36);

        // THEN
        assertThat(q.sql()).isEqualTo("select o.id from chapter o where o.id in (" + TAG + " intersect " + TAG
                + ") and o.page_num >= ? order by o.id desc limit ? offset ?");
        assertThat(q.params()).containsExactly(1, 2, 20, 36, 36L);
    }

    @Test
    void shouldWalkTheSortIndexWithTheListAsAFilterOnlyForOtherSorts()
    {
        // GIVEN a single status: a filter here, so (status, score) can be walked in score order
        var c = new SearchCriteria();
        c.setTagIds(new ArrayList<>(List.of(1)));
        c.setStatuses(List.of(Status.NEW));

        // WHEN
        var byScoreAsc = CompoundSearchQuery.pageIds(c, null, SortBy.SCORE, Sort.Direction.ASC, 0, 36);
        var byScoreDesc = CompoundSearchQuery.pageIds(c, null, SortBy.SCORE, Sort.Direction.DESC, 0, 36);
        var byPages = CompoundSearchQuery.pageIds(c, null, SortBy.PAGE_NUM, Sort.Direction.DESC, 0, 36);
        var bySize = CompoundSearchQuery.pageIds(c, null, SortBy.DISK_SIZE, Sort.Direction.ASC, 0, 36);

        // THEN
        String where = "select o.id from chapter o where +o.id in (" + TAG + ") and o.status in (?) order by ";
        assertThat(byScoreAsc.sql()).isEqualTo(where + "o.score asc nulls last, o.id asc limit ? offset ?");
        assertThat(byScoreDesc.sql()).isEqualTo(where + "o.score desc, o.id desc limit ? offset ?");
        assertThat(byPages.sql()).isEqualTo(where + "o.page_num desc, o.id desc limit ? offset ?");
        assertThat(bySize.sql()).isEqualTo(where + "o.disk_size asc, o.id asc limit ? offset ?");
        assertThat(byScoreAsc.params()).containsExactly(1, Status.NEW.ordinal(), 36, 0L);
    }

    @Test
    void shouldPageExclusionsOnlyFromEveryOwnerId()
    {
        // GIVEN
        var c = new SearchCriteria();
        c.setExcludedTagIds(new ArrayList<>(List.of(3)));

        // WHEN
        var q = CompoundSearchQuery.pageIds(c, null, SortBy.DATE, Sort.Direction.DESC, 0, 36);

        // THEN
        assertThat(q.sql()).isEqualTo("select id from chapter except " + TAG + " order by 1 desc limit ? offset ?");
        assertThat(q.params()).containsExactly(3, 36, 0L);
    }

    @Test
    void shouldReportARowFilterLeftOnlyWhenOneCannotBeStreamed()
    {
        // GIVEN
        var oneStatus = new SearchCriteria();
        oneStatus.setStatuses(List.of(Status.NEW));
        var twoStatuses = new SearchCriteria();
        twoStatuses.setStatuses(List.of(Status.NEW, Status.REVIEWED));
        var minScore = new SearchCriteria();
        minScore.setMinScore(5);

        // WHEN / THEN
        assertThat(CompoundSearchQuery.leavesRowFilters(oneStatus, null)).isFalse();
        assertThat(CompoundSearchQuery.leavesRowFilters(twoStatuses, null)).isTrue();
        assertThat(CompoundSearchQuery.leavesRowFilters(minScore, null)).isTrue();
    }

    @Test
    void shouldReportOwnerFiltersForAnyStatusOrRowFilterButNotForIncludedValues()
    {
        // GIVEN
        var tagOnly = new SearchCriteria();
        tagOnly.setTagIds(new ArrayList<>(List.of(1)));
        tagOnly.setTitle("term");
        var oneStatus = new SearchCriteria();
        oneStatus.setStatuses(List.of(Status.NEW));
        var seriesLanguage = new SearchCriteria();
        seriesLanguage.setType(SearchType.SERIES);
        seriesLanguage.setLanguages(List.of("English"));
        var chapterLanguage = new SearchCriteria();
        chapterLanguage.setLanguages(List.of("English"));

        // WHEN / THEN a series language is an included value, a chapter language a column of the row
        assertThat(CompoundSearchQuery.hasOwnerFilters(tagOnly)).isFalse();
        assertThat(CompoundSearchQuery.hasOwnerFilters(oneStatus)).isTrue();
        assertThat(CompoundSearchQuery.hasOwnerFilters(seriesLanguage)).isFalse();
        assertThat(CompoundSearchQuery.hasOwnerFilters(chapterLanguage)).isTrue();
    }

    @Test
    void shouldReportExclusionsOnlyWhenAnExcludedIdIsNotNull()
    {
        // GIVEN
        var none = new SearchCriteria();
        none.setExcludedTagIds(new ArrayList<>(Arrays.asList((Integer) null)));
        var one = new SearchCriteria();
        one.setExcludedGroupIds(new ArrayList<>(List.of(2)));

        // WHEN / THEN
        assertThat(CompoundSearchQuery.hasExclusions(none)).isFalse();
        assertThat(CompoundSearchQuery.hasExclusions(one)).isTrue();
    }
}

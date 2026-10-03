package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.CardDto;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.dto.SearchType;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.SearchService;
import io.github.mocchikon.hentie.service.TitleSearchIndex;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FTS5 trigram title index and the {@code LIKE} path for terms too short for a trigram. The two paths
 * must agree, also on literal wildcards and on non-ASCII case, which they reach differently (the index
 * folds Unicode case, the fallback matches every case variant).
 */
@SpringBootTest
@Transactional
class TitleSearchIndexIT
{
    @Autowired SearchService searchService;
    @Autowired TitleSearchIndex titleSearchIndex;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired CacheManager cacheManager;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    @Test
    void shouldSearchThroughTheIndexWhenTermIsLongEnough()
    {
        // GIVEN
        Chapter c = chapter("ftsprobe unique full", "ftsprobe pretty", "ftsprobe native");
        em.flush();
        clearCountCache();

        // WHEN / THEN the term is answered by the index (a phrase, not the LIKE fallback) and finds it
        assertThat(titleSearchIndex.phraseFor("ftsprobe unique")).contains("\"ftsprobe unique\"");
        assertThat(searchChapters("ftsprobe unique")).containsExactly("/chapter/" + c.getId());
    }

    @Test
    void shouldDeclineShortTermsSoSearchFallsBackToLike()
    {
        assertThat(titleSearchIndex.phraseFor("ab")).isEmpty();
    }

    @Test
    void shouldReturnNoRowsWhenTermMatchesNothing()
    {
        assertThat(titleSearchIndex.phraseFor("zzz-no-such-title-zzz")).isPresent();
        assertThat(searchChapters("zzz-no-such-title-zzz")).isEmpty();
    }

    @Test
    void shouldMatchTitleFullAndNativeTitleButNotPrettyTitle()
    {
        // GIVEN three chapters, each carrying the search term in a different column.
        Chapter byFull = chapter("ftscol alpha full", "unrelated pretty", "unrelated native");
        Chapter byNative = chapter("unrelated full one", "unrelated pretty", "ftscol alpha native");
        chapter("unrelated full two", "ftscol alpha pretty", "unrelated native");   // pretty only
        em.flush();
        clearCountCache();

        // WHEN
        List<String> hrefs = searchChapters("ftscol alpha");

        // THEN titleFull and nativeTitle match; the display-only pretty title does not.
        assertThat(hrefs).containsExactlyInAnyOrder("/chapter/" + byFull.getId(), "/chapter/" + byNative.getId());
    }

    @Test
    void shouldMatchSeriesTitleFullAndNativeTitleButNotPrettyTitle()
    {
        // GIVEN
        Series byFull = series("ftsser beta full", "unrelated pretty", "unrelated native");
        Series byNative = series("unrelated full one", "unrelated pretty", "ftsser beta native");
        series("unrelated full two", "ftsser beta pretty", "unrelated native");     // pretty only
        em.flush();
        clearCountCache();

        // WHEN
        List<String> hrefs = searchSeries("ftsser beta");

        // THEN
        assertThat(hrefs).containsExactlyInAnyOrder("/series/" + byFull.getId(), "/series/" + byNative.getId());
    }

    @Test
    void shouldReturnSameResultsThroughIndexAndLikeFallback()
    {
        // GIVEN chapters that a 3+ char term (index path) and a 2 char term (fallback path) both select.
        Chapter one = chapter("ftssame qqq one", "p", "n");
        Chapter two = chapter("ftssame qqq two", "p", "n");
        em.flush();
        clearCountCache();

        // WHEN the long term goes through the index and the short one through the LIKE fallback.
        assertThat(titleSearchIndex.phraseFor("ftssame qqq")).isPresent();
        List<String> viaIndex = searchChapters("ftssame qqq");
        clearCountCache();
        assertThat(titleSearchIndex.phraseFor("qq")).isEmpty();
        List<String> viaLike = searchChapters("qq");

        // THEN both paths agree on the same two chapters.
        assertThat(viaIndex).containsExactlyInAnyOrder("/chapter/" + one.getId(), "/chapter/" + two.getId());
        assertThat(viaLike).containsExactlyInAnyOrderElementsOf(viaIndex);
    }

    @Test
    void shouldFindNewlyInsertedChapterWhenTriggersKeepIndexInSync()
    {
        // GIVEN a chapter written after the index was built
        Chapter fresh = chapter("ftsinsert gamma full", "p", "n");
        em.flush();
        clearCountCache();

        // WHEN / THEN the AFTER INSERT trigger already indexed it
        assertThat(searchChapters("ftsinsert gamma")).containsExactly("/chapter/" + fresh.getId());
    }

    @Test
    void shouldReindexChapterWhenTitleUpdated()
    {
        // GIVEN an indexed chapter
        Chapter c = chapter("ftsupdate before-term", "p", "n");
        em.flush();

        // WHEN its title changes
        c.setTitleFull("ftsupdate after-term");
        chapterRepository.save(c);
        em.flush();
        clearCountCache();

        // THEN the old term no longer matches and the new one does (AFTER UPDATE trigger re-indexed it)
        assertThat(searchChapters("ftsupdate before-term")).isEmpty();
        assertThat(searchChapters("ftsupdate after-term")).containsExactly("/chapter/" + c.getId());
    }

    /**
     * Re-indexing an unchanged title would write the index's own tables too, which {@code total_changes()}
     * counts. The second update is the shape of a statement that names every column.
     */
    @Test
    void shouldLeaveTheIndexAloneWhenAChapterUpdateKeepsItsTitles()
    {
        // GIVEN an indexed chapter
        Chapter c = chapter("ftskeep chapter term", "p", "n");
        em.flush();
        flushTitleIndex();
        long before = totalChanges();

        // WHEN another column changes, then the titles are set to the values they have
        jdbc.update("update chapter set status = 1 where id = ?", c.getId());
        jdbc.update("update chapter set title_full = title_full, native_title = native_title where id = ?", c.getId());

        // THEN each update wrote its row and nothing else, and the title still matches
        assertThat(totalChanges() - before).isEqualTo(2);
        clearCountCache();
        assertThat(searchChapters("ftskeep chapter term")).containsExactly("/chapter/" + c.getId());
    }

    /** What {@code recomputeDerived} writes on every chapter change must not re-index the series title. */
    @Test
    void shouldLeaveTheIndexAloneWhenASeriesUpdateKeepsItsTitles()
    {
        // GIVEN an indexed series
        Series s = series("ftskeep series term", "p", "n");
        em.flush();
        flushTitleIndex();
        long before = totalChanges();

        // WHEN its score changes, then the titles are set to the values they have
        jdbc.update("update series set score = 7 where id = ?", s.getId());
        jdbc.update("update series set title_full = title_full, native_title = native_title where id = ?", s.getId());

        // THEN each update wrote its row and nothing else, and the title still matches
        assertThat(totalChanges() - before).isEqualTo(2);
        clearCountCache();
        assertThat(searchSeries("ftskeep series term")).containsExactly("/series/" + s.getId());
    }

    /** The app never changes an id, but a manual {@code sqlite3} session may, and the index must follow it. */
    @Test
    void shouldMoveTheIndexEntryWhenAChapterIdChanges()
    {
        // GIVEN an indexed chapter
        Chapter c = chapter("ftsmove chapter term", "p", "n");
        em.flush();
        int movedTo = c.getId() + 1_000_000;

        // WHEN SQL gives it another id
        jdbc.update("update chapter set id = ? where id = ?", movedTo, c.getId());

        // THEN the index names the new id
        assertThat(jdbc.queryForList("select rowid from chapter_fts where chapter_fts match ?", Integer.class,
                "\"ftsmove chapter term\"")).containsExactly(movedTo);
    }

    @Test
    void shouldDropChapterFromIndexWhenDeleted()
    {
        // GIVEN an indexed chapter
        Chapter c = chapter("ftsdelete delta full", "p", "n");
        em.flush();
        assertThat(searchChapters("ftsdelete delta")).containsExactly("/chapter/" + c.getId());
        clearCountCache();

        // WHEN it is deleted
        chapterRepository.delete(c);
        em.flush();
        clearCountCache();

        // THEN the AFTER DELETE trigger removed it from the index too
        assertThat(searchChapters("ftsdelete delta")).isEmpty();
    }

    @Test
    void shouldCombineIndexedTitleWithOtherFacets()
    {
        // GIVEN two chapters matching the title term but differing in language
        Chapter english = chapter("ftsfacet epsilon one", "p", "n");
        Chapter japanese = chapter("ftsfacet epsilon two", "p", "n");
        japanese.setLanguage("Japanese");
        chapterRepository.save(japanese);
        em.flush();
        clearCountCache();

        // WHEN the indexed title predicate is AND-ed with a language filter
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("ftsfacet epsilon");
        sc.setLanguages(List.of("English"));

        // THEN only the English one matches, and the count agrees with the content
        var page = searchService.search(sc);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(CardDto::getHref)
                .containsExactly("/chapter/" + english.getId())
                .doesNotContain("/chapter/" + japanese.getId());
    }

    @Test
    void shouldMatchAccentedTitleRegardlessOfCaseThroughTheIndex()
    {
        // GIVEN a title stored upper-case with accents (native titles routinely are not ASCII)
        Chapter c = chapter("ftscase ÄÖÜ Grüße", "p", "n");
        em.flush();
        clearCountCache();

        // WHEN searching for it in lower case
        List<String> hrefs = searchChapters("ftscase äöü grüße");

        // THEN it matches: the trigram index folds Unicode case, while SQLite LIKE/lower() fold ASCII only
        assertThat(hrefs).containsExactly("/chapter/" + c.getId());
    }

    @Test
    void shouldTreatWildcardsInTheTermAsLiteralTextOnTheIndexPath()
    {
        // GIVEN one title holding a literal percent sign, and one that matches only if % is a wildcard
        Chapter literal = chapter("ftswild 50% off", "p", "n");
        Chapter wildcardOnly = chapter("ftswild 5000 off", "p", "n");
        em.flush();
        clearCountCache();

        // WHEN the term contains the percent sign
        List<String> hrefs = searchChapters("50% off");

        // THEN only the literal match comes back - an FTS5 phrase has no wildcards
        assertThat(hrefs).contains("/chapter/" + literal.getId())
                .doesNotContain("/chapter/" + wildcardOnly.getId());
    }

    @Test
    void shouldTreatWildcardsInTheTermAsLiteralTextOnTheLikeFallbackPath()
    {
        // GIVEN two titles a 2-char term (the LIKE path) tells apart only when the underscore is escaped
        Chapter literal = chapter("ftsund x_b marker", "p", "n");
        Chapter wildcardOnly = chapter("ftsund xzb marker", "p", "n");
        em.flush();
        clearCountCache();

        // WHEN
        assertThat(titleSearchIndex.phraseFor("_b")).isEmpty();   // too short -> LIKE fallback
        List<String> hrefs = searchChapters("_b");

        // THEN the underscore matched itself rather than "any character"
        assertThat(hrefs).contains("/chapter/" + literal.getId())
                .doesNotContain("/chapter/" + wildcardOnly.getId());
    }

    @Test
    void shouldRepairADriftedIndexWhenRebuilt()
    {
        // GIVEN an indexed chapter whose index entries are wiped behind the triggers' back
        Chapter c = chapter("ftsrebuild zeta full", "p", "n");
        em.flush();
        assertThat(searchChapters("ftsrebuild zeta")).containsExactly("/chapter/" + c.getId());
        clearCountCache();
        jdbc.update("INSERT INTO chapter_fts(chapter_fts) VALUES ('delete-all')");
        assertThat(searchChapters("ftsrebuild zeta")).isEmpty();
        clearCountCache();

        // WHEN the Manage maintenance action runs
        titleSearchIndex.rebuildAll();
        clearCountCache();

        // THEN the chapter is findable again
        assertThat(searchChapters("ftsrebuild zeta")).containsExactly("/chapter/" + c.getId());
    }

    @Test
    void shouldMatchUpperCaseTermAgainstLowerCaseTitleThroughTheIndex()
    {
        // GIVEN a lower-case stored title with a non-ASCII part
        Chapter c = chapter("ftsupper äöü title", "p", "n");
        em.flush();
        clearCountCache();

        // WHEN searching in upper case
        List<String> hrefs = searchChapters("FTSUPPER ÄÖÜ TITLE");

        // THEN it matches - the trigram index folds case both ways, for ASCII and beyond
        assertThat(hrefs).containsExactly("/chapter/" + c.getId());
    }

    @Test
    void shouldMatchShortAccentedTermRegardlessOfCaseThroughTheLikeFallback()
    {
        // GIVEN a title holding an upper-case accented character
        Chapter c = chapter("ftsshort Äx marker", "p", "n");
        em.flush();
        clearCountCache();

        // WHEN searching with a 2-char term in the other case, which the ASCII-only LIKE path answers
        assertThat(titleSearchIndex.phraseFor("äx")).isEmpty();
        List<String> hrefs = searchChapters("äx");

        // THEN it still matches, because the fallback matches every case variant of the term
        assertThat(hrefs).contains("/chapter/" + c.getId());
    }

    @Test
    void shouldStayCaseInsensitiveWhenTermMatchesMoreRowsThanAnIdSetCouldCarry()
    {
        // GIVEN more matches than a Java-side id set capped at 1000 could hold
        for (int i = 0; i < 1001; i++)
        {
            chapter("ftsbroad ÄÖÜ " + i, "p", "n");
        }
        em.flush();
        clearCountCache();

        // WHEN searching for the accented term in lower case
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle("ftsbroad äöü");
        var page = searchService.search(sc);

        // THEN every row is found - no cap, no scan fallback, and still case-insensitive
        assertThat(page.getTotalElements()).isEqualTo(1001);
        assertThat(page.getContent()).isNotEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    private List<String> searchChapters(String term)
    {
        SearchCriteria sc = new SearchCriteria();
        sc.setTitle(term);
        return searchService.search(sc).getContent().stream().map(CardDto::getHref).toList();
    }

    private List<String> searchSeries(String term)
    {
        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.SERIES);
        sc.setTitle(term);
        return searchService.search(sc).getContent().stream().map(CardDto::getHref).toList();
    }

    private Chapter chapter(String full, String pretty, String nativeTitle)
    {
        var c = new Chapter();
        c.setTitleFull(full);
        c.setTitle(pretty);
        c.setNativeTitle(nativeTitle);
        c.setUploadDate(LocalDate.of(2021, 1, 1));
        c.setLanguage("English");
        return chapterRepository.save(c);
    }

    private Series series(String full, String pretty, String nativeTitle)
    {
        var s = new Series();
        s.setTitleFull(full);
        s.setTitle(pretty);
        s.setNativeTitle(nativeTitle);
        s.setCreatedDate(LocalDate.of(2021, 1, 1));   // NOT NULL: set on creation, never user-editable
        return seriesRepository.save(s);
    }

    /** The count cache is shared and no transaction rolls it back. */
    /**
     * FTS5 holds a transaction's index changes in memory and writes them at the next savepoint, which the next
     * statement touching the index opens. Flushed here, they are not counted against the updates under test.
     */
    private void flushTitleIndex()
    {
        jdbc.execute("savepoint flush_title_index");
        jdbc.execute("release flush_title_index");
    }

    /** Rows changed on this connection so far, by triggers and the index's own tables included. */
    private long totalChanges()
    {
        return jdbc.queryForObject("select total_changes()", Long.class);
    }

    private void clearCountCache()
    {
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();
    }
}

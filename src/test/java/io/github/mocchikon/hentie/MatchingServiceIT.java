package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.CandidateScope;
import io.github.mocchikon.hentie.dto.ChapterMatchDto;
import io.github.mocchikon.hentie.dto.SeriesMatchDto;
import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.MatchingService;
import io.github.mocchikon.hentie.service.SeriesService;
import io.github.mocchikon.hentie.service.match.TitleKey;
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
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Transactional
class MatchingServiceIT
{
    @Autowired MatchingService matchingService;
    @Autowired SeriesService seriesService;
    @Autowired SeriesRepository seriesRepository;
    @Autowired ChapterRepository chapterRepository;
    @Autowired ArtistRepository artistRepository;
    @PersistenceContext EntityManager em;

    private Artist artist(String name)
    {
        Artist a = new Artist();
        a.setName(name);
        return artistRepository.save(a);
    }

    /** With the matching keys and effective artists {@code SeriesService} maintains, which the seeks read. */
    private Series series(String titleFull, List<Artist> artists)
    {
        Series s = new Series();
        s.setTitleFull(titleFull);
        s.setTitle(titleFull);                        // title_pretty is NOT NULL (falls back to titleFull)
        s.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        s.setArtists(new ArrayList<>(artists));
        s.setEffectiveArtists(new ArrayList<>(artists));
        SeriesService.applyMatchKeys(s);
        return seriesRepository.save(s);
    }

    /** A chapter in no series, with the matching key {@code ChapterService} writes. */
    private Chapter chapter(String titleFull, List<Artist> artists)
    {
        return chapter(titleFull, "", artists);
    }

    /** With both keys {@code ChapterService} writes. */
    private Chapter chapter(String titleFull, String nativeTitle, List<Artist> artists)
    {
        Chapter c = new Chapter();
        c.setTitle(titleFull);
        c.setTitleFull(titleFull);
        c.setNativeTitle(nativeTitle);
        c.setUploadDate(LocalDate.of(2021, 1, 1));
        c.setLanguage("English");
        c.setArtists(new ArrayList<>(artists));
        c.setMatchKey(TitleKey.of(titleFull).getMatchKey());
        c.setNativeMatchKey(TitleKey.nativeKey(nativeTitle, TitleKey.of(titleFull)));
        return chapterRepository.save(c);
    }

    private Chapter filedIn(Series series, Chapter chapter)
    {
        chapter.setSeries(series);
        return chapterRepository.save(chapter);
    }

    private static List<Integer> chapterIds(List<ChapterMatchDto> matches)
    {
        return matches.stream().map(ChapterMatchDto::getChapterId).toList();
    }

    private static ChapterMatchDto matchFor(List<ChapterMatchDto> matches, Chapter chapter)
    {
        return matches.stream().filter(m -> m.getChapterId() == chapter.getId()).findFirst().orElseThrow();
    }

    @Test
    void shouldRankTheSharedArtistFirstWhenTwoSeriesCarryTheSameTitle()
    {
        // GIVEN two series a chapter could belong to by title alone, one of them by the same artist.
        Artist shared = artist("Shared Artist");
        Series sameArtist = series("My Great Adventure", List.of(shared));
        Series otherArtist = series("My Great Adventure", List.of(artist("Another Artist")));

        Chapter chapter = chapter("My Great Adventure", List.of(shared));
        em.flush();
        em.clear();

        // WHEN
        List<SeriesMatchDto> matches = matchingService.topMatches(chapter.getId(), 10);

        // THEN the shared artist decides, and pushes the other below the auto-linking threshold.
        assertThat(matches).extracting(SeriesMatchDto::getSeriesId)
                .contains(sameArtist.getId(), otherArtist.getId());
        assertThat(matches.getFirst().getSeriesId()).isEqualTo(sameArtist.getId());
        assertThat(matches.getFirst().getSharedArtists()).isEqualTo(1);
        assertThat(matches.getFirst().getScore()).isGreaterThan(matches.get(1).getScore());
    }

    @Test
    void shouldNotOfferASeriesWhenNeitherTitleNorArtistRelatesToIt()
    {
        // GIVEN a series sharing no artist, no title token and not even a blocking key.
        Series unrelated = series("zzzzzzzzzzzzzzzz", List.of());
        Chapter chapter = chapter("111111111111", List.of());
        em.flush();
        em.clear();

        // WHEN
        List<SeriesMatchDto> matches = matchingService.topMatches(chapter.getId(), 10);

        // THEN
        assertThat(matches)
                .extracting(SeriesMatchDto::getSeriesId)
                .doesNotContain(unrelated.getId());
    }

    @Test
    void shouldUseArtistsDerivedFromChaptersWhenSeriesHasNoOverride()
    {
        // GIVEN
        Artist shared = artist("Derived Artist");
        Series series = series("Host Series No Override", List.of());   // no artist override
        Chapter member = chapter("member", List.of(shared));
        member.setSeries(series);
        chapterRepository.save(member);
        seriesService.recomputeDerived(series.getId());   // materializes the effective artists

        Chapter incoming = chapter("incoming", List.of(shared));
        em.flush();
        em.clear();

        // WHEN
        List<SeriesMatchDto> matches = matchingService.topMatches(incoming.getId(), 10);

        // THEN
        assertThat(matches).anySatisfy(m ->
        {
            assertThat(m.getSeriesId()).isEqualTo(series.getId());
            assertThat(m.getSharedArtists()).isEqualTo(1);
        });
    }

    /**
     * A misspelled chapter that auto-linking gave a series of its own. Counted as an exact hit, that series
     * would switch off the fuzzy pass that finds the family, then be excluded, leaving nothing to offer.
     */
    @Test
    void shouldOfferTheFamilyOfAMisspelledChapterThatAutoLinkingGaveASeriesOfItsOwn()
    {
        // GIVEN the family, and a sibling by its artist misspelling the title, alone in a series named after it.
        Artist artist = artist("Misspelled Sibling Artist");
        Series family = series("Isekai Yuusha", List.of());
        filedIn(family, chapter("Isekai Yuusha 2", List.of(artist)));
        seriesService.recomputeDerived(family.getId());

        Chapter misspelled = chapter("Isekai Yuushaa 3", List.of(artist));
        Series own = series("Isekai Yuushaa 3", List.of());
        filedIn(own, misspelled);
        seriesService.recomputeDerived(own.getId());
        assertThat(own.getMatchKey()).isEqualTo(misspelled.getMatchKey()).isEqualTo("isekai yuushaa");
        em.flush();
        em.clear();

        // WHEN
        List<SeriesMatchDto> matches = matchingService.topMatches(misspelled.getId(), 5);

        // THEN the family leads over the auto-linking threshold, and the chapter's own series is not offered.
        assertThat(matches).extracting(SeriesMatchDto::getSeriesId).doesNotContain(own.getId());
        assertThat(matches).first().satisfies(m ->
        {
            assertThat(m.getSeriesId()).isEqualTo(family.getId());
            assertThat(m.getTitleFull()).isEqualTo("Isekai Yuusha");
            assertThat(m.getChapterCount()).isEqualTo(1);
            assertThat(m.getSharedArtists()).isEqualTo(1);
            assertThat(m.getTitleSimilarity()).isCloseTo(0.986, within(0.001));
            assertThat(m.getScore()).isCloseTo(0.991, within(0.001));
        });
    }

    @Test
    void shouldRespectLimitWhenFindingTopMatches()
    {
        // GIVEN
        Artist shared = artist("Popular Artist");
        for (int i = 0; i < 5; i++)
        {
            series("Series " + i, List.of(shared));
        }
        Chapter chapter = chapter("chapter for limit", List.of(shared));
        em.flush();
        em.clear();

        // WHEN
        List<SeriesMatchDto> matches = matchingService.topMatches(chapter.getId(), 2);

        // THEN
        assertThat(matches).hasSize(2);
    }

    @Test
    void shouldMatchFullAndNativeTitlesButNotThePrettyOneWhenSearchingByName()
    {
        // GIVEN
        Series byFull = series("Alpha Full Title", List.of());
        Series byPretty = series("unrelated-full-1", List.of());
        byPretty.setTitle("Alpha Pretty");
        Series byNative = series("unrelated-full-2", List.of());
        byNative.setNativeTitle("Alpha Native");
        seriesRepository.saveAll(List.of(byPretty, byNative));
        em.flush();
        em.clear();

        // WHEN
        List<SeriesMatchDto> matches = matchingService.searchByName("alpha", 10);

        // THEN the box searches what the search form does: titleFull or nativeTitle, never the pretty title.
        assertThat(matches)
                .extracting(SeriesMatchDto::getSeriesId)
                .contains(byFull.getId(), byNative.getId())
                .doesNotContain(byPretty.getId());
    }

    @Test
    void shouldStillMatchWhenTheTermDiffersInCaseOrIsTooShortForTheIndex()
    {
        // GIVEN a title the index matches only case-insensitively, and a term too short for the index.
        Series accented = series("ÄÖÜ Cased Series", List.of());
        em.flush();
        em.clear();

        // WHEN + THEN
        assertThat(matchingService.searchByName("äöü", 10))
                .extracting(SeriesMatchDto::getSeriesId).contains(accented.getId());
        assertThat(matchingService.searchByName("Ä", 10))
                .extracting(SeriesMatchDto::getSeriesId).contains(accented.getId());
    }

    @Test
    void shouldRankTheClosestNameFirstWhenSearchingByName()
    {
        // GIVEN two matches, one of which is the term itself.
        Series exact = series("Rankterm", List.of());
        Series longer = series("Rankterm With A Much Longer Tail", List.of());
        em.flush();
        em.clear();

        // WHEN
        List<SeriesMatchDto> matches = matchingService.searchByName("Rankterm", 10);

        // THEN the closer name leads, not the lower id.
        assertThat(matches).extracting(SeriesMatchDto::getSeriesId)
                .containsSubsequence(exact.getId(), longer.getId());
        assertThat(matches.getFirst().getTitleSimilarity()).isEqualTo(1.0);
    }

    @Test
    void shouldReturnEmptyWhenSearchQueryBlank()
    {
        // GIVEN
        series("Anything", List.of());
        em.flush();

        // WHEN
        List<SeriesMatchDto> blankQuery = matchingService.searchByName("   ", 10);
        List<SeriesMatchDto> nullQuery = matchingService.searchByName(null, 10);

        // THEN
        assertThat(blankQuery).isEmpty();
        assertThat(nullQuery).isEmpty();
    }

    @Test
    void shouldRespectLimitWhenSearchingByName()
    {
        // GIVEN
        for (int i = 0; i < 4; i++)
        {
            series("Match Term " + i, List.of());
        }
        em.flush();

        // WHEN
        List<SeriesMatchDto> matches = matchingService.searchByName("Match Term", 2);

        // THEN
        assertThat(matches).hasSize(2);
    }

    // ---- Link chapters: the chapters a series is missing ---------------------

    @Test
    void shouldOfferTheUnlinkedChaptersOfTheFamilyAndNothingFiledInASeriesByDefault()
    {
        // GIVEN a series with its own chapter, unlinked chapters under its exact and an extending key, one of the
        // family filed in another series, and an unrelated one.
        Series series = series("Lcm Ohayo Saga", List.of());
        Chapter own = filedIn(series, chapter("Lcm Ohayo Saga 1", List.of()));
        Chapter exact = chapter("Lcm Ohayo Saga 2", List.of());
        Chapter extension = chapter("Lcm Ohayo Saga Suizokukan", List.of());
        Chapter elsewhere = filedIn(series("Lcm Other Home", List.of()), chapter("Lcm Ohayo Saga 3", List.of()));
        Chapter unrelated = chapter("Lcm Something Else Entirely", List.of());
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> matches = matchingService.topChapterMatches(series.getId(), CandidateScope.UNLINKED, 30);

        // THEN the two unlinked members, the exact key ahead of the extension...
        assertThat(chapterIds(matches))
                .containsSubsequence(exact.getId(), extension.getId())
                .doesNotContain(own.getId(), elsewhere.getId(), unrelated.getId());
        // ...each scored as auto-linking would score it.
        assertThat(matchFor(matches, exact)).satisfies(m ->
        {
            assertThat(m.getTitleFull()).isEqualTo("Lcm Ohayo Saga 2");
            assertThat(m.getLanguage()).isEqualTo("English");
            assertThat(m.getTitleSimilarity()).isEqualTo(1.0);
            assertThat(m.getSharedArtists()).isZero();
            assertThat(m.getScore()).isCloseTo(0.82, within(0.001));   // identical key, artist unknown
            assertThat(m.getSeriesId()).isNull();
            assertThat(m.getSeriesTitle()).isNull();
            assertThat(m.getSeriesChapterCount()).isZero();
        });
        assertThat(matchFor(matches, extension)).satisfies(m ->
        {
            assertThat(m.getTitleSimilarity()).isCloseTo(0.95, within(0.001));   // a clean token prefix
            assertThat(m.getScore()).isCloseTo(0.79, within(0.001));
            assertThat(m.getSeriesId()).isNull();
        });
    }

    @Test
    void shouldAlsoOfferChaptersFiledInOtherSeriesButNeverItsOwnWhenLookingAmongAll()
    {
        // GIVEN a series with a chapter of its own, a sibling filed in another series of two, and a loose one.
        Series series = series("Lcm Kaze Tachinu", List.of());
        Chapter own = filedIn(series, chapter("Lcm Kaze Tachinu 1", List.of()));
        Series otherHome = series("Lcm Kaze Other Home", List.of());
        Chapter elsewhere = filedIn(otherHome, chapter("Lcm Kaze Tachinu 2", List.of()));
        filedIn(otherHome, chapter("Lcm Kaze Other Home 2", List.of()));
        Chapter loose = chapter("Lcm Kaze Tachinu 3", List.of());
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> matches = matchingService.topChapterMatches(series.getId(), CandidateScope.ALL, 30);

        // THEN both the loose chapter and the one filed elsewhere - never one already here...
        assertThat(chapterIds(matches)).contains(elsewhere.getId(), loose.getId()).doesNotContain(own.getId());
        // ...and the one filed elsewhere says where it is now, and how many chapters that series holds.
        assertThat(matchFor(matches, elsewhere)).satisfies(m ->
        {
            assertThat(m.getSeriesId()).isEqualTo(otherHome.getId());
            assertThat(m.getSeriesTitle()).isEqualTo("Lcm Kaze Other Home");
            assertThat(m.getSeriesChapterCount()).isEqualTo(2);
            assertThat(m.getTitleSimilarity()).isEqualTo(1.0);
        });
        assertThat(matchFor(matches, loose)).satisfies(m ->
        {
            assertThat(m.getSeriesId()).isNull();
            assertThat(m.getSeriesChapterCount()).isZero();
        });
    }

    /**
     * A title misspelled in its first letters shares neither key nor block, so only the artist finds it; the
     * artist seek must run even when the exact key found chapters.
     */
    @Test
    void shouldFindASiblingMisspelledInItsFirstLettersThroughASharedArtist()
    {
        // GIVEN a series, a chapter under its exact key, and one misspelling its first word - both by its artist.
        Artist artist = artist("Lcm Typo Artist");
        Series series = series("Lcm Isekai Yuusha", List.of(artist));
        Chapter exact = chapter("Lcm Isekai Yuusha 2", List.of(artist));
        Chapter misspelled = chapter("Lxm Isekai Yuusha 3", List.of(artist));
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> matches = matchingService.topChapterMatches(series.getId(), CandidateScope.UNLINKED, 30);

        // THEN both are offered, the misspelled one on the shared artist alone.
        assertThat(chapterIds(matches)).containsSubsequence(exact.getId(), misspelled.getId());
        assertThat(matchFor(matches, misspelled)).satisfies(m ->
        {
            assertThat(m.getSharedArtists()).isEqualTo(1);
            assertThat(m.getTitleSimilarity()).isZero();
            assertThat(m.getScore()).isCloseTo(0.4, within(0.001));
        });
        assertThat(matchFor(matches, exact)).satisfies(m ->
        {
            assertThat(m.getSharedArtists()).isEqualTo(1);
            assertThat(m.getTitleSimilarity()).isEqualTo(1.0);
            assertThat(m.getScore()).isEqualTo(1.0);
        });
    }

    /**
     * Words run together and no shared artist: the artist seek runs and finds nothing, so the block seek must
     * run too. Scored as Add to series scores the pair.
     */
    @Test
    void shouldFindAChapterRunningTheWordsTogetherThatSharesNoArtist()
    {
        // GIVEN a series with an artist, and an artist-less chapter titled with its words run together.
        Series series = series("[Lcm Test] Lcm My Test", List.of(artist("Lcm Block Artist")));
        Chapter joined = chapter("LcmMyTestNew 2", List.of());
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> matches = matchingService.topChapterMatches(series.getId(), CandidateScope.UNLINKED, 30);

        // THEN the word-break-blind comparison relates them.
        assertThat(matchFor(matches, joined)).satisfies(m ->
        {
            assertThat(m.getSharedArtists()).isZero();
            assertThat(m.getTitleSimilarity()).isCloseTo(0.93, within(0.001));
            assertThat(m.getScore()).isCloseTo(0.778, within(0.001));   // artist unknown on the chapter's side
            assertThat(m.getSeriesId()).isNull();
        });
    }

    /** The other way round: the chapter breaks a word the series' title keeps whole. */
    @Test
    void shouldFindAChapterSplittingAWordOfTheSeriesTitle()
    {
        // GIVEN
        Series series = series("Lcm Splitsaga", List.of());
        Chapter split = chapter("Lcm Split Saga 4", List.of());
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> matches = matchingService.topChapterMatches(series.getId(), CandidateScope.UNLINKED, 30);

        // THEN the same letters broken differently score as the same title, just under an exact one.
        assertThat(matchFor(matches, split)).satisfies(m ->
        {
            assertThat(m.getTitleSimilarity()).isCloseTo(0.97, within(0.001));
            assertThat(m.getScore()).isCloseTo(0.802, within(0.001));
        });
    }

    /** The walk outward from the series' title must reach a misspelling on either side of it in key order. */
    @Test
    void shouldFindChaptersMisspelledPastTheBlockOnEitherSideOfTheTitle()
    {
        // GIVEN two artist-less chapters misspelling the second word, one sorting below the series' spaceless
        // title ("lcmkitsn..." < "lcmkitsu...") and one above it ("lcmkitsy...").
        Series series = series("Lcm Kitsune Tales", List.of());
        Chapter below = chapter("Lcm Kitsnue Tales 2", List.of());
        Chapter above = chapter("Lcm Kitsyne Tales 3", List.of());
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> matches = matchingService.topChapterMatches(series.getId(), CandidateScope.UNLINKED, 30);

        // THEN both are offered over the auto-linking threshold.
        assertThat(matchFor(matches, below)).satisfies(m ->
        {
            assertThat(m.getTitleSimilarity()).isBetween(0.9, 1.0);
            assertThat(m.getScore()).isGreaterThan(0.75);
        });
        assertThat(matchFor(matches, above)).satisfies(m ->
        {
            assertThat(m.getTitleSimilarity()).isBetween(0.9, 1.0);
            assertThat(m.getScore()).isGreaterThan(0.75);
        });
    }

    /**
     * A translation titled in another language shares no word with the series, but carries the original's
     * Japanese title, as the series' chapters do.
     */
    @Test
    void shouldOfferATranslationThroughTheNativeTitleItsChaptersShare()
    {
        // GIVEN a series whose chapter carries a native title, and a translation under an English title
        Series series = series("[Lcm Circle] Lcm Isekai Bouken", List.of());
        filedIn(series, chapter("[Lcm Circle] Lcm Isekai Bouken 1", "[サークル] 異世界勇者の冒険 1", List.of()));
        Chapter translated = chapter("[Lcm Circle] The Adventures of an Otherworld Hero Ch. 2",
                "[サークル] 異世界勇者の冒険 第2話 [英訳]", List.of());
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> offered = matchingService.topChapterMatches(series.getId(), CandidateScope.UNLINKED, 30);
        List<SeriesMatchDto> homes = matchingService.topMatches(translated.getId(), 5);

        // THEN both pages relate them on the native title alone, with the same score.
        assertThat(matchFor(offered, translated)).satisfies(m ->
        {
            assertThat(m.getTitleSimilarity()).isEqualTo(1.0);
            assertThat(m.getScore()).isCloseTo(0.82, within(0.001));   // no artist known on either side
        });
        assertThat(homes).first().satisfies(m ->
        {
            assertThat(m.getSeriesId()).isEqualTo(series.getId());
            assertThat(m.getScore()).isCloseTo(0.82, within(0.001));
        });
    }

    /** "Add to series" must look past the key hit of the chapter's own series, as "Link chapters" does. */
    @Test
    void shouldOfferTheSeriesLinkChaptersWouldOfferEvenWhenTheChapterIsAloneInASeriesOfItsOwn()
    {
        // GIVEN a family, and its translation alone in a series named after it, sharing the family's artist
        Artist artist = artist("Lcm Own Series Artist");
        Series family = series("Lcm Kawaii Houhou", List.of());
        filedIn(family, chapter("Lcm Kawaii Houhou 1", "[サークル] カワイイ方法 1", List.of(artist)));
        seriesService.recomputeDerived(family.getId());
        Chapter translated = chapter("Lcm Method to Catch Her 2", "[サークル] カワイイ方法 2", List.of(artist));
        Series own = series("Lcm Method to Catch Her", List.of());
        filedIn(own, translated);
        seriesService.recomputeDerived(own.getId());
        em.flush();
        em.clear();

        // WHEN
        List<SeriesMatchDto> homes = matchingService.topMatches(translated.getId(), 5);
        List<ChapterMatchDto> offered = matchingService.topChapterMatches(family.getId(), CandidateScope.ALL, 30);

        // THEN the family is offered from both sides, with one score.
        assertThat(homes).first().satisfies(m ->
        {
            assertThat(m.getSeriesId()).isEqualTo(family.getId());
            assertThat(m.getScore()).isEqualTo(1.0);
        });
        assertThat(matchFor(offered, translated).getScore()).isEqualTo(1.0);
    }

    @Test
    void shouldRankTheChapterSharingTheArtistFirstAmongIdenticalTitles()
    {
        // GIVEN two unlinked chapters with the series' very title, the older one by a different artist.
        Artist shared = artist("Lcm Shared Artist");
        Series series = series("Lcm Twin Title", List.of(shared));
        Chapter byOther = chapter("Lcm Twin Title", List.of(artist("Lcm Other Artist")));
        Chapter byShared = chapter("Lcm Twin Title", List.of(shared));
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> matches = matchingService.topChapterMatches(series.getId(), CandidateScope.UNLINKED, 30);

        // THEN the artist decides, not the id.
        assertThat(chapterIds(matches)).containsSubsequence(byShared.getId(), byOther.getId());
        assertThat(matches.getFirst().getChapterId()).isEqualTo(byShared.getId());
        assertThat(matches.getFirst().getSharedArtists()).isEqualTo(1);
        assertThat(matchFor(matches, byOther).getSharedArtists()).isZero();
        assertThat(matchFor(matches, byOther).getScore()).isCloseTo(0.6, within(0.001));
        assertThat(matches.getFirst().getScore()).isGreaterThan(matchFor(matches, byOther).getScore());
    }

    @Test
    void shouldOfferNoChaptersForASeriesWhoseTitleLeavesNoKey()
    {
        // GIVEN a series titled only by a bracket group, and a chapter titled the same.
        Series series = series("(Lcm C99)", List.of());
        chapter("(Lcm C99)", List.of());
        em.flush();
        em.clear();

        // WHEN + THEN nothing is matched on emptiness.
        assertThat(matchingService.topChapterMatches(series.getId(), CandidateScope.ALL, 30)).isEmpty();
    }

    @Test
    void shouldRespectTheLimitWhenRankingChapters()
    {
        // GIVEN
        Series series = series("Lcm Limit Saga", List.of());
        for (int i = 2; i <= 5; i++)
        {
            chapter("Lcm Limit Saga " + i, List.of());
        }
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> matches = matchingService.topChapterMatches(series.getId(), CandidateScope.UNLINKED, 2);

        // THEN
        assertThat(matches).hasSize(2);
    }

    /** The name search takes the page's scope, so it never offers what the ranking would refuse. */
    @Test
    void shouldSearchChaptersByNameWithinTheScopeAndNeverOfferTheSeriesOwn()
    {
        // GIVEN chapters sharing a term: one in the series, one filed in another series, one in none.
        Series series = series("Lcm Search Host", List.of());
        Chapter own = filedIn(series, chapter("Lcm Needle Own", List.of()));
        Series otherHome = series("Lcm Search Elsewhere", List.of());
        Chapter elsewhere = filedIn(otherHome, chapter("Lcm Needle Elsewhere", List.of()));
        Chapter loose = chapter("Lcm Needle Loose", List.of());
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> unlinked = matchingService.searchChaptersByName("lcm needle", series.getId(),
                CandidateScope.UNLINKED, 10);
        List<ChapterMatchDto> all = matchingService.searchChaptersByName("lcm needle", series.getId(),
                CandidateScope.ALL, 10);

        // THEN chapters in no series only, or every one not already here...
        assertThat(chapterIds(unlinked)).contains(loose.getId()).doesNotContain(own.getId(), elsewhere.getId());
        assertThat(chapterIds(all)).contains(loose.getId(), elsewhere.getId()).doesNotContain(own.getId());
        // ...the one filed elsewhere naming where it is now.
        assertThat(matchFor(all, elsewhere)).satisfies(m ->
        {
            assertThat(m.getSeriesId()).isEqualTo(otherHome.getId());
            assertThat(m.getSeriesTitle()).isEqualTo("Lcm Search Elsewhere");
            assertThat(m.getSeriesChapterCount()).isEqualTo(1);
            assertThat(m.getSharedArtists()).isZero();
        });
        assertThat(matchFor(all, loose).getSeriesId()).isNull();
    }

    @Test
    void shouldRankTheClosestNameFirstWhenSearchingChaptersByName()
    {
        // GIVEN two matches, one of which is the term itself.
        Series series = series("Lcm Rank Host", List.of());
        Chapter exact = chapter("Lcm Rankterm", List.of());
        Chapter longer = chapter("Lcm Rankterm With A Much Longer Tail", List.of());
        em.flush();
        em.clear();

        // WHEN
        List<ChapterMatchDto> matches = matchingService.searchChaptersByName("Lcm Rankterm", series.getId(),
                CandidateScope.UNLINKED, 10);

        // THEN the closer name leads, not the lower id.
        assertThat(chapterIds(matches)).containsSubsequence(exact.getId(), longer.getId());
        assertThat(matches.getFirst().getTitleSimilarity()).isEqualTo(1.0);
        assertThat(matches.getFirst().getScore()).isEqualTo(1.0);
    }

    @Test
    void shouldFindNoChaptersWhenTheNameSearchIsBlank()
    {
        // GIVEN
        Series series = series("Lcm Blank Host", List.of());
        chapter("Lcm Blank Candidate", List.of());
        em.flush();

        // WHEN
        List<ChapterMatchDto> blankQuery = matchingService.searchChaptersByName("   ", series.getId(), CandidateScope.ALL, 10);
        List<ChapterMatchDto> nullQuery = matchingService.searchChaptersByName(null, series.getId(), CandidateScope.ALL, 10);

        // THEN
        assertThat(blankQuery).isEmpty();
        assertThat(nullQuery).isEmpty();
    }
}

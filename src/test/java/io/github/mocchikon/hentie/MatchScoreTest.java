package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.match.MatchScore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the scoring model against the default threshold, since that one comparison decides which title and
 * artist agreements may merge two things.
 */
class MatchScoreTest
{
    private static final double THRESHOLD = SettingsService.DEFAULT_MATCH_THRESHOLD / 100.0;

    private static final List<String> ISEKAI_YUUSHA = List.of("isekai", "yuusha");

    private static double score(List<String> a, List<String> b, double artistScore)
    {
        return MatchScore.combined(MatchScore.titleScore(a, b), artistScore);
    }

    @Test
    void shouldScoreOneWhenBothTitlesAndArtistsAgree()
    {
        assertThat(MatchScore.titleScore(ISEKAI_YUUSHA, ISEKAI_YUUSHA)).isCloseTo(1.0, within(0.001));
        assertThat(score(ISEKAI_YUUSHA, ISEKAI_YUUSHA, MatchScore.ARTIST_SHARED))
                .isCloseTo(1.0, within(0.001));
    }

    @Test
    void shouldRankAnExtensionJustBelowAnExactMatchWhenOneTitlePrefixesTheOther()
    {
        double prefix = MatchScore.titleScore(List.of("ohayo"), List.of("ohayo", "suizokukan"));
        assertThat(prefix).isCloseTo(0.95, within(0.001));
        assertThat(MatchScore.titleScore(List.of("ohayo", "suizokukan"), List.of("ohayo")))
                .isCloseTo(prefix, within(0.001));
        assertThat(score(List.of("ohayo"), List.of("ohayo", "am10", "00"), MatchScore.ARTIST_SHARED))
                .isGreaterThan(THRESHOLD);
    }

    @Test
    void shouldStillMatchWhenASingleTokenIsMisspelled()
    {
        // The reason scoring is fuzzy: a typo must not start a second series.
        assertThat(MatchScore.titleScore(List.of("isekai", "yusha"), ISEKAI_YUUSHA)).isGreaterThan(0.9);
        assertThat(score(List.of("isekai", "yusha"), ISEKAI_YUUSHA, MatchScore.ARTIST_SHARED))
                .isGreaterThan(THRESHOLD);
    }

    @Test
    void shouldNotMergeADifferentWorkWhenOnlyTheArtistAndALeadingWordAgree()
    {
        double partial = MatchScore.titleScore(List.of("isekai", "maou"), ISEKAI_YUUSHA);
        assertThat(partial).isLessThan(0.5);
        assertThat(score(List.of("isekai", "maou"), ISEKAI_YUUSHA, MatchScore.ARTIST_SHARED))
                .isLessThan(THRESHOLD);
    }

    @Test
    void shouldNotMergeWhenTheTitleIsIdenticalButTheArtistsAreDisjoint()
    {
        assertThat(score(ISEKAI_YUUSHA, ISEKAI_YUUSHA, MatchScore.ARTIST_DISJOINT)).isLessThan(THRESHOLD);
        // The cap is the title weight, so the veto survives any threshold above it.
        assertThat(score(ISEKAI_YUUSHA, ISEKAI_YUUSHA, MatchScore.ARTIST_DISJOINT))
                .isCloseTo(MatchScore.TITLE_WEIGHT, within(0.001));
    }

    @Test
    void shouldStillMatchOnTheTitleAloneWhenNoArtistIsKnown()
    {
        assertThat(score(ISEKAI_YUUSHA, ISEKAI_YUUSHA, MatchScore.ARTIST_UNKNOWN))
                .isGreaterThanOrEqualTo(THRESHOLD);
        assertThat(score(List.of("isekai", "maou"), ISEKAI_YUUSHA, MatchScore.ARTIST_UNKNOWN))
                .isLessThan(THRESHOLD);
    }

    @Test
    void shouldReportArtistAgreementFromTheTwoSets()
    {
        assertThat(MatchScore.artistScore(Set.of(1, 2), Set.of(2, 3))).isEqualTo(MatchScore.ARTIST_SHARED);
        assertThat(MatchScore.artistScore(Set.of(1), Set.of(3))).isEqualTo(MatchScore.ARTIST_DISJOINT);
        assertThat(MatchScore.artistScore(Set.of(), Set.of(3))).isEqualTo(MatchScore.ARTIST_UNKNOWN);
        assertThat(MatchScore.artistScore(Set.of(1), Set.of())).isEqualTo(MatchScore.ARTIST_UNKNOWN);
        assertThat(MatchScore.artistScore(null, Set.of(1))).isEqualTo(MatchScore.ARTIST_UNKNOWN);

        assertThat(MatchScore.sharedArtists(Set.of(1, 2, 3), Set.of(2, 3, 4))).isEqualTo(2);
        assertThat(MatchScore.sharedArtists(Set.of(1), Set.of(2))).isZero();
    }

    @Test
    void shouldMatchWhenOnlyTheWordBreaksDifferBetweenTheTitles()
    {
        // The token pass scores 0 here (one extra space shifts every position), so the condensed pass must
        // carry it, on the title alone.
        var spaced = List.of("ie", "de", "blah", "blah", "blah", "kei", "san");
        var joined = List.of("iede", "blah", "blah", "blah", "kei", "san");
        assertThat(MatchScore.titleScore(spaced, joined)).isCloseTo(0.97, within(0.001));
        assertThat(score(spaced, joined, MatchScore.ARTIST_UNKNOWN)).isGreaterThan(THRESHOLD);
        assertThat(score(spaced, joined, MatchScore.ARTIST_SHARED)).isGreaterThan(THRESHOLD);
        // Below a token-exact match, so that one always outranks it.
        assertThat(MatchScore.titleScore(joined, spaced)).isCloseTo(0.97, within(0.001));
        assertThat(MatchScore.titleScore(spaced, joined)).isLessThan(MatchScore.titleScore(spaced, spaced));
    }

    @Test
    void shouldMatchASiblingSharingALongHeadOnlyWhenTheArtistAgrees()
    {
        // Word breaks and tails differ: 22 shared characters suggest one family but do not prove it, so a
        // shared artist is needed.
        var a = List.of("ie", "de", "blah", "blah", "blah", "kei", "san", "something", "suizokukan");
        var b = List.of("iede", "blah", "blah", "blah", "kei", "san", "de", "inaka");
        assertThat(MatchScore.titleScore(a, b)).isCloseTo(0.80, within(0.001));
        assertThat(score(a, b, MatchScore.ARTIST_SHARED)).isGreaterThan(THRESHOLD);
        assertThat(score(a, b, MatchScore.ARTIST_UNKNOWN)).isLessThan(THRESHOLD);
        assertThat(score(a, b, MatchScore.ARTIST_DISJOINT)).isLessThan(THRESHOLD);
    }

    @Test
    void shouldNotMergeOnAShortSharedHeadEvenWithTheSameArtist()
    {
        // Controls for the case above: coverage ratios are close to it, so only the absolute shared length
        // (6/3/5 characters against 22) separates them.
        assertThat(MatchScore.titleScore(List.of("isekai", "maou"), ISEKAI_YUUSHA)).isLessThan(0.5);
        assertThat(MatchScore.titleScore(List.of("the", "end"), List.of("the", "beginning"))).isLessThan(0.5);
        assertThat(score(List.of("isekai", "maou"), ISEKAI_YUUSHA, MatchScore.ARTIST_SHARED))
                .isLessThan(THRESHOLD);
        assertThat(score(List.of("the", "end"), List.of("the", "beginning"), MatchScore.ARTIST_SHARED))
                .isLessThan(THRESHOLD);
        // Ohayo siblings still relate through their shared base, not to each other.
        assertThat(score(List.of("ohayo", "am10", "00"), List.of("ohayo", "suizokukan"),
                MatchScore.ARTIST_SHARED)).isLessThan(THRESHOLD);
        assertThat(score(List.of("ohayo"), List.of("ohayo", "suizokukan"), MatchScore.ARTIST_SHARED))
                .isGreaterThan(THRESHOLD);
    }

    @Test
    void shouldNotTakeADifferentWordForATypoWhenOnlyItsStartAgrees()
    {
        // Jaro-Winkler gives these 0.91 for their shared start; five edits apart, they are two words.
        assertThat(MatchScore.titleScore(List.of("oshiri"), List.of("oshiroibana"))).isLessThan(0.5);
        assertThat(score(List.of("oshiri"), List.of("oshiroibana"), MatchScore.ARTIST_UNKNOWN)).isLessThan(THRESHOLD);
        // Too short to carry a typo, and two letters apart.
        assertThat(MatchScore.titleScore(List.of("iya", "naka"), List.of("iyada", "naka"))).isLessThan(0.5);
        assertThat(MatchScore.titleScore(List.of("rikoteki", "emotion"), List.of("ritateki", "emotion")))
                .isLessThan(0.5);
        // In Japanese one character is a word: a sleeping wife is not a sleeping girl.
        assertThat(MatchScore.titleScore(List.of("起きない妻"), List.of("起きない子"))).isLessThan(0.5);
    }

    @Test
    void shouldStillTakeOneSmallEditForATypo()
    {
        // A swap of neighbours is one edit, as is a doubled letter.
        assertThat(MatchScore.titleScore(List.of("kitsnue", "tales"), List.of("kitsune", "tales"))).isGreaterThan(0.9);
        assertThat(MatchScore.titleScore(List.of("isekai", "yuushaa"), ISEKAI_YUUSHA)).isGreaterThan(0.9);
    }

    @Test
    void shouldNotTakeAMarkerWordForTheBaseOfEveryTitleStartingWithIt()
    {
        // "after" is what "after❤" keys to; as a base it would claim "After-School Tutoring".
        var after = List.of("after");
        assertThat(MatchScore.titleScore(after, List.of("after", "school", "tutoring"))).isLessThanOrEqualTo(0.5);
        assertThat(MatchScore.titleScore(after, List.of("afterschool"))).isZero();
        assertThat(score(after, List.of("after", "school", "tutoring"), MatchScore.ARTIST_UNKNOWN))
                .isLessThan(THRESHOLD);
        // The same word is still the same title.
        assertThat(MatchScore.titleScore(after, after)).isEqualTo(1.0);
    }

    @Test
    void shouldNotVetoAWorkDrawnByManyWhenItsArtistsDiffer()
    {
        // An anthology volume or a magazine issue has a new line-up every time.
        var anthology = Set.of(2, 3, 4, 5, 6);
        assertThat(MatchScore.artistScore(Set.of(1), anthology)).isEqualTo(MatchScore.ARTIST_UNKNOWN);
        assertThat(MatchScore.artistScore(anthology, Set.of(1))).isEqualTo(MatchScore.ARTIST_UNKNOWN);
        assertThat(score(List.of("dungeon", "kouryaku"), List.of("dungeon", "kouryaku"),
                MatchScore.artistScore(Set.of(1), anthology))).isGreaterThanOrEqualTo(THRESHOLD);
        // A few artists still veto.
        assertThat(MatchScore.artistScore(Set.of(1), Set.of(2, 3))).isEqualTo(MatchScore.ARTIST_DISJOINT);
    }

    @Test
    void shouldIgnoreAgreeingNativeTitlesWhenTheyAreTooShortToNameAWork()
    {
        assertThat(MatchScore.nativeTitleScore(List.of("総集編"), List.of("総集編"))).isZero();
        assertThat(MatchScore.nativeTitleScore(List.of("起きない子"), List.of("起きない子"))).isEqualTo(1.0);
        assertThat(MatchScore.nativeTitleScore(List.of("異世界勇者"), List.of("異世界勇者", "まとめ")))
                .isCloseTo(0.95, within(0.001));
        // So the finders do not seek such keys at all; spaces do not count.
        assertThat(MatchScore.isDistinctiveNative("総集編")).isFalse();
        assertThat(MatchScore.isDistinctiveNative("総集 編")).isFalse();
        assertThat(MatchScore.isDistinctiveNative("起きない子")).isTrue();
    }

    @Test
    void shouldScoreZeroWhenEitherTitleHasNoUsableTokens()
    {
        assertThat(MatchScore.titleScore(List.of(), ISEKAI_YUUSHA)).isZero();
        assertThat(MatchScore.titleScore(ISEKAI_YUUSHA, List.of())).isZero();
    }
}

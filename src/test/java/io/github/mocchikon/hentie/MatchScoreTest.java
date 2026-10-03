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
    void shouldScoreZeroWhenEitherTitleHasNoUsableTokens()
    {
        assertThat(MatchScore.titleScore(List.of(), ISEKAI_YUUSHA)).isZero();
        assertThat(MatchScore.titleScore(ISEKAI_YUUSHA, List.of())).isZero();
    }
}

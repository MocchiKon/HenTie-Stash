package io.github.mocchikon.hentie.service.match;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * One score in {@code [0,1]}, compared against {@code matching.threshold} (default 0.75).
 *
 * <pre>score = {@value #TITLE_WEIGHT} * titleScore + {@value #ARTIST_WEIGHT} * artistScore</pre>
 *
 * <p>The title is compared token by token, not with plain Jaro-Winkler: JW rewards a shared prefix, so
 * {@code "isekai yuusha"} vs {@code "isekai maou"} would score ~0.85 and merge unrelated works.
 *
 * <table><caption>combined score</caption>
 * <tr><th>title</th><th>shared artist</th><th>artist unknown</th><th>artists disjoint</th></tr>
 * <tr><td>identical key (1.00)</td><td><b>1.00</b></td><td><b>0.82</b></td><td>0.60</td></tr>
 * <tr><td>token prefix (0.95)</td><td><b>0.98</b></td><td><b>0.79</b></td><td>0.57</td></tr>
 * <tr><td>prefix + typo (0.90)</td><td><b>0.94</b></td><td>0.75</td><td>0.54</td></tr>
 * <tr><td>partial overlap (0.50 max)</td><td>0.70</td><td>0.48</td><td>0.30</td></tr>
 * </table>
 * Disjoint artists cap the score at {@value #TITLE_WEIGHT}, and a shared artist lifts an unrelated title
 * to 0.70 at most, so any threshold above ~0.71 is safe. Do not raise the artist weight: at 0.5 a partial
 * overlap reaches 0.75 and different works by one artist merge.
 */
public final class MatchScore
{
    public static final double TITLE_WEIGHT = 0.6;

    public static final double ARTIST_WEIGHT = 0.4;

    public static final double ARTIST_SHARED = 1.0;

    /** One side has no artist, so the facet says nothing. */
    public static final double ARTIST_UNKNOWN = 0.55;

    public static final double ARTIST_DISJOINT = 0.0;

    /** Similarity at which two tokens count as one word with a typo. */
    private static final double TOKEN_MATCH = 0.90;

    /** So an exact match always outranks a prefix. */
    private static final double EXTENSION_FACTOR = 0.95;

    private static final double PARTIAL_CEILING = 0.5;

    /** Same title with different word breaks; just under 1.0 so a token-exact match still ranks first. */
    private static final double CONDENSED_EQUAL = 0.97;

    private static final double CONDENSED_PREFIX = 0.93;

    /**
     * A long shared head, then different text. Clears the default threshold only with a shared artist
     * (0.88), not without one (0.70).
     */
    private static final double SHARED_HEAD = 0.80;

    /**
     * This length, not the coverage ratio, is what keeps {@code isekai yuusha} and {@code isekai maou}
     * apart: their coverage (0.600) is close to a real pair's (0.759), their length (6 vs 22) is not.
     * {@link ChapterCandidateFinder} seeks by it, so it must not be larger there.
     */
    static final int MIN_SHARED_HEAD = 12;

    private static final double SHARED_HEAD_COVERAGE = 0.65;

    private static final JaroWinklerSimilarity SIMILARITY = new JaroWinklerSimilarity();

    private MatchScore()
    {
    }

    public static double combined(double titleScore, double artistScore)
    {
        return TITLE_WEIGHT * titleScore + ARTIST_WEIGHT * artistScore;
    }

    /**
     * The better of a token-by-token pass and a pass that ignores word breaks. The second exists because
     * romanized Japanese often breaks words differently ({@code Ie de}/{@code Iede}): one extra space
     * shifts every later token and the token pass scores 0.0. Lowering {@value #TOKEN_MATCH} instead
     * would merge different words.
     */
    public static double titleScore(List<String> a, List<String> b)
    {
        double tokenScore = tokenScore(a, b);
        // The condensed pass cannot beat this, so skip it in the common case.
        return tokenScore >= CONDENSED_PREFIX ? tokenScore : Math.max(tokenScore, condensedScore(a, b));
    }

    /**
     * Only a common prefix counts, never a substring or an edit distance: the head is the work's name, so
     * titles that differ from the first character are different works.
     */
    private static double condensedScore(List<String> a, List<String> b)
    {
        String x = String.join("", a);
        String y = String.join("", b);
        if (x.isEmpty() || y.isEmpty())
        {
            return 0.0;
        }
        if (x.equals(y))
        {
            return CONDENSED_EQUAL;
        }
        int shorter = Math.min(x.length(), y.length());
        int shared = 0;
        while (shared < shorter && x.charAt(shared) == y.charAt(shared))
        {
            shared++;
        }
        if (shared == shorter)
        {
            return CONDENSED_PREFIX;
        }
        return shared >= MIN_SHARED_HEAD && (double) shared / shorter >= SHARED_HEAD_COVERAGE
                ? SHARED_HEAD : 0.0;
    }

    /** A divergence before the shorter list ends is capped low enough that a shared artist cannot save it. */
    private static double tokenScore(List<String> a, List<String> b)
    {
        int shorter = Math.min(a.size(), b.size());
        int longer = Math.max(a.size(), b.size());
        if (shorter == 0)
        {
            return 0.0;
        }

        double total = 0.0;
        int matched = 0;
        for (int i = 0; i < shorter; i++)
        {
            double token = similarity(a.get(i), b.get(i));
            if (token < TOKEN_MATCH)
            {
                break;   // the titles diverge here; everything after it is a different work
            }
            total += token;
            matched++;
        }
        if (matched < shorter)
        {
            double mean = matched == 0 ? 0.0 : total / matched;
            return PARTIAL_CEILING * ((double) matched / shorter) * mean;
        }
        double mean = total / shorter;
        return shorter == longer ? mean : EXTENSION_FACTOR * mean;
    }

    public static double artistScore(Collection<Integer> a, Collection<Integer> b)
    {
        if (a == null || b == null || a.isEmpty() || b.isEmpty())
        {
            return ARTIST_UNKNOWN;
        }
        return sharedArtists(a, b) > 0 ? ARTIST_SHARED : ARTIST_DISJOINT;
    }

    public static int sharedArtists(Collection<Integer> a, Collection<Integer> b)
    {
        if (a == null || b == null)
        {
            return 0;
        }
        Set<Integer> smaller = Set.copyOf(a.size() <= b.size() ? a : b);
        Collection<Integer> larger = a.size() <= b.size() ? b : a;
        return (int) larger.stream().distinct().filter(smaller::contains).count();
    }

    private static double similarity(String a, String b)
    {
        if (a.equals(b))
        {
            return 1.0;
        }
        Double score = SIMILARITY.apply(a, b);
        return score == null ? 0.0 : score;
    }
}

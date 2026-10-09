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
 * <p>
 * A work drawn by many people (an anthology volume, a magazine issue) has a different line-up every time, so
 * from {@value #COLLECTION_ARTISTS} artists on either side a disjoint set counts as unknown, not as a veto.
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

    /**
     * Jaro-Winkler alone rewards a shared start: {@code oshiri} and {@code oshiroibana} score 0.91, and two works
     * would merge. A typo must also be a small edit of a word long enough to have one: shorter words differ in
     * meaning ({@code iya}, {@code iyada}).
     */
    private static final int MIN_TYPO_LENGTH = 4;

    /** From this length on a typo may take two edits; below it one. */
    private static final int TWO_EDIT_LENGTH = 10;

    /** See the class comment. */
    static final int COLLECTION_ARTISTS = 5;

    /**
     * Characters (spaces aside) the shorter of two native titles needs before agreeing means anything: below it,
     * Japanese titles are generic ({@code 総集編} "compilation", {@code おまけ} "bonus") and circle names, which
     * would tell them apart, are bracketed and stripped.
     */
    static final int MIN_NATIVE_LENGTH = 4;

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

    /** {@link #titleScore} for two native keys; under {@value #MIN_NATIVE_LENGTH} characters they agree on nothing. */
    public static double nativeTitleScore(List<String> a, List<String> b)
    {
        return isDistinctiveNative(String.join(" ", a)) && isDistinctiveNative(String.join(" ", b))
                ? titleScore(a, b) : 0.0;
    }

    /** Whether agreeing on this native key can score at all: seeking a shorter one finds nothing to rank. */
    public static boolean isDistinctiveNative(String nativeKey)
    {
        return nativeKey.replace(" ", "").length() >= MIN_NATIVE_LENGTH;
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
            return namesNothing(x.length() < y.length() ? a : b) ? 0.0 : CONDENSED_PREFIX;
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
        if (shorter == longer)
        {
            return mean;
        }
        // "after" (from "after❤") names no work, so it is no base of "after school tutoring".
        return namesNothing(a.size() < b.size() ? a : b) ? PARTIAL_CEILING * mean : EXTENSION_FACTOR * mean;
    }

    private static boolean namesNothing(List<String> tokens)
    {
        return tokens.size() == 1 && TitleKey.isFunctionWord(tokens.getFirst());
    }

    public static double artistScore(Collection<Integer> a, Collection<Integer> b)
    {
        if (a == null || b == null || a.isEmpty() || b.isEmpty())
        {
            return ARTIST_UNKNOWN;
        }
        if (sharedArtists(a, b) > 0)
        {
            return ARTIST_SHARED;
        }
        return a.size() >= COLLECTION_ARTISTS || b.size() >= COLLECTION_ARTISTS ? ARTIST_UNKNOWN : ARTIST_DISJOINT;
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
        if (!couldBeTypo(a, b))
        {
            return 0.0;
        }
        Double score = SIMILARITY.apply(a, b);
        return score == null ? 0.0 : score;
    }

    /**
     * Latin letters only: in Japanese one character is a word of its own ({@code 起きない妻} and
     * {@code 起きない子} are two works), and a different digit is a different number, not a typo.
     */
    private static boolean couldBeTypo(String a, String b)
    {
        if (Math.min(a.length(), b.length()) < MIN_TYPO_LENGTH || !isLatinWord(a) || !isLatinWord(b))
        {
            return false;
        }
        int allowed = Math.max(a.length(), b.length()) >= TWO_EDIT_LENGTH ? 2 : 1;
        return Math.abs(a.length() - b.length()) <= allowed && editDistance(a, b) <= allowed;
    }

    private static boolean isLatinWord(String token)
    {
        return token.chars().allMatch(c -> c >= 'a' && c <= 'z');
    }

    /** Optimal string alignment: a swap of two neighbours ({@code kitsnue}) is one edit, as a typist makes it. */
    private static int editDistance(String a, String b)
    {
        var d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++)
        {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++)
        {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++)
        {
            for (int j = 1; j <= b.length(); j++)
            {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}

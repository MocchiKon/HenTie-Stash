package io.github.mocchikon.hentie.service.match;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;

/**
 * Parses a title into what matching needs: the match key, the chapter number and the base titles an
 * auto-created series is named with. Pure; scoring is in {@link MatchScore}.
 *
 * <p>Always fed {@code titleFull}: the pretty {@code title} is user-editable, so a cosmetic rename would
 * move a chapter into another family. {@code nativeTitle} is not used, because it is written in another
 * script.
 *
 * <p>Family members are related by a token <b>prefix</b>, not key equality: {@code Ohayo} and
 * {@code Ohayo suizokukan hen} differ by trailing words. A prefix is also something a B-tree can seek.
 *
 * <p>Bracket groups are kept as opaque atoms, so one parse serves every output. A marker inside an atom is
 * never stripped.
 * <table><caption>outputs</caption>
 * <tr><th>output</th><th>brackets</th><th>markers</th><th>used for</th></tr>
 * <tr><td>{@link #getMatchKey()} / {@link #getTokens()} / {@link #getMatchBlock()}</td>
 *     <td>removed</td><td>stripped</td><td>candidate lookup + the indexed columns</td></tr>
 * <tr><td>{@link #getBaseTitleFull()}</td><td><b>kept, in place</b></td><td>stripped</td>
 *     <td>{@code title_full} of an auto-created series</td></tr>
 * <tr><td>{@link #getBaseTitlePretty()}</td><td>removed</td><td>stripped</td>
 *     <td>{@code title_pretty} of an auto-created series</td></tr>
 * <tr><td>{@link #getPrettyTitle()}</td><td>removed</td><td><b>kept</b></td>
 *     <td>{@code title_pretty} of a <b>chapter</b> whose form left it blank</td></tr>
 * </table>
 * A series loses the numbering, a chapter keeps it to stay distinct from its siblings:
 * {@code "Comic Hero 2024-06 [Digital]"} gives the series {@code "Comic Hero"} and the chapter
 * {@code "Comic Hero 2024-06"}.
 */
@Getter
public final class TitleKey
{
    public static final int BLOCK_LENGTH = 4;

    /**
     * Stripping may never reduce a key to one of these: {@code "The End"} would become {@code "the"}, a
     * prefix of every {@code "The ..."} title. Not a minimum word count, because {@code "Naruto 2"} must
     * still strip to {@code "naruto"}.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "this", "that", "these", "those",
            "my", "your", "his", "her", "its", "our", "their",
            "and", "or", "but", "of", "in", "on", "at", "to", "for", "with", "from", "by",
            "le", "la", "les", "un", "une", "des", "der", "die", "das", "el", "il", "lo",
            "no", "wa", "ga", "ni", "de", "wo", "ne", "yo", "mo");

    /**
     * Words that mark a position, not a work. Stripped from the tail only and, like {@link #STOPWORDS},
     * never left as the whole key: {@code "Omake 2"} keeps {@code "omake 2"}.
     */
    private static final Set<String> MARKER_WORDS = Set.of(
            "ch", "chapter", "vol", "volume", "part", "pt", "ep", "episode", "no",
            "omake", "extra", "bangaihen", "hen", "zenpen", "chuuhen", "kouhen", "kanketsuhen",
            "after", "epilogue", "prologue", "final", "fin", "end",
            "前編", "後編", "中編", "番外編", "続き");

    /** Leaves out {@code i}, {@code v} and {@code x}: those are more often title words than numerals. */
    private static final Map<String, Integer> ROMAN = Map.ofEntries(
            Map.entry("ii", 2), Map.entry("iii", 3), Map.entry("iv", 4), Map.entry("vi", 6),
            Map.entry("vii", 7), Map.entry("viii", 8), Map.entry("ix", 9), Map.entry("xi", 11),
            Map.entry("xii", 12), Map.entry("xiii", 13), Map.entry("xiv", 14), Map.entry("xv", 15),
            Map.entry("xvi", 16), Map.entry("xvii", 17), Map.entry("xviii", 18), Map.entry("xix", 19),
            Map.entry("xx", 20));

    /**
     * A number chain is a date only if it holds a year in this range. The upper bound is next year: a
     * larger 4-digit number is an id or part of the name, and reading it as a date would merge unrelated
     * titles. Read once at class load, so the key written on create and the one the sweep computes agree.
     */
    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = LocalDate.now().getYear() + 1;

    /**
     * Separators of a CJK date ({@code 2024年06月号}), used only by the date recognizer: {@link #normalize}
     * keeps them because they are letters.
     */
    private static final String DATE_SEPARATORS = "年月日号";

    private static final String OPENERS = "([{<（［｛【";
    private static final String CLOSERS = ")]}>）］｝】";

    /** The value stored in {@code match_key}. */
    private final String matchKey;

    private final List<String> tokens;

    /** {@value #BLOCK_LENGTH} leading characters of the key, spaces removed. */
    private final String matchBlock;

    private final float chapterNum;

    private final String baseTitleFull;

    private final String baseTitlePretty;

    private final String prettyTitle;

    private TitleKey(String matchKey, List<String> tokens, String matchBlock, float chapterNum,
                     String baseTitleFull, String baseTitlePretty, String prettyTitle)
    {
        this.matchKey = matchKey;
        this.tokens = tokens;
        this.matchBlock = matchBlock;
        this.chapterNum = chapterNum;
        this.baseTitleFull = baseTitleFull;
        this.baseTitlePretty = baseTitlePretty;
        this.prettyTitle = prettyTitle;
    }

    /** A blank or undecipherable title yields an empty key, never null. */
    public static TitleKey of(String title)
    {
        var markers = new ArrayList<Segment>();
        List<Piece> parsed = pieces(title);
        List<Piece> stripped = strip(parsed, markers);   // strip() copies, so `parsed` stays whole

        var tokens = new ArrayList<String>();
        for (Piece piece : stripped)
        {
            if (!piece.atom())
            {
                tokens.addAll(List.of(StringUtils.split(normalize(piece.text()), ' ')));
            }
        }
        String key = String.join(" ", tokens);
        String block = StringUtils.left(key.replace(" ", ""), BLOCK_LENGTH);
        return new TitleKey(key, List.copyOf(tokens), block, chapterNum(markers),
                base(stripped, title, true), base(stripped, title, false),
                base(parsed, title, false));   // markers kept: the unstripped pieces
    }

    public static String baseTitleFull(String title)
    {
        return of(title).getBaseTitleFull();
    }

    public static String baseTitlePretty(String title)
    {
        return of(title).getBaseTitlePretty();
    }

    private static String base(List<Piece> stripped, String title, boolean keepAtoms)
    {
        var result = new StringBuilder();
        for (Piece piece : stripped)
        {
            if (piece.atom() && !keepAtoms)
            {
                continue;
            }
            if (!result.isEmpty())
            {
                result.append(' ');
            }
            result.append(piece.text());
        }
        String base = result.toString().trim();
        return base.isEmpty() ? StringUtils.trimToEmpty(title) : base;
    }

    /**
     * The chapter number the marker chain implies - first segment is the main number, second the
     * sub-number (an unnumbered suffix like {@code omake} counting as sub-part 1):
     * <pre>
     *   Isekai Yuusha          -&gt; 1.0      Isekai Yuusha 2         -&gt; 2.0
     *   Isekai Yuusha 2 omake  -&gt; 2.01     Isekai Yuusha 2 part 2  -&gt; 2.02
     *   Comic Hero 2024-06     -&gt; 2024.06  Comic Hero 01-2026      -&gt; 2026.01
     * </pre>
     * The sub-number is always divided by 100, so part 10 (2.10) differs from part 1 (2.01) and part 11
     * sorts after part 2. A date fills the same slots as year and month, so there is no room for the day.
     * <p>
     * Depends on the title only, so the same chapter in two languages gets the same number and prev/next
     * lines up across languages.
     */
    private static float chapterNum(List<Segment> markers)
    {
        int main = markers.isEmpty() ? 1 : positive(markers.get(0).number(), 1);
        int sub = markers.size() < 2 ? 0 : positive(markers.get(1).number(), 1);
        float fraction = sub / 100f;
        return Math.round((main + fraction) * 100f) / 100f;
    }

    private static int positive(Integer value, int fallback)
    {
        return (value == null || value <= 0) ? fallback : value;
    }

    // ---- parsing -----------------------------------------------------------

    /** A bracket group ({@code atom}) or a single word. */
    private record Piece(String text, boolean atom)
    {
    }

    /** {@code word} is null for a bare number, {@code number} null for an unnumbered marker. */
    private record Segment(String word, Integer number)
    {
    }

    private record IssueDate(int year, int month)
    {
    }

    /**
     * Nesting is depth-counted, so {@code "[a (b)]"} is one atom; an unmatched opener makes the rest one
     * atom; a stray closer is just text.
     */
    private static List<Piece> pieces(String title)
    {
        var pieces = new ArrayList<Piece>();
        if (StringUtils.isBlank(title))
        {
            return pieces;
        }
        var word = new StringBuilder();
        var atom = new StringBuilder();
        int depth = 0;

        for (int i = 0; i < title.length(); i++)
        {
            char c = title.charAt(i);
            if (depth > 0)
            {
                atom.append(c);
                if (OPENERS.indexOf(c) >= 0)
                {
                    depth++;
                }
                else if (CLOSERS.indexOf(c) >= 0 && --depth == 0)
                {
                    pieces.add(new Piece(atom.toString(), true));
                    atom.setLength(0);
                }
            }
            else if (OPENERS.indexOf(c) >= 0)
            {
                flush(word, pieces);
                depth = 1;
                atom.append(c);
            }
            else if (Character.isWhitespace(c))
            {
                flush(word, pieces);
            }
            else
            {
                word.append(c);
            }
        }
        if (depth > 0)
        {
            pieces.add(new Piece(atom.toString(), true));   // unclosed bracket: the rest is one atom
        }
        flush(word, pieces);
        return pieces;
    }

    private static void flush(StringBuilder word, List<Piece> pieces)
    {
        if (!word.isEmpty())
        {
            pieces.add(new Piece(word.toString(), false));
            word.setLength(0);
        }
    }

    /**
     * Removes trailing markers, recording them left to right in {@code markers}. Atoms are skipped, not
     * stripped, so a trailing {@code (Alpha)} does not hide the {@code omake} before it. At least one
     * identifying word always survives ({@link #leavesNothingIdentifying}).
     */
    private static List<Piece> strip(List<Piece> pieces, List<Segment> markers)
    {
        var remaining = new ArrayList<Piece>(pieces);
        var stripped = new ArrayDeque<Segment>();

        while (countWords(remaining) > 1)
        {
            int last = previousWord(remaining, remaining.size());
            String word = normalize(remaining.get(last).text());
            int previous = previousWord(remaining, last);
            String before = previous < 0 ? "" : normalize(remaining.get(previous).text());

            Integer number = numberOf(word);
            if (number != null)
            {
                // "... part 2": the number goes with the marker before it, unless removing both would
                // leave nothing identifying.
                boolean pair = MARKER_WORDS.contains(before) && countWords(remaining) > 2
                        && !leavesNothingIdentifying(remaining, 2);
                if (!pair && leavesNothingIdentifying(remaining, 1))
                {
                    // The key keeps the number, but the chapter number is still read from it.
                    stripped.addFirst(new Segment(null, number));
                    break;
                }
                if (pair)
                {
                    remaining.remove(last);
                    remaining.remove(previous);
                    stripped.addFirst(new Segment(before, number));
                }
                else
                {
                    remaining.remove(last);
                    stripped.addFirst(new Segment(null, number));
                }
                continue;
            }

            IssueDate date = issueDate(word);
            if (date != null)
            {
                // Recorded even when the word must stay in the key, as for a bare number.
                boolean blocked = leavesNothingIdentifying(remaining, 1);
                if (!blocked)
                {
                    remaining.remove(last);
                }
                stripped.addFirst(new Segment(null, date.month()));
                stripped.addFirst(new Segment(null, date.year()));
                if (blocked)
                {
                    break;
                }
                continue;
            }

            Segment decorated = decoratedMarker(word);
            if (decorated != null)
            {
                if (leavesNothingIdentifying(remaining, 1))
                {
                    // As for a bare number: "The vol.2" numbers 2.0 like "The 2". An unnumbered marker is
                    // not recorded, since it would shift the main/sub positions.
                    if (decorated.number() != null)
                    {
                        stripped.addFirst(new Segment(null, decorated.number()));
                    }
                    break;
                }
                remaining.remove(last);
                stripped.addFirst(decorated);
                continue;
            }

            // "side story" is the one two-word marker; "story" alone is an ordinary title word.
            if (word.equals("story") && before.equals("side") && countWords(remaining) > 2)
            {
                if (leavesNothingIdentifying(remaining, 2))
                {
                    break;
                }
                remaining.remove(last);
                remaining.remove(previous);
                stripped.addFirst(new Segment("side story", null));
                continue;
            }
            if (MARKER_WORDS.contains(word))
            {
                if (leavesNothingIdentifying(remaining, 1))
                {
                    break;   // unnumbered, so nothing to record - see the decorated branch above
                }
                remaining.remove(last);
                stripped.addFirst(new Segment(word, null));
                continue;
            }
            break;
        }
        markers.addAll(stripped);
        return remaining;
    }

    /** A marker and its number packed into one word, e.g. {@code "vol.2"} normalizing to {@code "vol 2"}. */
    private static Segment decoratedMarker(String normalizedWord)
    {
        var parts = StringUtils.split(normalizedWord, ' ');
        if (parts.length == 1)
        {
            return gluedMarker(parts[0]);
        }
        if (parts.length != 2)
        {
            return null;
        }
        if (MARKER_WORDS.contains(parts[0]))
        {
            Integer number = numberOf(parts[1]);
            if (number != null)
            {
                return new Segment(parts[0], number);
            }
        }
        return parts[0].equals("side") && parts[1].equals("story") ? new Segment("side story", null) : null;
    }

    /**
     * {@code "part2"}, {@code "ch2"}: without this, {@code "X part2"} and {@code "X part 2"} would land in
     * different series. The letters must be a whole marker word, so {@code "am10"} stays a title word.
     */
    private static Segment gluedMarker(String word)
    {
        int digits = word.length();
        while (digits > 0 && Character.isDigit(word.charAt(digits - 1)))
        {
            digits--;
        }
        if (digits == 0 || digits == word.length())
        {
            return null;   // all digits (handled by numberOf) or none: not a glued marker
        }
        if (!MARKER_WORDS.contains(word.substring(0, digits)))
        {
            return null;
        }
        Integer number = numberOf(word.substring(digits));
        return number == null ? null : new Segment(word.substring(0, digits), number);
    }

    /**
     * Accepts {@code 2024-06}, {@code 01-2026}, {@code 2024年06月}, {@code 15-06-2024}.
     * <p>
     * A 4-digit year is required: {@code "06-05"} could be month-day, day-month or a chapter range, and a
     * wrong guess would merge unrelated runs. The day is dropped, because a {@code float} chapter number
     * has room for the month only. In {@code DD-MM-YYYY} the middle number is the month.
     */
    private static IssueDate issueDate(String normalizedWord)
    {
        boolean cjk = StringUtils.containsAny(normalizedWord, DATE_SEPARATORS);
        if (!cjk && normalizedWord.indexOf(' ') < 0)
        {
            return null;   // the common case: a single token cannot be a multi-part date
        }
        String spaced = normalizedWord;
        for (int i = 0; cjk && i < DATE_SEPARATORS.length(); i++)
        {
            spaced = spaced.replace(DATE_SEPARATORS.charAt(i), ' ');
        }

        var parts = StringUtils.split(StringUtils.normalizeSpace(spaced), ' ');
        if (parts.length < 2 || parts.length > 3)
        {
            return null;
        }
        var numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            Integer value = digits(parts[i]);
            if (value == null)
            {
                return null;   // a word among the numbers, or a roman numeral: not a date
            }
            numbers[i] = value;
        }

        if (isYear(parts[0], numbers[0]))
        {
            return isMonth(numbers[1]) ? new IssueDate(numbers[0], numbers[1]) : null;
        }
        int last = parts.length - 1;
        if (!isYear(parts[last], numbers[last]))
        {
            return null;
        }
        int month = numbers[last - 1];
        return isMonth(month) ? new IssueDate(numbers[last], month) : null;
    }

    private static boolean isYear(String part, int value)
    {
        return part.length() == 4 && value >= MIN_YEAR && value <= MAX_YEAR;
    }

    private static boolean isMonth(int value)
    {
        return value >= 1 && value <= 12;
    }

    private static Integer numberOf(String word)
    {
        if (word.isEmpty() || word.indexOf(' ') >= 0)
        {
            return null;
        }
        Integer value = digits(word);
        return value != null ? value : ROMAN.get(word);
    }

    /** No roman numerals: a date must be made of digits. */
    private static Integer digits(String word)
    {
        if (word.isEmpty() || !StringUtils.isNumeric(word))
        {
            return null;
        }
        try
        {
            return Integer.valueOf(word);
        }
        catch (NumberFormatException e)
        {
            return null;   // an absurdly long digit run is not a chapter number
        }
    }

    private static int countWords(List<Piece> pieces)
    {
        return (int) pieces.stream().filter(p -> !p.atom()).count();
    }

    /**
     * True when dropping the last {@code words} words would leave one stopword or marker word. A marker
     * counts too, or {@code "Omake 2"} and {@code "Omake 3"} of unrelated works would both become
     * {@code "omake"} and merge.
     */
    private static boolean leavesNothingIdentifying(List<Piece> remaining, int words)
    {
        if (countWords(remaining) - words != 1)
        {
            return false;
        }
        for (Piece piece : remaining)
        {
            if (!piece.atom())
            {
                String survivor = normalize(piece.text());
                return STOPWORDS.contains(survivor) || MARKER_WORDS.contains(survivor);
            }
        }
        return false;
    }

    /** -1 when there is none. */
    private static int previousWord(List<Piece> pieces, int from)
    {
        for (int i = from - 1; i >= 0; i--)
        {
            if (!pieces.get(i).atom())
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * May yield several tokens ({@code "am10:00"} becomes {@code "am10 00"}), which stops such a word
     * being read as a marker.
     */
    private static String normalize(String word)
    {
        String folded = StringUtils.stripAccents(Normalizer.normalize(word, Normalizer.Form.NFKC))
                .toLowerCase(Locale.ROOT);
        var result = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++)
        {
            char c = folded.charAt(i);
            result.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return StringUtils.normalizeSpace(result.toString());
    }
}

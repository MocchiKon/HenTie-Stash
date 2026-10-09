package io.github.mocchikon.hentie.service.match;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a title into what matching needs: the match key, the chapter number and the base titles an
 * auto-created series is named with. Pure; scoring is in {@link MatchScore}.
 *
 * <p>Fed {@code titleFull}: the pretty {@code title} is user-editable, so a cosmetic rename would move a chapter
 * into another family. {@code nativeTitle} gets a key of its own ({@link #nativeKey}), compared only with other
 * native keys, because it is written in another script.
 *
 * <p>Family members are related by a token <b>prefix</b>, not key equality: {@code Ohayo} and
 * {@code Ohayo suizokukan hen} differ by trailing words. A prefix is also something a B-tree can seek.
 *
 * <p>Bracket groups are kept as opaque atoms, so one parse serves every output. A marker inside an atom is
 * never stripped; the one atom read is a bare number after the title ({@code "(5)"}).
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
 *
 * <p>A title is split into parts at a standalone dash, bar or tilde ({@code "Title 2 - Subtitle"},
 * {@code "Title 2 | Translation 2"}), and markers come off the tail of <b>each</b> part. Stripping the whole
 * title's tail only would keep a number in front of a subtitle, splitting the family and numbering the chapter 1.
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

    /**
     * Markers that only say where something is when a number follows them. Without one they are stripped but
     * not counted, or {@code "Wakarase Yuri Hen Vol. 1"} would number 1.01 instead of 1.
     */
    private static final Set<String> POSITION_WORDS = Set.of(
            "ch", "chapter", "vol", "volume", "part", "pt", "ep", "episode", "no", "hen");

    /**
     * An unnumbered marker that ends a chapter's own name: in {@code "Mankitsu-chu 2 Karaoke Hen"} the number
     * before the name is the chapter's.
     */
    private static final Set<String> ARC_WORDS = Set.of("hen", "chapter", "ch", "episode", "ep");

    /** Words of a chapter's own name between its number and an {@link #ARC_WORDS} marker. */
    private static final int MAX_ARC_NAME_WORDS = 2;

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

    /** What a {@link Kind#SEPARATOR} is made of, after NFKC (which folds the full-width forms into these). */
    private static final String SEPARATOR_CHARS = "-|~〜–—―";

    /** {@code "DLO-19"}: a catalogue number. It never numbers a chapter, and the number before it does. */
    private static final Pattern CODE = Pattern.compile("[A-Z]{2,5}-\\d{1,5}");

    /**
     * {@code "1-24"}, {@code "7-24"}: several chapters in one, so no single number. Rising and under 1000 only:
     * {@code "06-05"} may be a date, {@code "2024-2026"} is years.
     */
    private static final Pattern RANGE = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,2})?)[-~〜～](\\d{1,3}(?:\\.\\d{1,2})?)");

    /** {@code "3.5"}: a chapter between 3 and 4. Two decimals at most, so it fits the hundredths slot. */
    private static final Pattern DECIMAL = Pattern.compile("(\\d{1,3})\\.(\\d{1,2})");

    /**
     * {@code "(5)"} after the title: the fifth chapter, not decoration. A year ({@code "(2019)"}) is decoration
     * ({@link #isNumberedAtom}).
     */
    private static final Pattern NUMBERED_ATOM = Pattern.compile("[(（]\\s*(\\d{1,4}|\\d{1,3}\\.\\d{1,2})\\s*[)）]");

    /**
     * A number glued to Japanese text or to an ellipsis: {@code "日記12"}, {@code "様子が…3"}. Never to a Latin
     * letter, where the number is usually part of the name ({@code "R18"}, {@code "am10"}).
     */
    private static final Pattern GLUED_NUMBER = Pattern.compile(
            "(.*(?:[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々]|\\.\\.\\.))(\\d{1,4}|\\d{1,3}\\.\\d{1,2})");

    /**
     * {@code "第3話"}, {@code "第五"}, {@code "宿志第五"}: an ordinal. What precedes it in the same word names that
     * one chapter ("the fifth: long-cherished wish"), so the whole word goes.
     */
    private static final Pattern CJK_ORDINAL = Pattern.compile(
            "(.*)第(\\d{1,4}(?:[-~〜～]\\d{1,4}(?:\\.\\d{1,2})?)?|[〇零一二三四五六七八九十百]{1,5})"
                    + "[話巻章回部弾集夜幕節号]?(?:前編|中編|後編|番外編)?");

    /** {@code "3話"}, {@code "2巻"}. */
    private static final Pattern CJK_COUNTER = Pattern.compile("(\\d{1,4})[話巻章回]");

    private static final String KANJI_DIGITS = "〇一二三四五六七八九";

    /** The value stored in {@code match_key}. */
    private final String matchKey;

    private final List<String> tokens;

    /** {@value #BLOCK_LENGTH} leading characters of the key, spaces removed. */
    private final String matchBlock;

    private final float chapterNum;

    /** Whether the title holds a number at all: a chapter number of 1 may only mean it holds none. */
    private final boolean numbered;

    private final String baseTitleFull;

    private final String baseTitlePretty;

    private final String prettyTitle;

    private TitleKey(String matchKey, List<String> tokens, String matchBlock, float chapterNum, boolean numbered,
                     String baseTitleFull, String baseTitlePretty, String prettyTitle)
    {
        this.matchKey = matchKey;
        this.tokens = tokens;
        this.matchBlock = matchBlock;
        this.chapterNum = chapterNum;
        this.numbered = numbered;
        this.baseTitleFull = baseTitleFull;
        this.baseTitlePretty = baseTitlePretty;
        this.prettyTitle = prettyTitle;
    }

    /** A blank or undecipherable title yields an empty key, never null. */
    public static TitleKey of(String title)
    {
        List<Piece> parsed = pieces(title);
        var kept = new ArrayList<Piece>();
        List<Segment> numbering = List.of();
        for (List<Piece> part : parts(parsed))
        {
            var markers = new ArrayList<Segment>();
            kept.addAll(strip(part, markers, false));   // strip() copies, so `parsed` stays whole
            if (numbering.isEmpty() && markers.stream().anyMatch(marker -> marker.number() != null))
            {
                numbering = markers;
            }
        }

        var tokens = new ArrayList<String>();
        for (Piece piece : kept)
        {
            if (piece.kind() == Kind.WORD)
            {
                tokens.addAll(List.of(StringUtils.split(normalize(piece.text()), ' ')));
            }
        }
        String key = String.join(" ", tokens);
        String block = StringUtils.left(key.replace(" ", ""), BLOCK_LENGTH);
        return new TitleKey(key, List.copyOf(tokens), block, chapterNum(numbering), !numbering.isEmpty(),
                base(kept, title, true), base(kept, title, false),
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

    /**
     * What native titles are compared by. A chapter without one whose own title ({@code title}, the parse of
     * {@code titleFull}) is in Japanese, Chinese or Korean is keyed by that title instead, so a translation carrying
     * it as its native title still finds the original. Empty when there is nothing to compare.
     */
    public static String nativeKey(String nativeTitle, TitleKey title)
    {
        if (StringUtils.isNotBlank(nativeTitle))
        {
            return of(nativeTitle).getMatchKey();
        }
        return title.getMatchKey().codePoints().anyMatch(TitleKey::isNativeScript) ? title.getMatchKey() : "";
    }

    /**
     * The number {@code titleFull} implies, else the one its native title does: an English title without a
     * number may translate a numbered Japanese one.
     */
    public static float chapterNum(TitleKey title, String nativeTitle)
    {
        if (title.isNumbered() || StringUtils.isBlank(nativeTitle))
        {
            return title.getChapterNum();
        }
        TitleKey nativeKey = of(nativeTitle);
        return nativeKey.isNumbered() ? nativeKey.getChapterNum() : title.getChapterNum();
    }

    /** The tokens of a stored key: a key is never re-parsed, which is cheaper and always what was indexed. */
    static List<String> tokensOf(String storedKey)
    {
        return storedKey == null || storedKey.isEmpty() ? List.of() : List.of(storedKey.split(" "));
    }

    /**
     * A stopword or marker word: as a whole key it names no work ({@code "after"} from {@code "after❤"}), so it
     * must not be taken for the base of every title starting with it.
     */
    public static boolean isFunctionWord(String token)
    {
        return STOPWORDS.contains(token) || MARKER_WORDS.contains(token);
    }

    private static boolean isNativeScript(int codePoint)
    {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * A separator is kept only where words follow it: a dangling one ({@code "Lyrics - Soushuuhen 5 -"}) would
     * end a series name in punctuation.
     */
    private static String base(List<Piece> pieces, String title, boolean keepAtoms)
    {
        var result = new StringBuilder();
        String separator = null;
        for (Piece piece : pieces)
        {
            if (piece.kind() == Kind.ATOM && !keepAtoms)
            {
                continue;
            }
            if (piece.kind() == Kind.SEPARATOR)
            {
                separator = piece.text();
                continue;
            }
            if (!result.isEmpty())
            {
                result.append(' ');
                if (separator != null && piece.kind() != Kind.ATOM)
                {
                    result.append(separator).append(' ');
                }
            }
            if (piece.kind() != Kind.ATOM)
            {
                separator = null;
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
     *   Haramaseya 3.5         -&gt; 3.5      Title 2 - Subtitle      -&gt; 2.0
     * </pre>
     * The sub-number is always divided by 100, so part 10 (2.10) differs from part 1 (2.01) and part 11
     * sorts after part 2. A date fills the same slots as year and month, so there is no room for the day; a
     * decimal fills them as written.
     * <p>
     * Depends on the title only, so the same chapter in two languages gets the same number and prev/next
     * lines up across languages.
     */
    private static float chapterNum(List<Segment> markers)
    {
        List<Segment> counted = markers.stream()
                .filter(marker -> marker.number() != null || marker.word() == null
                        || !POSITION_WORDS.contains(marker.word()))
                .toList();
        if (counted.isEmpty())
        {
            return 1f;
        }
        Segment first = counted.getFirst();
        int main = positive(first.number(), 1);
        int sub;
        if (first.fraction() != null)
        {
            sub = first.fraction();
        }
        else
        {
            sub = counted.size() < 2 ? 0 : positive(counted.get(1).number(), 1);
        }
        float fraction = sub / 100f;
        return Math.round((main + fraction) * 100f) / 100f;
    }

    private static int positive(Integer value, int fallback)
    {
        return (value == null || value <= 0) ? fallback : value;
    }

    // ---- parsing -----------------------------------------------------------

    private enum Kind
    {
        WORD,
        /** A bracket group. */
        ATOM,
        /** A dash, bar or tilde standing alone ({@code "-"}, {@code "|"}, {@code "~"}): where a title splits. */
        SEPARATOR,
        /**
         * Any other word of punctuation only ({@code "&"}, {@code "/"}): kept in a series name, but neither a word
         * nor a split. As a split, {@code "Him & Her 2"} would end a part in a stopword and keep its number.
         */
        PUNCTUATION
    }

    private record Piece(String text, Kind kind)
    {
    }

    /**
     * {@code word} is null for a bare number, {@code number} null for an unnumbered marker, {@code fraction}
     * the hundredths of a decimal.
     */
    private record Segment(String word, Integer number, Integer fraction)
    {
        Segment(String word, Integer number)
        {
            this(word, number, null);
        }
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
                    pieces.add(new Piece(atom.toString(), Kind.ATOM));
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
            pieces.add(new Piece(atom.toString(), Kind.ATOM));   // unclosed bracket: the rest is one atom
        }
        flush(word, pieces);
        return pieces;
    }

    private static void flush(StringBuilder word, List<Piece> pieces)
    {
        if (!word.isEmpty())
        {
            pieces.add(wordPiece(word.toString()));
            word.setLength(0);
        }
    }

    private static Piece wordPiece(String text)
    {
        if (!normalize(text).isEmpty())
        {
            return new Piece(text, Kind.WORD);
        }
        boolean separator = Normalizer.normalize(text, Normalizer.Form.NFKC).chars()
                .allMatch(c -> SEPARATOR_CHARS.indexOf(c) >= 0);
        return new Piece(text, separator ? Kind.SEPARATOR : Kind.PUNCTUATION);
    }

    /**
     * Splits at separators; each part after the first starts with its separator. A part of markers only
     * ({@code "... ~ 2"}, {@code "... - Part 2"}) is the tail of the part before it, so it joins that one and
     * loses its separator.
     */
    private static List<List<Piece>> parts(List<Piece> pieces)
    {
        var parts = new ArrayList<List<Piece>>();
        var current = new ArrayList<Piece>();
        for (Piece piece : pieces)
        {
            if (piece.kind() == Kind.SEPARATOR)
            {
                parts.add(current);
                current = new ArrayList<>();
            }
            current.add(piece);
        }
        parts.add(current);

        var joined = new ArrayList<List<Piece>>();
        for (List<Piece> part : parts)
        {
            if (!joined.isEmpty() && countWords(part) > 0 && countWords(strip(part, new ArrayList<>(), true)) == 0)
            {
                part.stream().filter(piece -> piece.kind() != Kind.SEPARATOR).forEach(joined.getLast()::add);
            }
            else
            {
                joined.add(part);
            }
        }
        return joined;
    }

    /**
     * Removes trailing markers, recording them left to right in {@code markers}. Atoms are skipped, not
     * stripped, so a trailing {@code (Alpha)} does not hide the {@code omake} before it. At least one
     * identifying word always survives ({@link #leavesNothingIdentifying}), unless {@code all} asks whether
     * anything would be left at all.
     */
    private static List<Piece> strip(List<Piece> pieces, List<Segment> markers, boolean all)
    {
        var remaining = new ArrayList<Piece>(pieces);
        var stripped = new ArrayDeque<Segment>();
        numberedAtom(remaining).ifPresent(stripped::addFirst);

        while (countWords(remaining) > 0)
        {
            int last = previousWord(remaining, remaining.size());
            String raw = Normalizer.normalize(remaining.get(last).text(), Normalizer.Form.NFKC);

            // The number comes off, the word stays: this never leaves the key empty.
            Matcher glued = GLUED_NUMBER.matcher(raw);
            if (glued.matches())
            {
                remaining.set(last, wordPiece(withoutNumber(remaining.get(last).text(), glued)));
                stripped.addFirst(numberSegment(null, glued.group(2)));
                continue;
            }
            if (countWords(remaining) == 1 && !all)
            {
                break;
            }

            String word = normalize(remaining.get(last).text());
            int previous = previousWord(remaining, last);
            String before = previous < 0 ? "" : normalize(remaining.get(previous).text());

            Segment number = numberIn(remaining.get(last).text());
            if (number != null)
            {
                // "... part 2": the number goes with the marker before it, unless removing both would
                // leave nothing identifying.
                boolean pair = MARKER_WORDS.contains(before) && countWords(remaining) > 2
                        && !leavesNothingIdentifying(remaining, 2, all);
                if (!pair && leavesNothingIdentifying(remaining, 1, all))
                {
                    // The key keeps the number, but the chapter number is still read from it.
                    stripped.addFirst(number);
                    break;
                }
                if (pair)
                {
                    remaining.remove(last);
                    remaining.remove(previous);
                    stripped.addFirst(new Segment(before, number.number(), number.fraction()));
                }
                else
                {
                    remaining.remove(last);
                    stripped.addFirst(number);
                }
                continue;
            }

            IssueDate date = issueDate(word);
            if (date != null)
            {
                // Recorded even when the word must stay in the key, as for a bare number.
                boolean blocked = leavesNothingIdentifying(remaining, 1, all);
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

            // A range or a catalogue number numbers nothing; it only has to leave the key.
            if (isRange(raw) || CODE.matcher(raw).matches())
            {
                if (leavesNothingIdentifying(remaining, 1, all))
                {
                    break;
                }
                remaining.remove(last);
                continue;
            }

            Segment ordinal = cjkOrdinal(raw);
            if (ordinal != null)
            {
                if (leavesNothingIdentifying(remaining, 1, all))
                {
                    if (ordinal.number() != null)
                    {
                        stripped.addFirst(ordinal);
                    }
                    break;
                }
                remaining.remove(last);
                if (ordinal.number() != null)
                {
                    stripped.addFirst(ordinal);
                }
                continue;
            }

            Segment decorated = decoratedMarker(word);
            if (decorated != null)
            {
                if (leavesNothingIdentifying(remaining, 1, all))
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
            if (word.equals("story") && before.equals("side") && (countWords(remaining) > 2 || all))
            {
                if (leavesNothingIdentifying(remaining, 2, all))
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
                if (leavesNothingIdentifying(remaining, 1, all))
                {
                    break;   // unnumbered, so nothing to record - see the decorated branch above
                }
                remaining.remove(last);
                stripped.addFirst(new Segment(word, null));
                continue;
            }
            break;
        }
        arcNumber(remaining, stripped);
        markers.addAll(stripped);
        return remaining;
    }

    /**
     * {@code "Mankitsu-chu 2 Karaoke Hen"}: once the tail held only an {@link #ARC_WORDS} marker, a number
     * followed by up to {@value #MAX_ARC_NAME_WORDS} words is the chapter's number and those words its own name.
     * Both leave the key, so every arc of the work keys like the work.
     */
    private static void arcNumber(List<Piece> remaining, Deque<Segment> stripped)
    {
        if (stripped.isEmpty() || !stripped.stream().allMatch(marker -> marker.number() == null
                && marker.word() != null && ARC_WORDS.contains(marker.word())))
        {
            return;
        }
        // Word indexes from the last one back.
        var words = new ArrayList<Integer>();
        for (int i = previousWord(remaining, remaining.size()); i >= 0; i = previousWord(remaining, i))
        {
            words.add(i);
        }
        for (int name = 1; name <= MAX_ARC_NAME_WORDS && name < words.size(); name++)
        {
            if (numberIn(remaining.get(words.get(name - 1)).text()) != null)
            {
                return;   // a name is words: "... 2 3 Hen" is not one
            }
            Segment number = numberIn(remaining.get(words.get(name)).text());
            if (number == null)
            {
                continue;
            }
            int left = words.size() - name - 1;
            if (left == 0 || (left == 1 && isFunctionWord(normalize(remaining.get(words.getLast()).text()))))
            {
                return;
            }
            for (int i = 0; i <= name; i++)
            {
                remaining.remove((int) words.get(i));   // descending indexes, so the rest stay valid
            }
            stripped.addFirst(number);
            return;
        }
    }

    /**
     * The word as written, minus the glued number, so a series name keeps its own characters ({@code …}, not
     * the {@code ...} it normalizes to). Digits and dots normalize one to one, full-width ones included.
     */
    private static String withoutNumber(String text, Matcher glued)
    {
        int end = text.length() - glued.group(2).length();
        if (end < 0)
        {
            return glued.group(1);
        }
        for (int i = end; i < text.length(); i++)
        {
            String c = Normalizer.normalize(text.substring(i, i + 1), Normalizer.Form.NFKC);
            if (!(c.equals(".") || (c.length() == 1 && Character.isDigit(c.charAt(0)))))
            {
                return glued.group(1);
            }
        }
        return text.substring(0, end);
    }

    /** {@code "(5)"} after the last word, other atoms aside: read as the number, and dropped like one. */
    private static Optional<Segment> numberedAtom(List<Piece> remaining)
    {
        int lastWord = previousWord(remaining, remaining.size());
        if (lastWord < 0)
        {
            return Optional.empty();   // nothing for it to number
        }
        for (int i = remaining.size() - 1; i > lastWord; i--)
        {
            Piece piece = remaining.get(i);
            if (piece.kind() == Kind.ATOM)
            {
                Matcher matcher = NUMBERED_ATOM.matcher(Normalizer.normalize(piece.text(), Normalizer.Form.NFKC));
                if (isNumberedAtom(matcher))
                {
                    remaining.remove(i);
                    return Optional.of(numberSegment(null, matcher.group(1)));
                }
            }
        }
        return Optional.empty();
    }

    /** The release year a title often ends with is no chapter number. */
    private static boolean isNumberedAtom(Matcher matcher)
    {
        if (!matcher.matches())
        {
            return false;
        }
        Integer whole = digits(matcher.group(1));
        return whole == null || !isYear(matcher.group(1), whole);
    }

    /** {@code "12"} or {@code "3.5"}. */
    private static Segment numberSegment(String word, String number)
    {
        Matcher decimal = DECIMAL.matcher(number);
        if (decimal.matches())
        {
            String digits = decimal.group(2);
            int hundredths = Integer.parseInt(digits) * (digits.length() == 1 ? 10 : 1);
            return new Segment(word, Integer.valueOf(decimal.group(1)), hundredths);
        }
        return new Segment(word, Integer.valueOf(number));
    }

    /** A bare number, roman numeral or decimal; never a percentage ({@code "100%"} is a quantity). */
    private static Segment numberIn(String text)
    {
        String raw = Normalizer.normalize(text, Normalizer.Form.NFKC);
        if (raw.indexOf('%') >= 0)
        {
            return null;
        }
        if (DECIMAL.matcher(raw).matches())
        {
            return numberSegment(null, raw);
        }
        Integer value = numberOf(normalize(text));
        return value == null ? null : new Segment(null, value);
    }

    private static boolean isRange(String raw)
    {
        Matcher range = RANGE.matcher(raw);
        return range.matches() && Double.parseDouble(range.group(1)) < Double.parseDouble(range.group(2));
    }

    /**
     * A {@code 第}-ordinal or counted number. Of two numbers, a rising pair is a range ({@code "第1-8話"}),
     * recognized but numbering nothing; any other is the chapter and its part ({@code "第3-2話"}, 3.02).
     */
    private static Segment cjkOrdinal(String raw)
    {
        Matcher ordinal = CJK_ORDINAL.matcher(raw);
        if (ordinal.matches())
        {
            String number = ordinal.group(2);
            if (!Character.isDigit(number.charAt(0)))
            {
                return new Segment("第", kanjiNumber(number));
            }
            String[] ends = number.split("[-~〜～]");
            int main = Integer.parseInt(ends[0]);
            if (ends.length == 1)
            {
                return new Segment("第", main);
            }
            double second = Double.parseDouble(ends[1]);
            if (second > main)
            {
                return new Segment("第", null);
            }
            // The part goes in the hundredths slot, like "part 2"; one that does not fit is dropped.
            Integer part = digits(ends[1]);
            return part != null && part >= 1 && part < 100 ? new Segment("第", main, part) : new Segment("第", main);
        }
        Matcher counter = CJK_COUNTER.matcher(raw);
        return counter.matches() ? new Segment("第", Integer.valueOf(counter.group(1))) : null;
    }

    /** {@code 五} 5, {@code 十一} 11, {@code 二十三} 23, {@code 百二} 102. */
    private static Integer kanjiNumber(String numeral)
    {
        int total = 0;
        int current = 0;
        for (char c : numeral.toCharArray())
        {
            int digit = c == '零' ? 0 : KANJI_DIGITS.indexOf(c);
            if (digit >= 0)
            {
                current = current * 10 + digit;
            }
            else
            {
                total += (current == 0 ? 1 : current) * (c == '十' ? 10 : 100);
                current = 0;
            }
        }
        return total + current;
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
        return (int) pieces.stream().filter(p -> p.kind() == Kind.WORD).count();
    }

    /**
     * True when dropping the last {@code words} words would leave one stopword or marker word. A marker
     * counts too, or {@code "Omake 2"} and {@code "Omake 3"} of unrelated works would both become
     * {@code "omake"} and merge. Never true for {@code all}, which strips everything it can.
     */
    private static boolean leavesNothingIdentifying(List<Piece> remaining, int words, boolean all)
    {
        if (all || countWords(remaining) - words != 1)
        {
            return false;
        }
        for (Piece piece : remaining)
        {
            if (piece.kind() == Kind.WORD)
            {
                return isFunctionWord(normalize(piece.text()));
            }
        }
        return false;
    }

    /** -1 when there is none. */
    private static int previousWord(List<Piece> pieces, int from)
    {
        for (int i = from - 1; i >= 0; i--)
        {
            if (pieces.get(i).kind() == Kind.WORD)
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * May yield several tokens ({@code "am10:00"} becomes {@code "am10 00"}), which stops such a word
     * being read as a marker. Accents go, but a kana's voicing mark stays on it ({@code ギ}, not {@code キ}):
     * dropped, it would turn into a word break.
     */
    private static String normalize(String word)
    {
        String folded = Normalizer.normalize(
                        StringUtils.stripAccents(Normalizer.normalize(word, Normalizer.Form.NFKC)), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        var result = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++)
        {
            char c = folded.charAt(i);
            result.append(Character.isLetterOrDigit(c) || isMark(c) ? c : ' ');
        }
        return StringUtils.normalizeSpace(result.toString());
    }

    private static boolean isMark(char c)
    {
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }
}

package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.service.match.TitleKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The tricky title-parsing cases behind matching, kept free of Spring so they are cheap to run. */
class TitleKeyTest
{
    @ParameterizedTest(name = "\"{0}\" -> key \"{1}\", chapter {2}")
    @CsvSource({
            // The sub-number is always hundredths, so part 1 and part 10 stay distinct and part 11 sorts
            // after part 2.
            "Isekai Yuusha,           isekai yuusha, 1.0",
            "Isekai Yuusha 2,         isekai yuusha, 2.0",
            "Isekai Yuusha 2 omake,   isekai yuusha, 2.01",
            "Isekai Yuusha 2 part 2,  isekai yuusha, 2.02",
            "Isekai Yuusha 2 part 10, isekai yuusha, 2.10",
            "Isekai Yuusha 2 part 11, isekai yuusha, 2.11",
            // A family related by one key being a prefix of another.
            "Ohayo,                  ohayo,             1.0",
            "Ohayo suizokukan hen,   ohayo suizokukan,  1.0",
            "Ohayo am10:00,          ohayo am10 00,     1.0",
            "Ohayo pm10:00,          ohayo pm10 00,     1.0",
            // Other marker spellings.
            "Some Title vol.2,       some title, 2.0",
            "Some Title chapter 3,   some title, 3.0",
            "Some Title III,         some title, 3.0",
            "Some Title side story,  some title, 1.0",
            "Some Title 4 final,     some title, 4.01",
            // A one-word key is fine when the word names the work.
            "Naruto 2,               naruto,     2.0",
            // Never a bare stopword: "the" would be a prefix of every "The ..." title.
            "The End,                the end,    1.0",
            "The Final 2,            the final,  2.0",
            // A trailing issue date fills the same two slots with year + month, so a magazine run orders
            // correctly with no special case.
            "Comic Hero 2024-06,     comic hero, 2024.06",
            "Comic Hero 2026-01,     comic hero, 2026.01",
            "Comic Hero 01-2026,     comic hero, 2026.01",
            "Comic Hero 2024/06,     comic hero, 2024.06",
            "Comic Hero 2024.06,     comic hero, 2024.06",
            "Comic Hero 2024_06,     comic hero, 2024.06",
            // The day is dropped: the /100 sub-number has room for the month only.
            "Comic Hero 2024-06-15,  comic hero, 2024.06",
            "Comic Hero 15-06-2024,  comic hero, 2024.06",
            // A bare year is just an ordinary trailing number.
            "Comic Hero 2024,        comic hero, 2024.0",
    })
    void shouldStripMarkersAndDeriveTheChapterNumberWhenParsingATitle(String title, String key, double number)
    {
        // WHEN
        var parsed = TitleKey.of(title);

        // THEN
        assertThat(parsed.getMatchKey()).isEqualTo(key);
        assertThat(parsed.getChapterNum()).isCloseTo(number, within(0.001));
    }

    @ParameterizedTest(name = "\"{0}\" -> key \"{1}\", chapter {2}")
    @CsvSource(delimiter = '|', value = {
            // A number in front of a subtitle or a translation numbers the chapter, and leaves the key: every part
            // of the title loses its own tail.
            "Isekai Yuusha 2 - The Return                       | isekai yuusha the return              | 2.0",
            "'Isekai Yuusha 3 | Hero of Another World 3'         | isekai yuusha hero of another world   | 3.0",
            "'Isekai Yuusha (4) | Hero of Another World (4)'     | isekai yuusha hero of another world   | 4.0",
            "'Isekai Yuusha | Hero of Another World…2'           | isekai yuusha hero of another world   | 2.0",
            // A part of markers only is the tail of the part before it.
            "Isekai Yuusha ~ 2                                  | isekai yuusha                         | 2.0",
            "Isekai Yuusha 5 -                                  | isekai yuusha                         | 5.0",
            "Isekai Yuusha - Part 2                             | isekai yuusha                         | 2.0",
            // Other punctuation does not split: as a part of its own, "& Her 2" would keep its number.
            "Him & Her 2                                        | him her                               | 2.0",
            // A bare number in brackets after the title is its number, not decoration.
            "Isekai Yuusha (5) [Digital]                        | isekai yuusha                         | 5.0",
            // ...but a year there is decoration.
            "Isekai Yuusha (2019)                               | isekai yuusha                         | 1.0",
            "Isekai Yuusha 3.5                                  | isekai yuusha                         | 3.5",
            // A catalogue number numbers nothing, and the number before it does.
            "Isekai Yuusha 2 DLO-19                             | isekai yuusha                         | 2.0",
            "Isekai Yuusha DLO-16                               | isekai yuusha                         | 1.0",
            // Several chapters in one: no single number, but the work's key.
            "Isekai Yuusha Ch. 1-24                             | isekai yuusha                         | 1.0",
            "Isekai Yuusha 7-24                                 | isekai yuusha                         | 1.0",
            // The number before an arc's name, which leaves the key with it.
            "Isekai Yuusha 2 Karaoke Hen                        | isekai yuusha                         | 2.0",
            "Isekai Yuusha 4 Water Park Chapter                 | isekai yuusha                         | 4.0",
            // An unnumbered position word does not count: chapter 1, not 1.01.
            "Isekai Yuusha Hen Vol. 1                           | isekai yuusha                         | 1.0",
            // A percentage is a quantity.
            "Isekai Yuusha 100%                                 | isekai yuusha 100                     | 1.0",
            // Japanese: a number glued on, an ordinal (the subtitle before it is the chapter's own), a counter.
            "異世界勇者12                                         | 異世界勇者                             | 12.0",
            "異世界勇者3.5                                        | 異世界勇者                             | 3.5",
            "異世界勇者…3                                         | 異世界勇者                             | 3.0",
            "異世界勇者 第3話                                      | 異世界勇者                             | 3.0",
            "異世界勇者 旅立ち第五                                  | 異世界勇者                             | 5.0",
            "異世界勇者 第十二話                                    | 異世界勇者                             | 12.0",
            "異世界勇者 第1-8話                                    | 異世界勇者                             | 1.0",
            "異世界勇者 第1000-2000話                              | 異世界勇者                             | 1.0",
            // Not rising: the chapter and its part.
            "異世界勇者 第3-2話                                    | 異世界勇者                             | 3.02",
            "異世界勇者 3話                                       | 異世界勇者                             | 3.0",
            "異世界勇者100%                                       | 異世界勇者100                          | 1.0",
    })
    void shouldFindTheNumberWhereverATitleWritesItWhenParsingATitle(String title, String key, double number)
    {
        // WHEN
        var parsed = TitleKey.of(title);

        // THEN
        assertThat(parsed.getMatchKey()).isEqualTo(key);
        assertThat(parsed.getChapterNum()).isCloseTo(number, within(0.001));
        assertThat(TitleKey.of(parsed.getBaseTitleFull()).getMatchKey())
                .as("the series named after it keys the same")
                .isEqualTo(key);
    }

    @Test
    void shouldNotReadANumberGluedToALatinWordOrANumberThatIsPartOfAName()
    {
        // "R18", "am10" and "girlfriend-1.5" are names; only Japanese text or an ellipsis glues a number on.
        assertThat(TitleKey.of("Isekai Yuusha R18").getMatchKey()).isEqualTo("isekai yuusha r18");
        assertThat(TitleKey.of("Isekai Yuusha-2").getChapterNum()).isCloseTo(1.0, within(0.001));
        // A falling pair is no range (it may be a date), and years are no range either.
        assertThat(TitleKey.of("Comic Hero 06-05").getMatchKey()).isEqualTo("comic hero 06 05");
        assertThat(TitleKey.of("Comic Hero 2024-2026").getMatchKey()).isEqualTo("comic hero 2024 2026");
        // Without a separator a number before a subtitle stays where it is.
        assertThat(TitleKey.of("Isekai Yuusha 2 Melonbooks Bonus").getMatchKey())
                .isEqualTo("isekai yuusha 2 melonbooks bonus");
        assertThat(TitleKey.of("Isekai Yuusha 2 Melonbooks Bonus").isNumbered()).isFalse();
    }

    @Test
    void shouldDropTheNumberButKeepTheSeparatorsBetweenPartsInTheSeriesName()
    {
        assertThat(TitleKey.baseTitleFull("[Circle] Isekai Yuusha 3 | Hero of Another World 3 [English]"))
                .isEqualTo("[Circle] Isekai Yuusha | Hero of Another World [English]");
        // A separator nothing follows would end the name in punctuation.
        assertThat(TitleKey.baseTitleFull("[Circle] Isekai Yuusha - Soushuuhen 5 - [Digital]"))
                .isEqualTo("[Circle] Isekai Yuusha - Soushuuhen [Digital]");
        assertThat(TitleKey.baseTitleFull("Him & Her 2")).isEqualTo("Him & Her");
        assertThat(TitleKey.baseTitlePretty("Isekai Yuusha (5) [Digital]")).isEqualTo("Isekai Yuusha");
        // The glued number goes, the word stays as written.
        assertThat(TitleKey.baseTitlePretty("異世界勇者…3")).isEqualTo("異世界勇者…");
    }

    @Test
    void shouldKeepAKanaWithItsVoicingMarkWhenBuildingTheKey()
    {
        // Stripping accents decomposes ギ into キ and a combining mark, which would otherwise become a word break.
        assertThat(TitleKey.of("ギャルだくみ").getMatchKey()).isEqualTo("ギャルだくみ");
        assertThat(TitleKey.of("ギャルだくみ").getTokens()).hasSize(1);
        assertThat(TitleKey.of("Café Pokémon").getMatchKey()).isEqualTo("cafe pokemon");
    }

    @Test
    void shouldKeyTheNativeTitleOrAJapaneseTitleWhenBuildingTheNativeKey()
    {
        assertThat(TitleKey.nativeKey("[サークル] 異世界勇者 2 [英訳]", TitleKey.of("[Circle] Isekai Yuusha 2 [English]")))
                .isEqualTo("異世界勇者");
        // Without a native title, a title in Japanese is its own native title; a romanized one has none.
        assertThat(TitleKey.nativeKey("", TitleKey.of("[サークル] 異世界勇者 2"))).isEqualTo("異世界勇者");
        assertThat(TitleKey.nativeKey(null, TitleKey.of("[Circle] Isekai Yuusha 2"))).isEmpty();
    }

    @Test
    void shouldTakeTheNativeTitlesNumberOnlyWhenTheTitleHasNone()
    {
        TitleKey unnumbered = TitleKey.of("Hero of Another World");
        assertThat(TitleKey.chapterNum(unnumbered, "異世界勇者 第3話")).isCloseTo(3.0, within(0.001));
        assertThat(TitleKey.chapterNum(TitleKey.of("Hero of Another World 2"), "異世界勇者 第3話"))
                .isCloseTo(2.0, within(0.001));
        assertThat(TitleKey.chapterNum(unnumbered, null)).isCloseTo(1.0, within(0.001));
    }

    @Test
    void shouldRelateAFamilyByTokenPrefixWhenTheMembersDifferByWords()
    {
        // Why the relation is a prefix, not a canonical form: nothing collapses these onto one value.
        assertThat(TitleKey.of("Ohayo am10:00").getTokens()).startsWith("ohayo");
        assertThat(TitleKey.of("Ohayo suizokukan hen").getTokens()).startsWith("ohayo");
        assertThat(TitleKey.of("Ohayo").getTokens()).containsExactly("ohayo");
    }

    @Test
    void shouldIgnoreEveryKindOfBracketWhenBuildingTheKey()
    {
        assertThat(TitleKey.of("[Doujin] Isekai Yuusha 2 (Alpha) {scan} <v2>").getMatchKey())
                .isEqualTo("isekai yuusha");
        // Nested groups are one atom, and full-width brackets count too.
        assertThat(TitleKey.of("[a (b)] Isekai Yuusha").getMatchKey()).isEqualTo("isekai yuusha");
        assertThat(TitleKey.of("（同人）Isekai Yuusha").getMatchKey()).isEqualTo("isekai yuusha");
    }

    @Test
    void shouldTreatTheRestAsDecorationWhenABracketIsNeverClosed()
    {
        assertThat(TitleKey.of("Isekai Yuusha (unclosed").getMatchKey()).isEqualTo("isekai yuusha");
        assertThat(TitleKey.baseTitleFull("Isekai Yuusha (unclosed")).isEqualTo("Isekai Yuusha (unclosed");
        assertThat(TitleKey.baseTitlePretty("Isekai Yuusha (unclosed")).isEqualTo("Isekai Yuusha");
    }

    @Test
    void shouldKeepBracketsInTheFullBaseTitleAndDropThemFromThePrettyOne()
    {
        var decorated = "[Doujin] Isekai Yuusha 2 omake (Alpha) <v2>";

        // The full title keeps its decoration verbatim and in place; only the markers go.
        assertThat(TitleKey.baseTitleFull(decorated)).isEqualTo("[Doujin] Isekai Yuusha (Alpha) <v2>");
        assertThat(TitleKey.baseTitlePretty(decorated)).isEqualTo("Isekai Yuusha");
    }

    @Test
    void shouldNotStripMarkersThatSitInsideABracketGroup()
    {
        // "(part 2)" is decoration, not a sequence marker on the title itself.
        assertThat(TitleKey.baseTitleFull("Isekai Yuusha (part 2)")).isEqualTo("Isekai Yuusha (part 2)");
        assertThat(TitleKey.of("Isekai Yuusha (part 2)").getMatchKey()).isEqualTo("isekai yuusha");
    }

    @Test
    void shouldKeepAtLeastOneWordWhenTheTitleIsNothingButMarkers()
    {
        // An empty key, or a bare "part", would be shared by every unrelated work, so the number stays in
        // the key; the chapter number is still read off it.
        assertThat(TitleKey.of("Part 2").getMatchKey()).isEqualTo("part 2");
        assertThat(TitleKey.of("Part 2").getChapterNum()).isCloseTo(2.0, within(0.001));
        assertThat(TitleKey.baseTitlePretty("Part 2")).isEqualTo("Part 2");
        assertThat(TitleKey.baseTitleFull("(Alpha)")).isEqualTo("(Alpha)");

        // A bare "omake" key would merge two unrelated works' omake.
        assertThat(TitleKey.of("Omake 2").getMatchKey()).isEqualTo("omake 2");
        assertThat(TitleKey.of("Omake 3").getMatchKey()).isEqualTo("omake 3");
    }

    @Test
    void shouldStillReadTheNumberWhenTheKeyMustKeepTheMarkerWord()
    {
        // A blocked strip keeps the marker in the key but still yields its number, or two spellings of one
        // chapter would number differently.
        assertThat(TitleKey.of("The vol.2").getChapterNum()).isCloseTo(2.0, within(0.001));
        assertThat(TitleKey.of("The 2").getChapterNum()).isCloseTo(2.0, within(0.001));
        assertThat(TitleKey.of("The vol.2").getMatchKey()).isEqualTo("the vol 2");

        // An unnumbered blocked marker must not shift main/sub.
        assertThat(TitleKey.of("The End").getChapterNum()).isCloseTo(1.0, within(0.001));
        assertThat(TitleKey.of("The Final 2").getChapterNum()).isCloseTo(2.0, within(0.001));
    }

    @Test
    void shouldCollapseADatedMagazineRunOntoOneKeyAndOrderItByTheDate()
    {
        // The point of the date rule: issues years apart are one family, ordered by date.
        var june2024 = TitleKey.of("Comic Hero 2024-06 [Digital]");
        var january2026 = TitleKey.of("Comic Hero 2026-01 [Scan]");

        assertThat(june2024.getMatchKey()).isEqualTo(january2026.getMatchKey()).isEqualTo("comic hero");
        assertThat(june2024.getChapterNum()).isLessThan(january2026.getChapterNum());
        assertThat(june2024.getChapterNum()).isCloseTo(2024.06, within(0.001));
        assertThat(january2026.getChapterNum()).isCloseTo(2026.01, within(0.001));

        // The series is named after the work: brackets survive only in the full title, the date in neither.
        assertThat(june2024.getBaseTitleFull()).isEqualTo("Comic Hero [Digital]");
        assertThat(june2024.getBaseTitlePretty()).isEqualTo("Comic Hero");
        assertThat(january2026.getBaseTitlePretty()).isEqualTo("Comic Hero");
    }

    @Test
    void shouldReadACjkIssueDateWhenTheSeparatorsAreYearAndMonthCharacters()
    {
        // 年 / 月 / 号 are letters and survive normalization, so the date recognizer must treat them as
        // separators itself.
        assertThat(TitleKey.of("Comic Hero 2024年06月").getMatchKey()).isEqualTo("comic hero");
        assertThat(TitleKey.of("Comic Hero 2024年06月").getChapterNum()).isCloseTo(2024.06, within(0.001));
        assertThat(TitleKey.of("Comic Hero 2024年06月号").getChapterNum()).isCloseTo(2024.06, within(0.001));
    }

    @Test
    void shouldKeepTheNumberingButDropTheBracketsInTheChapterPrettyTitle()
    {
        // The display title keeps the markers: the issue date is how a reader tells chapters apart.
        assertThat(TitleKey.of("Comic Hero 2024-06 [Digital]").getPrettyTitle())
                .isEqualTo("Comic Hero 2024-06");
        assertThat(TitleKey.of("[Doujin] Isekai Yuusha 2 omake (Alpha)").getPrettyTitle())
                .isEqualTo("Isekai Yuusha 2 omake");
        // Contrast the series name, which loses the numbering too.
        assertThat(TitleKey.of("Comic Hero 2024-06 [Digital]").getBaseTitlePretty()).isEqualTo("Comic Hero");
        // A title with nothing but decoration keeps it - the column is NOT NULL.
        assertThat(TitleKey.of("[Anthology]").getPrettyTitle()).isEqualTo("[Anthology]");
        // Nothing to drop: unchanged.
        assertThat(TitleKey.of("Comic Hero 2024-06").getPrettyTitle()).isEqualTo("Comic Hero 2024-06");
    }

    @Test
    void shouldTreatOnlyYearsUpToNextYearAsADate()
    {
        // Nothing is published later than next year, so a bigger 4-digit number is an id or part of the
        // name and stays in the key.
        int nextYear = LocalDate.now().getYear() + 1;
        assertThat(TitleKey.of("Comic Hero " + nextYear + "-06").getMatchKey()).isEqualTo("comic hero");
        assertThat(TitleKey.of("Comic Hero " + nextYear + "-06").getChapterNum())
                .isCloseTo(nextYear + 0.06, within(0.001));

        int tooFar = nextYear + 1;
        assertThat(TitleKey.of("Comic Hero " + tooFar + "-06").getMatchKey())
                .isEqualTo("comic hero " + tooFar + " 06");
        // And the lower bound: 1899 is not a year either.
        assertThat(TitleKey.of("Comic Hero 1899-06").getMatchKey()).isEqualTo("comic hero 1899 06");
        assertThat(TitleKey.of("Comic Hero 1900-06").getMatchKey()).isEqualTo("comic hero");
    }

    @Test
    void shouldNotTreatANumberChainAsADateWithoutARecognizableYear()
    {
        // Without a 4-digit year it is not guessed at: "06-05" could be month-day, day-month or a range,
        // and a wrong guess would misorder issues and merge unrelated runs.
        assertThat(TitleKey.of("Comic Hero 06-05").getMatchKey()).isEqualTo("comic hero 06 05");
        assertThat(TitleKey.of("Comic Hero 06-05").getChapterNum()).isCloseTo(1.0, within(0.001));
        // An impossible month, and two years with no month, are not dates either.
        assertThat(TitleKey.of("Comic Hero 2024-13").getMatchKey()).isEqualTo("comic hero 2024 13");
        assertThat(TitleKey.of("Comic Hero 2024-2026").getMatchKey()).isEqualTo("comic hero 2024 2026");
        // A year outside 1900-2100 is an ordinary number, not a year.
        assertThat(TitleKey.of("Comic Hero 3024-06").getMatchKey()).isEqualTo("comic hero 3024 06");
        // "am10" is not all digits, so the time of day stays untouched.
        assertThat(TitleKey.of("Ohayo am10:00").getMatchKey()).isEqualTo("ohayo am10 00");
        assertThat(TitleKey.of("Ohayo am10:00").getChapterNum()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void shouldStripADateOnlyFromTheTailLikeEveryOtherMarker()
    {
        // Markers come off the tail only, so with a trailing subtitle the date stays in the key.
        assertThat(TitleKey.of("Comic Hero 2024-06 Special").getMatchKey())
                .isEqualTo("comic hero 2024 06 special");
        // Inside a bracket group it is decoration, so it neither strips nor numbers.
        assertThat(TitleKey.of("Comic Hero (2024-06)").getMatchKey()).isEqualTo("comic hero");
        assertThat(TitleKey.of("Comic Hero (2024-06)").getChapterNum()).isCloseTo(1.0, within(0.001));
        assertThat(TitleKey.baseTitleFull("Comic Hero (2024-06)")).isEqualTo("Comic Hero (2024-06)");
        // A bracket AFTER the date is skipped over, so the date is still the tail.
        assertThat(TitleKey.of("Comic Hero 2024-06 [Digital]").getMatchKey()).isEqualTo("comic hero");
        // Blocked by the stopword guard: the word stays, the number is still read off it.
        assertThat(TitleKey.of("The 2024-06").getMatchKey()).isEqualTo("the 2024 06");
        assertThat(TitleKey.of("The 2024-06").getChapterNum()).isCloseTo(2024.06, within(0.001));
    }

    @Test
    void shouldReadAMarkerWhoseNumberIsGluedStraightOn()
    {
        // "part2"/"ch2" are common in scraped titles; as plain words they would split a family.
        assertThat(TitleKey.of("Isekai Yuusha part2").getMatchKey()).isEqualTo("isekai yuusha");
        assertThat(TitleKey.of("Isekai Yuusha part2").getChapterNum()).isCloseTo(2.0, within(0.001));
        assertThat(TitleKey.of("Isekai Yuusha ch3").getMatchKey()).isEqualTo("isekai yuusha");
        assertThat(TitleKey.of("Isekai Yuusha ch3").getChapterNum()).isCloseTo(3.0, within(0.001));

        // Only a WHOLE marker word counts, so a title word that merely ends in digits is left alone.
        assertThat(TitleKey.of("Ohayo am10").getMatchKey()).isEqualTo("ohayo am10");
        assertThat(TitleKey.of("Ohayo am10").getChapterNum()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void shouldStripToASingleWordWhenThatWordNamesTheWork()
    {
        // The stopword guard is about which word survives, not how many.
        assertThat(TitleKey.of("Naruto 2").getMatchKey()).isEqualTo("naruto");
        assertThat(TitleKey.of("Naruto 2").getChapterNum()).isCloseTo(2.0, within(0.001));
        assertThat(TitleKey.baseTitlePretty("Naruto 2")).isEqualTo("Naruto");
        assertThat(TitleKey.baseTitleFull("[Scan] Naruto 2 (Kishi)")).isEqualTo("[Scan] Naruto (Kishi)");
    }

    @Test
    void shouldNotStripDownToABareStopword()
    {
        // A "the" key would be a token prefix of every "The ..." title, scoring 0.95 and merging unrelated
        // works.
        assertThat(TitleKey.of("The End").getMatchKey()).isEqualTo("the end");
        assertThat(TitleKey.of("The Beginning").getTokens())
                .isNotEqualTo(TitleKey.of("The End").getTokens());
        assertThat(TitleKey.baseTitlePretty("The End")).isEqualTo("The End");

        // Also for a marker carrying a number, and for a bare trailing number.
        assertThat(TitleKey.of("The Final 2").getMatchKey()).isEqualTo("the final");
        assertThat(TitleKey.of("The 2").getMatchKey()).isEqualTo("the 2");
        assertThat(TitleKey.of("The 2").getChapterNum()).isCloseTo(2.0, within(0.001));
    }

    @Test
    void shouldNotMistakeAnOrdinaryWordForAMarkerWhenItOnlyLooksLikeOne()
    {
        // "story" is a marker only in "side story"; "i"/"v"/"x" are words far more often than numerals.
        assertThat(TitleKey.of("Love Story").getMatchKey()).isEqualTo("love story");
        assertThat(TitleKey.of("Chapter X").getMatchKey()).isEqualTo("chapter x");
    }

    @Test
    void shouldFoldCaseAndAccentsWhenBuildingTheKey()
    {
        assertThat(TitleKey.of("ÄÖÜ Tëst").getMatchKey()).isEqualTo("aou test");
        assertThat(TitleKey.of("進撃 2").getMatchKey()).isEqualTo("進撃");
    }

    @Test
    void shouldBeSafeOnBlankAndVeryLongTitles()
    {
        assertThat(TitleKey.of(null).getMatchKey()).isEmpty();
        assertThat(TitleKey.of("   ").getMatchKey()).isEmpty();
        assertThat(TitleKey.of("!!! ???").getMatchKey()).isEmpty();

        var long250 = "Word ".repeat(50).trim();
        assertThat(TitleKey.of(long250).getMatchKey()).startsWith("word word");
        assertThat(TitleKey.of(long250).getChapterNum()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void shouldTakeTheBlockFromTheFirstCharactersWhenBuildingTheBlockingKey()
    {
        assertThat(TitleKey.of("Isekai Yuusha").getMatchBlock()).isEqualTo("isek");
        assertThat(TitleKey.of("Ohayo").getMatchBlock()).isEqualTo("ohay");
        // Spaces do not count, so a two-word title still yields four real characters.
        assertThat(TitleKey.of("A B C D E").getMatchBlock()).isEqualTo("abcd");
        assertThat(TitleKey.of("Ab").getMatchBlock()).isEqualTo("ab");
    }
}

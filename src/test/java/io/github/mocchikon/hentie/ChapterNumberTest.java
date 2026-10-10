package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.ChapterNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ChapterNumberTest
{
    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "3,         3.0",
            "' 3 ',     3.0",
            "2.01,      2.01",
            "2.1,       2.1",
            "2.10,      2.1",
            "02,        2.0",
            "2024.06,   2024.06",
            "2.10142,   2.10142",
            "21556.1265, 21556.1265",
            "9007199254740992, 9007199254740992",
    })
    void shouldParsePlainDigitsWithAnyNumberOfDecimals(String text, double expected)
    {
        // WHEN / THEN
        assertThat(ChapterNumber.problem(text)).isNull();
        assertThat(ChapterNumber.parse(text)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"abc", "NaN", "Infinity", "-3", "1e3", "2f", "2d", "0x1p3", "2,5", ".5", "5.", "1.2.3"})
    void shouldRefuseAnythingButPlainDigitsWhenParsing(String text)
    {
        // WHEN / THEN
        assertThat(ChapterNumber.problem(text)).isEqualTo(ChapterNumber.INVALID);
        assertThatIllegalArgumentException().isThrownBy(() -> ChapterNumber.parse(text))
                .withMessage(ChapterNumber.INVALID);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"9007199254740993", "2.100000000000000000001", "1.0000000000000000001"})
    void shouldRefuseRatherThanRoundANumberADoubleCannotHold(String text)
    {
        // WHEN / THEN
        assertThat(ChapterNumber.problem(text)).isEqualTo(ChapterNumber.TOO_PRECISE);
        assertThatIllegalArgumentException().isThrownBy(() -> ChapterNumber.parse(text))
                .withMessage(ChapterNumber.TOO_PRECISE);
    }

    @Test
    void shouldRefuseANumberTooLargeForADouble()
    {
        // WHEN / THEN
        assertThat(ChapterNumber.problem("1" + "0".repeat(400))).isEqualTo(ChapterNumber.TOO_PRECISE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void shouldReadABlankFieldAsNoNumber(String text)
    {
        // WHEN / THEN
        assertThat(ChapterNumber.parse(text)).isNull();
        assertThat(ChapterNumber.problem(text)).isNull();
    }

    @ParameterizedTest(name = "{0} -> \"{1}\"")
    @CsvSource({
            "3.0,       3",
            "2.01,      2.01",
            // Part 10, which "2.1" would hide.
            "2.1,       2.10",
            "3.5,       3.50",
            "2024.06,   2024.06",
            "2026.1,    2026.10",
            "2.10142,   2.10142",
            "21556.1265, 21556.1265",
            "10000000,  10000000",
            "0.0001,    0.0001",
    })
    void shouldWriteAtLeastTwoDecimalsWheneverThereAreAny(double number, String expected)
    {
        // WHEN / THEN
        assertThat(ChapterNumber.format(number)).isEqualTo(expected);
        assertThat(ChapterNumber.parse(ChapterNumber.format(number))).isEqualTo(number);
    }

    @ParameterizedTest
    @NullSource
    void shouldWriteNothingForNoNumber(Double number)
    {
        // WHEN / THEN
        assertThat(ChapterNumber.format(number)).isNull();
    }
}

package io.github.mocchikon.hentie.dto;

import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A chapter number as the user types and reads it. The first two decimals are the part ({@code 2.01} is
 * part 1 of chapter 2, {@code 2.10} part 10, {@code 2024.06} an issue date), the same {@code main + sub/100}
 * that matching reads from titles; any further decimals order within it.
 */
public final class ChapterNumber
{
    /** Plain digits only. Parsing as a number would also take {@code NaN}, {@code Infinity}, {@code -3}, {@code 1e3} and {@code 2f}. */
    private static final Pattern PLAIN = Pattern.compile("\\d+(\\.\\d+)?");

    public static final String INVALID = "A chapter number is digits with an optional decimal part, like 3, 2.01 or 2024.06.";

    public static final String TOO_PRECISE =
            "A chapter number with that many digits cannot be stored exactly; use about 15 digits or fewer.";

    private ChapterNumber()
    {
    }

    /**
     * Null for a blank field (no number typed) or a number that can be stored. A number a double would round is
     * refused rather than saved as another number, which would show as something the user never typed.
     */
    public static String problem(String text)
    {
        if (StringUtils.isBlank(text))
        {
            return null;
        }
        String trimmed = text.strip();
        if (!PLAIN.matcher(trimmed).matches())
        {
            return INVALID;
        }
        double number = Double.parseDouble(trimmed);
        return Double.isFinite(number) && new BigDecimal(trimmed).compareTo(BigDecimal.valueOf(number)) == 0
               ? null : TOO_PRECISE;
    }

    public static Optional<String> firstProblem(Collection<String> texts)
    {
        return texts.stream().map(ChapterNumber::problem).filter(Objects::nonNull).findFirst();
    }

    /**
     * Null for a blank field: no number typed.
     *
     * @throws IllegalArgumentException with the {@link #problem} as its message
     */
    public static Double parse(String text)
    {
        String problem = problem(text);
        if (problem != null)
        {
            throw new IllegalArgumentException(problem);
        }
        return StringUtils.isBlank(text) ? null : Double.valueOf(text.strip());
    }

    /**
     * At least two decimals whenever there are any, so part 10 reads {@code 2.10}, not {@code 2.1}, which looks
     * like part 1. Always with a period: a locale comma would not parse back.
     */
    public static String format(Double number)
    {
        if (number == null)
        {
            return null;
        }
        // valueOf goes through Double.toString: the shortest decimal that reads back as this double.
        BigDecimal value = BigDecimal.valueOf(number).stripTrailingZeros();
        if (value.scale() <= 0)
        {
            return value.toPlainString();
        }
        return value.scale() == 1 ? value.setScale(2).toPlainString() : value.toPlainString();
    }
}

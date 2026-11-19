package io.github.mocchikon.hentie.scrapper.gallerydl;

import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The choices a paste makes for gallery-dl. Each value reaches gallery-dl's command line, so <b>nothing gets here
 * that was not checked against a whitelist or a strict pattern</b>: a hand-made POST must not be able to add an
 * option of its own.
 *
 * @param originals      e-hentai's original files instead of the resampled ones
 * @param delay          a {@link #normalizedDelay normalized} delay in seconds, {@code "0.4-0.65"} or {@code "2"}
 */
public record GalleryDlOptions(boolean originals, String delay)
{
    /**
     * A longer wait between two images would only look like a hang. Well under the idle timeout
     * ({@code app.gallery-dl.idle-timeout-seconds}, 300): gallery-dl prints nothing while it sleeps, so a longer
     * delay would get every run killed before its first image.
     */
    public static final int MAX_DELAY_SECONDS = 120;

    /**
     * The delay a Download-page paste starts at when it holds no e-hentai link. Only e-hentai's bans call for the
     * slower Settings default, so the other sources are not held back for them. A delay the user types always wins.
     */
    public static final String DEFAULT_DELAY_WITHOUT_EHENTAI = "0.1-0.12";

    private static final Pattern DELAY =
            Pattern.compile("\\s*(\\d{1,3}(?:\\.\\d{1,3})?)\\s*(?:-\\s*(\\d{1,3}(?:\\.\\d{1,3})?))?\\s*");

    /**
     * @throws IllegalArgumentException for a delay gallery-dl cannot read: replacing it would decide how fast a site
     *                                  is asked, and no delay at all is what its bans count
     */
    public GalleryDlOptions
    {
        String given = delay;
        delay = normalizedDelay(delay).orElseThrow(() -> new IllegalArgumentException(
                "\"" + StringUtils.abbreviate(given, 40) + "\" is not a delay gallery-dl understands"));
    }

    /**
     * A number of seconds or a range of them, as gallery-dl reads it, spaces removed. Empty for anything else, and
     * for a range whose end comes before its start: the caller decides whether that is an error or a default.
     */
    public static Optional<String> normalizedDelay(String delay)
    {
        if (delay == null)
        {
            return Optional.empty();
        }
        Matcher matcher = DELAY.matcher(delay);
        if (!matcher.matches())
        {
            return Optional.empty();
        }
        BigDecimal from = new BigDecimal(matcher.group(1));
        BigDecimal to = matcher.group(2) == null ? from : new BigDecimal(matcher.group(2));
        if (to.compareTo(from) < 0 || to.compareTo(BigDecimal.valueOf(MAX_DELAY_SECONDS)) > 0)
        {
            return Optional.empty();
        }
        String start = from.stripTrailingZeros().toPlainString();
        return Optional.of(matcher.group(2) == null ? start : start + "-" + to.stripTrailingZeros().toPlainString());
    }
}

package io.github.mocchikon.hentie.scrapper.nhentai;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Where a subscription's walk continues on nhentai: the galleries below gallery {@code below}, except those it has
 * had already.
 * <p>
 * nhentai's search has only page numbers, and they move whenever a gallery is added or removed, so neither a stored
 * page nor two pages fetched one after the other can be trusted to meet. The search's own filter
 * {@code uploaded:>Nh} does not move: it keeps every gallery uploaded more than N hours ago. With N just below the
 * age of the second the walk stopped in, the first page of that search holds at most an hour of galleries the walk
 * has had and then the rest, <b>all in one answer</b>, however long the walk was paused and however much changed
 * meanwhile. nhentai reads hours only up to about half a year back, so an older cursor is counted in days
 * ({@code uploaded:>Nd}), which keeps up to a day of galleries above it.
 * <p>
 * <b>The cursor stands above a whole second, never inside one.</b> nhentai sorts by upload time, and lists the
 * galleries of one second in no order of their ids (checked on the site: 5785, 5786, 5784 and 5783, all uploaded at
 * 17:01:04, in that order), so the second a page ends in may go on on the next page, with galleries newer by id than
 * the page's last. Ids follow upload times otherwise, so everything not yet walked has a lower id than the first
 * gallery uploaded after that second, and the galleries of the second the walk has had are named instead.
 *
 * @param below      the lowest gallery known to be uploaded after that second: the next page lists lower ids only
 * @param uploadedAt epoch seconds: the second the walk stopped in, which the filter must keep
 * @param handled    galleries below {@code below} the walk has had already, left out of the next page; those of
 *                   about one second, so few
 */
record NhentaiSearchCursor(long below, long uploadedAt, Set<Long> handled)
{
    /** nhentai answers {@code uploaded:>1h} (and every other 1) with nothing, so the filter starts at 2. */
    static final int MIN_FILTER_HOURS = 2;

    /**
     * The most hours nhentai reads as hours. Somewhere above it, every larger number lists the same galleries (checked
     * on the site: 6000, 8000 and 96000 hours alike, while 4000 hours lists as many as 167 days), which would keep
     * months of galleries above an older cursor; days have no such limit.
     */
    static final int MAX_FILTER_HOURS = 4000;

    /**
     * The filter's edge stays this far after the second the walk stopped in: its galleries must stay in, and the
     * server's clock is known only to the second.
     */
    static final Duration MARGIN = Duration.ofMinutes(2);

    private static final Pattern TOKEN = Pattern.compile("(\\d{1,12})@(\\d{1,12})(?::(\\d{1,12}(?:,\\d{1,12})*))?");

    NhentaiSearchCursor
    {
        handled = Set.copyOf(handled);
    }

    /** @throws IllegalArgumentException for a token no cursor wrote */
    static NhentaiSearchCursor parse(String token)
    {
        Matcher matcher = TOKEN.matcher(token == null ? "" : token.strip());
        if (!matcher.matches())
        {
            throw new IllegalArgumentException("Not an nhentai search cursor: '" + token + "'");
        }
        Set<Long> handled = matcher.group(3) == null ? Set.of()
                : Arrays.stream(matcher.group(3).split(",")).map(Long::parseLong).collect(Collectors.toSet());
        return new NhentaiSearchCursor(Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)), handled);
    }

    String token()
    {
        String token = below + "@" + uploadedAt;
        return handled.isEmpty() ? token
                : token + ":" + handled.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** Whether a listed gallery is one the next page holds. */
    boolean follows(long id)
    {
        return id < below && !handled.contains(id);
    }

    /**
     * The filter that keeps the second the walk stopped in and everything older, and as few newer galleries as whole
     * hours (whole days, for a second older than nhentai counts in hours) allow. Empty while that second is too recent
     * for the filter: its galleries are then on the plain search's first pages.
     */
    Optional<String> filter(Instant serverNow)
    {
        Duration room = Duration.between(Instant.ofEpochSecond(uploadedAt), serverNow).minus(MARGIN);
        long hours = room.toHours();
        if (room.isNegative() || hours < MIN_FILTER_HOURS)
        {
            return Optional.empty();
        }
        return Optional.of(hours <= MAX_FILTER_HOURS ? "uploaded:>" + hours + "h" : "uploaded:>" + room.toDays() + "d");
    }

    /** The user's search, narrowed by {@link #filter}. */
    static String filtered(String query, String filter)
    {
        return query + " " + filter;
    }
}

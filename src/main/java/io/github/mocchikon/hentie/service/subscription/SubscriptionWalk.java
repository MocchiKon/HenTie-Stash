package io.github.mocchikon.hentie.service.subscription;

import io.github.mocchikon.hentie.scrapper.SearchSource.SearchPage;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * What one page of a search does to a subscription's walk. Pure, so every transition is tested without a site or a
 * database; {@link SubscriptionService} applies the result together with the page's queue rows.
 * <p>
 * The walk covers one stretch of the search: every gallery between the head (newest) and the tail (oldest) was
 * handled. A <b>backfill</b> extends it downwards one page at a time. A <b>check</b> reads the newest page; when that
 * holds only new galleries and more follow, it leaves a <b>catch-up</b> (top, cursor, stop) that later steps carry
 * down to the stop, and only then does the top become the head - so a crash part-way leaves the head where everything
 * below it is still true. A check during a catch-up stops at its top. A <b>re-check</b> is a check whose stop is
 * older than the head, for galleries tagged since they were first passed.
 * <p>
 * Galleries are compared by position in the site's order, never by page, so a head, tail or stop whose gallery was
 * deleted works all the same.
 */
public final class SubscriptionWalk
{
    private SubscriptionWalk()
    {
    }

    public enum Kind
    {
        /** One page further down a catch-up. */
        CATCH_UP,
        /** The newest page. */
        CHECK,
        /** One page below the tail. */
        BACKFILL
    }

    /** Which of the state's cursors a step's page has to fill in, so the walk can continue below it. */
    public enum CursorField
    {
        NONE,
        OLDEST,
        CATCH_UP
    }

    /** The walk's columns; galleries as stored gallery ids. */
    public record State(String newest, String oldest, String oldestCursor, boolean reachedEnd,
                 String catchUpTop, String catchUpCursor, String catchUpStop)
    {
        static final State EMPTY = new State(null, null, null, false, null, null, null);

        public boolean catchingUp()
        {
            return catchUpTop != null;
        }
    }

    /** A source's own way of reading its galleries, so the walk needs no site. */
    public interface Positions
    {
        String galleryId(String resourceId);

        /** Higher is newer. */
        long of(String galleryId);
    }

    /**
     * @param after   where the page is fetched from: a cursor, or null for the newest page
     * @param stop    the gallery a check or catch-up ends at, itself not handled; null for none. A check during a
     *                catch-up ends at the catch-up's top
     * @param recheck a check whose stop lies below the head
     */
    public record Step(Kind kind, String after, String stop, boolean recheck)
    {
    }

    /** @param firstSeen never listed before: it counts in the subscription's totals */
    public record Listed(String resourceId, boolean firstSeen)
    {
    }

    /**
     * @param listed     the page's galleries to handle, newest first
     * @param next       the walk afterwards, without the cursor
     * @param cursor     where {@link #next(String)} puts the cursor for continuing below the page
     * @param newestGrew the head moved up, so where it was is worth a checkpoint
     */
    public record Transition(List<Listed> listed, State next, CursorField cursor, boolean newestGrew)
    {
        public State next(String cursorToken)
        {
            return switch (cursor)
            {
                case NONE -> next;
                case OLDEST -> new State(next.newest(), next.oldest(), cursorToken, next.reachedEnd(),
                        next.catchUpTop(), next.catchUpCursor(), next.catchUpStop());
                case CATCH_UP -> new State(next.newest(), next.oldest(), next.oldestCursor(), next.reachedEnd(),
                        next.catchUpTop(), cursorToken, next.catchUpStop());
            };
        }
    }

    static Transition after(State state, Step step, SearchPage page, Positions positions)
    {
        return switch (step.kind())
        {
            case CHECK -> check(state, step, page, positions);
            case CATCH_UP -> catchUp(state, page, positions);
            case BACKFILL -> backfill(state, page, positions);
        };
    }

    private static Transition check(State state, Step step, SearchPage page, Positions positions)
    {
        List<String> ids = page.resourceIds();
        if (ids.isEmpty())
        {
            return new Transition(List.of(), state, CursorField.NONE, false);
        }
        if (state.newest() == null)
        {
            // The first galleries the search has: the walk starts at this page, and backfills below it.
            State next = new State(positions.galleryId(ids.getFirst()), positions.galleryId(ids.getLast()), null,
                    !page.more(), null, null, null);
            return new Transition(listed(ids, positions, position -> true), next,
                    page.more() ? CursorField.OLDEST : CursorField.NONE, true);
        }
        long newest = positions.of(state.newest());
        List<String> above = above(ids, step.stop(), positions);
        List<Listed> listed = listed(above, positions, position -> position > newest);
        boolean reachesStop = above.isEmpty() || above.size() < ids.size() || !page.more();
        if (state.catchingUp())
        {
            // The stop is the catch-up's top. Starting the catch-up over would list its pages again, counting them
            // twice, and lose a re-check's stop; so what fits on this page raises the top, and more than a page waits
            // for the check after the catch-up, which lists it above the head the catch-up leaves.
            if (!reachesStop)
            {
                return new Transition(List.of(), state, CursorField.NONE, false);
            }
            String top = newer(state.catchUpTop(), above.isEmpty() ? null : positions.galleryId(above.getFirst()),
                    positions);
            State next = new State(state.newest(), state.oldest(), state.oldestCursor(), state.reachedEnd(), top,
                    state.catchUpCursor(), state.catchUpStop());
            return new Transition(listed, next, CursorField.NONE, false);
        }
        if (reachesStop)
        {
            String head = newer(state.newest(), above.isEmpty() ? null : positions.galleryId(above.getFirst()),
                    positions);
            return new Transition(listed, withNewest(state, head), CursorField.NONE, !head.equals(state.newest()));
        }
        // More than a page above the stop: later steps carry the catch-up down, and the head moves once it is done.
        State next = new State(state.newest(), state.oldest(), state.oldestCursor(), state.reachedEnd(),
                positions.galleryId(ids.getFirst()), null, step.stop());
        return new Transition(listed, next, CursorField.CATCH_UP, false);
    }

    private static Transition catchUp(State state, SearchPage page, Positions positions)
    {
        List<String> ids = page.resourceIds();
        List<String> above = above(ids, state.catchUpStop(), positions);
        Long newest = state.newest() == null ? null : positions.of(state.newest());
        List<Listed> listed = listed(above, positions, position -> newest == null || position > newest);
        if (above.isEmpty() || above.size() < ids.size() || !page.more())
        {
            String head = newer(state.newest(), state.catchUpTop(), positions);
            State next = new State(head, state.oldest(), state.oldestCursor(), state.reachedEnd(), null, null, null);
            return new Transition(listed, next, CursorField.NONE, !head.equals(state.newest()));
        }
        return new Transition(listed, state, CursorField.CATCH_UP, false);
    }

    private static Transition backfill(State state, SearchPage page, Positions positions)
    {
        List<String> ids = page.resourceIds();
        // Every gallery below a cursor is listed for the first time, also one above the tail: on nhentai, the rest of
        // the second the last page ended in, which the cursor names when it had it.
        List<Listed> listed = listed(ids, positions, position -> true);
        if (ids.isEmpty())
        {
            return new Transition(listed, new State(state.newest(), state.oldest(), null, true, state.catchUpTop(),
                    state.catchUpCursor(), state.catchUpStop()), CursorField.NONE, false);
        }
        String tail = older(state.oldest(), positions.galleryId(ids.getLast()), positions);
        State next = new State(state.newest(), tail, null, !page.more(), state.catchUpTop(), state.catchUpCursor(),
                state.catchUpStop());
        return new Transition(listed, next, page.more() ? CursorField.OLDEST : CursorField.NONE, false);
    }

    /** The prefix of a newest-first page that lies above the stop; all of it without one. */
    private static List<String> above(List<String> ids, String stop, Positions positions)
    {
        if (stop == null)
        {
            return ids;
        }
        long limit = positions.of(stop);
        return ids.stream().takeWhile(id -> positions.of(positions.galleryId(id)) > limit).toList();
    }

    private static List<Listed> listed(List<String> ids, Positions positions, LongPredicate firstSeen)
    {
        return ids.stream()
                .map(id -> new Listed(id, firstSeen.test(positions.of(positions.galleryId(id)))))
                .toList();
    }

    private static State withNewest(State state, String newest)
    {
        return new State(newest, state.oldest(), state.oldestCursor(), state.reachedEnd(), state.catchUpTop(),
                state.catchUpCursor(), state.catchUpStop());
    }

    /** The head never moves down: the newest gallery may be deleted while it is the head. */
    static String newer(String current, String candidate, Positions positions)
    {
        if (candidate == null)
        {
            return current;
        }
        return current == null || positions.of(candidate) > positions.of(current) ? candidate : current;
    }

    static String older(String current, String candidate, Positions positions)
    {
        if (candidate == null)
        {
            return current;
        }
        return current == null || positions.of(candidate) < positions.of(current) ? candidate : current;
    }

    // ---- when -----------------------------------------------------------------------------------------------

    static boolean checkDue(LocalDateTime lastCheckedAt, int pollMinutes, LocalDateTime now)
    {
        return lastCheckedAt == null || !now.isBefore(lastCheckedAt.plusMinutes(pollMinutes));
    }

    static boolean recheckDue(LocalDateTime lastRecheckedAt, int everyHours, LocalDateTime now)
    {
        return everyHours > 0 && (lastRecheckedAt == null || !now.isBefore(lastRecheckedAt.plusHours(everyHours)));
    }

    /**
     * When a subscription that failed may try again: the wait doubles with each failure in a row, but never
     * exceeds its polling interval, which it would have waited anyway. Null when it did not fail.
     */
    static LocalDateTime retryAt(LocalDateTime lastErrorAt, int failedSteps, int pollMinutes, int firstWaitSeconds)
    {
        if (lastErrorAt == null || failedSteps <= 0)
        {
            return null;
        }
        Duration doubled = Duration.ofSeconds(Math.max(0, firstWaitSeconds))
                .multipliedBy(1L << Math.min(failedSteps - 1, 20));
        Duration poll = Duration.ofMinutes(pollMinutes);
        return lastErrorAt.plus(doubled.compareTo(poll) < 0 ? doubled : poll);
    }
}

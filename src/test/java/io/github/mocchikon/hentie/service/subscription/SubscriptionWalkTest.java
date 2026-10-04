package io.github.mocchikon.hentie.service.subscription;

import io.github.mocchikon.hentie.scrapper.SearchSource.SearchPage;
import io.github.mocchikon.hentie.service.subscription.SubscriptionWalk.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionWalkTest
{
    /** Gallery ids {@code t:<n>}, at position n. */
    private static final SubscriptionWalk.Positions POSITIONS = new SubscriptionWalk.Positions()
    {
        @Override
        public String galleryId(String resourceId)
        {
            return "t:" + resourceId;
        }

        @Override
        public long of(String galleryId)
        {
            return Long.parseLong(galleryId.substring(2));
        }
    };

    private static SearchPage page(boolean more, long... ids)
    {
        return new SearchPage(Arrays.stream(ids).mapToObj(Long::toString).toList(), Set.of(), more, null);
    }

    private static State state(Long newest, Long oldest, boolean reachedEnd)
    {
        return new State(newest == null ? null : "t:" + newest, oldest == null ? null : "t:" + oldest,
                oldest == null ? null : "c" + oldest, reachedEnd, null, null, null);
    }

    private static Transition after(State state, Step step, SearchPage page)
    {
        return SubscriptionWalk.after(state, step, page, POSITIONS);
    }

    private static List<String> ids(Transition transition)
    {
        return transition.listed().stream().map(Listed::resourceId).toList();
    }

    private static List<String> firstSeen(Transition transition)
    {
        return transition.listed().stream().filter(Listed::firstSeen).map(Listed::resourceId).toList();
    }

    @Test
    void shouldStartTheWalkAtTheFirstPageAndBackfillBelowIt()
    {
        // WHEN a new subscription's first check finds a full page with more below
        Transition transition = after(State.EMPTY, new Step(Kind.CHECK, null, null, false), page(true, 30, 29, 28));

        // THEN everything on it is new, the head and tail span it, and the backfill continues from its last gallery
        assertThat(firstSeen(transition)).containsExactly("30", "29", "28");
        assertThat(transition.cursor()).isEqualTo(CursorField.OLDEST);
        assertThat(transition.newestGrew()).isTrue();
        assertThat(transition.next("c28")).isEqualTo(new State("t:30", "t:28", "c28", false, null, null, null));
    }

    @Test
    void shouldEndAtOnceWhenTheFirstPageIsTheLast()
    {
        // WHEN
        Transition transition = after(State.EMPTY, new Step(Kind.CHECK, null, null, false), page(false, 3, 2));

        // THEN
        assertThat(transition.cursor()).isEqualTo(CursorField.NONE);
        assertThat(transition.next(null)).isEqualTo(new State("t:3", "t:2", null, true, null, null, null));
    }

    /** A search with nothing yet keeps no head, so a later check starts the walk. */
    @Test
    void shouldLeaveAnEmptySearchAsItWas()
    {
        // WHEN
        Transition transition = after(State.EMPTY, new Step(Kind.CHECK, null, null, false), page(false));

        // THEN
        assertThat(transition.listed()).isEmpty();
        assertThat(transition.next(null)).isEqualTo(State.EMPTY);
    }

    @Test
    void shouldFindNothingNewAboveTheHead()
    {
        // GIVEN
        State walked = state(10L, 1L, true);

        // WHEN
        Transition transition = after(walked, new Step(Kind.CHECK, null, "t:10", false), page(true, 10, 9, 8));

        // THEN
        assertThat(transition.listed()).isEmpty();
        assertThat(transition.next(null)).isEqualTo(walked);
        assertThat(transition.newestGrew()).isFalse();
    }

    @Test
    void shouldTakeTheNewGalleriesOfThePageThatReachesTheHead()
    {
        // GIVEN
        State walked = state(10L, 1L, true);

        // WHEN
        Transition transition = after(walked, new Step(Kind.CHECK, null, "t:10", false), page(true, 13, 12, 11, 10, 9));

        // THEN
        assertThat(firstSeen(transition)).containsExactly("13", "12", "11");
        assertThat(transition.next(null).newest()).isEqualTo("t:13");
        assertThat(transition.newestGrew()).isTrue();
    }

    /** The head moves only once the catch-up is done: a crash part-way must not leave a gap below the head. */
    @Test
    void shouldLeaveACatchUpWhenAWholePageIsNew()
    {
        // GIVEN
        State walked = state(10L, 1L, true);

        // WHEN
        Transition transition = after(walked, new Step(Kind.CHECK, null, "t:10", false), page(true, 15, 14, 13));

        // THEN
        assertThat(firstSeen(transition)).containsExactly("15", "14", "13");
        assertThat(transition.cursor()).isEqualTo(CursorField.CATCH_UP);
        assertThat(transition.next("c13")).isEqualTo(new State("t:10", "t:1", "c1", true, "t:15", "c13", "t:10"));
    }

    @Test
    void shouldCarryACatchUpDownToItsStop()
    {
        // GIVEN a catch-up from 15 down to the head at 10
        State catchingUp = new State("t:10", "t:1", "c1", true, "t:15", "c13", "t:10");

        // WHEN the next page still lies above the stop, and the one after reaches it
        Transition middle = after(catchingUp, new Step(Kind.CATCH_UP, "c13", "t:10", false), page(true, 12, 11));
        State moved = middle.next("c11");
        Transition last = after(moved, new Step(Kind.CATCH_UP, "c11", "t:10", false), page(true, 10, 9));

        // THEN the head becomes the catch-up's top only at the end
        assertThat(firstSeen(middle)).containsExactly("12", "11");
        assertThat(moved).isEqualTo(new State("t:10", "t:1", "c1", true, "t:15", "c11", "t:10"));
        assertThat(last.listed()).isEmpty();
        assertThat(last.next(null)).isEqualTo(new State("t:15", "t:1", "c1", true, null, null, null));
        assertThat(last.newestGrew()).isTrue();
    }

    /** Starting the catch-up over would list its pages again and lose its stop, a re-check's here. */
    @Test
    void shouldRaiseAWaitingCatchUpsTopOnACheck()
    {
        // GIVEN a re-check's catch-up from 15 down to the checkpoint at 5, below the head at 10
        State catchingUp = new State("t:10", "t:1", "c1", true, "t:15", "c13", "t:5");

        // WHEN a check finds two new galleries above the catch-up's top
        Transition transition = after(catchingUp, new Step(Kind.CHECK, null, "t:15", false),
                page(true, 17, 16, 15, 14));

        // THEN they are listed, and the catch-up goes on below its cursor with the new top and its own stop
        assertThat(ids(transition)).containsExactly("17", "16");
        assertThat(firstSeen(transition)).containsExactly("17", "16");
        assertThat(transition.cursor()).isEqualTo(CursorField.NONE);
        assertThat(transition.newestGrew()).isFalse();
        assertThat(transition.next(null)).isEqualTo(new State("t:10", "t:1", "c1", true, "t:17", "c13", "t:5"));
    }

    @Test
    void shouldLeaveMoreThanAPageAboveAWaitingCatchUpForTheCheckAfterIt()
    {
        // GIVEN
        State catchingUp = new State("t:10", "t:1", "c1", true, "t:15", "c13", "t:5");

        // WHEN a whole page lies above the catch-up's top
        Transition transition = after(catchingUp, new Step(Kind.CHECK, null, "t:15", false),
                page(true, 20, 19, 18));

        // THEN nothing is listed and the catch-up is untouched
        assertThat(transition.listed()).isEmpty();
        assertThat(transition.next(null)).isEqualTo(catchingUp);
    }

    @Test
    void shouldEndACatchUpAtTheSearchsEnd()
    {
        // GIVEN
        State catchingUp = new State("t:10", "t:1", "c1", true, "t:15", "c13", "t:10");

        // WHEN the galleries between the cursor and the head were all deleted
        Transition transition = after(catchingUp, new Step(Kind.CATCH_UP, "c13", "t:10", false), page(false));

        // THEN
        assertThat(transition.next(null).catchingUp()).isFalse();
        assertThat(transition.next(null).newest()).isEqualTo("t:15");
    }

    /** A re-check lists below the head again; what was listed before counts only if it gets queued now. */
    @Test
    void shouldListTheRecentPastAgainOnARecheck()
    {
        // GIVEN a head at 20 and a checkpoint at 15
        State walked = state(20L, 1L, true);

        // WHEN
        Transition transition = after(walked, new Step(Kind.CHECK, null, "t:15", true),
                page(true, 22, 21, 20, 19, 18, 17, 16, 15, 14));

        // THEN
        assertThat(ids(transition)).containsExactly("22", "21", "20", "19", "18", "17", "16");
        assertThat(firstSeen(transition)).containsExactly("22", "21");
        assertThat(transition.next(null).newest()).isEqualTo("t:22");
    }

    @Test
    void shouldExtendTheTailOnePageAtATime()
    {
        // GIVEN
        State walked = state(30L, 28L, false);

        // WHEN
        Transition transition = after(walked, new Step(Kind.BACKFILL, "c28", null, false), page(true, 27, 26, 25));

        // THEN
        assertThat(firstSeen(transition)).containsExactly("27", "26", "25");
        assertThat(transition.cursor()).isEqualTo(CursorField.OLDEST);
        assertThat(transition.next("c25")).isEqualTo(new State("t:30", "t:25", "c25", false, null, null, null));
    }

    /**
     * A page below a cursor may hold galleries above the tail (on nhentai, the rest of the second the last page ended
     * in); the cursor leaves out those it had, so they are new and count.
     */
    @Test
    void shouldCountGalleriesAboveTheTailBelowACursor()
    {
        // GIVEN
        State walked = state(30L, 28L, false);

        // WHEN
        Transition transition = after(walked, new Step(Kind.BACKFILL, "c29", null, false), page(false, 29, 27));

        // THEN
        assertThat(ids(transition)).containsExactly("29", "27");
        assertThat(firstSeen(transition)).containsExactly("29", "27");
        assertThat(transition.next(null)).isEqualTo(new State("t:30", "t:27", null, true, null, null, null));
    }

    @Test
    void shouldReachTheEndOnAnEmptyPage()
    {
        // WHEN
        Transition transition = after(state(30L, 28L, false), new Step(Kind.BACKFILL, "c28", null, false),
                page(false));

        // THEN
        assertThat(transition.next(null).reachedEnd()).isTrue();
        assertThat(transition.next(null).oldest()).isEqualTo("t:28");
    }

    /** The head may be deleted from the site; positions still order everything around it. */
    @Test
    void shouldCompareWithADeletedHeadByPosition()
    {
        // WHEN the head 10 is gone from the page
        Transition transition = after(state(10L, 1L, true), new Step(Kind.CHECK, null, "t:10", false),
                page(true, 12, 9, 8));

        // THEN
        assertThat(firstSeen(transition)).containsExactly("12");
        assertThat(transition.next(null).newest()).isEqualTo("t:12");
    }

    @Test
    void shouldTellWhenChecksAreDue()
    {
        // GIVEN
        LocalDateTime now = LocalDateTime.of(2026, 10, 4, 12, 0);

        // WHEN + THEN
        assertThat(SubscriptionWalk.checkDue(null, 10, now)).isTrue();
        assertThat(SubscriptionWalk.checkDue(now.minusMinutes(10), 10, now)).isTrue();
        assertThat(SubscriptionWalk.checkDue(now.minusMinutes(9), 10, now)).isFalse();
        assertThat(SubscriptionWalk.recheckDue(now.minusHours(24), 24, now)).isTrue();
        assertThat(SubscriptionWalk.recheckDue(now.minusHours(23), 24, now)).isFalse();
        assertThat(SubscriptionWalk.recheckDue(now.minusYears(1), 0, now)).isFalse();
    }

    @Test
    void shouldDoubleTheWaitAfterEachFailureUpToThePollingInterval()
    {
        // GIVEN
        LocalDateTime failedAt = LocalDateTime.of(2026, 10, 4, 12, 0);

        // WHEN + THEN
        assertThat(SubscriptionWalk.retryAt(null, 0, 60, 60)).isNull();
        assertThat(SubscriptionWalk.retryAt(failedAt, 1, 60, 60)).isEqualTo(failedAt.plusMinutes(1));
        assertThat(SubscriptionWalk.retryAt(failedAt, 3, 60, 60)).isEqualTo(failedAt.plusMinutes(4));
        assertThat(SubscriptionWalk.retryAt(failedAt, 9, 60, 60)).isEqualTo(failedAt.plusMinutes(60));
        assertThat(SubscriptionWalk.retryAt(failedAt, 400, 60, 60)).isEqualTo(failedAt.plusMinutes(60));
    }
}

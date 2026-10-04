package io.github.mocchikon.hentie.scrapper.nhentai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NhentaiSearchCursorTest
{
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void shouldReadBackTheTokenItWrote()
    {
        // GIVEN
        var cursor = new NhentaiSearchCursor(685814, 1791024076L, Set.of(685813L, 685811L));
        var bare = new NhentaiSearchCursor(685814, 1791024076L, Set.of());

        // WHEN
        String token = cursor.token();

        // THEN
        assertThat(token).isEqualTo("685814@1791024076:685811,685813");
        assertThat(NhentaiSearchCursor.parse(token)).isEqualTo(cursor);
        assertThat(NhentaiSearchCursor.parse(bare.token())).isEqualTo(bare);
        assertThat(bare.token()).isEqualTo("685814@1791024076");
        for (String broken : List.of("685814", "685814@", "685814@1:", "685814@1:2,", "ehentai:1/abc"))
        {
            assertThatThrownBy(() -> NhentaiSearchCursor.parse(broken)).as(broken)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void shouldFollowWithLowerIdsOnlyAndNeverWithWhatItHadAlready()
    {
        // GIVEN
        var cursor = new NhentaiSearchCursor(100, 1791024076L, Set.of(98L));

        // WHEN + THEN
        assertThat(cursor.follows(99)).isTrue();
        assertThat(cursor.follows(98)).isFalse();
        assertThat(cursor.follows(100)).isFalse();
        assertThat(cursor.follows(101)).isFalse();
    }

    /** Whole hours only, and the edge after the upload: rounding up would cut the galleries just below the cursor. */
    @Test
    void shouldKeepTheCursorsUploadInsideTheFilter()
    {
        // GIVEN a gallery uploaded 30 hours ago
        var cursor = cursor(NOW.minus(Duration.ofHours(30)));

        // WHEN
        Optional<String> filter = cursor.filter(NOW);

        // THEN "more than 29 hours ago" still keeps it, with the margin to spare
        assertThat(filter).hasValue("uploaded:>29h");
        assertThat(NOW.minus(Duration.ofHours(29)))
                .isAfter(Instant.ofEpochSecond(cursor.uploadedAt()).plus(NhentaiSearchCursor.MARGIN));
        assertThat(NhentaiSearchCursor.filtered("tag:\"big breasts\"", filter.get()))
                .isEqualTo("tag:\"big breasts\" uploaded:>29h");
    }

    /** nhentai answers {@code uploaded:>1h} with nothing at all, which a walk would take as the end of the search. */
    @Test
    void shouldNeverAskForOneHour()
    {
        // GIVEN galleries uploaded just over two hours ago, and just under
        var olderThanTwo = cursor(NOW.minus(Duration.ofMinutes(125)));
        var underTwo = cursor(NOW.minus(Duration.ofMinutes(121)));
        var justUploaded = cursor(NOW.minusSeconds(30));

        // WHEN + THEN the plain search serves the young ones
        assertThat(olderThanTwo.filter(NOW)).hasValue("uploaded:>2h");
        assertThat(underTwo.filter(NOW)).isEmpty();
        assertThat(justUploaded.filter(NOW)).isEmpty();
    }

    @Test
    void shouldUseThePlainSearchWithAClockBehind()
    {
        // GIVEN a second the site's clock has not reached yet
        var future = cursor(NOW.plus(Duration.ofHours(5)));

        // WHEN + THEN
        assertThat(future.filter(NOW)).isEmpty();
    }

    /** nhentai reads hours only so far back; an older cursor would keep a whole stretch of galleries above it. */
    @Test
    void shouldCountAnOldCursorInDays()
    {
        // GIVEN cursors just inside the hours nhentai reads, just beyond, and a year old
        long max = NhentaiSearchCursor.MAX_FILTER_HOURS;
        var lastInHours = cursor(NOW.minus(Duration.ofHours(max).plus(NhentaiSearchCursor.MARGIN)).minusSeconds(60));
        var firstInDays = cursor(NOW.minus(Duration.ofHours(max + 1).plus(NhentaiSearchCursor.MARGIN)).minusSeconds(60));
        var yearOld = cursor(NOW.minus(Duration.ofDays(365)));

        // WHEN + THEN
        assertThat(lastInHours.filter(NOW)).hasValue("uploaded:>" + max + "h");
        assertThat(firstInDays.filter(NOW)).hasValue("uploaded:>" + (max + 1) / 24 + "d");
        assertThat(yearOld.filter(NOW)).hasValue("uploaded:>364d");
    }

    private static NhentaiSearchCursor cursor(Instant uploaded)
    {
        return new NhentaiSearchCursor(1, uploaded.getEpochSecond(), Set.of());
    }
}

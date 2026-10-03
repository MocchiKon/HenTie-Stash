package io.github.mocchikon.hentie.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Status} is stored as an ORDINAL, so reordering it would silently corrupt every existing row. */
class StatusTest
{
    @Test
    void shouldKeepOrdinalOrderStableWhenStoredInDb()
    {
        // Existing rows hold these numbers. Do not reorder.
        // WHEN + THEN
        assertThat(Status.NEW.ordinal()).isZero();
        assertThat(Status.REVIEWED.ordinal()).isEqualTo(1);
        assertThat(Status.REVIEWED_FAVOURITE.ordinal()).isEqualTo(2);
        assertThat(Status.values()).containsExactly(
                Status.NEW, Status.REVIEWED, Status.REVIEWED_FAVOURITE);
    }

    @Test
    void shouldReturnHumanFriendlyDisplayNamesWhenGettingDisplayName()
    {
        // WHEN
        String newName = Status.NEW.getDisplayName();
        String reviewedName = Status.REVIEWED.getDisplayName();
        String favouriteName = Status.REVIEWED_FAVOURITE.getDisplayName();

        // THEN
        assertThat(newName).isEqualTo("New");
        assertThat(reviewedName).isEqualTo("Reviewed");
        // Not derivable from the constant name.
        assertThat(favouriteName).isEqualTo("Favourite");
    }
}

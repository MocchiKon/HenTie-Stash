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
        // 2 was the favourite until V8 moved it into a column of its own.
        assertThat(Status.UPDATED.ordinal()).isEqualTo(2);
        assertThat(Status.values()).containsExactly(Status.NEW, Status.REVIEWED, Status.UPDATED);
    }

    @Test
    void shouldReturnHumanFriendlyDisplayNamesWhenGettingDisplayName()
    {
        // WHEN
        String newName = Status.NEW.getDisplayName();
        String reviewedName = Status.REVIEWED.getDisplayName();
        String updatedName = Status.UPDATED.getDisplayName();

        // THEN
        assertThat(newName).isEqualTo("New");
        assertThat(reviewedName).isEqualTo("Reviewed");
        assertThat(updatedName).isEqualTo("Updated");
    }
}

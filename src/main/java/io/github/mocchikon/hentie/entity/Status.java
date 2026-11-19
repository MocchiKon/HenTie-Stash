package io.github.mocchikon.hentie.entity;

import java.util.List;

/**
 * ORDINAL in the database: never reorder, existing rows hold the integer. A new value goes at the end, with a
 * migration widening the {@code status} CHECK constraints (V8 did it for {@code UPDATED}).
 */
public enum Status
{
    NEW("New"),
    REVIEWED("Reviewed"),
    REVIEWED_FAVOURITE("Favourite"),
    /** Series only: a reviewed series that a new chapter joined since. */
    UPDATED("Updated");

    /** What a chapter can be: "updated" says something about a series' chapters, not about one chapter. */
    public static final List<Status> CHAPTER_VALUES = List.of(NEW, REVIEWED, REVIEWED_FAVOURITE);

    private final String displayName;

    Status(String displayName)
    {
        this.displayName = displayName;
    }

    public String getDisplayName()
    {
        return displayName;
    }
}

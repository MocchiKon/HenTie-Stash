package io.github.mocchikon.hentie.entity;

/** ORDINAL in the database: never reorder, existing rows hold 0/1/2. */
public enum Status
{
    NEW("New"),
    REVIEWED("Reviewed"),
    REVIEWED_FAVOURITE("Favourite");

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

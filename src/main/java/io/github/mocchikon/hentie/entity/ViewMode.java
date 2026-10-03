package io.github.mocchikon.hentie.entity;

public enum ViewMode
{
    FIT("Fit to page"),
    FIT_HORIZONTAL("Fit horizontal"),
    ORIGINAL("Original size");

    private final String displayName;

    ViewMode(String displayName)
    {
        this.displayName = displayName;
    }

    public String getDisplayName()
    {
        return displayName;
    }
}

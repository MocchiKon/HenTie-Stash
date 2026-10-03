package io.github.mocchikon.hentie.dto;

/**
 * Lets a detail view mute the decoration around the work's own name. When the pretty title is not inside
 * {@code titleFull} (a user rename), {@link #match()} is the whole title and nothing is muted.
 *
 * <p>The lookup ignores case, but the pieces are cut from {@code titleFull}, so the text shown is always the
 * stored full title.
 */
public record TitleParts(String prefix, String match, String suffix)
{
    public static TitleParts of(String titleFull, String prettyTitle)
    {
        String full = titleFull == null ? "" : titleFull;
        if (prettyTitle == null || prettyTitle.isBlank() || prettyTitle.length() >= full.length())
        {
            return new TitleParts("", full, "");
        }
        int start = full.toLowerCase().indexOf(prettyTitle.toLowerCase());
        if (start < 0)
        {
            return new TitleParts("", full, "");
        }
        int end = start + prettyTitle.length();
        return new TitleParts(full.substring(0, start), full.substring(start, end), full.substring(end));
    }

    public boolean isSplit()
    {
        return !prefix.isEmpty() || !suffix.isEmpty();
    }
}

package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.entity.Metadata;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * {@code count} = chapters in the series carrying the value; null on chapter views and for overrides.
 * {@code favouriteAction} is where the chip's heart posts; null for a chip without one (only artists have it).
 */
@Getter
@AllArgsConstructor
public class ChipDto
{
    private String label;
    private String href;
    private Long count;
    private String favouriteAction;
    private boolean favourite;

    public ChipDto(String label, String href, Long count)
    {
        this(label, href, count, null, false);
    }

    /** An artist's chip gets a heart; every other value's chip is a plain link. */
    public static ChipDto of(Metadata item, String href, Long count)
    {
        if (item instanceof Artist artist)
        {
            return new ChipDto(artist.getName(), href, count, "/artist/" + artist.getId() + "/favourite",
                    artist.isFavourite());
        }
        return new ChipDto(item.getName(), href, count);
    }
}

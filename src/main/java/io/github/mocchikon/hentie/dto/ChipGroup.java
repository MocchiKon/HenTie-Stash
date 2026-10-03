package io.github.mocchikon.hentie.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ChipGroup
{
    private String title;
    private List<ChipDto> chips;
    /** True when the series has no override for this facet. */
    private boolean derived;

    public boolean isEmpty()
    {
        return chips == null || chips.isEmpty();
    }
}

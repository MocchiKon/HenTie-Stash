package io.github.mocchikon.hentie.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** {@code count} = chapters in the series carrying the value; null on chapter views and for overrides. */
@Getter
@AllArgsConstructor
public class ChipDto
{
    private String label;
    private String href;
    private Long count;
}

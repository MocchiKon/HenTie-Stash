package io.github.mocchikon.hentie.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CardDto
{
    /** Read from the grid by the results page's bulk actions. */
    private int id;
    private String href;
    private String thumbnailUrl;
    private String caption;
}

package io.github.mocchikon.hentie.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Carries the two halves of {@code score} as well, so the ranking can be explained on the page. */
@Getter
@AllArgsConstructor
public class SeriesMatchDto
{
    private Integer seriesId;
    private String titleFull;
    private String thumbnailUrl;
    private int chapterCount;
    private int sharedArtists;
    private double titleSimilarity;
    private double score;
}

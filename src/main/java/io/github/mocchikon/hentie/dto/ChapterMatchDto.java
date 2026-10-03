package io.github.mocchikon.hentie.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Names the chapter's current series and its size because linking moves the chapter out, and an emptied
 * series is deleted.
 */
@Getter
@AllArgsConstructor
public class ChapterMatchDto
{
    private int chapterId;
    private String titleFull;
    private String thumbnailUrl;
    private String language;
    private int pageCount;
    /** Null when it is in none. */
    private Integer seriesId;
    private String seriesTitle;
    /** This chapter included; 0 when it is in none. */
    private long seriesChapterCount;
    private int sharedArtists;
    private double titleSimilarity;
    private double score;
}

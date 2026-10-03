package io.github.mocchikon.hentie.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ChapterCardDto
{
    private Integer id;
    private String thumbnailUrl;
    private String titleFull;
    /** Null for a chapter in no series. */
    private Float chapterNum;
}

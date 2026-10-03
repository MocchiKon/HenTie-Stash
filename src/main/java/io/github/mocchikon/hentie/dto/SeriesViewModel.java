package io.github.mocchikon.hentie.dto;

import java.time.LocalDate;
import java.util.List;

import io.github.mocchikon.hentie.entity.Series;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SeriesViewModel
{
    private Series series;
    private String thumbnailUrl;
    private List<ChipGroup> detailGroups;

    private List<String> languages;
    private String selectedLanguage;

    /** Filtered by selectedLanguage. */
    private List<CardDto> chapterCards;

    private int chapterCount;
    private int pageCount;
    private String diskSizeDisplay;
    /** 1-10: the override, else the chapter average. */
    private Integer score;
    private boolean scoreDerived;
    private LocalDate createdDate;
    private String effectiveLanguage;

    public TitleParts getTitleParts()
    {
        return TitleParts.of(series.getTitleFull(), series.getTitle());
    }
}

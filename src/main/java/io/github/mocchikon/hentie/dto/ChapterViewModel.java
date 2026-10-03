package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ChapterViewModel
{
    private Chapter chapter;
    private String thumbnailUrl;
    private List<ChipGroup> detailGroups;
    private List<String> pageUrls;
    private int pageCount;
    private String diskSizeDisplay;
    private String statusDisplay;

    // Null when the chapter is in no series.
    private Integer seriesId;
    private Integer prevChapterId;
    private Integer nextChapterId;

    public TitleParts getTitleParts()
    {
        return TitleParts.of(chapter.getTitleFull(), chapter.getTitle());
    }

    public boolean hasSeries()
    {
        return seriesId != null;
    }

    /** Shown because nothing else on the page tells a cut-short download from a complete chapter. */
    public boolean isDownloadIncomplete()
    {
        return chapter.getDownloadStatus() == DownloadStatus.PENDING;
    }
}

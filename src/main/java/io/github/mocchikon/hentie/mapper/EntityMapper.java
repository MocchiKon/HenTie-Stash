package io.github.mocchikon.hentie.mapper;

import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Metadata;
import io.github.mocchikon.hentie.entity.ScoreSource;
import io.github.mocchikon.hentie.entity.Series;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.ArrayList;
import java.util.List;

/** Entity to form only: form to entity lives in the services, which must resolve the ids. */
@Mapper(componentModel = "spring")
public interface EntityMapper
{
    @Mapping(target = "tagIds", expression = "java(ids(chapter.getTags()))")
    @Mapping(target = "artistIds", expression = "java(ids(chapter.getArtists()))")
    @Mapping(target = "characterIds", expression = "java(ids(chapter.getCharacters()))")
    @Mapping(target = "parodyIds", expression = "java(ids(chapter.getParodies()))")
    @Mapping(target = "groupIds", expression = "java(ids(chapter.getGroups()))")
    @Mapping(target = "categoryIds", expression = "java(ids(chapter.getCategories()))")
    ChapterForm toForm(Chapter chapter);

    // Override only: a derived average must not show up in the form as if the user had set it.
    @Mapping(target = "score", expression = "java(userScore(series))")
    @Mapping(target = "tagIds", expression = "java(ids(series.getTags()))")
    @Mapping(target = "artistIds", expression = "java(ids(series.getArtists()))")
    @Mapping(target = "characterIds", expression = "java(ids(series.getCharacters()))")
    @Mapping(target = "parodyIds", expression = "java(ids(series.getParodies()))")
    @Mapping(target = "groupIds", expression = "java(ids(series.getGroups()))")
    @Mapping(target = "categoryIds", expression = "java(ids(series.getCategories()))")
    @Mapping(target = "chapterIds", ignore = true)
    @Mapping(target = "chapterNums", ignore = true)
    SeriesForm toForm(Series series);

    default Integer userScore(Series series)
    {
        return series.getScoreSource() == ScoreSource.USER_SET && series.getScore() != null
                ? series.getScore().intValue() : null;
    }

    /** Picked up by MapStruct for every score property. */
    default Integer scoreToInt(Short score)
    {
        return score == null ? null : score.intValue();
    }

    default List<Integer> ids(List<? extends Metadata> items)
    {
        List<Integer> result = new ArrayList<>();
        if (items != null)
        {
            for (Metadata item : items)
            {
                result.add(item.getId());
            }
        }
        return result;
    }
}

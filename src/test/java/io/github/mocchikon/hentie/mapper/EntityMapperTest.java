package io.github.mocchikon.hentie.mapper;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.ScoreSource;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.entity.Tag;

import static org.assertj.core.api.Assertions.*;

class EntityMapperTest
{
    private final EntityMapper mapper = new EntityMapperImpl();

    private Tag tag(int id, String name)
    {
        Tag t = new Tag();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private Artist artist(int id, String name)
    {
        Artist a = new Artist();
        a.setId(id);
        a.setName(name);
        return a;
    }

    @Test
    void shouldFlattenChapterMetadataToIdListsAndScalarsWhenMappingToForm()
    {
        // GIVEN
        Chapter c = new Chapter();
        c.setId(42);
        c.setTitle("Pretty");
        c.setTitleFull("Full");
        c.setNativeTitle("Native");
        c.setStatus(Status.REVIEWED_FAVOURITE);
        c.setGalleryId("G-9");
        c.setLanguage("English");
        c.setScore((short) 8);
        c.setTags(new ArrayList<>(List.of(tag(1, "a"), tag(2, "b"))));
        c.setArtists(new ArrayList<>(List.of(artist(3, "artist"))));

        // WHEN
        ChapterForm form = mapper.toForm(c);

        // THEN
        assertThat(form.getId()).isEqualTo(42);
        assertThat(form.getTitle()).isEqualTo("Pretty");
        assertThat(form.getTitleFull()).isEqualTo("Full");
        assertThat(form.getNativeTitle()).isEqualTo("Native");
        assertThat(form.getStatus()).isEqualTo(Status.REVIEWED_FAVOURITE);
        assertThat(form.getGalleryId()).isEqualTo("G-9");
        assertThat(form.getLanguage()).isEqualTo("English");
        assertThat(form.getScore()).isEqualTo(8);
        assertThat(form.getTagIds()).containsExactly(1, 2);
        assertThat(form.getArtistIds()).containsExactly(3);
        assertThat(form.getCharacterIds()).isEmpty();
    }

    @Test
    void shouldLeaveScoreNullWhenChapterScoreIsUnset()
    {
        // GIVEN
        Chapter c = new Chapter();
        c.setTitleFull("No Score");
        c.setScore(null);

        // WHEN
        ChapterForm form = mapper.toForm(c);

        // THEN
        assertThat(form.getScore()).isNull();
    }

    @Test
    void shouldMapUserSetOverrideScoreWhenMappingSeriesToForm()
    {
        // GIVEN
        Series s = new Series();
        s.setId(7);
        s.setTitleFull("Series Full");
        s.setScore((short) 3);
        s.setScoreSource(ScoreSource.USER_SET);
        s.setStatus(Status.REVIEWED);
        s.setTags(new ArrayList<>(List.of(tag(5, "t"))));

        // WHEN
        SeriesForm form = mapper.toForm(s);

        // THEN
        assertThat(form.getId()).isEqualTo(7);
        assertThat(form.getTitleFull()).isEqualTo("Series Full");
        assertThat(form.getScore()).isEqualTo(3);
        assertThat(form.getStatus()).isEqualTo(Status.REVIEWED);
        assertThat(form.getTagIds()).containsExactly(5);
        // Services own chapter membership.
        assertThat(form.getChapterIds()).isEmpty();
    }

    @Test
    void shouldBlankTheFormScoreWhenSeriesScoreIsDerived()
    {
        // GIVEN
        // A DERIVED score is the chapter average; pre-filled, it would silently become an override on save.
        Series s = new Series();
        s.setId(8);
        s.setTitleFull("Derived Series");
        s.setScore((short) 6);
        s.setScoreSource(ScoreSource.DERIVED);
        s.setStatus(Status.REVIEWED);

        // WHEN
        SeriesForm form = mapper.toForm(s);

        // THEN
        assertThat(form.getScore()).isNull();
    }

    @Test
    void shouldConvertScoreToIntWhenValueIsNullOrPresent()
    {
        // WHEN
        Integer fromNull = mapper.scoreToInt(null);
        Integer fromValue = mapper.scoreToInt((short) 10);

        // THEN
        assertThat(fromNull).isNull();
        assertThat(fromValue).isEqualTo(10);
    }

    @Test
    void shouldReturnEmptyListWhenCollectionIsNull()
    {
        // WHEN
        var ids = mapper.ids(null);

        // THEN
        assertThat(ids).isEmpty();
    }
}

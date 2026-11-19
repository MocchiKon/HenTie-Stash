package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.CardDto;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.dto.SearchType;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.SearchService;
import io.github.mocchikon.hentie.service.SeriesService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code favourite_artist} is kept by the database's triggers alone, so each test changes artists and links the way
 * the app does (forms, series membership, merge, removal) and reads the flag from the rows afterwards.
 */
@SpringBootTest
@Transactional
class FavouriteArtistIT
{
    @Autowired ChapterService chapterService;
    @Autowired SeriesService seriesService;
    @Autowired MetadataService metadataService;
    @Autowired SearchService searchService;
    @Autowired ArtistRepository artistRepository;
    @Autowired CacheManager cacheManager;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void clearCaches()
    {
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.METADATA)).clear();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();
    }

    @Test
    void shouldFlagWhatCarriesAnArtistOnlyWhileItIsAFavourite()
    {
        // GIVEN a favourite-to-be on two chapters and a series of one of them, and a series whose own artists
        // override its chapters'
        Artist loved = artist("fa-loved");
        Artist other = artist("fa-other");
        int alone = chapter("fa-alone", loved);
        int shared = chapter("fa-shared", loved, other);
        chapter("fa-unrelated", other);
        series("fa-derived", List.of(), alone);
        series("fa-overridden", List.of(other.getId()), shared);

        // WHEN
        metadataService.setArtistFavourite(loved.getId(), true);
        List<String> marked = flagged();
        metadataService.setArtistFavourite(loved.getId(), false);
        List<String> unmarked = flagged();

        // THEN the series follows its effective artists: an override without the favourite is not flagged
        assertThat(marked).containsExactlyInAnyOrder("chapter fa-alone", "chapter fa-shared", "series fa-derived");
        assertThat(unmarked).isEmpty();
    }

    @Test
    void shouldFollowAChapterEditAndItsSeries()
    {
        // GIVEN a favourite and a chapter without it, in a series
        Artist loved = favourite("fa-edit-loved");
        Artist other = artist("fa-edit-other");
        int id = chapter("fa-edit", other);
        series("fa-edit-series", List.of(), id);

        // WHEN the favourite is added to the chapter, then taken off again
        edit(id, loved.getId(), other.getId());
        List<String> added = flagged();
        edit(id, other.getId());
        List<String> removed = flagged();

        // THEN
        assertThat(added).containsExactlyInAnyOrder("chapter fa-edit", "series fa-edit-series");
        assertThat(removed).isEmpty();
    }

    @Test
    void shouldKeepTheMarkWhenAFavouriteIsMergedIntoAnotherArtist()
    {
        // GIVEN
        Artist loved = favourite("fa-merge-loved");
        Artist target = artist("fa-merge-target");
        chapter("fa-merge-a", loved);
        chapter("fa-merge-b", target);

        // WHEN
        metadataService.merge(MetadataType.ARTIST, loved.getId(), target.getId(), false);

        // THEN whatever carried either artist carries a favourite
        assertThat(jdbc.queryForObject("select favourite from artist where id = ?", Boolean.class, target.getId()))
                .isTrue();
        assertThat(flagged()).containsExactlyInAnyOrder("chapter fa-merge-a", "chapter fa-merge-b");
    }

    @Test
    void shouldClearTheFlagWhenAFavouriteIsRemoved()
    {
        // GIVEN
        Artist loved = favourite("fa-remove-loved");
        int id = chapter("fa-remove", loved);
        series("fa-remove-series", List.of(), id);
        List<String> before = flagged();

        // WHEN
        metadataService.remove(MetadataType.ARTIST, loved.getId(), false);

        // THEN
        assertThat(before).containsExactlyInAnyOrder("chapter fa-remove", "series fa-remove-series");
        assertThat(flagged()).isEmpty();
    }

    @Test
    void shouldFindOnlyChaptersAndSeriesWithAFavouriteArtist()
    {
        // GIVEN
        Artist loved = favourite("fa-search-loved");
        Artist other = artist("fa-search-other");
        int found = chapter("fa-search-found", loved, other);
        int missed = chapter("fa-search-missed", other);
        int foundSeries = series("fa-search-found-series", List.of(), found);
        series("fa-search-missed-series", List.of(), missed);

        // WHEN
        List<Integer> chapters = search(SearchType.CHAPTER);
        List<Integer> seriesFound = search(SearchType.SERIES);

        // THEN
        assertThat(chapters).containsExactly(found);
        assertThat(seriesFound).containsExactly(foundSeries);
    }

    @Test
    void shouldIgnoreAnUnknownArtist()
    {
        assertThat(metadataService.setArtistFavourite(Integer.MAX_VALUE, true)).isFalse();
    }

    private List<Integer> search(SearchType type)
    {
        em.flush();
        SearchCriteria c = new SearchCriteria();
        c.setType(type);
        c.setTitle("fa-search");
        c.setFavouriteArtist(true);
        return searchService.search(c).getContent().stream().map(CardDto::getId).toList();
    }

    /** What the rows say, read past the persistence context: the triggers write behind Hibernate's back. */
    private List<String> flagged()
    {
        em.flush();
        List<String> rows = new ArrayList<>(jdbc.queryForList(
                "select 'chapter ' || title_full from chapter where favourite_artist = 1 and title_full like 'fa-%'",
                String.class));
        rows.addAll(jdbc.queryForList(
                "select 'series ' || title_full from series where favourite_artist = 1 and title_full like 'fa-%'",
                String.class));
        return rows;
    }

    private Artist artist(String name)
    {
        Artist artist = new Artist();
        artist.setName(name);
        return artistRepository.save(artist);
    }

    private Artist favourite(String name)
    {
        Artist artist = artist(name);
        artist.setFavourite(true);
        return artistRepository.save(artist);
    }

    private int chapter(String titleFull, Artist... artists)
    {
        ChapterForm form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        form.setArtistIds(new ArrayList<>(List.of(artists).stream().map(Artist::getId).toList()));
        return chapterService.create(form);
    }

    private void edit(int id, Integer... artistIds)
    {
        ChapterForm form = chapterService.toForm(id);
        form.setArtistIds(new ArrayList<>(List.of(artistIds)));
        chapterService.update(form);
    }

    private int series(String titleFull, List<Integer> artistIds, Integer... chapterIds)
    {
        SeriesForm form = new SeriesForm();
        form.setTitleFull(titleFull);
        form.setStatus(Status.NEW);
        form.setArtistIds(new ArrayList<>(artistIds));
        form.setChapterIds(new ArrayList<>(List.of(chapterIds)));
        return seriesService.create(form);
    }
}

package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.match.ChapterMatchingSweep;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** "Match chapters" over a mixed library: unrelated families must stay separate series, not collapse into one. */
@SpringBootTest
@Transactional
class ChapterMatchingIT
{
    @Autowired ChapterMatchingSweep sweep;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired ArtistRepository artistRepository;
    @PersistenceContext EntityManager em;

    private Artist artist(String name)
    {
        var artist = new Artist();
        artist.setName(name);
        return artistRepository.save(artist);
    }

    /** As an import leaves it: no series and no matching keys yet. */
    private Chapter chapter(String title, String language, Artist... artists)
    {
        return chapter(title, title, language, artists);
    }

    private Chapter chapter(String titleFull, String title, String language, Artist... artists)
    {
        var chapter = new Chapter();
        chapter.setTitleFull(titleFull);
        chapter.setTitle(title);
        chapter.setNativeTitle("");
        chapter.setUploadDate(LocalDate.of(2021, 1, 1));
        chapter.setLanguage(language);
        chapter.setStatus(Status.NEW);
        chapter.setArtists(new ArrayList<>(List.of(artists)));
        return chapterRepository.save(chapter);
    }

    private Chapter reload(Chapter chapter)
    {
        return chapterRepository.findById(chapter.getId()).orElseThrow();
    }

    private static Integer seriesId(Chapter chapter)
    {
        return chapter.getSeries() == null ? null : chapter.getSeries().getId();
    }

    private Series series(Integer id)
    {
        return seriesRepository.findById(id).orElseThrow();
    }

    @Test
    void shouldDeriveChapterNumbersAndKeepFamiliesApartWhenMatchingUnlinkedChapters()
    {
        // GIVEN four families that must not be confused with each other.
        Artist alpha = artist("Alpha");
        Artist beta = artist("Beta");
        Artist gamma = artist("Gamma");

        // The family under test. The two "Isekai Yuusha" rows are the same chapter in two languages.
        Chapter base = chapter("Isekai Yuusha", "English", alpha);
        Chapter baseJp = chapter("Isekai Yuusha", "Japanese", alpha);
        Chapter second = chapter("Isekai Yuusha 2", "Japanese", alpha);
        Chapter secondPart2 = chapter("Isekai Yuusha 4 part 2", "English", alpha);
        Chapter secondOmake = chapter("Isekai Yuusha 2 omake", "English", alpha);

        // Same title, different artist: must NOT join the family above.
        Chapter otherArtist = chapter("Isekai Yuusha 3", "English", beta);
        // Same artist, different work: must NOT join it either.
        Chapter otherWork = chapter("Isekai Maou", "English", alpha);
        // No artist at all, identical title: joins on the title alone.
        Chapter noArtist = chapter("Isekai Yuusha 4", "English");
        // Bracket decoration around the title must not stop it matching.
        Chapter decorated = chapter("[Doujin] Isekai Yuusha 5 omake (Alpha) <v2>",
                "[Doujin] Isekai Yuusha 5 omake", "English", alpha);

        // A second family, related only by a shared leading word.
        Chapter ohayo = chapter("Ohayo", "English", gamma);
        Chapter ohayoAm = chapter("Ohayo am10:00", "English", gamma);
        Chapter ohayoHen = chapter("Ohayo suizokukan hen", "Japanese", gamma);
        Chapter ohayoPm = chapter("Ohayo pm10:00", "English", gamma);
        em.flush();

        // WHEN
        ChapterMatchingSweep.Result result = sweep.matchUnlinked();
        em.flush();
        em.clear();

        // THEN every chapter is in a series, and the four families are four series.
        Integer familyA = seriesId(reload(base));
        Integer familyB = seriesId(reload(otherArtist));
        Integer familyC = seriesId(reload(otherWork));
        Integer familyD = seriesId(reload(ohayo));
        assertThat(List.of(familyA, familyB, familyC, familyD)).doesNotHaveDuplicates();

        assertThat(seriesId(reload(baseJp))).isEqualTo(familyA);
        assertThat(seriesId(reload(second))).isEqualTo(familyA);
        assertThat(seriesId(reload(secondPart2))).isEqualTo(familyA);
        assertThat(seriesId(reload(secondOmake))).isEqualTo(familyA);
        assertThat(seriesId(reload(decorated))).isEqualTo(familyA);
        // Identical title, no artist to contradict it: joins the bigger of the two same-key families.
        assertThat(seriesId(reload(noArtist))).isEqualTo(familyA);

        assertThat(seriesId(reload(ohayoAm))).isEqualTo(familyD);
        assertThat(seriesId(reload(ohayoHen))).isEqualTo(familyD);
        assertThat(seriesId(reload(ohayoPm))).isEqualTo(familyD);

        // AND each series is named after its family, not after the chapter that created it.
        assertThat(series(familyA).getTitle()).isEqualTo("Isekai Yuusha");
        assertThat(series(familyA).getTitleFull()).isEqualTo("Isekai Yuusha");
        assertThat(series(familyC).getTitle()).isEqualTo("Isekai Maou");
        assertThat(series(familyD).getTitle()).isEqualTo("Ohayo");
        // familyB has the same name as familyA: duplicate titles are allowed, the artist keeps them apart.
        assertThat(series(familyB).getTitle()).isEqualTo("Isekai Yuusha");
        assertThat(series(familyB).getId()).isNotEqualTo(series(familyA).getId());

        // AND the numbers come from the titles, so both languages of one chapter share a number.
        assertThat(reload(base).getChapterNum()).isCloseTo(1.0f, within(0.001f));
        assertThat(reload(baseJp).getChapterNum()).isCloseTo(1.0f, within(0.001f));
        assertThat(reload(second).getChapterNum()).isCloseTo(2.0f, within(0.001f));
        assertThat(reload(secondPart2).getChapterNum()).isCloseTo(4.02f, within(0.001f));
        assertThat(reload(secondOmake).getChapterNum()).isCloseTo(2.01f, within(0.001f));
        assertThat(reload(decorated).getChapterNum()).isCloseTo(5.01f, within(0.001f));

        // The sweep sees every unlinked chapter in the database, so assert the shape, not totals.
        assertThat(result.getScanned()).isGreaterThanOrEqualTo(13);
        assertThat(result.getMatched()).isEqualTo(result.getScanned());
    }

    @Test
    void shouldLeaveChaptersThatAlreadyBelongToASeriesAloneWhenMatching()
    {
        // GIVEN a linked chapter with a hand-set number, and a loose one that would match it.
        Artist alpha = artist("Solo Artist");
        Chapter linked = chapter("Kept Series 2", "English", alpha);
        Chapter loose = chapter("Kept Series 3", "English", alpha);

        var series = new Series();
        series.setTitleFull("Kept Series");
        series.setTitle("Kept Series");
        series.setCreatedDate(LocalDate.of(2020, 1, 1));
        series.setStatus(Status.REVIEWED);
        em.persist(series);
        linked.setSeries(series);
        linked.setChapterNum(9.5f);
        chapterRepository.save(linked);
        em.flush();

        // WHEN
        ChapterMatchingSweep.Result result = sweep.matchUnlinked();
        em.flush();
        em.clear();

        // THEN the sweep put every chapter it scanned into a series (it only ever sees unlinked ones).
        assertThat(result.getScanned()).isGreaterThanOrEqualTo(1);
        assertThat(result.getMatched()).isEqualTo(result.getScanned());

        // AND the linked chapter kept both its series and its hand-set number - it was never scanned.
        assertThat(seriesId(reload(linked))).isEqualTo(series.getId());
        assertThat(reload(linked).getChapterNum()).isCloseTo(9.5f, within(0.001f));
        // AND the loose one was matched into it.
        assertThat(seriesId(reload(loose))).isEqualTo(series.getId());
        assertThat(reload(loose).getChapterNum()).isCloseTo(3.0f, within(0.001f));
        // AND linking into an existing series leaves its hand-picked name alone.
        assertThat(series(series.getId()).getTitle()).isEqualTo("Kept Series");
        assertThat(series(series.getId()).getTitleFull()).isEqualTo("Kept Series");
    }
}

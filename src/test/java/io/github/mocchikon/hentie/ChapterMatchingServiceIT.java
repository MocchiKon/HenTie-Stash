package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.SeriesService;
import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Auto-linking on the create path: join the best series above the threshold, or start one. */
@SpringBootTest
@Transactional
class ChapterMatchingServiceIT
{
    @Autowired ChapterService chapterService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired SeriesService seriesService;
    @Autowired SettingsService settingsService;
    @Autowired AppProperties appProperties;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void enableAutoLinking()
    {
        // The test profile pins auto-linking off (app.match-auto-link-default); this suite tests it.
        settingsService.setMatchAutoLinkEnabled(true);
        settingsService.setMatchThreshold(SettingsService.DEFAULT_MATCH_THRESHOLD);
    }

    @AfterEach
    void restoreDefaults()
    {
        // The settings cache is a shared singleton that the test transaction does not roll back.
        settingsService.setMatchAutoLinkEnabled(appProperties.isMatchAutoLinkDefault());
        settingsService.setMatchThreshold(SettingsService.DEFAULT_MATCH_THRESHOLD);
    }

    private Artist artist(String name)
    {
        var artist = new Artist();
        artist.setName(name);
        return artistRepository.save(artist);
    }

    private int create(String titleFull, String title, Artist... artists)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setTitle(title);
        form.setLanguage("English");
        form.setStatus(Status.NEW);
        form.setArtistIds(Arrays.stream(artists).map(Artist::getId).toList());
        int id = chapterService.create(form);
        em.flush();
        return id;
    }

    private int createWithNative(String titleFull, String nativeTitle, Artist... artists)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setNativeTitle(nativeTitle);
        form.setLanguage("English");
        form.setStatus(Status.NEW);
        form.setArtistIds(Arrays.stream(artists).map(Artist::getId).toList());
        int id = chapterService.create(form);
        em.flush();
        return id;
    }

    private Chapter chapter(int id)
    {
        return chapterRepository.findById(id).orElseThrow();
    }

    private Series seriesOf(int chapterId)
    {
        Chapter chapter = chapter(chapterId);
        assertThat(chapter.getSeries()).as("chapter %s should be in a series", chapterId).isNotNull();
        return seriesRepository.findById(chapter.getSeries().getId()).orElseThrow();
    }

    @Test
    void shouldStartASeriesForTheFirstChapterAndLinkTheNextOneToIt()
    {
        // GIVEN
        Artist artist = artist("Autolink Artist");

        // WHEN
        int first = create("Autolink Saga", "Autolink Saga", artist);
        int second = create("Autolink Saga 2", "Autolink Saga 2", artist);
        em.flush();

        // THEN both ended up in the same, automatically created series...
        Series series = seriesOf(first);
        assertThat(chapter(second).getSeries().getId()).isEqualTo(series.getId());
        // ...named after the family, and not renamed by the second chapter joining it...
        assertThat(series.getTitle()).isEqualTo("Autolink Saga");
        assertThat(series.getTitleFull()).isEqualTo("Autolink Saga");
        // ...numbered from their own titles...
        assertThat(chapter(first).getChapterNum()).isCloseTo(1.0, within(0.001));
        assertThat(chapter(second).getChapterNum()).isCloseTo(2.0, within(0.001));
        // ...and the series is a bare, unreviewed shell whose facets stay derived from its chapters.
        assertThat(series.getStatus()).isEqualTo(Status.NEW);
        assertThat(series.getScoreSource()).isEqualTo(ScoreSource.DERIVED);
        assertThat(series.getTags()).isEmpty();
        assertThat(series.getArtists()).isEmpty();
        assertThat(series.getEffectiveArtists()).extracting(Artist::getId).containsExactly(artist.getId());
    }

    @Test
    void shouldNameANewSeriesAfterTheDeNumberedTitleKeepingBracketsOnlyInTheFullOne()
    {
        // WHEN a decorated title starts a series
        int id = create("[Scan] Bracket Saga 2 omake (Zed)", "Bracket Saga 2 omake", artist("Zed"));

        // THEN the series is named after the family rather than after this one chapter.
        Series series = seriesOf(id);
        assertThat(series.getTitleFull()).isEqualTo("[Scan] Bracket Saga (Zed)");
        assertThat(series.getTitle()).isEqualTo("Bracket Saga");
        assertThat(chapter(id).getChapterNum()).isCloseTo(2.01, within(0.001));
    }

    @Test
    void shouldNameANewSeriesAfterTheWorkWhenTheTitleIsOneWordPlusANumber()
    {
        // WHEN a "<name> <number>" chapter starts a series
        Artist artist = artist("Kishi");
        int id = create("Naruto 2", "Naruto 2", artist);

        // THEN the number is stripped even though only one word is left: that word is the work's name.
        Series series = seriesOf(id);
        assertThat(series.getTitle()).isEqualTo("Naruto");
        assertThat(series.getTitleFull()).isEqualTo("Naruto");
        assertThat(chapter(id).getChapterNum()).isCloseTo(2.0, within(0.001));

        // AND the next one in the family joins it rather than starting its own.
        int third = create("Naruto 3", "Naruto 3", artist);
        assertThat(chapter(third).getSeries().getId()).isEqualTo(series.getId());
    }

    @Test
    void shouldNotMergeTwoWorksThatOnlyShareALeadingStopword()
    {
        // GIVEN a title whose marker would strip it down to "the"
        Artist artist = artist("Stopword Artist");
        int end = create("The End", "The End", artist);

        // THEN the series keeps the whole title - "The" would token-prefix every other "The ..." title
        assertThat(seriesOf(end).getTitle()).isEqualTo("The End");

        // AND another work by the same artist starting with that stopword does NOT join it
        int beginning = create("The Beginning", "The Beginning", artist);
        assertThat(chapter(beginning).getSeries().getId()).isNotEqualTo(chapter(end).getSeries().getId());
        assertThat(seriesOf(beginning).getTitle()).isEqualTo("The Beginning");
    }

    @Test
    void shouldLeaveTheChapterUnlinkedWhenAutoLinkingIsSwitchedOff()
    {
        // GIVEN
        Artist artist = artist("Manual Artist");
        settingsService.setMatchAutoLinkEnabled(false);

        // WHEN
        int id = create("Manual Only Saga", "Manual Only Saga", artist);

        // THEN
        assertThat(chapter(id).getSeries()).isNull();
        // AND the matching key is still written, so a later sweep can pick it up.
        assertThat(chapter(id).getMatchKey()).isEqualTo("manual only saga");
    }

    @Test
    void shouldStartASeparateSeriesWhenTheBestCandidateIsBelowTheThreshold()
    {
        // GIVEN two chapters with the same title and no artist at all - a match worth 82%.
        settingsService.setMatchThreshold(90);
        int first = create("Threshold Saga", "Threshold Saga");

        // WHEN
        int second = create("Threshold Saga", "Threshold Saga");

        // THEN 82% is not enough, so the second chapter got its own series.
        assertThat(chapter(second).getSeries().getId()).isNotEqualTo(chapter(first).getSeries().getId());

        // AND the same pair does link once the threshold allows it.
        settingsService.setMatchThreshold(SettingsService.DEFAULT_MATCH_THRESHOLD);
        int third = create("Threshold Saga", "Threshold Saga");
        assertThat(chapter(third).getSeries()).isNotNull();
        assertThat(chapter(third).getSeries().getId()).isEqualTo(chapter(first).getSeries().getId());
    }

    @Test
    void shouldNotJoinAWorkByADifferentArtistWhenTheTitleIsIdentical()
    {
        // GIVEN
        int mine = create("Shared Name Saga", "Shared Name Saga", artist("Artist One"));

        // WHEN the same title arrives from another artist
        int theirs = create("Shared Name Saga 2", "Shared Name Saga 2", artist("Artist Two"));

        // THEN it starts its own series (a disjoint artist caps the score at 60%), under the same name.
        assertThat(chapter(theirs).getSeries().getId()).isNotEqualTo(chapter(mine).getSeries().getId());
        assertThat(seriesOf(theirs).getTitle()).isEqualTo("Shared Name Saga");
        assertThat(seriesOf(mine).getTitle()).isEqualTo("Shared Name Saga");
    }

    @Test
    void shouldPutADatedMagazineRunIntoOneSeriesNamedAfterTheWork()
    {
        // GIVEN two issues years apart, with no artist (a magazine is an anthology) and blank pretty titles.
        int june2024 = create("Comic Hero 2024-06 [Digital]", null);
        int january2026 = create("Comic Hero 2026-01 [Scan]", null);

        // THEN they are one series, named after the work rather than after either issue...
        Series series = seriesOf(june2024);
        assertThat(chapter(january2026).getSeries().getId()).isEqualTo(series.getId());
        assertThat(series.getTitle()).isEqualTo("Comic Hero");
        assertThat(series.getTitleFull()).isEqualTo("Comic Hero [Digital]");

        // ...ordered by their dates...
        assertThat(chapter(june2024).getChapterNum()).isCloseTo(2024.06, within(0.001));
        assertThat(chapter(january2026).getChapterNum()).isCloseTo(2026.01, within(0.001));
        assertThat(chapter(june2024).getChapterNum()).isLessThan(chapter(january2026).getChapterNum());

        // ...while each chapter keeps its dated title; only the series name drops the date.
        assertThat(chapter(june2024).getTitle()).isEqualTo("Comic Hero 2024-06");
        assertThat(chapter(june2024).getTitleFull()).isEqualTo("Comic Hero 2024-06 [Digital]");
        assertThat(chapter(january2026).getTitle()).isEqualTo("Comic Hero 2026-01");
    }

    @Test
    void shouldJoinTheSameMagazineRunWhenIssuesWriteTheirDateDifferently()
    {
        // GIVEN issues written year-first, month-first, with a day, and with CJK separators
        int yearFirst = create("Comic Weekly 2024-06", "Comic Weekly 2024-06");

        // WHEN the other spellings arrive
        int monthFirst = create("Comic Weekly 07-2024", "Comic Weekly 07-2024");
        int withDay = create("Comic Weekly 2024-08-15", "Comic Weekly 2024-08-15");
        int cjk = create("Comic Weekly 2024年09月号", "Comic Weekly 2024年09月号");

        // THEN all four are one run, in date order.
        int run = seriesOf(yearFirst).getId();
        assertThat(chapter(monthFirst).getSeries().getId()).isEqualTo(run);
        assertThat(chapter(withDay).getSeries().getId()).isEqualTo(run);
        assertThat(chapter(cjk).getSeries().getId()).isEqualTo(run);
        assertThat(seriesOf(yearFirst).getTitle()).isEqualTo("Comic Weekly");

        assertThat(chapter(yearFirst).getChapterNum()).isCloseTo(2024.06, within(0.001));
        assertThat(chapter(monthFirst).getChapterNum()).isCloseTo(2024.07, within(0.001));
        assertThat(chapter(withDay).getChapterNum()).isCloseTo(2024.08, within(0.001));
        assertThat(chapter(cjk).getChapterNum()).isCloseTo(2024.09, within(0.001));
    }

    @Test
    void shouldJoinTheSameFamilyWhenTheTitlesDisagreeAboutWordBreaks()
    {
        // GIVEN a romanized title split into words one way...
        Artist artist = artist("Boundary Artist");
        int spaced = create("Ie de tanoshii asobi Kei-san", "Ie de tanoshii asobi Kei-san", artist);

        // WHEN the same title arrives split another way, and then a sibling with a different tail
        int joined = create("Iede tanoshii asobi Kei san", "Iede tanoshii asobi Kei san", artist);
        int sibling = create("Iede tanoshii asobi Kei san de inaka", "Iede tanoshii asobi Kei san de inaka",
                artist);

        // THEN all three are one family: both the comparison and the blocking key ignore spaces.
        int family = seriesOf(spaced).getId();
        assertThat(chapter(joined).getSeries().getId()).isEqualTo(family);
        assertThat(chapter(sibling).getSeries().getId()).isEqualTo(family);
    }

    @Test
    void shouldNotJoinAWorkThatOnlySharesAShortHeadWithTheSameArtist()
    {
        // GIVEN one work by an artist
        Artist artist = artist("Short Head Artist");
        int yuusha = create("Isekai Yuusha", "Isekai Yuusha", artist);

        // WHEN a different work by the same artist shares only its leading word
        int maou = create("Isekai Maou", "Isekai Maou", artist);

        // THEN they stay apart - the shared-head band needs a long head, not just a franchise word.
        assertThat(chapter(maou).getSeries().getId()).isNotEqualTo(chapter(yuusha).getSeries().getId());
    }

    @Test
    void shouldJoinTheOriginalThroughItsJapaneseTitleWhenATranslationIsTitledDifferently()
    {
        // GIVEN an original titled in Japanese, and a romanized edition of another chapter of it
        Artist artist = artist("Native Title Artist");
        int original = createWithNative("[サークル] 異世界勇者の冒険 2", null, artist);
        int romanized = createWithNative("[Circle] Isekai Yuusha no Bouken 3", "[サークル] 異世界勇者の冒険 3", artist);

        // WHEN a translation arrives under an English title nothing else carries, with the original's native title
        int translated = createWithNative("[Circle] The Adventures of an Otherworld Hero Ch. 4 [English]",
                "[サークル] 異世界勇者の冒険 第4話 [英訳]");

        // THEN all three are one series, though the titles share no word - and numbered from their own titles.
        int series = seriesOf(original).getId();
        assertThat(chapter(romanized).getSeries().getId()).isEqualTo(series);
        assertThat(chapter(translated).getSeries().getId()).isEqualTo(series);
        assertThat(chapter(translated).getChapterNum()).isCloseTo(4.0, within(0.001));
        assertThat(chapter(original).getChapterNum()).isCloseTo(2.0, within(0.001));
    }

    @Test
    void shouldNotJoinTwoWorksWhoseJapaneseTitlesDifferByOneCharacter()
    {
        // GIVEN a work, and another by the same artist whose Japanese title differs in its last character
        Artist artist = artist("One Kanji Artist");
        int girl = createWithNative("[Circle] Nemuru Ko", "[サークル] 眠り続ける子", artist);

        // WHEN
        int wife = createWithNative("[Circle] Nemuru Tsuma", "[サークル] 眠り続ける妻", artist);

        // THEN they stay apart: in Japanese one character is a word, not a typo.
        assertThat(chapter(wife).getSeries().getId()).isNotEqualTo(chapter(girl).getSeries().getId());
    }

    @Test
    void shouldJoinTheBaseWhenATitleCarriesALongTranslationAfterItsNumber()
    {
        // GIVEN a base title of five words
        Artist artist = artist("Long Translation Artist");
        int base = create("Long Base Title Of Five", "Long Base Title Of Five", artist);

        // WHEN a sibling carries a translation ten words long: the base is its shortest prefix, not a long one
        int sibling = create("Long Base Title Of Five 2 | A Translated Title That Runs On For Very Many Words 2",
                null, artist);

        // THEN it joins the base, numbered from before the separator.
        assertThat(chapter(sibling).getSeries().getId()).isEqualTo(seriesOf(base).getId());
        assertThat(chapter(sibling).getChapterNum()).isCloseTo(2.0, within(0.001));
    }

    @Test
    void shouldReadTheNumberInFrontOfASubtitleAndKeyTheChapterLikeItsSiblings()
    {
        // GIVEN
        Artist artist = artist("Subtitle Artist");
        int first = create("Subtitle Saga - The Return", null, artist);

        // WHEN
        int second = create("Subtitle Saga 2 - The Return", null, artist);
        int third = create("Subtitle Saga 3 - The Return [English]", null, artist);

        // THEN one series named without the number, chapters numbered 1-3.
        Series series = seriesOf(first);
        assertThat(chapter(second).getSeries().getId()).isEqualTo(series.getId());
        assertThat(chapter(third).getSeries().getId()).isEqualTo(series.getId());
        assertThat(series.getTitle()).isEqualTo("Subtitle Saga - The Return");
        assertThat(chapter(second).getChapterNum()).isCloseTo(2.0, within(0.001));
        assertThat(chapter(third).getChapterNum()).isCloseTo(3.0, within(0.001));
    }

    @Test
    void shouldNotJoinASeriesWhoseWholeTitleIsAMarkerWord()
    {
        // GIVEN a series keyed by a lone marker word ("After❤" keys to "after"), with no artist to veto
        int after = create("After❤", "After❤");

        // WHEN an unrelated title starts with that word
        int school = create("After-School Tutoring", null);

        // THEN it is no family member: "after" names no work.
        assertThat(chapter(school).getSeries().getId()).isNotEqualTo(chapter(after).getSeries().getId());
    }

    @Test
    void shouldJoinAnAnthologyVolumeDrawnByOtherArtists()
    {
        // GIVEN an anthology volume drawn by five artists
        List<Artist> lineUp = List.of(artist("Anthology A"), artist("Anthology B"), artist("Anthology C"),
                artist("Anthology D"), artist("Anthology E"));
        int first = create("[Anthology] Dungeon Anthology Vol. 1", null, lineUp.toArray(Artist[]::new));

        // WHEN a later volume names only one, new artist
        int later = create("[Anthology] Dungeon Anthology Vol. 8", null, artist("Anthology F"));

        // THEN it joins: a work drawn by many has a new line-up every time, so the artists veto nothing.
        assertThat(chapter(later).getSeries().getId()).isEqualTo(seriesOf(first).getId());
        assertThat(chapter(later).getChapterNum()).isCloseTo(8.0, within(0.001));
    }

    @Test
    void shouldDropTheAutoCreatedSeriesWhenItsOnlyChapterIsRefiledByHand()
    {
        // GIVEN a chapter auto-linked into a series of its own, and a curated series to move it to.
        int chapterId = create("Refiled Saga", "Refiled Saga", artist("Refile Artist"));
        int autoCreated = seriesOf(chapterId).getId();
        int curated = seriesService.create(reviewedForm("Curated Home"));
        em.flush();

        // WHEN the user re-files it (what Add-to-series does)
        seriesService.addChapters(curated, List.of(chapterId));
        em.flush();
        em.clear();

        // THEN the chapter is in the curated series...
        assertThat(chapter(chapterId).getSeries().getId()).isEqualTo(curated);
        assertThat(chapter(chapterId).getChapterNum()).isCloseTo(1.0, within(0.001));
        // ...and the emptied series is gone rather than lingering in search.
        assertThat(seriesRepository.findById(autoCreated)).isEmpty();
        assertThat(seriesRepository.findById(curated)).isPresent();
    }

    @Test
    void shouldDropACuratedSeriesTooWhenItsLastChapterIsRefiledByHand()
    {
        // GIVEN a chapter in a curated (REVIEWED) series, plus a second curated series.
        int chapterId = create("Curated Move Saga", "Curated Move Saga", artist("Curated Artist"));
        int source = seriesService.create(reviewedForm("Curated Source"));
        int target = seriesService.create(reviewedForm("Curated Target"));
        seriesService.addChapters(source, List.of(chapterId));
        em.flush();

        // WHEN its last chapter moves out
        seriesService.addChapters(target, List.of(chapterId));
        em.flush();
        em.clear();

        // THEN the emptied series goes too: no curated exemption, an empty series describes nothing.
        assertThat(chapter(chapterId).getSeries().getId()).isEqualTo(target);
        assertThat(seriesRepository.findById(source)).isEmpty();
        assertThat(seriesRepository.findById(target)).isPresent();
    }

    @Test
    void shouldKeepASeriesThatWasCreatedEmpty()
    {
        // GIVEN a series created with no chapters at all - somewhere to file things later.
        int empty = seriesService.create(reviewedForm("Created Empty"));
        int chapterId = create("Unrelated Saga", "Unrelated Saga", artist("Unrelated Artist"));
        em.flush();

        // WHEN unrelated chapter activity happens around it
        seriesService.removeChapter(seriesOf(chapterId).getId(), chapterId);
        em.flush();
        em.clear();

        // THEN it survives: only a series that loses its last chapter is cleaned up.
        assertThat(seriesRepository.findById(empty)).isPresent();
        assertThat(seriesRepository.findById(empty).orElseThrow().getStatus()).isEqualTo(Status.REVIEWED);
    }

    @Test
    void shouldDropTheAutoCreatedSeriesWhenItsLastChapterIsUnlinked()
    {
        // GIVEN a chapter auto-linked into a series of its own
        int chapterId = create("Unlinked Saga", "Unlinked Saga", artist("Unlink Artist"));
        int autoCreated = seriesOf(chapterId).getId();

        // WHEN it is removed from that series (the series edit page's "remove" button)
        seriesService.removeChapter(autoCreated, chapterId);
        em.flush();
        em.clear();

        // THEN the chapter survives, unlinked and unnumbered, and the empty shell is gone
        assertThat(chapter(chapterId).getSeries()).isNull();
        assertThat(chapter(chapterId).getChapterNum()).isNull();
        assertThat(seriesRepository.findById(autoCreated)).isEmpty();
    }

    @Test
    void shouldDropTheAutoCreatedSeriesWhenItsLastChapterIsDeleted()
    {
        // GIVEN two chapters of one family, so the series is not empty until both are gone
        Artist artist = artist("Delete Artist");
        int first = create("Deleted Saga", "Deleted Saga", artist);
        int second = create("Deleted Saga 2", "Deleted Saga 2", artist);
        int autoCreated = seriesOf(first).getId();
        assertThat(chapter(second).getSeries().getId()).isEqualTo(autoCreated);

        // WHEN the first is deleted the series still has the second, so it stays
        chapterService.delete(first);
        em.flush();
        assertThat(seriesRepository.findById(autoCreated)).isPresent();

        // WHEN the last one goes too
        chapterService.delete(second);
        em.flush();
        em.clear();

        // THEN nothing is left to describe, so the shell is gone
        assertThat(seriesRepository.findById(autoCreated)).isEmpty();
    }

    @Test
    void shouldDropACuratedSeriesTooWhenItsLastChapterIsDeleted()
    {
        // GIVEN two chapters in a curated (REVIEWED) series
        int first = create("Curated Delete Saga", "Curated Delete Saga", artist("Curated Delete Artist"));
        int second = create("Curated Delete Saga 2", "Curated Delete Saga 2", artist("Curated Delete Two"));
        int curated = seriesService.create(reviewedForm("Curated Keeper"));
        seriesService.addChapters(curated, List.of(first, second));
        em.flush();

        // WHEN the first is deleted it still has the second, so it stays
        chapterService.delete(first);
        em.flush();
        assertThat(seriesRepository.findById(curated)).isPresent();

        // WHEN the last one goes too
        chapterService.delete(second);
        em.flush();
        em.clear();

        // THEN the curated series goes with it - same rule as an auto-created one.
        assertThat(seriesRepository.findById(curated)).isEmpty();
    }

    private SeriesForm reviewedForm(String titleFull)
    {
        var form = new SeriesForm();
        form.setTitleFull(titleFull);
        form.setStatus(Status.REVIEWED);   // what SeriesController.create() sets for a user-made series
        return form;
    }
}

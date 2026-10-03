package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.ChapterViewModel;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ChapterServiceIT
{
    @Autowired ChapterService chapterService;
    @Autowired ChapterImageService chapterImageService;
    @Autowired SeriesService seriesService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired ImageService imageService;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    private ChapterForm minimalForm(String titleFull)
    {
        ChapterForm form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        return form;
    }

    private SeriesForm seriesForm(String titleFull)
    {
        SeriesForm form = new SeriesForm();
        form.setTitleFull(titleFull);
        form.setStatus(Status.REVIEWED);
        return form;
    }

    private int scoredChapter(String titleFull, Integer score)
    {
        ChapterForm form = minimalForm(titleFull);
        form.setScore(score);
        return chapterService.create(form);
    }

    /** Edits a chapter's score through the real update path (round-tripping its current fields). */
    private void editScore(int id, String titleFull, Integer score)
    {
        ChapterForm form = minimalForm(titleFull);
        form.setId(id);
        form.setTitle(titleFull);
        form.setStatus(Status.NEW);
        form.setScore(score);
        chapterService.update(form);
    }

    private Short seriesScore(int seriesId)
    {
        return seriesRepository.findById(seriesId).orElseThrow().getScore();
    }

    @Test
    void shouldApplyDefaultsWhenOnlyTitleFullGiven()
    {
        // WHEN
        int id = chapterService.create(minimalForm("Only Full Title"));
        em.flush();
        em.clear();

        // THEN
        Chapter c = chapterRepository.findById(id).orElseThrow();
        assertThat(c.getTitleFull()).isEqualTo("Only Full Title");
        assertThat(c.getTitle()).isEqualTo("Only Full Title");
        assertThat(c.getStatus()).isEqualTo(Status.NEW);
        assertThat(c.getUploadDate()).isEqualTo(LocalDate.now());
        assertThat(c.getScore()).isNull();
    }

    @Test
    void shouldDeriveThePrettyTitleFromTheFullOneWhenLeftBlank()
    {
        // GIVEN a scraper-style full title and no pretty title
        ChapterForm form = minimalForm("Comic Hero 2024-06 [Digital]");

        // WHEN
        int id = chapterService.create(form);
        em.flush();
        em.clear();

        // THEN the brackets are dropped but the issue date, which tells siblings apart, is kept.
        Chapter c = chapterRepository.findById(id).orElseThrow();
        assertThat(c.getTitle()).isEqualTo("Comic Hero 2024-06");
        assertThat(c.getTitleFull()).isEqualTo("Comic Hero 2024-06 [Digital]");
    }

    @Test
    void shouldKeepThePrettyTitleTheUserTypedWhenGiven()
    {
        // GIVEN an explicit pretty title
        ChapterForm form = minimalForm("Comic Hero 2024-07 [Digital]");
        form.setTitle("My Own Name");

        // WHEN
        int id = chapterService.create(form);
        em.flush();
        em.clear();

        // THEN it is stored verbatim - the derivation is only a fallback
        assertThat(chapterRepository.findById(id).orElseThrow().getTitle()).isEqualTo("My Own Name");
    }

    @Test
    void shouldReDeriveThePrettyTitleWhenClearedOnUpdate()
    {
        // GIVEN a chapter with a hand-typed pretty title
        ChapterForm form = minimalForm("Comic Hero 2024-08 [Digital]");
        form.setTitle("Temporary Name");
        int id = chapterService.create(form);
        em.flush();

        // WHEN the pretty title is cleared on edit
        ChapterForm edit = chapterService.toForm(id);
        edit.setTitle("  ");
        chapterService.update(edit);
        em.flush();
        em.clear();

        // THEN it is re-derived rather than blanking a NOT NULL column
        assertThat(chapterRepository.findById(id).orElseThrow().getTitle()).isEqualTo("Comic Hero 2024-08");
    }

    @Test
    void shouldPersistExplicitValuesWhenAllFieldsGiven()
    {
        // GIVEN
        ChapterForm form = minimalForm("Explicit Chapter");
        form.setTitle("Pretty");
        form.setNativeTitle("ネイティブ");
        form.setStatus(Status.REVIEWED_FAVOURITE);
        form.setGalleryId("GID-1");
        form.setScore(8);

        // WHEN
        int id = chapterService.create(form);
        em.flush();
        em.clear();

        // THEN
        Chapter c = chapterRepository.findById(id).orElseThrow();
        assertThat(c.getTitle()).isEqualTo("Pretty");
        assertThat(c.getNativeTitle()).isEqualTo("ネイティブ");
        assertThat(c.getStatus()).isEqualTo(Status.REVIEWED_FAVOURITE);
        assertThat(c.getGalleryId()).isEqualTo("GID-1");
        assertThat(c.getScore()).isEqualTo((short) 8);
    }

    @Test
    void shouldAllowCreateWhenDuplicateTitleFull()
    {
        // GIVEN - title_full is not unique.
        int first = chapterService.create(minimalForm("Dup Full"));
        em.flush();

        // WHEN
        int second = chapterService.create(minimalForm("Dup Full"));
        em.flush();
        em.clear();

        // THEN
        assertThat(first).isNotEqualTo(second);
        assertThat(chapterRepository.findById(first).orElseThrow().getTitleFull()).isEqualTo("Dup Full");
        assertThat(chapterRepository.findById(second).orElseThrow().getTitleFull()).isEqualTo("Dup Full");
    }

    @Test
    void shouldRejectCreateWhenDuplicateGalleryId()
    {
        // GIVEN
        ChapterForm first = minimalForm("Gallery One");
        first.setGalleryId("SAME");
        chapterService.create(first);
        em.flush();

        // WHEN + THEN
        ChapterForm second = minimalForm("Gallery Two");
        second.setGalleryId("SAME");
        assertThatThrownBy(() -> chapterService.create(second))
                .isInstanceOf(DuplicateValueException.class)
                .satisfies(e -> assertThat(((DuplicateValueException) e).getField()).isEqualTo("galleryId"));
    }

    @Test
    void shouldRejectCreateWhenUnknownLanguage()
    {
        // GIVEN
        ChapterForm form = minimalForm("Bad Language");
        form.setLanguage("Klingon");

        // WHEN + THEN
        assertThatThrownBy(() -> chapterService.create(form))
                .isInstanceOf(DuplicateValueException.class)
                .satisfies(e -> assertThat(((DuplicateValueException) e).getField()).isEqualTo("language"));
    }

    @Test
    void shouldStoreCanonicalLanguageSpellingWhenCreatingAndUpdating()
    {
        // GIVEN - language is searched by exact value, so two spellings would be two facets.
        ChapterForm form = minimalForm("Lowercase Language");
        form.setLanguage("english");

        // WHEN
        int id = chapterService.create(form);
        em.flush();

        // THEN
        assertThat(chapterRepository.findById(id).orElseThrow().getLanguage()).isEqualTo("English");

        // WHEN
        ChapterForm edit = minimalForm("Lowercase Language");
        edit.setId(id);
        edit.setLanguage("  JAPANESE ");
        chapterService.update(edit);
        em.flush();

        // THEN
        assertThat(chapterRepository.findById(id).orElseThrow().getLanguage()).isEqualTo("Japanese");
    }

    @Test
    void shouldChangeFieldsButKeepUploadDateWhenUpdating()
    {
        // GIVEN
        int id = chapterService.create(minimalForm("Editable"));
        em.flush();
        LocalDate created = chapterRepository.findById(id).orElseThrow().getUploadDate();
        em.clear();

        // WHEN
        ChapterForm edit = minimalForm("Editable Renamed");
        edit.setId(id);
        edit.setTitle("New Pretty");
        edit.setStatus(Status.REVIEWED);
        chapterService.update(edit);
        em.flush();
        em.clear();

        // THEN
        Chapter c = chapterRepository.findById(id).orElseThrow();
        assertThat(c.getTitleFull()).isEqualTo("Editable Renamed");
        assertThat(c.getTitle()).isEqualTo("New Pretty");
        assertThat(c.getStatus()).isEqualTo(Status.REVIEWED);
        assertThat(c.getUploadDate()).isEqualTo(created);   // untouched on edit
    }

    @Test
    void shouldAllowMultipleChaptersWhenGalleryIdBlank()
    {
        // GIVEN
        ChapterForm first = minimalForm("Blank Gallery One");
        first.setGalleryId("   ");
        chapterService.create(first);
        em.flush();

        // WHEN
        ChapterForm second = minimalForm("Blank Gallery Two");
        second.setGalleryId("");
        // Must not collide even though both normalise to "no gallery id".
        int id = chapterService.create(second);
        em.flush();
        em.clear();

        // THEN
        assertThat(chapterRepository.findById(id).orElseThrow().getGalleryId()).isNull();
    }

    @Test
    void shouldAllowUpdateWhenDuplicateTitleFullOfAnotherChapter()
    {
        // GIVEN - title_full is not unique.
        chapterService.create(minimalForm("Update Dup Title A"));
        int b = chapterService.create(minimalForm("Update Dup Title B"));
        em.flush();

        // WHEN
        ChapterForm edit = minimalForm("Update Dup Title A");
        edit.setId(b);
        edit.setTitle("Update Dup Title A");   // a real edit form always round-trips the current title
        chapterService.update(edit);
        em.flush();
        em.clear();

        // THEN
        assertThat(chapterRepository.findById(b).orElseThrow().getTitleFull()).isEqualTo("Update Dup Title A");
    }

    @Test
    void shouldRejectUpdateWhenDuplicateGalleryIdOfAnotherChapter()
    {
        // GIVEN
        ChapterForm first = minimalForm("Update Dup Gallery A");
        first.setGalleryId("SHARED-GID");
        chapterService.create(first);
        int b = chapterService.create(minimalForm("Update Dup Gallery B"));
        em.flush();

        // WHEN + THEN
        ChapterForm edit = minimalForm("Update Dup Gallery B");
        edit.setId(b);
        edit.setGalleryId("SHARED-GID");
        assertThatThrownBy(() -> chapterService.update(edit))
                .isInstanceOf(DuplicateValueException.class)
                .satisfies(e -> assertThat(((DuplicateValueException) e).getField()).isEqualTo("galleryId"));
    }

    @Test
    void shouldAllowUpdateWhenKeepingItsOwnTitleFullAndGalleryIdUnchanged()
    {
        // GIVEN
        ChapterForm form = minimalForm("Keep Own Values");
        form.setGalleryId("KEEP-GID");
        int id = chapterService.create(form);
        em.flush();

        // WHEN
        ChapterForm edit = minimalForm("Keep Own Values");
        edit.setId(id);
        edit.setTitle("Keep Own Values");   // a real edit form always round-trips the current title
        edit.setGalleryId("KEEP-GID");
        edit.setStatus(Status.REVIEWED);
        // Both values exist already, but on this same chapter.
        chapterService.update(edit);
        em.flush();
        em.clear();

        // THEN
        assertThat(chapterRepository.findById(id).orElseThrow().getStatus()).isEqualTo(Status.REVIEWED);
    }

    @Test
    void shouldRejectUpdateWhenUnknownLanguage()
    {
        // GIVEN
        int id = chapterService.create(minimalForm("Update Bad Language"));
        em.flush();

        // WHEN + THEN
        ChapterForm edit = minimalForm("Update Bad Language");
        edit.setId(id);
        edit.setLanguage("Klingon");
        assertThatThrownBy(() -> chapterService.update(edit))
                .isInstanceOf(DuplicateValueException.class)
                .satisfies(e -> assertThat(((DuplicateValueException) e).getField()).isEqualTo("language"));
    }

    @Test
    void shouldThrowNotFoundWhenChapterMissing()
    {
        // WHEN + THEN
        assertThatThrownBy(() -> chapterService.get(999_999))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void shouldLinkPrevAndNextWhenWithinSameSeriesAndLanguage()
    {
        // GIVEN
        Series series = new Series();
        series.setTitleFull("Nav Series");
        series.setTitle("Nav Series");                     // title_pretty is NOT NULL
        series.setCreatedDate(LocalDate.of(2021, 1, 1));   // created_date is NOT NULL
        series = seriesRepository.save(series);

        Chapter c1 = seriesChapter(series, "Nav 1", "English", 1f);
        Chapter c2 = seriesChapter(series, "Nav 2", "English", 2f);
        Chapter c3 = seriesChapter(series, "Nav 3", "English", 3f);
        // A different-language chapter must not appear in this chapter's prev/next chain.
        seriesChapter(series, "Nav JP", "Japanese", 2f);
        em.flush();
        em.clear();

        // WHEN
        ChapterViewModel middle = chapterService.buildView(c2.getId());
        ChapterViewModel first = chapterService.buildView(c1.getId());
        ChapterViewModel last = chapterService.buildView(c3.getId());

        // THEN
        assertThat(middle.getPrevChapterId()).isEqualTo(c1.getId());
        assertThat(middle.getNextChapterId()).isEqualTo(c3.getId());
        assertThat(middle.getSeriesId()).isEqualTo(series.getId());

        assertThat(first.getPrevChapterId()).isNull();
        assertThat(first.getNextChapterId()).isEqualTo(c2.getId());

        assertThat(last.getPrevChapterId()).isEqualTo(c2.getId());
        assertThat(last.getNextChapterId()).isNull();
    }

    @Test
    void shouldRecomputeOwningSeriesDerivedScoreWhenChapterScoreEdited()
    {
        // GIVEN
        int a = scoredChapter("ces-a", 4);
        int b = scoredChapter("ces-b", 6);   // derived average 5
        int seriesId = seriesService.create(seriesForm("Chapter Edit Derived"));
        seriesService.addChapters(seriesId, List.of(a, b));
        em.flush();
        assertThat(seriesScore(seriesId)).isEqualTo((short) 5);
        em.clear();

        // WHEN
        editScore(a, "ces-a", 10);   // (10 + 6) / 2 = 8
        em.flush();
        em.clear();

        // THEN
        assertThat(seriesScore(seriesId)).isEqualTo((short) 8);
    }

    @Test
    void shouldNotChangeUserSetSeriesScoreWhenChapterScoreEdited()
    {
        // GIVEN
        int a = scoredChapter("cue-a", 2);
        SeriesForm f = seriesForm("Chapter Edit Override");
        f.setScore(5);
        f.setChapterIds(List.of(a));
        int seriesId = seriesService.create(f);
        em.flush();
        em.clear();

        // WHEN
        editScore(a, "cue-a", 10);
        em.flush();
        em.clear();

        // THEN
        Series s = seriesRepository.findById(seriesId).orElseThrow();
        assertThat(s.getScoreSource()).isEqualTo(ScoreSource.USER_SET);
        assertThat(s.getScore()).isEqualTo((short) 5);
    }

    @Test
    void shouldCleanupWhenChapterDeleted() throws Exception
    {
        // GIVEN two chapters in one series, each with its own tag and score, and a page on disk for A.
        Tag tagA = tag("cleanup-tag-a");
        Artist artistA = artist("cleanup-artist-a");
        Tag tagB = tag("cleanup-tag-b");

        ChapterForm formA = minimalForm("cleanup-chapter-a");
        formA.setScore(4);
        formA.setTagIds(new ArrayList<>(List.of(tagA.getId())));
        formA.setArtistIds(new ArrayList<>(List.of(artistA.getId())));
        int a = chapterService.create(formA);
        int b = chapterWithTagAndScore("cleanup-chapter-b", tagB.getId(), 8);

        int seriesId = seriesService.create(seriesForm("Cleanup Series"));
        seriesService.addChapters(seriesId, List.of(a, b));
        em.flush();
        assertThat(seriesScore(seriesId)).isEqualTo((short) 6);   // average of 4 and 8
        assertThat(seriesRepository.findById(seriesId).orElseThrow().getEffectiveTags())
                .extracting("id").containsExactlyInAnyOrder(tagA.getId(), tagB.getId());

        MockMultipartFile file = new MockMultipartFile(
                "files", "p.jpg", "image/jpeg", "bytes".getBytes(StandardCharsets.UTF_8));
        imageService.saveImages(a, List.of(file));
        assertThat(imageService.pageCount(a)).isEqualTo(1);
        em.clear();

        try
        {
            // WHEN
            chapterService.delete(a);
            em.flush();
            em.clear();

            // THEN A's join rows and images are gone (foreign_keys=ON would reject leftovers), the shared
            // metadata survives, and the series now reflects only B.
            assertThat(chapterRepository.findById(a)).isEmpty();
            assertThat(joinCount("chapter_tags", "chapter_id", a)).isZero();
            assertThat(joinCount("chapter_artists", "chapter_id", a)).isZero();
            assertThat(tagRepository.findById(tagA.getId())).isPresent();
            assertThat(artistRepository.findById(artistA.getId())).isPresent();
            assertThat(imageService.pageCount(a)).isZero();

            assertThat(seriesScore(seriesId)).isEqualTo((short) 8);
            assertThat(seriesRepository.findById(seriesId).orElseThrow().getEffectiveTags())
                    .extracting("id").containsExactly(tagB.getId());
            assertThat(chapterRepository.findById(b)).isPresent();
            assertThat(joinCount("chapter_tags", "chapter_id", b)).isEqualTo(1);

            // WHEN the series' last chapter goes too.
            chapterService.delete(b);
            em.flush();
            em.clear();

            // THEN the emptied series is deleted with its join rows; the shared metadata survives.
            assertThat(seriesRepository.findById(seriesId)).isEmpty();
            assertThat(joinCount("series_tags", "series_id", seriesId)).isZero();
            assertThat(joinCount("series_effective_tags", "series_id", seriesId)).isZero();
            assertThat(tagRepository.findById(tagB.getId())).isPresent();
            assertThat(artistRepository.findById(artistA.getId())).isPresent();
        }
        finally
        {
            imageService.deleteAll(a);   // safety net if an assertion above ever fails
        }
    }

    /** The batch path must end in the same state as deleting the chapters one at a time. */
    @Test
    void shouldRecomputeTheSeriesLeftAndDropTheOneEmptiedWhenBatchDeleting()
    {
        // GIVEN series S1 with chapters scored 4 and 8, and series S2 holding a single chapter
        int a = scoredChapter("batch-delete-a", 4);
        int b = scoredChapter("batch-delete-b", 8);
        int c = scoredChapter("batch-delete-c", 6);
        int s1 = seriesService.create(seriesForm("Batch Delete Kept"));
        seriesService.addChapters(s1, List.of(a, b));
        int s2 = seriesService.create(seriesForm("Batch Delete Emptied"));
        seriesService.addChapters(s2, List.of(c));
        em.flush();
        em.clear();

        // WHEN one batch takes a chapter from each, plus an id that no longer exists
        int deleted = chapterService.deleteAll(List.of(a, c, Integer.MAX_VALUE));
        em.flush();
        em.clear();

        // THEN
        assertThat(deleted).isEqualTo(2);
        assertThat(chapterRepository.findAllById(List.of(a, c))).isEmpty();
        assertThat(seriesScore(s1)).isEqualTo((short) 8);
        assertThat(seriesRepository.findById(s2)).isEmpty();
    }

    @Test
    void shouldPromoteOnlyANewChapterWhenMarkedReviewed()
    {
        // GIVEN
        int fresh = chapterService.create(minimalForm("mark-reviewed-new"));
        ChapterForm favouriteForm = minimalForm("mark-reviewed-favourite");
        favouriteForm.setStatus(Status.REVIEWED_FAVOURITE);
        int favourite = chapterService.create(favouriteForm);

        // WHEN
        chapterService.markReviewed(fresh);
        chapterService.markReviewed(favourite);
        em.flush();
        em.clear();

        // THEN NEW moves on, a favourite is never demoted
        assertThat(chapterRepository.findById(fresh).orElseThrow().getStatus()).isEqualTo(Status.REVIEWED);
        assertThat(chapterRepository.findById(favourite).orElseThrow().getStatus()).isEqualTo(Status.REVIEWED_FAVOURITE);
    }

    @Test
    void shouldSyncPageAndDiskStatsWhenImagesUploadedAndDeleted() throws Exception
    {
        // GIVEN a chapter in a series, whose summed stats must follow the chapter's.
        int chapterId = chapterService.create(minimalForm("stats-chapter"));
        int seriesId = seriesService.create(seriesForm("Stats Series"));
        seriesService.addChapters(seriesId, List.of(chapterId));
        em.flush();
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isZero();
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getDiskSize()).isZero();
        em.clear();

        try
        {
            // WHEN two page images are uploaded through the service (5 + 2 = 7 bytes).
            MockMultipartFile p1 = new MockMultipartFile(
                    "files", "a.jpg", "image/jpeg", "abcde".getBytes(StandardCharsets.UTF_8));
            MockMultipartFile p2 = new MockMultipartFile(
                    "files", "b.jpg", "image/jpeg", "fg".getBytes(StandardCharsets.UTF_8));
            chapterImageService.upload(chapterId, List.of(p1, p2));
            em.flush();
            em.clear();

            // THEN the chapter records 2 pages / 7 bytes, and the owning series's totals match.
            Chapter c = chapterRepository.findById(chapterId).orElseThrow();
            assertThat(c.getPageNum()).isEqualTo(2);
            assertThat(c.getDiskSize()).isEqualTo(7L);
            Series s = seriesRepository.findById(seriesId).orElseThrow();
            assertThat(s.getPageNum()).isEqualTo(2);
            assertThat(s.getDiskSize()).isEqualTo(7L);

            // WHEN the first page (5 bytes) is deleted.
            chapterService.deletePage(chapterId, "1.jpg");
            em.flush();
            em.clear();

            // THEN chapter + series drop to 1 page / 2 bytes.
            Chapter c2 = chapterRepository.findById(chapterId).orElseThrow();
            assertThat(c2.getPageNum()).isEqualTo(1);
            assertThat(c2.getDiskSize()).isEqualTo(2L);
            Series s2 = seriesRepository.findById(seriesId).orElseThrow();
            assertThat(s2.getPageNum()).isEqualTo(1);
            assertThat(s2.getDiskSize()).isEqualTo(2L);
        }
        finally
        {
            imageService.deleteAll(chapterId);   // on-disk files aren't rolled back with the transaction
        }
    }

    private Tag tag(String name)
    {
        Tag t = new Tag();
        t.setName(name);
        return tagRepository.save(t);
    }

    private Artist artist(String name)
    {
        Artist a = new Artist();
        a.setName(name);
        return artistRepository.save(a);
    }

    private int chapterWithTagAndScore(String titleFull, int tagId, int score)
    {
        ChapterForm form = minimalForm(titleFull);
        form.setScore(score);
        form.setTagIds(new ArrayList<>(List.of(tagId)));
        return chapterService.create(form);
    }

    private int joinCount(String table, String fkColumn, int id)
    {
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where " + fkColumn + " = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private Chapter seriesChapter(Series series, String titleFull, String language, Float num)
    {
        Chapter c = new Chapter();
        c.setTitle(titleFull);
        c.setTitleFull(titleFull);
        c.setNativeTitle("");
        c.setUploadDate(LocalDate.of(2021, 1, 1));
        c.setLanguage(language);
        c.setSeries(series);
        c.setChapterNum(num);
        return chapterRepository.save(c);
    }
}

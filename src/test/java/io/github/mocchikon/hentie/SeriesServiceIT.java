package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.ChipGroup;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.dto.SeriesViewModel;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.SeriesService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Transactional
class SeriesServiceIT
{
    @Autowired SeriesService seriesService;
    @Autowired SeriesRepository seriesRepository;
    @Autowired ChapterRepository chapterRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ImageDirectory imageDirectory;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager cacheManager;
    @PersistenceContext EntityManager em;

    private SeriesForm form(String titleFull)
    {
        SeriesForm f = new SeriesForm();
        f.setTitleFull(titleFull);
        f.setStatus(Status.REVIEWED);
        return f;
    }

    private Tag tag(String name)
    {
        Tag t = new Tag();
        t.setName(name);
        return tagRepository.save(t);
    }

    private Chapter chapter(String titleFull, String language, Short score, List<Tag> tags)
    {
        Chapter c = new Chapter();
        c.setTitle(titleFull);
        c.setTitleFull(titleFull);
        c.setNativeTitle("");
        c.setUploadDate(LocalDate.of(2021, 1, 1));
        c.setLanguage(language);
        c.setScore(score);
        c.setTags(new ArrayList<>(tags));
        return chapterRepository.save(c);
    }

    private Chapter chapterWithStats(String titleFull, int pageNum, long diskSize)
    {
        Chapter c = chapter(titleFull, "English", null, List.of());
        c.setPageNum(pageNum);
        c.setDiskSize(diskSize);
        return chapterRepository.save(c);
    }

    @Test
    void shouldSetCreatedDateToTodayWhenCreating()
    {
        // WHEN
        int id = seriesService.create(form("Fresh Series"));
        em.flush();
        em.clear();

        // THEN
        Series s = seriesRepository.findById(id).orElseThrow();
        assertThat(s.getCreatedDate()).isEqualTo(LocalDate.now());
        assertThat(s.getStatus()).isEqualTo(Status.REVIEWED);
    }

    @Test
    void shouldAllowDuplicateTitleFullWhenCreating()
    {
        // GIVEN - title_full is not unique, because the scraper produces duplicates.
        int first = seriesService.create(form("Svc Dup Series"));
        em.flush();

        // WHEN
        int second = seriesService.create(form("Svc Dup Series"));
        em.flush();
        em.clear();

        // THEN - both rows exist with the same full title.
        assertThat(first).isNotEqualTo(second);
        assertThat(seriesRepository.findById(first).orElseThrow().getTitleFull()).isEqualTo("Svc Dup Series");
        assertThat(seriesRepository.findById(second).orElseThrow().getTitleFull()).isEqualTo("Svc Dup Series");
    }

    /** "Create series from this chapter" numbers its chapter from the title, like every manual link. */
    @Test
    void shouldNumberAttachedChaptersFromTheirTitlesWhenCreating()
    {
        // GIVEN a chapter whose title carries no number, and one whose title does.
        Chapter plain = chapter("Attached", "English", null, List.of());
        Chapter numbered = chapter("Attached 4", "English", null, List.of());
        em.flush();

        // WHEN
        SeriesForm f = form("With Chapters");
        f.setChapterIds(new ArrayList<>(List.of(plain.getId(), numbered.getId())));
        int id = seriesService.create(f);
        em.flush();
        em.clear();

        // THEN both are linked: the plain one as the first chapter, the other as the fourth its title says.
        Chapter plainNow = chapterRepository.findById(plain.getId()).orElseThrow();
        Chapter numberedNow = chapterRepository.findById(numbered.getId()).orElseThrow();
        assertThat(plainNow.getSeries().getId()).isEqualTo(id);
        assertThat(plainNow.getChapterNum()).isEqualTo(1f);
        assertThat(numberedNow.getSeries().getId()).isEqualTo(id);
        assertThat(numberedNow.getChapterNum()).isEqualTo(4f);
    }

    @Test
    void shouldDeriveFacetsFromChaptersWithCountsWhenNoOverride()
    {
        // GIVEN
        Tag shared = tag("shared");
        Tag rare = tag("rare");
        int id = seriesService.create(form("Derived Facets"));
        Series series = seriesRepository.findById(id).orElseThrow();

        Chapter a = chapter("ca", "English", null, List.of(shared, rare));
        Chapter b = chapter("cb", "English", null, List.of(shared));
        a.setSeries(series);
        b.setSeries(series);
        chapterRepository.saveAll(List.of(a, b));
        em.flush();
        em.clear();

        // WHEN
        SeriesViewModel vm = seriesService.buildView(id, null);
        ChipGroup tags = vm.getDetailGroups().stream()
                .filter(g -> g.getTitle().equals("Tags")).findFirst().orElseThrow();

        // THEN
        assertThat(tags.isDerived()).isTrue();
        assertThat(tags.getChips()).extracting("label").containsExactly("shared", "rare");
        assertThat(tags.getChips().get(0).getCount()).isEqualTo(2L);
        assertThat(tags.getChips().get(1).getCount()).isEqualTo(1L);
    }

    @Test
    void shouldUseOverrideFacetsWhenPresent()
    {
        // GIVEN
        Tag override = tag("override-tag");
        Tag chapterTag = tag("chapter-tag");

        SeriesForm f = form("Override Facets");
        f.setTagIds(new ArrayList<>(List.of(override.getId())));
        int id = seriesService.create(f);
        Series series = seriesRepository.findById(id).orElseThrow();

        Chapter c = chapter("co", "English", null, List.of(chapterTag));
        c.setSeries(series);
        chapterRepository.save(c);
        em.flush();
        em.clear();

        // WHEN
        SeriesViewModel vm = seriesService.buildView(id, null);
        ChipGroup tags = vm.getDetailGroups().stream()
                .filter(g -> g.getTitle().equals("Tags")).findFirst().orElseThrow();

        // THEN
        assertThat(tags.isDerived()).isFalse();
        assertThat(tags.getChips()).extracting("label").containsExactly("override-tag");
    }

    @Test
    void shouldDeriveScoreAsRoundedChapterAverageWhenNoOverride()
    {
        // GIVEN
        int id = seriesService.create(form("Derived Score"));
        // 7.5 rounds to 8. Attached through the service, since buildView reads the stored column.
        Chapter a = chapter("sa", "English", (short) 7, List.of());
        Chapter b = chapter("sb", "English", (short) 8, List.of());
        seriesService.addChapters(id, List.of(a.getId(), b.getId()));
        em.flush();
        em.clear();

        // WHEN
        SeriesViewModel vm = seriesService.buildView(id, null);

        // THEN
        assertThat(vm.isScoreDerived()).isTrue();
        assertThat(vm.getScore()).isEqualTo(8);
        Series stored = seriesRepository.findById(id).orElseThrow();
        assertThat(stored.getScore()).isEqualTo((short) 8);
        assertThat(stored.getScoreSource()).isEqualTo(ScoreSource.DERIVED);
    }

    @Test
    void shouldUseOverrideScoreWhenSet()
    {
        // GIVEN
        SeriesForm f = form("Override Score");
        f.setScore(3);
        int id = seriesService.create(f);
        Series series = seriesRepository.findById(id).orElseThrow();
        Chapter a = chapter("os", "English", (short) 10, List.of());
        a.setSeries(series);
        chapterRepository.save(a);
        em.flush();
        em.clear();

        // WHEN
        SeriesViewModel vm = seriesService.buildView(id, null);

        // THEN
        assertThat(vm.isScoreDerived()).isFalse();
        assertThat(vm.getScore()).isEqualTo(3);
        Series stored = seriesRepository.findById(id).orElseThrow();
        assertThat(stored.getScore()).isEqualTo((short) 3);
        assertThat(stored.getScoreSource()).isEqualTo(ScoreSource.USER_SET);
    }

    @Test
    void shouldReturnNullScoreWhenNoChapterScores()
    {
        // GIVEN
        int id = seriesService.create(form("No Score"));
        Series series = seriesRepository.findById(id).orElseThrow();
        Chapter a = chapter("ns", "English", null, List.of());
        a.setSeries(series);
        chapterRepository.save(a);
        em.flush();
        em.clear();

        // WHEN
        SeriesViewModel vm = seriesService.buildView(id, null);

        // THEN
        assertThat(vm.isScoreDerived()).isTrue();
        assertThat(vm.getScore()).isNull();
        Series stored = seriesRepository.findById(id).orElseThrow();
        assertThat(stored.getScore()).isNull();
        assertThat(stored.getScoreSource()).isEqualTo(ScoreSource.DERIVED);
    }

    @Test
    void shouldMaterializeDerivedScoreWhenCreatingWithScoredChapters()
    {
        // GIVEN
        Chapter a = chapter("cds-a", "English", (short) 7, List.of());
        Chapter b = chapter("cds-b", "English", (short) 8, List.of());   // average 7.5 -> 8
        em.flush();
        SeriesForm f = form("Create With Scored Chapters");
        f.setChapterIds(new ArrayList<>(List.of(a.getId(), b.getId())));

        // WHEN
        int id = seriesService.create(f);
        em.flush();
        em.clear();

        // THEN
        Series s = seriesRepository.findById(id).orElseThrow();
        assertThat(s.getScoreSource()).isEqualTo(ScoreSource.DERIVED);
        assertThat(s.getScore()).isEqualTo((short) 8);
    }

    @Test
    void shouldDeriveScoreFromAverageNotHighestChapter()
    {
        // GIVEN
        // The rounded average (5.5 -> 6), not the highest chapter score (10).
        Chapter hi = chapter("avg-hi", "English", (short) 10, List.of());
        Chapter lo = chapter("avg-lo", "English", (short) 1, List.of());
        em.flush();
        SeriesForm f = form("Average Not Max");
        f.setChapterIds(new ArrayList<>(List.of(hi.getId(), lo.getId())));

        // WHEN
        int id = seriesService.create(f);
        em.flush();
        em.clear();

        // THEN
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isEqualTo((short) 6);
    }

    @Test
    void shouldRoundDerivedScoreToNearestWhenAverageIsSixPointTwoFive()
    {
        // GIVEN
        // 6 + 6 + 6 + 7 = 25; average 6.25 rounds down to 6.
        Chapter a = chapter("r-a", "English", (short) 6, List.of());
        Chapter b = chapter("r-b", "English", (short) 6, List.of());
        Chapter c = chapter("r-c", "English", (short) 6, List.of());
        Chapter d = chapter("r-d", "English", (short) 7, List.of());
        em.flush();
        SeriesForm f = form("Rounding Six Point Two Five");
        f.setChapterIds(new ArrayList<>(List.of(a.getId(), b.getId(), c.getId(), d.getId())));

        // WHEN
        int id = seriesService.create(f);
        em.flush();
        em.clear();

        // THEN
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isEqualTo((short) 6);
    }

    @Test
    void shouldRecomputeDerivedScoreWhenChaptersAdded()
    {
        // GIVEN
        int id = seriesService.create(form("Add Chapters Derived"));
        em.flush();
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isNull();

        // WHEN
        Chapter a = chapter("acd-a", "English", (short) 6, List.of());
        seriesService.addChapters(id, List.of(a.getId()));
        em.flush();
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isEqualTo((short) 6);

        Chapter b = chapter("acd-b", "English", (short) 8, List.of());   // average 7
        seriesService.addChapters(id, List.of(b.getId()));
        em.flush();
        em.clear();

        // THEN
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isEqualTo((short) 7);
    }

    @Test
    void shouldRecomputeDerivedScoreWhenChapterRemoved()
    {
        // GIVEN
        int id = seriesService.create(form("Remove Chapter Derived"));
        Chapter a = chapter("rcd-a", "English", (short) 4, List.of());
        Chapter b = chapter("rcd-b", "English", (short) 8, List.of());   // average 6
        em.flush();
        seriesService.addChapters(id, List.of(a.getId(), b.getId()));
        em.flush();
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isEqualTo((short) 6);

        // WHEN
        seriesService.removeChapter(id, b.getId());
        em.flush();
        em.clear();

        // THEN
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isEqualTo((short) 4);
    }

    @Test
    void shouldKeepUserSetScoreWhenChapterRemoved()
    {
        // GIVEN
        Chapter a = chapter("ruo-a", "English", (short) 4, List.of());
        Chapter b = chapter("ruo-b", "English", (short) 8, List.of());
        em.flush();
        SeriesForm f = form("Remove Chapter Override");
        f.setScore(9);
        f.setChapterIds(new ArrayList<>(List.of(a.getId(), b.getId())));
        int id = seriesService.create(f);
        em.flush();

        // WHEN
        seriesService.removeChapter(id, b.getId());
        em.flush();
        em.clear();

        // THEN
        Series s = seriesRepository.findById(id).orElseThrow();
        assertThat(s.getScoreSource()).isEqualTo(ScoreSource.USER_SET);
        assertThat(s.getScore()).isEqualTo((short) 9);
    }

    @Test
    void shouldRecomputeBothSeriesWhenChapterMovedBetweenThem()
    {
        // GIVEN a source holding TWO chapters, because an emptied series would be deleted outright.
        Chapter c = chapter("move-me", "English", (short) 4, List.of());
        Chapter stays = chapter("move-me-not", "English", (short) 8, List.of());
        em.flush();
        int source = seriesService.create(form("Move Source"));
        seriesService.addChapters(source, List.of(c.getId(), stays.getId()));   // source derives to 6
        int target = seriesService.create(form("Move Target"));  // empty -> null
        em.flush();
        assertThat(seriesRepository.findById(source).orElseThrow().getScore()).isEqualTo((short) 6);

        // WHEN
        seriesService.addChapters(target, List.of(c.getId()));
        em.flush();
        em.clear();

        // THEN the source drops to the remaining chapter's score and the target picks up the moved one.
        assertThat(seriesRepository.findById(source).orElseThrow().getScore()).isEqualTo((short) 8);
        assertThat(seriesRepository.findById(target).orElseThrow().getScore()).isEqualTo((short) 4);
    }

    /** Numbered from the title, as matching would; a constant would give every chapter linked by hand the same one. */
    @Test
    void shouldNumberUnnumberedChaptersFromTheirTitlesWhenAddingThem()
    {
        // GIVEN a series with a chapter of its own, and two unnumbered, scored chapters in no series.
        Chapter member = chapter("Link Saga 1", "English", null, List.of());
        Chapter third = chapter("Link Saga 3", "English", (short) 4, List.of());
        Chapter omake = chapter("Link Saga 2 omake", "English", (short) 8, List.of());
        em.flush();
        int id = seriesService.create(form("Link Saga"));
        int first = seriesService.addChapters(id, List.of(member.getId()));
        em.flush();

        // WHEN all three are added - the member once more among them.
        int added = seriesService.addChapters(id, List.of(member.getId(), third.getId(), omake.getId()));
        em.flush();
        em.clear();

        // THEN each call counts only the chapters that joined...
        assertThat(first).isEqualTo(1);
        assertThat(added).isEqualTo(2);
        // ...each numbered from its title...
        Chapter thirdNow = chapterRepository.findById(third.getId()).orElseThrow();
        Chapter omakeNow = chapterRepository.findById(omake.getId()).orElseThrow();
        assertThat(thirdNow.getSeries().getId()).isEqualTo(id);
        assertThat(thirdNow.getChapterNum()).isEqualTo(3f);
        assertThat(omakeNow.getSeries().getId()).isEqualTo(id);
        assertThat(omakeNow.getChapterNum()).isCloseTo(2.01f, within(0.001f));
        // ...the member keeps the number it had...
        assertThat(chapterRepository.findById(member.getId()).orElseThrow().getChapterNum()).isEqualTo(1f);
        // ...and the series is recomputed over its new chapters.
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isEqualTo((short) 6);
    }

    /** A number the chapter already has may have been set by hand, so it is kept. */
    @Test
    void shouldKeepTheNumberAndDropTheEmptiedSeriesWhenAddingAChapterOutOfIt()
    {
        // GIVEN a chapter numbered from its title in a series of its own, then renumbered by hand there.
        Chapter moving = chapter("Mover 5", "English", null, List.of());
        em.flush();
        int former = seriesService.create(form("Former Home"));
        seriesService.addChapters(former, List.of(moving.getId()));
        em.flush();
        assertThat(chapterRepository.findById(moving.getId()).orElseThrow().getChapterNum()).isEqualTo(5f);
        seriesService.updateChapterNum(former, moving.getId(), 7.5f);
        int target = seriesService.create(form("New Home"));
        em.flush();

        // WHEN
        int added = seriesService.addChapters(target, List.of(moving.getId()));
        em.flush();
        em.clear();

        // THEN
        assertThat(added).isEqualTo(1);
        Chapter moved = chapterRepository.findById(moving.getId()).orElseThrow();
        assertThat(moved.getSeries().getId()).isEqualTo(target);
        assertThat(moved.getChapterNum()).isEqualTo(7.5f);
        assertThat(seriesRepository.findById(former)).isEmpty();
    }

    @Test
    void shouldAddNothingWhenNoChapterOrOnlyAMissingOneIsGiven()
    {
        // GIVEN
        int id = seriesService.create(form("Nothing To Link"));
        em.flush();

        // WHEN
        int none = seriesService.addChapters(id, List.of());
        int missing = seriesService.addChapters(id, List.of(Integer.MAX_VALUE));
        em.flush();

        // THEN
        assertThat(none).isZero();
        assertThat(missing).isZero();
        assertThat(chapterRepository.countBySeriesId(id)).isZero();
    }

    @Test
    void shouldDeleteTheSourceWhenTheMovedChapterWasItsLast()
    {
        // GIVEN a source holding exactly one chapter
        Chapter c = chapter("last-one-out", "English", (short) 4, List.of());
        em.flush();
        int source = seriesService.create(form("Emptied Source"));
        seriesService.addChapters(source, List.of(c.getId()));
        int target = seriesService.create(form("Filled Target"));
        em.flush();

        // WHEN that chapter moves to the target
        seriesService.addChapters(target, List.of(c.getId()));
        em.flush();
        em.clear();

        // THEN the emptied source is gone; the chapter and target are fine.
        assertThat(seriesRepository.findById(source)).isEmpty();
        assertThat(chapterRepository.findById(c.getId()).orElseThrow().getSeries().getId()).isEqualTo(target);
        assertThat(seriesRepository.findById(target).orElseThrow().getScore()).isEqualTo((short) 4);
    }

    @Test
    void shouldSwitchToDerivedAndRematerializeWhenOverrideClearedOnUpdate()
    {
        // GIVEN
        Chapter a = chapter("clr-a", "English", (short) 4, List.of());
        Chapter b = chapter("clr-b", "English", (short) 6, List.of());   // average 5
        em.flush();
        SeriesForm create = form("Clear Override");
        create.setScore(9);
        create.setChapterIds(new ArrayList<>(List.of(a.getId(), b.getId())));
        int id = seriesService.create(create);
        em.flush();
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isEqualTo((short) 9);
        em.clear();

        // WHEN
        SeriesForm edit = form("Clear Override");
        edit.setId(id);
        edit.setScore(null);   // cleared -> DERIVED
        seriesService.update(edit);
        em.flush();
        em.clear();

        // THEN
        Series s = seriesRepository.findById(id).orElseThrow();
        assertThat(s.getScoreSource()).isEqualTo(ScoreSource.DERIVED);
        assertThat(s.getScore()).isEqualTo((short) 5);
    }

    @Test
    void shouldSwitchToUserSetWhenOverrideAddedOnUpdate()
    {
        // GIVEN
        Chapter a = chapter("addov-a", "English", (short) 8, List.of());
        em.flush();
        int id = seriesService.create(form("Add Override"));
        seriesService.addChapters(id, List.of(a.getId()));
        em.flush();
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isEqualTo((short) 8);
        em.clear();

        // WHEN
        SeriesForm edit = form("Add Override");
        edit.setId(id);
        edit.setScore(3);
        seriesService.update(edit);
        em.flush();
        em.clear();

        // THEN
        Series s = seriesRepository.findById(id).orElseThrow();
        assertThat(s.getScoreSource()).isEqualTo(ScoreSource.USER_SET);
        assertThat(s.getScore()).isEqualTo((short) 3);
    }

    @Test
    void shouldSumPageAndDiskTotalsFromChaptersWhenRecomputing()
    {
        // GIVEN two chapters with distinct page counts + byte sizes.
        Chapter a = chapterWithStats("pd-a", 10, 1_000L);
        Chapter b = chapterWithStats("pd-b", 25, 2_500L);
        em.flush();

        // WHEN attached through the service.
        int id = seriesService.create(form("Page Disk Totals"));
        seriesService.addChapters(id, List.of(a.getId(), b.getId()));
        em.flush();
        em.clear();

        // THEN the series carries the summed page/disk totals.
        Series s = seriesRepository.findById(id).orElseThrow();
        assertThat(s.getPageNum()).isEqualTo(35);
        assertThat(s.getDiskSize()).isEqualTo(3_500L);
    }

    @Test
    void shouldRecomputePageAndDiskTotalsWhenChapterRemoved()
    {
        // GIVEN a series summing two chapters (35 pages, 3500 bytes).
        int id = seriesService.create(form("Remove Recompute Totals"));
        Chapter a = chapterWithStats("rpd-a", 10, 1_000L);
        Chapter b = chapterWithStats("rpd-b", 25, 2_500L);
        em.flush();
        seriesService.addChapters(id, List.of(a.getId(), b.getId()));
        em.flush();
        assertThat(seriesRepository.findById(id).orElseThrow().getPageNum()).isEqualTo(35);

        // WHEN one chapter is removed.
        seriesService.removeChapter(id, b.getId());
        em.flush();
        em.clear();

        // THEN the totals drop to the remaining chapter's.
        Series s = seriesRepository.findById(id).orElseThrow();
        assertThat(s.getPageNum()).isEqualTo(10);
        assertThat(s.getDiskSize()).isEqualTo(1_000L);
    }

    @Test
    void shouldZeroPageAndDiskTotalsWhenSeriesHasNoChapters()
    {
        // GIVEN + WHEN a freshly created series with no chapters.
        int id = seriesService.create(form("Empty Totals"));
        em.flush();
        em.clear();

        // THEN its totals are 0 (NOT NULL columns), not null.
        Series s = seriesRepository.findById(id).orElseThrow();
        assertThat(s.getPageNum()).isZero();
        assertThat(s.getDiskSize()).isZero();
    }

    @Test
    void shouldCopyTitlesAndMetadataWhenPrefillingFromChapter()
    {
        // GIVEN
        Tag t = tag("prefill-tag");
        Chapter c = chapter("Prefill Full", "English", null, List.of(t));
        c.setTitle("Prefill Pretty");
        c.setNativeTitle("Prefill Native");
        chapterRepository.save(c);
        em.flush();

        // WHEN
        SeriesForm f = seriesService.prefillFromChapter(c.getId());

        // THEN
        assertThat(f.getTitleFull()).isEqualTo("Prefill Full");
        assertThat(f.getTitle()).isEqualTo("Prefill Pretty");
        assertThat(f.getNativeTitle()).isEqualTo("Prefill Native");
        assertThat(f.getTagIds()).containsExactly(t.getId());
        assertThat(f.getChapterIds()).containsExactly(c.getId());
    }

    @Test
    void shouldUnlinkButKeepChapterRowWhenRemovingChapter()
    {
        // GIVEN
        int id = seriesService.create(form("Remove Chapter"));
        Series series = seriesRepository.findById(id).orElseThrow();
        Chapter c = chapter("rc", "English", null, List.of());
        c.setSeries(series);
        c.setChapterNum(2f);
        chapterRepository.save(c);
        em.flush();
        em.clear();

        // WHEN
        seriesService.removeChapter(id, c.getId());
        em.flush();
        em.clear();

        // THEN
        Chapter reloaded = chapterRepository.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getSeries()).isNull();
        assertThat(reloaded.getChapterNum()).isNull();
    }

    @Test
    void shouldIgnoreChapterOfDifferentSeriesWhenRemovingChapter()
    {
        // GIVEN
        int a = seriesService.create(form("Series A"));
        int b = seriesService.create(form("Series B"));
        Series seriesA = seriesRepository.findById(a).orElseThrow();
        Chapter c = chapter("owned-by-a", "English", null, List.of());
        c.setSeries(seriesA);
        chapterRepository.save(c);
        em.flush();
        em.clear();

        // WHEN
        seriesService.removeChapter(b, c.getId());
        em.flush();
        em.clear();

        // THEN
        assertThat(chapterRepository.findById(c.getId()).orElseThrow().getSeries().getId()).isEqualTo(a);
    }

    @Test
    void shouldApplyOnlyWithinTheOwningSeriesWhenUpdatingChapterNum()
    {
        // GIVEN
        int id = seriesService.create(form("Renumber"));
        Series series = seriesRepository.findById(id).orElseThrow();
        Chapter c = chapter("rn", "English", null, List.of());
        c.setSeries(series);
        c.setChapterNum(1f);
        chapterRepository.save(c);
        em.flush();
        em.clear();

        // WHEN + THEN
        seriesService.updateChapterNum(id, c.getId(), 5.5f);
        em.flush();
        em.clear();
        assertThat(chapterRepository.findById(c.getId()).orElseThrow().getChapterNum()).isEqualTo(5.5f);

        seriesService.updateChapterNum(id + 999, c.getId(), 9f);
        em.flush();
        em.clear();
        assertThat(chapterRepository.findById(c.getId()).orElseThrow().getChapterNum()).isEqualTo(5.5f);
    }

    @Test
    void shouldAllowDuplicateTitleFullOfAnotherSeriesWhenUpdating()
    {
        // GIVEN - title_full is not unique, so renaming B onto A's full title is accepted.
        seriesService.create(form("Update Dup A"));
        int b = seriesService.create(form("Update Dup B"));
        em.flush();

        // WHEN
        SeriesForm edit = form("Update Dup A");
        edit.setId(b);
        seriesService.update(edit);
        em.flush();
        em.clear();

        // THEN - B now carries the same full title as A.
        assertThat(seriesRepository.findById(b).orElseThrow().getTitleFull()).isEqualTo("Update Dup A");
    }

    @Test
    void shouldAllowKeepingItsOwnTitleFullUnchangedWhenUpdating()
    {
        // GIVEN
        int id = seriesService.create(form("Keep Own Title"));
        em.flush();

        // WHEN
        SeriesForm edit = form("Keep Own Title");
        edit.setId(id);
        edit.setScore(4);
        seriesService.update(edit);
        em.flush();
        em.clear();

        // THEN
        // "Keep Own Title" already exists, but it is this same series.
        assertThat(seriesRepository.findById(id).orElseThrow().getScore()).isEqualTo((short) 4);
    }

    @Test
    void shouldApplyChapterNumsFromTheFormWhenUpdating()
    {
        // GIVEN
        int id = seriesService.create(form("Renumber Via Update"));
        Series series = seriesRepository.findById(id).orElseThrow();
        Chapter a = chapter("ru-a", "English", null, List.of());
        Chapter b = chapter("ru-b", "English", null, List.of());
        a.setSeries(series);
        b.setSeries(series);
        chapterRepository.saveAll(List.of(a, b));
        em.flush();
        em.clear();

        // WHEN
        SeriesForm edit = form("Renumber Via Update");
        edit.setId(id);
        edit.setChapterNums(new HashMap<>(Map.of(a.getId(), 2.5f, b.getId(), 1.5f)));
        seriesService.update(edit);
        em.flush();
        em.clear();

        // THEN
        assertThat(chapterRepository.findById(a.getId()).orElseThrow().getChapterNum()).isEqualTo(2.5f);
        assertThat(chapterRepository.findById(b.getId()).orElseThrow().getChapterNum()).isEqualTo(1.5f);
    }

    @Test
    void shouldFilterCardsByLanguageAndExposeEffectiveLanguageWhenLanguageGiven()
    {
        // GIVEN
        int id = seriesService.create(form("Language Filter"));
        Series series = seriesRepository.findById(id).orElseThrow();
        Chapter en = chapter("lf-en", "English", null, List.of());
        Chapter jp = chapter("lf-jp", "Japanese", null, List.of());
        en.setSeries(series);
        jp.setSeries(series);
        chapterRepository.saveAll(List.of(en, jp));
        em.flush();
        em.clear();

        // WHEN
        SeriesViewModel unfiltered = seriesService.buildView(id, null);
        SeriesViewModel filtered = seriesService.buildView(id, "Japanese");

        // THEN
        assertThat(unfiltered.getLanguages()).containsExactlyInAnyOrder("English", "Japanese");
        assertThat(unfiltered.getSelectedLanguage()).isNull();
        assertThat(unfiltered.getChapterCards()).hasSize(2);
        assertThat(unfiltered.getEffectiveLanguage()).contains("English").contains("Japanese");

        assertThat(filtered.getSelectedLanguage()).isEqualTo("Japanese");
        assertThat(filtered.getChapterCards()).extracting("caption").containsExactly("lf-jp");
        // effectiveLanguage reflects every language present, not just the filtered one.
        assertThat(filtered.getEffectiveLanguage()).contains("English").contains("Japanese");
    }

    @Test
    void shouldIgnoreLanguageFilterWhenLanguageIsUnknown()
    {
        // GIVEN
        int id = seriesService.create(form("Unknown Language Filter"));
        Series series = seriesRepository.findById(id).orElseThrow();
        Chapter en = chapter("ulf-en", "English", null, List.of());
        en.setSeries(series);
        chapterRepository.save(en);
        em.flush();
        em.clear();

        // WHEN
        SeriesViewModel vm = seriesService.buildView(id, "Klingon");

        // THEN
        assertThat(vm.getSelectedLanguage()).isNull();
        assertThat(vm.getChapterCards()).hasSize(1);
    }

    @Test
    void shouldUsePlaceholderThumbnailWhenSeriesHasNoChapters()
    {
        // GIVEN
        int id = seriesService.create(form("No Chapters Thumbnail"));
        em.flush();
        em.clear();

        // WHEN
        SeriesViewModel vm = seriesService.buildView(id, null);

        // THEN
        assertThat(vm.getThumbnailUrl()).isEqualTo(io.github.mocchikon.hentie.service.ImageService.PLACEHOLDER);
        assertThat(vm.getChapterCount()).isZero();
    }

    @Test
    void shouldCleanupWhenSeriesDeleted()
    {
        // GIVEN a series overriding a tag it shares with a SIBLING series (the control), and two member
        // chapters in different languages, which must survive: deleting a series only unlinks them.
        Tag sharedTag = tag("cleanup-series-tag");
        SeriesForm f = form("Cleanup Series Delete Me");
        f.setTagIds(new ArrayList<>(List.of(sharedTag.getId())));
        int id = seriesService.create(f);
        Series series = seriesRepository.findById(id).orElseThrow();

        Chapter c1 = chapter("cleanup-sd-c1", "English", null, List.of());
        c1.setSeries(series);
        c1.setChapterNum(1f);
        chapterRepository.save(c1);
        Chapter c2 = chapter("cleanup-sd-c2", "Japanese", null, List.of());
        c2.setSeries(series);
        c2.setChapterNum(2f);
        chapterRepository.save(c2);

        SeriesForm siblingForm = form("Cleanup Sibling Series");
        siblingForm.setTagIds(new ArrayList<>(List.of(sharedTag.getId())));
        int siblingId = seriesService.create(siblingForm);
        em.flush();
        assertThat(seriesTagLink(id, sharedTag.getId())).isEqualTo(1);
        assertThat(seriesEffectiveTagLink(id, sharedTag.getId())).isEqualTo(1);
        em.clear();

        // WHEN
        seriesService.delete(id);
        em.flush();
        em.clear();

        // THEN the series and its own join rows are gone with no FK violation; both chapters survive
        // unlinked; the tag and the sibling's links to it are untouched.
        assertThat(seriesRepository.findById(id)).isEmpty();
        assertThat(seriesTagLink(id, sharedTag.getId())).isZero();
        assertThat(seriesEffectiveTagLink(id, sharedTag.getId())).isZero();

        Chapter r1 = chapterRepository.findById(c1.getId()).orElseThrow();
        assertThat(r1.getSeries()).isNull();
        assertThat(r1.getChapterNum()).isNull();
        Chapter r2 = chapterRepository.findById(c2.getId()).orElseThrow();
        assertThat(r2.getSeries()).isNull();
        assertThat(r2.getChapterNum()).isNull();

        assertThat(tagRepository.findById(sharedTag.getId())).isPresent();
        assertThat(seriesTagLink(siblingId, sharedTag.getId())).isEqualTo(1);
        assertThat(seriesEffectiveTagLink(siblingId, sharedTag.getId())).isEqualTo(1);
    }

    /** The mirror image of shouldCleanupWhenSeriesDeleted: the one series operation that removes chapter rows. */
    @Test
    void shouldDeleteChaptersAndTheirImagesWhenDeletingSeriesWithChapters()
    {
        // GIVEN a series with two chapters, one sharing a tag with a sibling series (the control), each
        // with a page on disk.
        Tag sharedTag = tag("delete-all-tag");
        int id = seriesService.create(form("Delete Me With Chapters"));
        Series series = seriesRepository.findById(id).orElseThrow();

        Chapter c1 = chapter("dwc-c1", "English", (short) 8, List.of(sharedTag));
        c1.setSeries(series);
        c1.setChapterNum(1f);
        chapterRepository.save(c1);
        Chapter c2 = chapter("dwc-c2", "Japanese", null, List.of());
        c2.setSeries(series);
        c2.setChapterNum(2f);
        chapterRepository.save(c2);

        SeriesForm siblingForm = form("Delete With Chapters Sibling");
        siblingForm.setTagIds(new ArrayList<>(List.of(sharedTag.getId())));
        int siblingId = seriesService.create(siblingForm);
        em.flush();
        em.clear();

        Path dir1 = page(c1.getId());
        Path dir2 = page(c2.getId());

        // WHEN
        int deleted = seriesService.deleteWithChapters(id);
        em.flush();
        em.clear();

        // THEN the series and both chapters are gone - rows, chapter_tags links and image directories.
        assertThat(deleted).isEqualTo(2);
        assertThat(seriesRepository.findById(id)).isEmpty();
        assertThat(chapterRepository.findById(c1.getId())).isEmpty();
        assertThat(chapterRepository.findById(c2.getId())).isEmpty();
        assertThat(chapterTagLink(c1.getId(), sharedTag.getId())).isZero();
        assertThat(dir1).doesNotExist();
        assertThat(dir2).doesNotExist();

        // ...while the shared tag entity and the sibling series' links to it are untouched.
        assertThat(tagRepository.findById(sharedTag.getId())).isPresent();
        assertThat(seriesTagLink(siblingId, sharedTag.getId())).isEqualTo(1);
        assertThat(seriesEffectiveTagLink(siblingId, sharedTag.getId())).isEqualTo(1);
    }

    @Test
    void shouldReportZeroWhenDeletingAnEmptySeriesWithChapters()
    {
        int id = seriesService.create(form("Empty Delete With Chapters"));
        em.flush();
        em.clear();

        assertThat(seriesService.deleteWithChapters(id)).isZero();
        assertThat(seriesRepository.findById(id)).isEmpty();
    }

    /**
     * {@code addChaptersDeferred} evicts nothing itself and reaches {@code deleteIfEmpty} by self-invocation,
     * where a {@code @CacheEvict} would be skipped; so the eviction must be programmatic.
     */
    @Test
    void shouldEvictCachedSearchCountsWhenAnEmptiedSeriesIsDropped()
    {
        // GIVEN a chapter in one series, another series to move it to, and a warm search-count cache.
        Chapter c = chapter("Evict Move Chapter", "English", null, List.of());
        int source = seriesService.create(form("Evict Move Source"));
        int target = seriesService.create(form("Evict Move Target"));
        seriesService.addChapters(source, List.of(c.getId()));
        em.flush();
        cacheManager.getCache(CacheConfig.SEARCH_COUNT).put("stale-total", 42L);

        // WHEN its last chapter is moved away by the deferred (bulk) path.
        seriesService.addChaptersDeferred(target, Map.of(c.getId(), 1f));
        em.flush();
        em.clear();

        // THEN the emptied series is gone, the chapter is in the target...
        assertThat(seriesRepository.findById(source)).isEmpty();
        assertThat(seriesRepository.findById(target)).isPresent();
        assertThat(chapterRepository.findById(c.getId()).orElseThrow().getSeries().getId()).isEqualTo(target);
        // ...and the totals that counted it were dropped.
        assertThat(cacheManager.getCache(CacheConfig.SEARCH_COUNT).get("stale-total")).isNull();
    }

    /** Nothing was deleted, so the cached totals still stand. */
    @Test
    void shouldKeepCachedSearchCountsWhenTheSeriesStillHasChapters()
    {
        // GIVEN a series with two chapters, one of which is about to move away.
        Chapter staying = chapter("Evict Keep Staying", "English", null, List.of());
        Chapter moving = chapter("Evict Keep Moving", "English", null, List.of());
        int source = seriesService.create(form("Evict Keep Source"));
        int target = seriesService.create(form("Evict Keep Target"));
        seriesService.addChapters(source, List.of(staying.getId(), moving.getId()));
        em.flush();
        cacheManager.getCache(CacheConfig.SEARCH_COUNT).put("still-valid", 42L);

        // WHEN one of them moves, leaving the source non-empty.
        seriesService.addChaptersDeferred(target, Map.of(moving.getId(), 1f));
        em.flush();
        em.clear();

        // THEN the source survives and no total was thrown away.
        assertThat(seriesRepository.findById(source)).isPresent();
        assertThat(chapterRepository.findById(staying.getId()).orElseThrow().getSeries().getId())
                .isEqualTo(source);
        assertThat(cacheManager.getCache(CacheConfig.SEARCH_COUNT).get("still-valid", Long.class))
                .isEqualTo(42L);
    }

    /** Writes one page file for a chapter and returns its directory. */
    private Path page(int chapterId)
    {
        Path dir = imageDirectory.chapterDir(chapterId);
        try
        {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("1.jpg"), "page");
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    private int chapterTagLink(int chapterId, int tagId)
    {
        return jdbc.queryForObject(
                "select count(*) from chapter_tags where chapter_id=? and tag_id=?",
                Integer.class, chapterId, tagId);
    }

    private int seriesTagLink(int seriesId, int tagId)
    {
        return jdbc.queryForObject(
                "select count(*) from series_tags where series_id=? and tag_id=?",
                Integer.class, seriesId, tagId);
    }

    private int seriesEffectiveTagLink(int seriesId, int tagId)
    {
        return jdbc.queryForObject(
                "select count(*) from series_effective_tags where series_id=? and tag_id=?",
                Integer.class, seriesId, tagId);
    }
}

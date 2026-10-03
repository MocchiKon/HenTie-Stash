package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.*;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.*;
import io.github.mocchikon.hentie.service.ChapterService.StatsSync;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repair of the materialized image stats against the files on disk. Images the app did not write leave
 * the columns at 0, and then a page-count or size sort ties every row and falls back to the id order.
 * <p>
 * Files are written straight into the data dir, the path that leaves the columns stale, and removed in a
 * {@code finally} because the rollback does not reach them.
 */
@SpringBootTest
@Transactional
class ImageStatsServiceIT
{
    @Autowired ImageStatsService imageStatsService;
    @Autowired ChapterService chapterService;
    @Autowired SeriesService seriesService;
    @Autowired SearchService searchService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired ImageService imageService;
    @Autowired ImageDirectory imageDirectory;
    @Autowired CacheManager cacheManager;
    @PersistenceContext EntityManager em;

    @Test
    void shouldRepairStaleChapterAndSeriesStatsWhenResyncingAll() throws Exception
    {
        // GIVEN a chapter in a series whose pages were put on disk behind the app's back.
        int chapterId = chapterService.create(form("resync-stats-chapter"));
        int seriesId = seriesService.create(seriesForm("Resync Stats Series"));
        seriesService.addChapters(seriesId, List.of(chapterId));
        em.flush();
        try
        {
            writePages(chapterId, "abcde", "fg");   // 2 pages, 5 + 2 = 7 bytes
            em.clear();
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isZero();
            assertThat(seriesRepository.findById(seriesId).orElseThrow().getPageNum()).isZero();

            // WHEN the bulk resync runs.
            int repaired = imageStatsService.resyncAll();
            em.flush();
            em.clear();

            // THEN the chapter matches the files, and the series totals are re-summed from it.
            assertThat(repaired).isPositive();
            Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
            assertThat(chapter.getPageNum()).isEqualTo(2);
            assertThat(chapter.getDiskSize()).isEqualTo(7L);
            assertThat(seriesRepository.findById(seriesId).orElseThrow().getPageNum()).isEqualTo(2);
            assertThat(seriesRepository.findById(seriesId).orElseThrow().getDiskSize()).isEqualTo(7L);

            // AND a second pass leaves it alone. Checked per chapter, because *ControllerIT suites commit
            // rows that may be drifted when this runs.
            assertThat(chapterService.syncImageStats(chapterId)).isEqualTo(StatsSync.IN_SYNC);
        }
        finally
        {
            imageService.deleteAll(chapterId);
        }
    }

    /**
     * The view shows the stored columns, and the detail page repairs them before showing them. The <b>database
     * row</b> is what tells a repair from a mere display scan, which would leave search, reading only the column,
     * still wrong.
     */
    @Test
    void shouldRepairDriftedStatsWhenTheDetailPageHealsThemBeforeBuildingTheView()
    {
        // GIVEN a chapter in a series whose pages appeared on disk without the app writing them.
        int chapterId = chapterService.create(form("viewstats-chapter"));
        int seriesId = seriesService.create(seriesForm("View Stats Series"));
        seriesService.addChapters(seriesId, List.of(chapterId));
        em.flush();
        try
        {
            writePages(chapterId, "abcd", "ef");   // 2 pages, 4 + 2 = 6 bytes
            em.clear();
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isZero();

            // WHEN the detail page builds the view while the columns are stale, heals, and builds it again.
            ChapterViewModel stale = chapterService.buildView(chapterId);
            boolean healed = heal(chapterId, stale);
            ChapterViewModel repaired = chapterService.buildView(chapterId);
            em.flush();
            em.clear();

            // THEN it reports the real numbers, and the columns were written on the chapter and its series.
            assertThat(healed).isTrue();
            assertThat(repaired.getPageCount()).isEqualTo(2);
            assertThat(repaired.getDiskSizeDisplay()).isEqualTo("6 B");
            assertThat(repaired.getPageUrls()).hasSize(2);
            Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
            assertThat(chapter.getPageNum()).isEqualTo(2);
            assertThat(chapter.getDiskSize()).isEqualTo(6L);
            assertThat(seriesRepository.findById(seriesId).orElseThrow().getPageNum()).isEqualTo(2);
            assertThat(seriesRepository.findById(seriesId).orElseThrow().getDiskSize()).isEqualTo(6L);

            // AND a second view finds nothing left to repair.
            ChapterViewModel again = chapterService.buildView(chapterId);
            assertThat(heal(chapterId, again)).isFalse();
            assertThat(again.getPageCount()).isEqualTo(2);
            assertThat(again.getDiskSizeDisplay()).isEqualTo("6 B");
            assertThat(again.getPageUrls()).hasSize(2);
            assertThat(chapterService.syncImageStats(chapterId)).isEqualTo(StatsSync.IN_SYNC);
        }
        finally
        {
            imageService.deleteAll(chapterId);
        }
    }

    /** A chapter with no images is in sync at 0, so viewing it must not keep repairing it. */
    @Test
    void shouldNotRepairAChapterWithNoImages()
    {
        int chapterId = chapterService.create(form("noimages-chapter"));
        em.flush();

        ChapterViewModel vm = chapterService.buildView(chapterId);
        assertThat(heal(chapterId, vm)).isFalse();

        assertThat(vm.getPageCount()).isZero();
        assertThat(vm.getDiskSizeDisplay()).isEqualTo("0 B");
        assertThat(vm.getPageUrls()).isEmpty();
        assertThat(chapterService.syncImageStats(chapterId)).isEqualTo(StatsSync.IN_SYNC);
    }

    @Test
    void shouldRejectTheDetailViewOfAMissingChapter()
    {
        assertThatThrownBy(() -> chapterService.buildView(Integer.MAX_VALUE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void shouldOrderSearchByRealPageCountOnlyAfterTheStatsAreResynced()
    {
        // GIVEN three chapters with 3 / 10 / 52 pages on disk, created in that order, stats never materialized.
        int few = chapterService.create(form("pgorder-few"));
        int mid = chapterService.create(form("pgorder-mid"));
        int many = chapterService.create(form("pgorder-many"));
        em.flush();
        try
        {
            writePages(few, page(3));
            writePages(mid, page(10));
            writePages(many, page(52));
            em.clear();

            // WHEN sorting by page count, biggest first, while every row still ties on 0.
            List<String> stale = captions(searchByPages(Sort.Direction.DESC));

            // THEN the tie falls back to the id order, which has nothing to do with page counts.
            assertThat(stale).containsExactly("pgorder-many", "pgorder-mid", "pgorder-few");
            assertThat(chapterRepository.findById(many).orElseThrow().getPageNum()).isZero();

            // WHEN the stats are repaired.
            imageStatsService.resyncAll();
            em.flush();
            em.clear();

            // THEN descending really is most-pages-first, and ascending is its exact mirror.
            assertThat(captions(searchByPages(Sort.Direction.DESC)))
                    .containsExactly("pgorder-many", "pgorder-mid", "pgorder-few");
            assertThat(captions(searchByPages(Sort.Direction.ASC)))
                    .containsExactly("pgorder-few", "pgorder-mid", "pgorder-many");
        }
        finally
        {
            imageService.deleteAll(few);
            imageService.deleteAll(mid);
            imageService.deleteAll(many);
        }
    }

    /**
     * The resync must read the disk uncached: only the app's own writes invalidate the listing cache, so
     * through it a page added by the scraper would never be counted.
     */
    @Test
    void shouldRepairStatsForAPageThatAppearedAfterTheListingWasCached()
    {
        // GIVEN a chapter in sync with its 2 pages, whose listing has been read and cached.
        int chapterId = chapterService.create(form("resync-warm-cache"));
        em.flush();
        try
        {
            writePages(chapterId, "ab", "cd");   // 2 pages, 2 + 2 = 4 bytes
            chapterService.syncImageStats(chapterId);
            assertThat(chapterService.buildView(chapterId).getPageUrls()).hasSize(2);
            em.flush();
            em.clear();

            // WHEN a third page appears behind the app's back and the bulk resync runs.
            writePage(chapterId, 3, "efg");
            imageStatsService.resyncAll();
            em.flush();
            em.clear();

            // THEN the columns follow the disk.
            Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
            assertThat(chapter.getPageNum()).isEqualTo(3);
            assertThat(chapter.getDiskSize()).isEqualTo(7L);
        }
        finally
        {
            imageService.deleteAll(chapterId);
        }
    }

    @Test
    void shouldRepairStatsForOneChapterAndItsSeriesWhenRescanningItsImages()
    {
        // GIVEN a chapter in a series, both in sync with the 2 pages on disk.
        int chapterId = chapterService.create(form("rescan-one-chapter"));
        int seriesId = seriesService.create(seriesForm("Rescan One Series"));
        seriesService.addChapters(seriesId, List.of(chapterId));
        em.flush();
        try
        {
            writePages(chapterId, "ab", "cd");
            chapterService.syncImageStats(chapterId);
            em.flush();
            em.clear();

            // WHEN a page appears behind the app's back and only this chapter is rescanned.
            writePage(chapterId, 3, "efg");
            boolean repaired = chapterService.rescanImages(chapterId);
            em.flush();
            em.clear();

            // THEN its columns and the series totals summed from them both follow the disk.
            assertThat(repaired).isTrue();
            Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
            assertThat(chapter.getPageNum()).isEqualTo(3);
            assertThat(chapter.getDiskSize()).isEqualTo(7L);
            assertThat(seriesRepository.findById(seriesId).orElseThrow().getPageNum()).isEqualTo(3);
            assertThat(seriesRepository.findById(seriesId).orElseThrow().getDiskSize()).isEqualTo(7L);

            // AND the page grid shows the new file, while a second rescan finds nothing left to repair.
            assertThat(chapterService.buildView(chapterId).getPageUrls()).hasSize(3);
            assertThat(chapterService.rescanImages(chapterId)).isFalse();
        }
        finally
        {
            imageService.deleteAll(chapterId);
        }
    }

    /**
     * Stored count and cached listing agree with each other but not with the disk: only the cached listing was
     * wrong, and the page must be read again although nothing was written.
     */
    @Test
    void shouldAskForAnotherReadWhenOnlyTheCachedListingWasWrong()
    {
        // GIVEN a chapter in sync with its 2 pages on disk, viewed as if its cached listing still showed one.
        int chapterId = chapterService.create(form("heal-stale-listing"));
        em.flush();
        try
        {
            writePages(chapterId, "ab", "cd");
            chapterService.syncImageStats(chapterId);

            // WHEN the detail page checks the stats against that listing.
            boolean readAgain = imageStatsService.healIfDrifted(chapterId, 2, 1);

            // THEN it asks for another read, and the stats, already right, stay as they are.
            assertThat(readAgain).isTrue();
            assertThat(chapterService.buildView(chapterId).getPageUrls()).hasSize(2);
            assertThat(chapterService.syncImageStats(chapterId)).isEqualTo(StatsSync.IN_SYNC);
        }
        finally
        {
            imageService.deleteAll(chapterId);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** As the detail page does it: with what the view read. */
    private boolean heal(int chapterId, ChapterViewModel vm)
    {
        return imageStatsService.healIfDrifted(chapterId, vm.getPageCount(), vm.getPageUrls().size());
    }

    private List<CardDto> searchByPages(Sort.Direction dir)
    {
        // The count cache is not rolled back with the transaction, so a sibling test could have seeded it.
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();
        SearchCriteria sc = new SearchCriteria();
        sc.setType(SearchType.CHAPTER);
        sc.setTitle("pgorder-");
        sc.setSortBy(io.github.mocchikon.hentie.dto.SortBy.PAGE_NUM);
        sc.setSortDir(dir);
        return searchService.search(sc).getContent();
    }

    private List<String> captions(List<CardDto> cards)
    {
        return cards.stream().map(CardDto::getCaption).toList();
    }

    /** {@code n} page files of 1 byte each, so a chapter's page count is exactly {@code n}. */
    private String[] page(int n)
    {
        String[] contents = new String[n];
        java.util.Arrays.fill(contents, "x");
        return contents;
    }

    /**
     * Writes pages the way the scraper does: straight into the folder, leaving the stats stale and the
     * cached listing <b>not</b> evicted, which the scraper cannot do either.
     */
    private void writePages(int chapterId, String... contents)
    {
        try
        {
            Path dir = imageDirectory.chapterDir(chapterId);
            Files.createDirectories(dir);
            for (int i = 0; i < contents.length; i++)
            {
                Files.writeString(dir.resolve((i + 1) + ".jpg"), contents[i], StandardCharsets.UTF_8);
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException("could not seed page files", e);
        }
    }

    /** One more page, written the way the scraper adds one. */
    private void writePage(int chapterId, int pageNumber, String content)
    {
        try
        {
            Files.writeString(imageDirectory.chapterDir(chapterId).resolve(pageNumber + ".jpg"),
                    content, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("could not seed page file", e);
        }
    }

    private ChapterForm form(String titleFull)
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
}

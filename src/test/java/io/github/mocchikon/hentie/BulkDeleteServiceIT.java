package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.dto.SearchType;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search-page bulk deletes and their confirmation counts; a series always goes with its chapters. The
 * search-count cache is cleared after each test because it outlives the rolled-back transaction.
 */
@SpringBootTest
@Transactional
class BulkDeleteServiceIT
{
    @Autowired BulkDeleteService bulkDeleteService;
    @Autowired SearchService searchService;
    @Autowired ChapterService chapterService;
    @Autowired SeriesService seriesService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired ImageDirectory imageDirectory;
    @Autowired CacheManager cacheManager;
    @PersistenceContext EntityManager em;

    @AfterEach
    void clearSearchCounts()
    {
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();
    }

    @Test
    void shouldDeleteOnlyTheChaptersTheSearchMatchesAndTheirImages()
    {
        // GIVEN two matching NEW chapters, a same-titled one the status filter rules out, and an unrelated one
        int first = chapter("Bulkdelq Match one", Status.NEW);
        int second = chapter("Bulkdelq Match two", Status.NEW);
        int reviewed = chapter("Bulkdelq Match three", Status.REVIEWED);
        int unrelated = chapter("Bulkdelq Other", Status.NEW);
        Path firstPages = page(first);
        SearchCriteria criteria = chapters("Bulkdelq Match", Status.NEW);

        // WHEN
        DeletedCount deleted = bulkDeleteService.deleteMatching(criteria);
        em.flush();
        em.clear();

        // THEN exactly the two matches went, images included
        assertThat(deleted).isEqualTo(new DeletedCount(0, 2));
        assertThat(chapterRepository.findAllById(List.of(first, second))).isEmpty();
        assertThat(firstPages).doesNotExist();
        assertThat(chapterRepository.findAllById(List.of(reviewed, unrelated))).hasSize(2);
    }

    @Test
    void shouldDeleteTheMatchingSeriesTogetherWithTheirChapters()
    {
        // GIVEN a matching series holding two chapters, and a non-matching one holding a third
        int doomed = seriesWith("Bulkdels Doomed", "bds-c1", "bds-c2");
        int kept = seriesWith("Bulkdels Kept", "bds-c3");
        List<Integer> doomedChapters = chapterIdsOf(doomed);
        List<Integer> keptChapters = chapterIdsOf(kept);

        // WHEN
        DeletedCount deleted = bulkDeleteService.deleteMatching(series("Bulkdels Doomed"));
        em.flush();
        em.clear();

        // THEN the series and both of its chapters are gone; the other series is untouched
        assertThat(deleted).isEqualTo(new DeletedCount(1, 2));
        assertThat(seriesRepository.findById(doomed)).isEmpty();
        assertThat(chapterRepository.findAllById(doomedChapters)).isEmpty();
        assertThat(seriesRepository.findById(kept)).isPresent();
        assertThat(chapterRepository.findAllById(keptChapters)).hasSize(1);
    }

    @Test
    void shouldDeleteOnlyTheGivenIdsAndSkipOnesThatNoLongerExist()
    {
        // GIVEN
        int doomed = chapter("Bulkdeli Doomed", Status.NEW);
        int kept = chapter("Bulkdeli Kept", Status.NEW);

        // WHEN a page posts one real id and one that is already gone
        DeletedCount deleted = bulkDeleteService.delete(SearchType.CHAPTER, List.of(doomed, Integer.MAX_VALUE));
        em.flush();
        em.clear();

        // THEN
        assertThat(deleted).isEqualTo(new DeletedCount(0, 1));
        assertThat(chapterRepository.findById(doomed)).isEmpty();
        assertThat(chapterRepository.findById(kept)).isPresent();
    }

    @Test
    void shouldPreviewTheSearchCountForAChapterSearch()
    {
        // GIVEN
        chapter("Bulkdelp Chapter one", Status.NEW);
        chapter("Bulkdelp Chapter two", Status.NEW);
        SearchCriteria criteria = chapters("Bulkdelp Chapter", null);

        // WHEN
        BulkDeleteService.Preview preview = bulkDeleteService.preview(criteria);

        // THEN it names the same number the results page shows, and chapters is that number too
        assertThat(preview).isEqualTo(new BulkDeleteService.Preview(2, 2));
        assertThat(preview.items()).isEqualTo(searchService.search(criteria).getTotalElements());
    }

    @Test
    void shouldPreviewTheChaptersInsideTheMatchingSeriesOnly()
    {
        // GIVEN two matching series (three chapters), one ruled out by status, and an unrelated one
        seriesWith("Bulkdelc Series one", Status.NEW, "bdc-c1", "bdc-c2");
        seriesWith("Bulkdelc Series two", Status.NEW, "bdc-c3");
        seriesWith("Bulkdelc Series three", Status.REVIEWED, "bdc-c4");
        seriesWith("Bulkdelc Unrelated", Status.NEW, "bdc-c5");
        SearchCriteria criteria = series("Bulkdelc Series");
        criteria.getStatuses().add(Status.NEW);

        // WHEN
        BulkDeleteService.Preview preview = bulkDeleteService.preview(criteria);

        // THEN
        assertThat(preview).isEqualTo(new BulkDeleteService.Preview(2, 3));
    }

    @Test
    void shouldPreviewOnlyTheIdsThatStillExistForAPage()
    {
        // GIVEN
        int one = seriesWith("Bulkdelx One", "bdx-c1", "bdx-c2");
        int two = seriesWith("Bulkdelx Two", "bdx-c3");

        // WHEN a page sends both, plus one that has meanwhile gone
        BulkDeleteService.Preview preview =
                bulkDeleteService.preview(SearchType.SERIES, List.of(one, two, Integer.MAX_VALUE));

        // THEN
        assertThat(preview).isEqualTo(new BulkDeleteService.Preview(2, 3));
        assertThat(bulkDeleteService.preview(SearchType.CHAPTER, List.of())).isEqualTo(new BulkDeleteService.Preview(0, 0));
    }

    @Test
    void shouldWalkTheMatchingIdsInAscendingOrderAfterTheCursor()
    {
        // GIVEN
        int first = chapter("Bulkdelk Walk one", Status.NEW);
        int second = chapter("Bulkdelk Walk two", Status.NEW);
        int third = chapter("Bulkdelk Walk three", Status.NEW);
        SearchCriteria criteria = chapters("Bulkdelk Walk", null);

        // WHEN / THEN each step starts strictly after the last id it returned, capped at the limit
        assertThat(searchService.idsAfter(criteria, 0, 2)).containsExactly(first, second);
        assertThat(searchService.idsAfter(criteria, second, 2)).containsExactly(third);
        assertThat(searchService.idsAfter(criteria, third, 2)).isEmpty();
    }

    private SearchCriteria chapters(String title, Status status)
    {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setTitle(title);
        if (status != null)
        {
            criteria.getStatuses().add(status);
        }
        return criteria;
    }

    private SearchCriteria series(String title)
    {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setType(SearchType.SERIES);
        criteria.setTitle(title);
        return criteria;
    }

    @Test
    void shouldCancelTheQueuedDownloadsOfTheChaptersItDeletes()
    {
        // GIVEN a pending and a failed queue row for two chapters, and a kept chapter with a pending row
        int pending = chapter("Bulkdelqueue Pending", "bdq-1");
        int failed = chapter("Bulkdelqueue Failed", "bdq-2");
        chapter("Bulkdelqueue Kept", "bdq-3");
        queued("bdq-1", null);
        queued("bdq-2", "Could not fetch page 2");
        queued("bdq-3", null);

        // WHEN the first two are deleted
        DeletedCount deleted = bulkDeleteService.delete(SearchType.CHAPTER, List.of(pending, failed));
        em.flush();
        em.clear();

        // THEN their downloads went too, so no retry re-imports a deleted gallery; the kept row stays
        assertThat(deleted).isEqualTo(new DeletedCount(0, 2));
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("bdq-1")).isEmpty();
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("bdq-2")).isEmpty();
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("bdq-3")).isPresent();
    }

    @Test
    void shouldCancelTheQueuedDownloadsOfTheChaptersASeriesTakesWithIt()
    {
        // GIVEN a series whose chapter still has a pending download, and an unrelated pending download
        var form = new SeriesForm();
        form.setTitleFull("Bulkdelqueue Series");
        form.setStatus(Status.REVIEWED);
        form.getChapterIds().add(chapter("Bulkdelqueue In series", "bdq-4"));
        int series = seriesService.create(form);
        queued("bdq-4", null);
        queued("bdq-5", null);

        // WHEN the series is deleted with its chapters
        DeletedCount deleted = bulkDeleteService.delete(SearchType.SERIES, List.of(series));
        em.flush();
        em.clear();

        // THEN the chapter's download went too, and only that one
        assertThat(deleted).isEqualTo(new DeletedCount(1, 1));
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("bdq-4")).isEmpty();
        assertThat(queueRepository.findFirstByGalleryIdOrderByIdAsc("bdq-5")).isPresent();
    }

    private int chapter(String titleFull, String galleryId)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        form.setStatus(Status.NEW);
        form.setGalleryId(galleryId);
        return chapterService.create(form);
    }

    private void queued(String galleryId, String error)
    {
        var item = TestDownloads.queueItem("link:" + galleryId, galleryId);
        item.setError(error);
        queueRepository.save(item);
    }

    private int chapter(String titleFull, Status status)
    {
        ChapterForm form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        form.setStatus(status);
        return chapterService.create(form);
    }

    private int seriesWith(String titleFull, String... chapterTitles)
    {
        return seriesWith(titleFull, Status.REVIEWED, chapterTitles);
    }

    private int seriesWith(String titleFull, Status status, String... chapterTitles)
    {
        SeriesForm form = new SeriesForm();
        form.setTitleFull(titleFull);
        form.setStatus(status);
        for (String chapterTitle : chapterTitles)
        {
            form.getChapterIds().add(chapter(chapterTitle, Status.NEW));
        }
        return seriesService.create(form);
    }

    private List<Integer> chapterIdsOf(int seriesId)
    {
        return chapterRepository.findBySeriesId(seriesId).stream().map(c -> c.getId()).toList();
    }

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
}

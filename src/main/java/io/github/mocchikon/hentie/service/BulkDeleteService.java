package io.github.mocchikon.hentie.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.dto.SearchType;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;

/**
 * The search results page's bulk deletes. A series is always deleted with its chapters: these buttons
 * exist to get rid of galleries, and deleting only the series would leave every one of them behind.
 * <p>
 * Deliberately not {@code @Transactional}: it hands committed slices to the transactional deletes, so a
 * huge delete never holds SQLite's write lock for long and the app stays usable in another tab.
 * <p>
 * <b>A slice is measured in chapters</b>, since each costs its rows and its folder of pages, and a write waiting in
 * another tab waits for one slice: 50 chapters took under a second at 1.5M chapters, 200 took two to four.
 */
@Service
@RequiredArgsConstructor
public class BulkDeleteService
{
    /**
     * Far larger than a delete slice, because every read re-evaluates the whole filter (and may sort every
     * remaining match). A million matches cost 50 reads, and the ids held stay well under a megabyte.
     */
    private static final int READ_BATCH = 20_000;
    private static final int CHAPTER_SLICE = 50;
    /** Also a cap on series without many chapters: a series has rows of its own to delete. */
    private static final int SERIES_SLICE = 20;
    /** Series ids per chapter count query, well within SQLite's bind limit. */
    private static final int COUNT_BATCH = 500;

    private static final String ACTIVITY = "deleting search results";

    private static final Logger log = LoggerFactory.getLogger(BulkDeleteService.class);

    private final SearchService searchService;
    private final ChapterService chapterService;
    private final SeriesService seriesService;
    private final ChapterRepository chapterRepository;
    private final SeriesRepository seriesRepository;
    private final WriteGate writeGate;

    /** For a chapter search {@code chapters} equals {@code items}. */
    public record Preview(long items, long chapters)
    {
    }

    /** Uses the cached search count, so it agrees with the number the results page shows. */
    public Preview preview(SearchCriteria criteria)
    {
        long items = searchService.count(criteria);
        if (!criteria.isSeries())
        {
            return new Preview(items, items);
        }
        return new Preview(items, items == 0 ? 0 : searchService.chapterCountInMatchingSeries(criteria));
    }

    /** Counts the rows that still exist, not merely the ids the page sent. */
    public Preview preview(SearchType type, List<Integer> ids)
    {
        if (ids.isEmpty())
        {
            return new Preview(0, 0);
        }
        if (type == SearchType.SERIES)
        {
            return new Preview(seriesRepository.countByIdIn(ids), chapterRepository.countBySeriesIdIn(ids));
        }
        long items = chapterRepository.countByIdIn(ids);
        return new Preview(items, items);
    }

    /**
     * Walks by an id keyset rather than re-reading page one, so a row a slice failed to delete cannot loop
     * the walk. Logged at the start because that line is the only sign the run is under way.
     */
    public DeletedCount deleteMatching(SearchCriteria criteria)
    {
        return writeGate.background(ACTIVITY, () -> deleteInSlices(criteria));
    }

    private DeletedCount deleteInSlices(SearchCriteria criteria)
    {
        log.info("Delete all matching ({}): starting - {}", criteria.getType(), criteria.filterKey());
        long startedAt = System.nanoTime();

        var total = new DeletedCount(0, 0);
        int afterId = 0;
        while (true)
        {
            List<Integer> ids = searchService.idsAfter(criteria, afterId, READ_BATCH);
            if (ids.isEmpty())
            {
                var took = DurationFormatUtils.formatDurationHMS(
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                log.info("Delete all matching ({}): finished - {} series, {} chapter(s) deleted, took {}",
                        criteria.getType(), total.series(), total.chapters(), took);
                return total;
            }
            afterId = ids.getLast();
            total = total.plus(deleteSlices(criteria.getType(), ids));
        }
    }

    /** Waits for its turn as long as it takes, like "Delete all matching": both are long-running forms. */
    public DeletedCount delete(SearchType type, List<Integer> ids)
    {
        return writeGate.background(ACTIVITY, () -> deleteSlices(type, ids));
    }

    private DeletedCount deleteSlices(SearchType type, List<Integer> ids)
    {
        var total = new DeletedCount(0, 0);
        if (type == SearchType.SERIES)
        {
            for (List<Integer> part : seriesSlices(ids))
            {
                total = total.plus(seriesService.deleteWithChapters(part));
            }
            return total;
        }
        for (int from = 0; from < ids.size(); from += CHAPTER_SLICE)
        {
            List<Integer> part = ids.subList(from, Math.min(from + CHAPTER_SLICE, ids.size()));
            total = total.plus(new DeletedCount(0, chapterService.deleteAll(part)));
        }
        return total;
    }

    /** A series with more chapters than a slice holds goes in a slice of its own. */
    private List<List<Integer>> seriesSlices(List<Integer> ids)
    {
        var chapters = new HashMap<Integer, Long>();
        for (int from = 0; from < ids.size(); from += COUNT_BATCH)
        {
            chapters.putAll(chapterRepository.chapterCountsBySeriesIds(
                    ids.subList(from, Math.min(from + COUNT_BATCH, ids.size()))));
        }
        var slices = new ArrayList<List<Integer>>();
        var slice = new ArrayList<Integer>();
        var chaptersInSlice = 0L;
        for (Integer id : ids)
        {
            long count = chapters.getOrDefault(id, 0L);
            if (!slice.isEmpty() && (chaptersInSlice + count > CHAPTER_SLICE || slice.size() == SERIES_SLICE))
            {
                slices.add(slice);
                slice = new ArrayList<>();
                chaptersInSlice = 0;
            }
            slice.add(id);
            chaptersInSlice += count;
        }
        if (!slice.isEmpty())
        {
            slices.add(slice);
        }
        return slices;
    }
}

package io.github.mocchikon.hentie.service.match;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.match.ChapterMatchingService.Outcome;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manage -&gt; Library maintenance -&gt; Match chapters. Walks a keyset in slices, each committed on its own
 * transaction, so no transaction holds the write lock for long.
 *
 * <p>Walking in {@code (match_key, id)} order is the algorithm: a base key sorts before every key extending
 * it, so a family arrives together, base first. The base creates the series and its siblings match it. No
 * clustering pass is needed.
 *
 * <p>Keys are backfilled first: a row without a {@code match_key} is invisible to matching.
 */
@Service
@RequiredArgsConstructor
public class ChapterMatchingSweep
{
    /** Chapters per transaction. */
    private static final int BATCH_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(ChapterMatchingSweep.class);

    private final ChapterRepository chapterRepository;
    private final SeriesRepository seriesRepository;
    private final ChapterMatchingService matchingService;
    private final WriteGate writeGate;

    @Getter
    public static class Result
    {
        private int linked;
        private int created;
        private int scanned;

        public int getMatched()
        {
            return linked + created;
        }

        private void add(Map<Outcome, Integer> counts)
        {
            linked += counts.getOrDefault(Outcome.LINKED, 0);
            created += counts.getOrDefault(Outcome.CREATED, 0);
            scanned += counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * Unbounded and not a background job, on purpose. Slices commit on their own, so the user can keep
     * using the app in another tab. A cap would only make the user click again, and a job framework is not
     * worth it in a single-user local app.
     */
    public Result matchUnlinked()
    {
        return writeGate.background("matching chapters to series", this::matchInSlices);
    }

    private Result matchInSlices()
    {
        log.info("Match chapters: starting");
        long startedAt = System.nanoTime();

        backfillKeys();

        var slice = PageRequest.of(0, BATCH_SIZE);
        var result = new Result();
        String afterKey = "";
        int afterId = 0;

        while (true)
        {
            // [id, match_key] only: the worker loads the entities itself.
            List<Object[]> rows = chapterRepository.findUnlinkedAfter(afterKey, afterId, slice);
            if (rows.isEmpty())
            {
                var took = DurationFormatUtils.formatDurationHMS(
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                log.info("Match chapters: finished - {} scanned, {} linked to an existing series, "
                                + "{} new series, took {}",
                        result.getScanned(), result.getLinked(), result.getCreated(), took);
                return result;
            }
            Object[] last = rows.getLast();
            afterId = ((Number) last[0]).intValue();
            afterKey = (String) last[1];

            result.add(matchingService.matchBatch(rows.stream().map(row -> ((Number) row[0]).intValue()).toList()));
        }
    }

    private void backfillKeys()
    {
        var slice = PageRequest.of(0, BATCH_SIZE);
        int chapters = 0;
        int series = 0;
        int afterId = 0;
        while (true)
        {
            List<Integer> ids = chapterRepository.findIdsWithoutMatchKeyAfter(afterId, slice);
            if (ids.isEmpty())
            {
                break;
            }
            afterId = ids.getLast();
            chapters += ids.size();
            matchingService.backfillChapterKeys(ids);
        }

        afterId = 0;
        while (true)
        {
            List<Integer> ids = seriesRepository.findIdsWithoutMatchKeyAfter(afterId, slice);
            if (ids.isEmpty())
            {
                break;
            }
            afterId = ids.getLast();
            series += ids.size();
            matchingService.backfillSeriesKeys(ids);
        }
        // On an imported library this pass can take minutes; without this line the sweep looks stuck.
        if (chapters > 0 || series > 0)
        {
            log.info("Match chapters: backfilled matching keys for {} chapter(s) and {} series", chapters, series);
        }
    }
}

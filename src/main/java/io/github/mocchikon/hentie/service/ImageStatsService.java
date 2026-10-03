package io.github.mocchikon.hentie.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.service.ChapterService.StatsSync;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ImageStatsService
{
    /**
     * Chapters per transaction. Each slice scans its chapters' folders while it holds the write lock (about 1 ms a
     * folder, many times that on a cold disk), and a request that wants to write waits for one slice.
     */
    private static final int BATCH_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(ImageStatsService.class);

    private final ChapterRepository chapterRepository;
    private final ChapterService chapterService;
    private final ImageService imageService;
    private final WriteGate writeGate;

    /**
     * Returns how many chapters had drifted stats. Logged at both ends because on a large library this runs
     * for a long time on the request thread, and the log is the only sign it is under way.
     */
    public int resyncAll()
    {
        return writeGate.background("recalculating page counts and sizes", this::resyncSlices);
    }

    private int resyncSlices()
    {
        log.info("Recalculate page counts & sizes: starting");
        long startedAt = System.nanoTime();

        PageRequest slice = PageRequest.of(0, BATCH_SIZE);
        int repaired = 0;
        int scanned = 0;
        int afterId = 0;
        while (true)
        {
            List<Integer> ids = chapterRepository.findIdsAfter(afterId, slice);
            if (ids.isEmpty())
            {
                var took = DurationFormatUtils.formatDurationHMS(
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                log.info("Recalculate page counts & sizes: finished - {} chapter(s) scanned, {} repaired, took {}",
                        scanned, repaired, took);
                return repaired;
            }
            afterId = ids.get(ids.size() - 1);
            scanned += ids.size();
            repaired += chapterService.syncImageStats(ids);
        }
    }

    /**
     * The detail page's and the reader's repair of stats for images the app did not write (the scraper, imported
     * rows on {@code DEFAULT 0}). <b>The caller passes the stored count it has already read</b>, so a chapter in
     * sync costs no query of its own, and only a mismatch pays the scan.
     * <p>
     * <b>Skipped while the library is busy</b>: a page view must never wait for the write lock, and the next view
     * tries again. Until then the page shows the stored numbers, the ones search filters on.
     * <p>
     * <b>Page count only</b>: checking {@code disk_size} would cost a {@code stat} per file on every view. That stays
     * the job of the explicit rescans.
     *
     * @param storedPageNum the chapter's {@code page_num} as the caller read it
     * @return whether what the caller read is out of date: the stats were repaired, or the cached listing was wrong.
     *         False for a folder that could not be read, whose stored numbers are kept.
     */
    public boolean healIfDrifted(int chapterId, Integer storedPageNum)
    {
        return healIfDrifted(chapterId, storedPageNum, imageService.pageUrls(chapterId).size());
    }

    /** @param listedPages the size of the listing the caller rendered, which is checked instead of read again */
    public boolean healIfDrifted(int chapterId, Integer storedPageNum, int listedPages)
    {
        if (storedPageNum != null && storedPageNum == listedPages)
        {
            return false;
        }
        Optional<StatsSync> outcome = writeGate.ifFree(() -> chapterService.syncImageStats(chapterId));
        if (outcome.isEmpty())
        {
            log.info("Chapter {}: stored page count {} disagrees with the {} page file(s) listed - left for a later "
                    + "view, the library is busy", chapterId, storedPageNum, listedPages);
            return false;
        }
        switch (outcome.get())
        {
            // The resync logged why. Reading again would give the caller the numbers it has.
            case UNREADABLE ->
            {
                return false;
            }
            case REPAIRED -> log.info("Chapter {}: stored page count {} disagreed with the {} page file(s) listed - "
                    + "repaired", chapterId, storedPageNum, listedPages);
            case IN_SYNC -> log.info("Chapter {}: the cached listing of {} page file(s) disagreed with the stored page "
                    + "count {}, which matches the disk - listing dropped", chapterId, listedPages, storedPageNum);
        }
        // Not left to the repair, which evicts only when it writes: when the stored columns matched the disk, the
        // cached listing was what was wrong.
        imageService.invalidateListing(chapterId);
        return true;
    }
}

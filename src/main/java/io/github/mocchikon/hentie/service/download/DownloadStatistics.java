package io.github.mocchikon.hentie.service.download;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * How fast downloads go, for the queue page. <b>Kept in memory only</b>: a speed says something about this run (the
 * sites' moods, the mode, the machine), not about the library, so a restart starts over.
 *
 * <p>Every rate is per second of the time spent on that work, never of wall-clock time: an idle queue would
 * otherwise make the downloads look slow. Pages are counted where each step ends, so the same page counts once per
 * step: downloaded when its bytes are here, processed when its encoder is done, saved when it is in its chapter's
 * folder (for a replacement, when the pages are published).
 *
 * <p>Two spans: the last {@link #RECENT} (how fast it goes now) and everything since the app started. The recent
 * one is kept in buckets, so it costs the same however long the app runs.
 */
@Component
public class DownloadStatistics
{
    /** Long enough to average out one slow gallery, short enough to show a site starting to throttle. */
    static final Duration RECENT = Duration.ofMinutes(10);
    private static final int BUCKETS = 60;
    private static final long BUCKET_MILLIS = RECENT.toMillis() / BUCKETS;

    private final LongSupplier clock;
    /** Whole attempts that fetch pages, metadata included: what a page costs from start to finish. */
    private final Meter saved = new Meter();
    private final Meter downloaded = new Meter();
    /** Busy while any page is with an encoder; encoders run in parallel, so this is throughput, not page time. */
    private final Meter processed = new Meter();
    /** One event per finished chapter, with its attempt's whole duration. */
    private final Meter chapters = new Meter();

    public DownloadStatistics()
    {
        // Monotonic: a clock change must not make a page take minus an hour.
        this(() -> System.nanoTime() / 1_000_000);
    }

    DownloadStatistics(LongSupplier clockMillis)
    {
        this.clock = clockMillis;
    }

    /** A point in time for {@link #startSaving} and {@link #chapterSaved}. */
    public long now()
    {
        return clock.getAsLong();
    }

    /**
     * @param attemptStarted when the attempt began, so the time spent on its metadata counts too; told only once
     *                       it is known the attempt fetches pages, so an attempt that skips its gallery does not
     *                       count as a slow one
     */
    public void startSaving(long attemptStarted)
    {
        saved.start(attemptStarted, now());
    }

    public void stopSaving()
    {
        saved.stop(now());
    }

    public void pagesSaved(int pages)
    {
        saved.events(now(), pages);
    }

    public void chapterSaved(long attemptStarted)
    {
        long now = now();
        chapters.timed(now, Math.max(0, now - attemptStarted));
    }

    public void startDownloading()
    {
        downloaded.start(now(), now());
    }

    public void stopDownloading()
    {
        downloaded.stop(now());
    }

    public void pageDownloaded()
    {
        downloaded.events(now(), 1);
    }

    /** Must be matched by {@link #pageProcessed} or {@link #processingGivenUp}, or processing never looks idle. */
    public void startProcessing()
    {
        processed.start(now(), now());
    }

    public void pageProcessed()
    {
        long now = now();
        processed.events(now, 1);
        processed.stop(now);
    }

    /** Pages whose processing ended without an answer (a task that died): their time stops, no page counts. */
    public void processingGivenUp(int pages)
    {
        for (int i = 0; i < pages; i++)
        {
            processed.stop(now());
        }
    }

    public Snapshot snapshot()
    {
        long now = now();
        return new Snapshot(figures(now, true), figures(now, false));
    }

    private Figures figures(long now, boolean recent)
    {
        Totals savedPages = saved.totals(now, recent);
        Totals chapterTimes = chapters.totals(now, recent);
        return new Figures(savedPages.perSecond(), downloaded.totals(now, recent).perSecond(),
                processed.totals(now, recent).perSecond(), chapterTimes.secondsEach(), savedPages.events(),
                chapterTimes.events());
    }

    /** @param recent over the last {@link #RECENT} */
    public record Snapshot(Figures recent, Figures sinceStart)
    {
        public long recentMinutes()
        {
            return RECENT.toMinutes();
        }
    }

    /**
     * Each value is null while nothing was measured.
     *
     * @param pagesSaved    what the averages rest on
     * @param chaptersSaved what the seconds per chapter rest on
     */
    public record Figures(Double savedPagesPerSecond, Double downloadedPagesPerSecond,
                          Double processedPagesPerSecond, Double secondsPerChapter, long pagesSaved,
                          long chaptersSaved)
    {
        public boolean isEmpty()
        {
            return savedPagesPerSecond == null && downloadedPagesPerSecond == null
                    && processedPagesPerSecond == null && secondsPerChapter == null;
        }
    }

    private record Totals(long events, long millis)
    {
        Double perSecond()
        {
            return millis <= 0 ? null : events * 1000.0 / millis;
        }

        Double secondsEach()
        {
            return events <= 0 ? null : millis / 1000.0 / events;
        }
    }

    /**
     * Events over busy time. Busy time is added as it passes, at every {@link #stop} and read, so a long download
     * still running counts in the recent span and a page arriving inside it is never weighed against no time.
     * {@code active} counts overlapping starts: the time between counts once.
     */
    private static final class Meter
    {
        private final long[] bucketOf = new long[BUCKETS];
        private final long[] bucketEvents = new long[BUCKETS];
        private final long[] bucketMillis = new long[BUCKETS];
        private long events;
        private long millis;
        private int active;
        /** Busy time before it has been added. */
        private long mark;

        Meter()
        {
            Arrays.fill(bucketOf, Long.MIN_VALUE);
        }

        synchronized void start(long since, long now)
        {
            if (active == 0)
            {
                mark = Math.min(since, now);
            }
            else
            {
                flush(now);
            }
            active++;
        }

        synchronized void stop(long now)
        {
            flush(now);
            active = Math.max(0, active - 1);
        }

        synchronized void events(long now, int count)
        {
            events += count;
            add(Math.floorDiv(now, BUCKET_MILLIS), count, 0);
        }

        /** An event that took {@code took}, all counted when it ended. */
        synchronized void timed(long now, long took)
        {
            events++;
            millis += took;
            add(Math.floorDiv(now, BUCKET_MILLIS), 1, took);
        }

        synchronized Totals totals(long now, boolean recent)
        {
            flush(now);
            if (!recent)
            {
                return new Totals(events, millis);
            }
            long current = Math.floorDiv(now, BUCKET_MILLIS);
            long sumEvents = 0;
            long sumMillis = 0;
            for (int slot = 0; slot < BUCKETS; slot++)
            {
                if (bucketOf[slot] > current - BUCKETS && bucketOf[slot] <= current)
                {
                    sumEvents += bucketEvents[slot];
                    sumMillis += bucketMillis[slot];
                }
            }
            return new Totals(sumEvents, sumMillis);
        }

        private void flush(long now)
        {
            if (active == 0 || now <= mark)
            {
                return;
            }
            millis += now - mark;
            // Split over the buckets it spans; older ones than the ring holds would be overwritten anyway.
            long last = Math.floorDiv(now - 1, BUCKET_MILLIS);
            long first = Math.max(Math.floorDiv(mark, BUCKET_MILLIS), last - BUCKETS + 1);
            for (long bucket = first; bucket <= last; bucket++)
            {
                long from = Math.max(mark, bucket * BUCKET_MILLIS);
                long to = Math.min(now, (bucket + 1) * BUCKET_MILLIS);
                add(bucket, 0, to - from);
            }
            mark = now;
        }

        private void add(long bucket, long count, long took)
        {
            int slot = (int) Math.floorMod(bucket, (long) BUCKETS);
            if (bucketOf[slot] > bucket)
            {
                // Read from the clock before another thread's later moment: already out of the span.
                return;
            }
            if (bucketOf[slot] != bucket)
            {
                bucketOf[slot] = bucket;
                bucketEvents[slot] = 0;
                bucketMillis[slot] = 0;
            }
            bucketEvents[slot] += count;
            bucketMillis[slot] += took;
        }
    }
}

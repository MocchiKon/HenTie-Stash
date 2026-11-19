package io.github.mocchikon.hentie.service.download;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DownloadStatisticsTest
{
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final DownloadStatistics statistics = new DownloadStatistics(clock::get);

    private void pass(long millis)
    {
        clock.addAndGet(millis);
    }

    @Test
    void shouldMeasureNothingBeforeTheFirstDownload()
    {
        // WHEN
        DownloadStatistics.Snapshot snapshot = statistics.snapshot();

        // THEN
        assertThat(snapshot.recent().isEmpty()).isTrue();
        assertThat(snapshot.sinceStart().isEmpty()).isTrue();
    }

    @Test
    void shouldAverageSavedPagesOverTheWholeAttemptAndChaptersOverTheirDuration()
    {
        // GIVEN an attempt that spent 2 s on metadata, then saved 4 pages in 2 s.
        long started = statistics.now();
        pass(2_000);
        statistics.startSaving(started);
        pass(2_000);
        statistics.pagesSaved(4);
        statistics.chapterSaved(started);
        statistics.stopSaving();

        // WHEN
        DownloadStatistics.Figures figures = statistics.snapshot().sinceStart();

        // THEN the metadata time counts too: 4 pages in 4 s.
        assertThat(figures.savedPagesPerSecond()).isCloseTo(1.0, within(1e-9));
        assertThat(figures.secondsPerChapter()).isCloseTo(4.0, within(1e-9));
        assertThat(figures.pagesSaved()).isEqualTo(4);
        assertThat(figures.chaptersSaved()).isEqualTo(1);
    }

    @Test
    void shouldNotCountIdleTimeBetweenDownloads()
    {
        // GIVEN two downloads of 1 s, 2 pages each, an hour apart.
        download(2, 1_000);
        pass(3_600_000);
        download(2, 1_000);

        // WHEN
        DownloadStatistics.Snapshot snapshot = statistics.snapshot();

        // THEN since start: 4 pages in 2 s of downloading; the last 10 minutes hold only the second one.
        assertThat(snapshot.sinceStart().downloadedPagesPerSecond()).isCloseTo(2.0, within(1e-9));
        assertThat(snapshot.recent().downloadedPagesPerSecond()).isCloseTo(2.0, within(1e-9));
        assertThat(snapshot.sinceStart().pagesSaved()).isZero();
    }

    @Test
    void shouldForgetTheRecentSpanButKeepTheTotalWhenNothingRanForLongerThanIt()
    {
        // GIVEN
        download(3, 1_000);
        pass(DownloadStatistics.RECENT.toMillis() + 1_000);

        // WHEN
        DownloadStatistics.Snapshot snapshot = statistics.snapshot();

        // THEN
        assertThat(snapshot.recent().downloadedPagesPerSecond()).isNull();
        assertThat(snapshot.sinceStart().downloadedPagesPerSecond()).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void shouldCountTheTimeOfADownloadStillRunning()
    {
        // GIVEN a download running for 4 s that has one page so far.
        statistics.startDownloading();
        pass(4_000);
        statistics.pageDownloaded();

        // WHEN
        DownloadStatistics.Figures figures = statistics.snapshot().recent();

        // THEN the page is weighed against the 4 s, not against nothing.
        assertThat(figures.downloadedPagesPerSecond()).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void shouldCountParallelProcessingTimeOnceAsThroughput()
    {
        // GIVEN two pages compressed at the same time, each taking 2 s.
        statistics.startProcessing();
        statistics.startProcessing();
        pass(2_000);
        statistics.pageProcessed();
        statistics.pageProcessed();

        // WHEN
        DownloadStatistics.Figures figures = statistics.snapshot().sinceStart();

        // THEN 2 pages in 2 s, not in 4.
        assertThat(figures.processedPagesPerSecond()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldStopCountingProcessingTimeForPagesGivenUp()
    {
        // GIVEN a page whose task died without an answer.
        statistics.startProcessing();
        pass(1_000);
        statistics.processingGivenUp(1);
        double before = statistics.snapshot().sinceStart().processedPagesPerSecond();

        // WHEN an hour passes
        pass(3_600_000);

        // THEN processing stays idle: no time is added.
        assertThat(before).isZero();
        assertThat(statistics.snapshot().sinceStart().processedPagesPerSecond()).isZero();
        statistics.startProcessing();
        pass(1_000);
        statistics.pageProcessed();
        assertThat(statistics.snapshot().sinceStart().processedPagesPerSecond()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void shouldSpreadALongDownloadOverTheBucketsItSpans()
    {
        // GIVEN a 20-minute download with 1200 pages, all counted at its end.
        statistics.startDownloading();
        pass(20 * 60_000);
        for (int i = 0; i < 1200; i++)
        {
            statistics.pageDownloaded();
        }
        statistics.stopDownloading();

        // WHEN
        DownloadStatistics.Snapshot snapshot = statistics.snapshot();

        // THEN only the last 10 minutes of its time are in the recent span.
        assertThat(snapshot.sinceStart().downloadedPagesPerSecond()).isCloseTo(1.0, within(1e-9));
        assertThat(snapshot.recent().downloadedPagesPerSecond()).isGreaterThan(1.9).isLessThan(2.1);
    }

    private void download(int pages, long millis)
    {
        statistics.startDownloading();
        pass(millis);
        for (int i = 0; i < pages; i++)
        {
            statistics.pageDownloaded();
        }
        statistics.stopDownloading();
    }
}

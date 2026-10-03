package io.github.mocchikon.hentie.scrapper;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/** Timings with generous margins: a slow build machine may only make the waits longer. */
class RequestPacerTest
{
    @Test
    void shouldLetTheFirstRequestGoAtOnceAndSpaceTheNextOnes() throws InterruptedException
    {
        // GIVEN
        var pacer = new RequestPacer();
        long start = System.nanoTime();

        // WHEN
        pacer.await(Duration.ofMillis(100));
        long first = elapsedMillis(start);
        pacer.await(Duration.ofMillis(100));
        pacer.await(Duration.ofMillis(100));

        // THEN
        assertThat(first).isLessThan(80);
        assertThat(elapsedMillis(start)).isGreaterThanOrEqualTo(195);
    }

    /** The limit is the site's, whichever thread asks. */
    @Test
    void shouldSpaceRequestsFromSeveralThreads() throws InterruptedException
    {
        // GIVEN
        var pacer = new RequestPacer();
        var turns = new ConcurrentLinkedQueue<Long>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++)
        {
            threads.add(Thread.ofPlatform().start(() ->
            {
                try
                {
                    pacer.await(Duration.ofMillis(60));
                    turns.add(System.nanoTime());
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // WHEN
        for (Thread thread : threads)
        {
            thread.join();
        }

        // THEN the four turns span three intervals at least.
        List<Long> sorted = turns.stream().sorted().toList();
        assertThat(sorted).hasSize(4);
        assertThat((sorted.getLast() - sorted.getFirst()) / 1_000_000).isGreaterThanOrEqualTo(170);
    }

    /** A 429 slows down every caller of the endpoint, not only the one that was refused. */
    @Test
    void shouldHoldEveryoneBackWhenTheSiteAsksToWait() throws InterruptedException
    {
        // GIVEN
        var pacer = new RequestPacer();
        pacer.await(Duration.ZERO);
        long start = System.nanoTime();

        // WHEN
        pacer.holdOff(Duration.ofMillis(150));
        pacer.await(Duration.ZERO);

        // THEN
        assertThat(elapsedMillis(start)).isGreaterThanOrEqualTo(145);
    }

    @Test
    void shouldTurnALimitPerMinuteIntoTheGapBetweenRequests()
    {
        assertThat(RequestPacer.interval(20)).isEqualTo(Duration.ofSeconds(3));
        assertThat(RequestPacer.interval(15)).isEqualTo(Duration.ofSeconds(4));
        // Rounded up, so the limit is never exceeded.
        assertThat(RequestPacer.interval(45)).isEqualTo(Duration.ofMillis(1334));
        assertThat(RequestPacer.interval(0)).isEqualTo(Duration.ZERO);
    }

    private static long elapsedMillis(long startNanos)
    {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

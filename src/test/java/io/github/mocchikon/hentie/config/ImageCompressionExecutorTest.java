package io.github.mocchikon.hentie.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ImageCompressionExecutorTest
{
    @Test
    void shouldUseTheWholePoolAtOneHundredPercent()
    {
        assertThat(ImageCompressionExecutor.threadsFor(8, 100)).isEqualTo(8);
        assertThat(ImageCompressionExecutor.threadsFor(1, 100)).isEqualTo(1);
    }

    @Test
    void shouldScaleAndRoundUpToAtLeastOneThread()
    {
        assertThat(ImageCompressionExecutor.threadsFor(8, 50)).isEqualTo(4);
        assertThat(ImageCompressionExecutor.threadsFor(8, 25)).isEqualTo(2);
        assertThat(ImageCompressionExecutor.threadsFor(3, 50)).isEqualTo(2);
        assertThat(ImageCompressionExecutor.threadsFor(8, 1)).isEqualTo(1);
    }

    @Test
    void shouldClampAPercentageOutsideTheRange()
    {
        assertThat(ImageCompressionExecutor.threadsFor(8, 0)).isEqualTo(1);
        assertThat(ImageCompressionExecutor.threadsFor(8, 101)).isEqualTo(8);
    }

    @Test
    void shouldRunOnlyTheConfiguredShareOfTasksAtOnce() throws Exception
    {
        // GIVEN half of a four-thread pool, and two tasks holding their permits until released.
        var percent = new AtomicInteger(50);
        var executor = new ImageCompressionExecutor(4, percent::get, daemonThreads());
        try
        {
            var twoStarted = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var started = new AtomicInteger();
            List<Future<?>> futures = submitAll(executor, 4, () ->
            {
                started.incrementAndGet();
                twoStarted.countDown();
                release.await();
            });

            // THEN only two run; the other two wait for a permit.
            assertThat(twoStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(started).hasValue(2);

            release.countDown();
            for (Future<?> future : futures)
            {
                future.get(2, TimeUnit.SECONDS);
            }
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldApplyARaisedPercentageToTasksAlreadyQueued() throws Exception
    {
        // GIVEN one thread of a four-thread pool, with three tasks already queued.
        var percent = new AtomicInteger(25);
        var executor = new ImageCompressionExecutor(4, percent::get, daemonThreads());
        try
        {
            var allStarted = new CountDownLatch(4);
            var release = new CountDownLatch(1);
            List<Future<?>> futures = submitAll(executor, 4, () ->
            {
                allStarted.countDown();
                release.await();
            });
            assertThat(allStarted.await(1, TimeUnit.SECONDS)).isFalse();

            // WHEN the percentage is raised.
            percent.set(100);
            executor.refresh();

            // THEN the queued tasks start without a new submission.
            assertThat(allStarted.await(2, TimeUnit.SECONDS)).isTrue();

            release.countDown();
            for (Future<?> future : futures)
            {
                future.get(2, TimeUnit.SECONDS);
            }
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    /** A task that waits on the release latch, so a test controls when each permit comes back. */
    private static List<Future<?>> submitAll(ImageCompressionExecutor executor, int count, ThrowingRunnable task)
    {
        var futures = new ArrayList<Future<?>>();
        for (int i = 0; i < count; i++)
        {
            futures.add(executor.submit(() ->
            {
                task.run();
                return null;
            }));
        }
        return futures;
    }

    private static ThreadFactory daemonThreads()
    {
        return runnable ->
        {
            var thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        };
    }

    @FunctionalInterface
    private interface ThrowingRunnable
    {
        void run() throws InterruptedException;
    }
}

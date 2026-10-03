package io.github.mocchikon.hentie;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.mocchikon.hentie.service.compress.ImageCompressionService;

/**
 * Holds the compression run lock like a library sweep in another tab would.
 *
 * <p>It must be a real second thread: the lock is per thread, so on the test's own thread the calls under
 * test would re-enter it instead of being refused.
 */
public final class RunLockHolder implements AutoCloseable
{
    private static final long TIMEOUT_SECONDS = 10;

    private final CountDownLatch release = new CountDownLatch(1);
    private final Thread thread;

    private RunLockHolder(ImageCompressionService service) throws InterruptedException
    {
        var held = new CountDownLatch(1);
        thread = Thread.ofPlatform().name("run-lock-holder").start(() -> service.exclusively(() ->
        {
            held.countDown();
            awaitRelease();
            return null;
        }));
        if (!held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        {
            throw new IllegalStateException("The run lock could not be taken - is another run still holding it?");
        }
    }

    public static RunLockHolder hold(ImageCompressionService service) throws InterruptedException
    {
        return new RunLockHolder(service);
    }

    private void awaitRelease()
    {
        try
        {
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for the release, so the next test starts with the lock free. */
    @Override
    public void close() throws InterruptedException
    {
        release.countDown();
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    }
}

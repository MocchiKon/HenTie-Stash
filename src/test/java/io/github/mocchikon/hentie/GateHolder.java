package io.github.mocchikon.hentie;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.mocchikon.hentie.config.WriteGate;

/**
 * Holds the write gate inside a real write transaction, as a sweep's slice or the download worker would, until
 * closed. It must be a second thread: the gate is per thread, so on the test's own thread the writes under test
 * would join its transaction instead of waiting for it.
 */
public final class GateHolder implements AutoCloseable
{
    private static final long TIMEOUT_SECONDS = 10;

    private final CountDownLatch release = new CountDownLatch(1);
    private final Thread thread;

    private GateHolder(PlatformTransactionManager transactionManager, WriteGate writeGate, String activity)
            throws InterruptedException
    {
        var held = new CountDownLatch(1);
        var transactions = new TransactionTemplate(transactionManager);
        thread = Thread.ofPlatform().name("gate-holder").start(() -> writeGate.background(activity, () ->
                transactions.executeWithoutResult(status ->
                {
                    held.countDown();
                    awaitRelease();
                })));
        if (!held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        {
            throw new IllegalStateException("The write gate could not be taken - is another test still holding it?");
        }
    }

    /** @param activity what the holder is busy with, as busy messages name it */
    public static GateHolder hold(PlatformTransactionManager transactionManager, WriteGate writeGate, String activity)
            throws InterruptedException
    {
        return new GateHolder(transactionManager, writeGate, activity);
    }

    /** For a hold that ends on its own while the test waits for it. */
    public GateHolder releaseIn(Duration delay)
    {
        Thread.ofVirtual().start(() ->
        {
            try
            {
                Thread.sleep(delay);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        return this;
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

    /** Waits for the holder's transaction to end, so the next step starts with the gate free. */
    @Override
    public void close() throws InterruptedException
    {
        release.countDown();
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    }
}

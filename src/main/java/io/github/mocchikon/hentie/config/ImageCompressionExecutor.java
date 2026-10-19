package io.github.mocchikon.hentie.config;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Image Compression's executor. The pool is sized to the machine, while the Settings percentage limits how
 * many of its tasks may run at once.
 *
 * <p><b>A semaphore, not the pool's own size</b>: shrinking a {@link ThreadPoolExecutor} leaves its idle
 * threads taking queued tasks until they time out, so a lowered percentage would not take effect at once.
 * Reducing the permits (which may go negative) stops a queued task from starting until enough running ones
 * have finished.
 *
 * <p>Daemon threads, so unfinished pages never keep the JVM alive; an interrupted page just stays as it was.
 */
public class ImageCompressionExecutor extends ThreadPoolExecutor
{
    private final int maxThreads;
    private final IntSupplier percent;
    private final AdjustableSemaphore slots;

    /**
     * @param percent read again on every task and on {@link #refresh()}, so a Settings change needs no
     *                restart and no new bean
     */
    ImageCompressionExecutor(int maxThreads, IntSupplier percent, ThreadFactory threadFactory)
    {
        super(maxThreads, maxThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), threadFactory);
        this.maxThreads = maxThreads;
        this.percent = percent;
        this.slots = new AdjustableSemaphore(threadsFor(maxThreads, percent.getAsInt()));
    }

    /** Applies the current Settings percentage, also to tasks that are already queued. */
    public synchronized void refresh()
    {
        slots.setLimit(threadsFor(maxThreads, percent.getAsInt()));
    }

    /**
     * One image per permit. The permit is taken on a pool thread, never on the caller's: a download hands
     * over each page as it arrives and must not wait for another page to finish.
     */
    @Override
    public void execute(Runnable command)
    {
        refresh();
        super.execute(() ->
        {
            slots.acquireUninterruptibly();
            try
            {
                // Once more after the wait: a percentage raised while this task was queued wakes the others.
                refresh();
                command.run();
            }
            finally
            {
                slots.release();
            }
        });
    }

    /** At least one, so a tiny percentage cannot stop compression altogether. */
    static int threadsFor(int maxThreads, int percent)
    {
        int bounded = Math.clamp(percent, 1, 100);
        return Math.max(1, (int) Math.ceil(Math.max(1, maxThreads) * bounded / 100.0));
    }

    /** {@link Semaphore}'s permit count, changeable at run time. */
    private static final class AdjustableSemaphore extends Semaphore
    {
        private int limit;

        AdjustableSemaphore(int permits)
        {
            super(permits);
            this.limit = permits;
        }

        synchronized void setLimit(int permits)
        {
            int delta = permits - limit;
            limit = permits;
            if (delta > 0)
            {
                release(delta);
            }
            else if (delta < 0)
            {
                reducePermits(-delta);
            }
        }
    }
}

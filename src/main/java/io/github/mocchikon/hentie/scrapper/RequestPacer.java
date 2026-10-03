package io.github.mocchikon.hentie.scrapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a site's requests to one rate limit, across threads. Requests are spaced evenly rather than sent in
 * bursts of "N per minute": evenly spaced, they stay within the limit however the site counts its minute, and no
 * caller waits a whole minute for the window to turn over.
 * <p>
 * A caller books its turn and sleeps outside the lock, so a waiting thread never blocks one booking a later turn.
 */
public final class RequestPacer
{
    /** {@link System#nanoTime()} of the next free turn; compared by subtraction, which survives overflow. */
    private long nextTurnNanos;
    private boolean booked;

    /**
     * Waits for this caller's turn. The next turn comes {@code interval} after it, so the interval may differ per
     * call (a site that allows more with an API key).
     */
    public void await(Duration interval) throws InterruptedException
    {
        long sleepNanos;
        synchronized (this)
        {
            long now = System.nanoTime();
            long turn = booked && nextTurnNanos - now > 0 ? nextTurnNanos : now;
            booked = true;
            nextTurnNanos = turn + Math.max(0, interval.toNanos());
            sleepNanos = turn - now;
        }
        if (sleepNanos > 0)
        {
            TimeUnit.NANOSECONDS.sleep(sleepNanos);
        }
    }

    /** For a site that asked to slow down: no turn starts before {@code wait} has passed, whoever asks. */
    public synchronized void holdOff(Duration wait)
    {
        long until = System.nanoTime() + Math.max(0, wait.toNanos());
        if (!booked || until - nextTurnNanos > 0)
        {
            nextTurnNanos = until;
            booked = true;
        }
    }

    /** {@code perMinute} requests a minute as the gap between two of them; 0 or less means no limit. */
    public static Duration interval(int perMinute)
    {
        return perMinute <= 0 ? Duration.ZERO : Duration.ofMillis((60_000L + perMinute - 1) / perMinute);
    }
}

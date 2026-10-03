package io.github.mocchikon.hentie.config;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Lets one write transaction run at a time, in the order they asked. Every write transaction waits here before it
 * begins ({@link WriteGateDialect}), so the app's writers never meet inside SQLite. There they would fail rather
 * than wait: SQLite has one writer, and a transaction that reads before it writes, as every Hibernate one does,
 * gets {@code SQLITE_BUSY} at once, or {@code SQLITE_BUSY_SNAPSHOT} when another writer committed after its first
 * read.
 * <p>
 * <b>Fair</b>, because SQLite's busy handler is not: it polls, so a loop that commits short transactions back to
 * back takes the lock again before a waiting writer wakes up. Here a waiting request gets the next turn, so it
 * waits for at most one unit of a background loop.
 * <p>
 * How long a write waits depends on its thread:
 * <ul>
 *   <li>A request's first write waits {@code app.writes.request-wait-millis}, then fails with
 *       {@link LibraryBusyException} before anything changed. Once the request had a turn it may have changed
 *       something, so its later writes wait as long as they must: a request never stops half-way for waiting.</li>
 *   <li>{@link #background} work also waits as long as it must, though it runs on a request thread.</li>
 *   <li>A thread that serves no request (the download worker, anything scheduled) always does, unasked.</li>
 *   <li>{@link #ifFree} work does not wait at all.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WriteGate
{
    private static final Logger log = LoggerFactory.getLogger(WriteGate.class);

    /** How often a writer that waits as long as it must reports that it still waits. */
    static final long PATIENCE_REPORT_MILLIS = 60_000;

    private final AppProperties appProperties;

    private final ReentrantLock lock = new ReentrantLock(true);

    /** Null on a thread that serves no request and runs no scope: it waits as long as it must. */
    private final ThreadLocal<Policy> policy = new ThreadLocal<>();

    /** What the thread holding the gate is doing, for busy messages and the log. Written only by that thread. */
    private volatile Holder holder;

    private enum Wait
    {
        /** Up to the request budget. */
        REQUEST,
        /** As long as it takes. */
        PATIENT,
        /** Not at all. */
        NONE
    }

    /** What a thread's writes wait for. Mutable, because a scope that had a turn waits patiently from then on. */
    private static final class Policy
    {
        private Wait wait;
        private final String activity;

        private Policy(Wait wait, String activity)
        {
            this.wait = wait;
            this.activity = activity;
        }
    }

    /** {@code otherProgram}: the holder has the gate but waits for SQLite's lock, held outside the app. */
    private record Holder(String activity, String thread, long sinceNanos, boolean otherProgram)
    {
    }

    /** One write's turn: how long {@link WriteGateDialect} may still wait for SQLite's lock within it. */
    static final class Turn
    {
        private final Policy policy;
        private final Wait wait;
        private final long deadlineNanos;

        private Turn(Policy policy, Wait wait, long deadlineNanos)
        {
            this.policy = policy;
            this.wait = wait;
            this.deadlineNanos = deadlineNanos;
        }

        boolean isPatient()
        {
            return wait == Wait.PATIENT;
        }

        long remainingMillis()
        {
            return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        }
    }

    /** Closing it restores the thread's previous policy; it throws nothing, so a caller needs no catch. */
    public interface Scope extends AutoCloseable
    {
        @Override
        void close();
    }

    // ---- scopes -------------------------------------------------------------------------------------------------

    /** For the servlet filter: until closed, this thread's writes wait as a request's first write does. */
    public Scope serveRequest()
    {
        return enterScope(new Policy(Wait.REQUEST, null));
    }

    /**
     * For work that must not fail for waiting: a sweep, a bulk delete, any long-running form, which a user starts
     * and then waits for. Its writes wait as long as they must, and busy messages and the log name
     * {@code activity}, a phrase such as "matching chapters to series".
     * <p>
     * <b>Each of its write transactions must be short</b> (a slice), never around network or disk work that could
     * run outside it: a request waiting for the gate waits for one of them, and gives up after its budget.
     */
    public <T> T background(String activity, Supplier<T> work)
    {
        try (Scope ignored = enterScope(new Policy(Wait.PATIENT, activity)))
        {
            return work.get();
        }
    }

    public void background(String activity, Runnable work)
    {
        background(activity, () ->
        {
            work.run();
            return null;
        });
    }

    /**
     * For a write off a request that must not wait for ever, such as one during shutdown: it waits as a request's
     * first write does.
     *
     * @throws LibraryBusyException when no turn came within the request's budget, before anything changed
     */
    public void withinRequestBudget(Runnable work)
    {
        try (Scope ignored = serveRequest())
        {
            work.run();
        }
    }

    /**
     * Runs {@code work}'s write only if nobody holds or waits for the gate, and SQLite's lock is free too: for a
     * repair a page may skip, which must never slow the page down. Only for a single write transaction; a second
     * one would wait.
     *
     * @return empty when the write was skipped, else what {@code work} returned
     */
    public <T> Optional<T> ifFree(Supplier<T> work)
    {
        Policy current = policy.get();
        try (Scope ignored = enterScope(new Policy(Wait.NONE, current == null ? null : current.activity)))
        {
            return Optional.ofNullable(work.get());
        }
        catch (LibraryBusyException busy)
        {
            return Optional.empty();
        }
    }

    /**
     * For an action that changes something before its first write transaction (saves files, starts a process):
     * waits for a turn now, as that write would, so a busy library refuses the action before anything changed.
     * Afterwards the request's writes wait as long as they must. Only the gate is checked, not SQLite's lock.
     *
     * @throws LibraryBusyException when no turn came within the request's budget
     */
    public void claimTurn()
    {
        Policy current = policy.get();
        if (current == null || current.wait == Wait.PATIENT || lock.isHeldByCurrentThread())
        {
            return;
        }
        began(enter());
        exit();
    }

    private Scope enterScope(Policy entered)
    {
        Policy previous = policy.get();
        policy.set(entered);
        return () ->
        {
            if (previous == null)
            {
                policy.remove();
            }
            else
            {
                policy.set(previous);
            }
        };
    }

    // ---- for WriteGateDialect -----------------------------------------------------------------------------------

    boolean isHeldByCurrentThread()
    {
        return lock.isHeldByCurrentThread();
    }

    /**
     * Waits for this thread's turn, as its scope allows.
     *
     * @throws LibraryBusyException when the turn did not come in time
     */
    Turn enter()
    {
        Policy current = policy.get();
        Wait wait = current == null ? Wait.PATIENT : current.wait;
        long budgetMillis = wait == Wait.REQUEST ? appProperties.getWrites().getRequestWaitMillis() : 0;
        long start = System.nanoTime();
        try
        {
            boolean acquired = switch (wait)
            {
                // Timed even at zero, because only the timed tryLock honours the queue: waiting threads come first.
                case NONE -> lock.tryLock(0, TimeUnit.NANOSECONDS);
                case REQUEST -> lock.tryLock(budgetMillis, TimeUnit.MILLISECONDS);
                case PATIENT -> awaitPatiently(start);
            };
            if (!acquired)
            {
                Holder blocking = holder;
                throw blocking != null && blocking.otherProgram()
                        ? LibraryBusyException.otherProgram()
                        : LibraryBusyException.heldBy(blocking == null ? null : blocking.activity());
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw LibraryBusyException.interrupted();
        }
        holder = new Holder(current == null ? null : current.activity, Thread.currentThread().getName(),
                System.nanoTime(), false);
        return new Turn(current, wait, start + TimeUnit.MILLISECONDS.toNanos(budgetMillis));
    }

    private boolean awaitPatiently(long start) throws InterruptedException
    {
        while (!lock.tryLock(PATIENCE_REPORT_MILLIS, TimeUnit.MILLISECONDS))
        {
            log.warn("Waited {} s so far to write to the library, which is busy with {}",
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start), describe(holder));
        }
        return true;
    }

    /** The write has its lock and may change things now, so the rest of its scope must not give up half-way. */
    void began(Turn turn)
    {
        if (turn.policy != null)
        {
            turn.policy.wait = Wait.PATIENT;
        }
    }

    /** While true, writers who give up are told another program holds the library, not this holder. */
    void waitingForOtherProgram(boolean waiting)
    {
        Holder current = holder;
        if (current != null)
        {
            holder = new Holder(current.activity(), current.thread(), current.sinceNanos(), waiting);
        }
    }

    void exit()
    {
        if (!lock.isHeldByCurrentThread())
        {
            throw new IllegalStateException("The write gate is not held by " + Thread.currentThread().getName());
        }
        Holder done = holder;
        holder = null;
        lock.unlock();
        long heldMillis = done == null ? 0 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - done.sinceNanos());
        // The holds that make requests give up: worth knowing about, since each should have been a short slice.
        if (heldMillis > appProperties.getWrites().getRequestWaitMillis())
        {
            log.info("One write held the library for {} ms ({}), longer than a request waits for its turn",
                    heldMillis, describe(done));
        }
    }

    private static String describe(Holder holder)
    {
        if (holder == null)
        {
            return "a write that has just finished";
        }
        String activity = holder.activity() == null ? "a write" : holder.activity();
        return activity + " on thread " + holder.thread() + (holder.otherProgram()
                ? ", waiting for another program that holds SQLite's write lock" : "");
    }
}

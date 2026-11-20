package io.github.mocchikon.hentie.service.export;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * How far each running export has got, for the popup to show while the browser receives the file. The browser's
 * own download shows only bytes, and with a ComfyUI workflow nothing arrives for as long as a page takes.
 *
 * <p><b>In memory, keyed by an id the popup made up</b>: an export lives as long as its request, and a restart ends
 * it anyway. A finished export is kept {@link #KEPT_MILLIS} so the popup's next poll still hears how it ended,
 * and also what went wrong after the download had started, which the browser cannot show.
 */
@Component
public class ExportProgress
{
    static final long KEPT_MILLIS = 5 * 60_000;

    /** Bounded, because it is kept in memory. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public enum State { RUNNING, DONE, FAILED, CANCELLED }

    public record Snapshot(State state, int done, int total, String message) {}

    private final Map<String, Tracker> exports = new ConcurrentHashMap<>();

    /** An export nobody asked to follow; it can still be stopped by its request ending. */
    public static Tracker untracked()
    {
        return new Tracker();
    }

    /** Untracked for a missing or malformed id, so a download without the popup still works. */
    public Tracker start(String id)
    {
        forgetFinished();
        if (id == null || !ID.matcher(id).matches())
        {
            return untracked();
        }
        var tracker = new Tracker();
        exports.put(id, tracker);
        return tracker;
    }

    public Optional<Snapshot> get(String id)
    {
        forgetFinished();
        return id == null ? Optional.empty() : Optional.ofNullable(exports.get(id)).map(Tracker::snapshot);
    }

    /** @return false when there is no such export */
    public boolean cancel(String id)
    {
        Tracker tracker = id == null ? null : exports.get(id);
        if (tracker == null)
        {
            return false;
        }
        tracker.cancel();
        return true;
    }

    private void forgetFinished()
    {
        long now = System.currentTimeMillis();
        exports.values().removeIf(tracker -> tracker.finishedBefore(now - KEPT_MILLIS));
    }

    public static final class Tracker
    {
        private State state = State.RUNNING;
        private int done;
        private int total;
        private String message;
        private long finishedAt;
        private volatile boolean cancelled;
        private Runnable onCancel = () -> {};

        public synchronized void total(int total)
        {
            this.total = total;
        }

        public synchronized void pageWritten()
        {
            done++;
            message = null;
        }

        /** What ComfyUI is doing with the page being waited for. */
        public synchronized void working(String message)
        {
            this.message = message;
        }

        public synchronized void finished()
        {
            end(State.DONE, null);
        }

        public synchronized void failed(String message)
        {
            end(cancelled ? State.CANCELLED : State.FAILED, cancelled ? null : message);
        }

        /** Runs at once when the export was cancelled already. */
        public void onCancel(Runnable action)
        {
            synchronized (this)
            {
                onCancel = action;
                if (!cancelled)
                {
                    return;
                }
            }
            action.run();
        }

        public boolean cancelled()
        {
            return cancelled;
        }

        /** @throws ExportPages.ExportCancelledException when the user cancelled the export */
        public void checkCancelled()
        {
            if (cancelled)
            {
                throw new ExportPages.ExportCancelledException();
            }
        }

        void cancel()
        {
            Runnable action;
            synchronized (this)
            {
                if (cancelled || state != State.RUNNING)
                {
                    return;
                }
                cancelled = true;
                action = onCancel;
            }
            action.run();
        }

        synchronized Snapshot snapshot()
        {
            return new Snapshot(state, done, total, message);
        }

        private void end(State state, String message)
        {
            if (this.state != State.RUNNING)
            {
                return;
            }
            this.state = state;
            this.message = message;
            this.finishedAt = System.currentTimeMillis();
        }

        private synchronized boolean finishedBefore(long time)
        {
            return state != State.RUNNING && finishedAt < time;
        }
    }
}

package io.github.mocchikon.hentie.service.comfy;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageSize;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.comfy.ComfyResultCache.PageVersion;
import io.github.mocchikon.hentie.service.compress.JxlTranscoder;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Decides which page ComfyUI works on next, and when it stops working on one.
 *
 * <p><b>One page at a time, and only while somebody wants it.</b> ComfyUI runs one prompt at a time anyway, and
 * a queue would put pages the reader left in front of the one they jumped to. One dispatcher thread hands over
 * the next job only when the previous one is done:
 * <ul>
 *   <li>the page being <i>read</i> before any <i>prefetch</i>, the one asked for last first;</li>
 *   <li>a viewer wants only what its <b>latest</b> request asks for ({@link Asker}); a late request with a lower
 *       {@code seq} changes nothing. Otherwise every page turned past would stay wanted and run first;</li>
 *   <li>a job nobody has asked about for {@code app.comfyui.abandon-after-millis} is dropped before it starts.</li>
 * </ul>
 *
 * <p><b>The page running is stopped as soon as nobody wants it</b>, because it can be minutes of work in front
 * of the page the reader is waiting for. Nobody wants it once every asker it ran for has asked for another page
 * that needs processing, or has left ({@link #leave}). Once the result is made it is never stopped. Two things
 * do <i>not</i> count as moving on:
 * <ul>
 *   <li>a request answered from the cache while the running page is still in the asker's plan: the viewer
 *       collects ready pages first, and stopping the page it will ask for next would waste the prefetch;</li>
 *   <li>silence: a sleeping tab still wants its page when it wakes. Silence only keeps pages from starting.</li>
 * </ul>
 *
 * <p><b>A request waits a bounded time, never for the whole run</b>, which can outlast a browser's patience. The
 * viewer asks again while {@link Pending}, which also keeps the job wanted. A failure is kept a few seconds so
 * every poller sees it, then forgotten so asking again retries.
 */
@Service
@RequiredArgsConstructor
public class PageProcessingService
{
    private static final Logger log = LoggerFactory.getLogger(PageProcessingService.class);

    /** Long enough for every poller of a failed page to be told, short enough that a retry is not refused. */
    private static final long FAILURE_RETENTION_MILLIS = 10_000;

    /**
     * More than {@link Progress.Estimate#LATEST_RUNS}, because pages of another size, which tell how time follows
     * size, are seldom among the latest few.
     */
    private static final int RUN_TIMES_KEPT = 30;

    /** A shorter run is ComfyUI's own cache answering, and would pull the estimate towards zero. */
    private static final long MIN_RUN_TIME_MILLIS = 1_000;

    private static final long STOP_WAIT_MILLIS = 5_000;

    private final AppProperties appProperties;
    private final ImageDirectory imageDirectory;
    private final JxlTranscoder jxlTranscoder;
    private final ComfyUiClient client;
    private final WorkflowCatalog catalog;
    private final ComfyResultCache cache;
    private final ComfyUiLauncher launcher;

    /**
     * Guards {@link #jobs}, {@link #interests}, {@link #runTimes}, the jobs' scheduling fields, {@link #sequence},
     * {@link #dispatcher}, {@link #runningJob} and {@link #stopping}.
     */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wake = lock.newCondition();
    private final Map<Key, Job> jobs = new HashMap<>();
    /** A viewer's latest request, or the anonymous requests for one page. */
    private final Map<String, Interest> interests = new HashMap<>();
    /** Oldest first. By version, not name: a re-exported workflow may use another model. */
    private final Map<String, Deque<RunTime>> runTimes = new HashMap<>();
    private long sequence;
    private Thread dispatcher;
    /** Null between two runs. */
    private Job runningJob;
    private boolean stopping;

    public sealed interface Outcome permits Ready, Pending, Failed {}

    /** A file of the results cache. */
    public record Ready(Path file) implements Outcome {}

    public record Pending(Progress progress) implements Outcome {}

    public record Failed(Problem problem, String message) implements Outcome {}

    @Getter
    @RequiredArgsConstructor
    public enum Problem
    {
        PAGE_NOT_FOUND(404),
        WORKFLOW_NOT_FOUND(404),
        WORKFLOW_INVALID(422),
        UNREACHABLE(503),
        FAILED(502),
        TIMEOUT(504);

        private final int httpStatus;

        static Problem of(ComfyUiException.Reason reason)
        {
            return switch (reason)
            {
                case UNREACHABLE -> UNREACHABLE;
                case WORKFLOW_NOT_FOUND -> WORKFLOW_NOT_FOUND;
                case WORKFLOW_INVALID -> WORKFLOW_INVALID;
                case TIMEOUT -> TIMEOUT;
                case JOB_FAILED, PROTOCOL -> FAILED;
            };
        }
    }

    /**
     * {@code viewer} is an id the viewer made up; {@code seq} rises each time it moves on; {@code plan} is the
     * pages it will ask for from where it is, page on screen first, which tells a running page still on its way
     * from one left behind. {@link #ANONYMOUS} (no viewer) makes each page count on its own and never be
     * superseded, which tests and scripts rely on.
     */
    public record Asker(String viewer, long seq, List<String> plan)
    {
        public static final Asker ANONYMOUS = new Asker(null, 0);

        /** Bounded, because it stays in memory as long as the viewer's interest. */
        private static final Pattern VIEWER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

        /** The page on screen, every page ahead, and the previous one. */
        static final int MAX_PLAN = SettingsService.MAX_PAGES_AHEAD + 2;

        static final int MAX_PAGE_NAME = 255;

        public Asker(String viewer, long seq)
        {
            this(viewer, seq, List.of());
        }

        /** Anonymous when the id is missing or not one a viewer would make up. */
        public static Asker of(String viewer, long seq)
        {
            return of(viewer, seq, List.of());
        }

        public static Asker of(String viewer, long seq, List<String> plan)
        {
            if (viewer == null || !VIEWER_ID.matcher(viewer).matches())
            {
                return ANONYMOUS;
            }
            return new Asker(viewer, seq, plan == null ? List.of() : plan.stream()
                    .filter(name -> name != null && name.length() <= MAX_PAGE_NAME)
                    .limit(MAX_PLAN)
                    .toList());
        }
    }

    public record Progress(Stage stage, int ahead, String node, int value, int max, Estimate estimate)
    {
        public enum Stage { QUEUED, STARTING, WAITING, RUNNING }

        public static Progress queued(int ahead)
        {
            return new Progress(Stage.QUEUED, ahead, null, 0, 0, null);
        }

        static Progress starting()
        {
            return new Progress(Stage.STARTING, 0, null, 0, 0, null);
        }

        static Progress waiting(int ahead)
        {
            return new Progress(Stage.WAITING, ahead, null, 0, 0, null);
        }

        static Progress running(String node, int value, int max)
        {
            return new Progress(Stage.RUNNING, 0, node, value, max, null);
        }

        Progress withEstimate(Estimate estimate)
        {
            return new Progress(stage, ahead, node, value, max, estimate);
        }

        /**
         * Null unless running. From the estimate when there is one, because a node's count is not time: an
         * upscaler's edge tiles are smaller, so "50%" can last from 58% to 92% of the run.
         */
        public Integer percent()
        {
            if (stage != Stage.RUNNING)
            {
                return null;
            }
            if (estimate != null && !estimate.overdue())
            {
                return estimate.percent();
            }
            return max > 0 ? Math.clamp(value * 100L / max, 0, 100) : null;
        }

        public String message()
        {
            return switch (stage)
            {
                case QUEUED -> ahead > 0 ? "Queued - " + ahead + (ahead == 1 ? " page" : " pages") + " ahead" : "Queued";
                case STARTING -> "Waiting for ComfyUI to start";
                case WAITING -> ahead > 0 ? "Waiting in ComfyUI's queue - " + ahead + " ahead" : "Waiting in ComfyUI's queue";
                case RUNNING -> (node == null ? "Processing" : node) + (percent() == null ? "" : " " + percent() + "%")
                        + (estimate == null ? "" : ", " + estimate.remaining());
            };
        }

        /**
         * {@code typicalMillis} is the median of recent runs and {@code slowMillis} their 75th percentile, the way
         * ComfyUI's own queue panel estimates, so both agree on how long is left.
         */
        public record Estimate(long elapsedMillis, long typicalMillis, long slowMillis)
        {
            /**
             * Enough to outvote one slow run (the first loads the model), few enough to follow a machine that
             * became slower or faster.
             */
            static final int LATEST_RUNS = 5;

            /** Pages closer in size than this say more about noise than about size. */
            static final double MIN_SIZE_RATIO = 1.5;

            /** Past four times the time for twice the pixels, it is only noise. */
            static final double MAX_EXPONENT = 2;

            /**
             * Each of the latest runs is scaled to this page's pixels by {@link #sizeExponent}; a run or page of
             * unknown size counts unscaled.
             *
             * @param runs oldest first
             */
            static Estimate of(List<RunTime> runs, long pixels, long elapsedMillis)
            {
                double exponent = sizeExponent(runs);
                long[] expected = runs.subList(Math.max(0, runs.size() - LATEST_RUNS), runs.size()).stream()
                        .mapToLong(run -> run.pixels() > 0 && pixels > 0
                                ? Math.round(run.millis() * Math.pow((double) pixels / run.pixels(), exponent))
                                : run.millis())
                        .sorted()
                        .toArray();
                return new Estimate(elapsedMillis, expected[(expected.length - 1) / 2],
                        expected[Math.min(expected.length - 1, expected.length * 3 / 4)]);
            }

            /**
             * k in time ~ pixels^k: 1 for an upscaler, 0 for a workflow that scales every page to one size first.
             * The median of the log-log slopes of every pair of runs on clearly different sizes, because a fitted
             * line would lean towards one run slowed by something else.
             *
             * <p>1 until such a pair exists: a double spread is the size change that matters most, and upscalers
             * are what this is for.
             */
            static double sizeExponent(Collection<RunTime> runs)
            {
                List<RunTime> sized = runs.stream().filter(run -> run.pixels() > 0).toList();
                var slopes = new ArrayList<Double>();
                for (int i = 0; i < sized.size(); i++)
                {
                    for (int j = i + 1; j < sized.size(); j++)
                    {
                        double sizes = Math.log((double) sized.get(i).pixels() / sized.get(j).pixels());
                        if (Math.abs(sizes) >= Math.log(MIN_SIZE_RATIO))
                        {
                            slopes.add(Math.log((double) sized.get(i).millis() / sized.get(j).millis()) / sizes);
                        }
                    }
                }
                if (slopes.isEmpty())
                {
                    return 1;
                }
                slopes.sort(null);
                int middle = slopes.size() / 2;
                double median = slopes.size() % 2 == 1
                        ? slopes.get(middle) : (slopes.get(middle - 1) + slopes.get(middle)) / 2;
                return Math.clamp(median, 0, MAX_EXPONENT);
            }

            boolean overdue()
            {
                return elapsedMillis >= slowMillis;
            }

            /** Never 100: the run is not done until its result is here. */
            int percent()
            {
                return Math.clamp(elapsedMillis * 100 / Math.max(1, typicalMillis), 0, 99);
            }

            /** "~3-4 s left", "~2 min left": ComfyUI's ranges, rounded as it rounds them. */
            String remaining()
            {
                if (overdue())
                {
                    return "taking longer than usual";
                }
                long least = Math.round(Math.max(0, typicalMillis - elapsedMillis) / 1000.0);
                long most = Math.round((slowMillis - elapsedMillis) / 1000.0);
                if (most <= 60)
                {
                    most = Math.max(1, most);
                    least = Math.max(1, Math.min(most, least));
                    return (least == most ? "~" + most : "~" + least + "-" + most) + " s left";
                }
                if (least >= 60 && most < 90)
                {
                    return "~1 min left";
                }
                least = Math.max(1, least / 60);
                most = Math.max(least, (most + 59) / 60);
                return (least == most ? "~" + least : "~" + least + "-" + most) + " min left";
            }
        }
    }

    private record Key(int chapterId, String filename, long pageModified, long pageSize, String workflow) {}

    /** By name, not {@link Key}: a page's version is only known when it is asked for. */
    private record Plan(int chapterId, String workflow, Set<String> filenames)
    {
        static final Plan NONE = new Plan(0, "", Set.of());

        boolean contains(Key page)
        {
            return page.chapterId() == chapterId && page.workflow().equals(workflow)
                    && filenames.contains(page.filename());
        }
    }

    /** {@code pixels} is 0 when the page's header could not be read. */
    record RunTime(long millis, long pixels) {}

    /** {@code pixels} is 0 when unknown. */
    private record Uploaded(String reference, long pixels) {}

    @RequiredArgsConstructor
    private static final class Job
    {
        final Key key;
        final CompletableFuture<Outcome> done = new CompletableFuture<>();
        volatile Progress progress = Progress.queued(0);
        /** 0 until ComfyUI starts executing it. Written on the websocket's thread. */
        volatile long startedAtMillis;
        /** 0 until uploaded, or when the header says nothing. */
        volatile long pixels;
        volatile String version;
        // Guarded by the lock:
        boolean running;
        /** A thread of its own: stopping interrupts it, and that interrupt must never reach the next job. */
        Thread thread;
        /** Stopped because nobody wants it; it ends without a result. */
        boolean cancelled;
        /** The result is made; stopping now would throw it away, so it is no longer stopped. */
        boolean committed;
        long finishedAtMillis;
    }

    /**
     * {@code key} is null once the latest request was answered at once. The interest is kept anyway, so an older
     * request is still known as older and the asker's plan still counts for the page running.
     */
    @RequiredArgsConstructor
    private static final class Interest
    {
        final String asker;
        final Key key;
        final long seq;
        final boolean prefetch;
        /** From {@link #sequence}: the page jumped to last is the one being read. */
        final long order;
        final Plan plan;
        // Guarded by the lock:
        long askedAtMillis;
        /** Requests for it held open right now. */
        int held;

        boolean wants(Key page)
        {
            return key == null ? plan.contains(page) : key.equals(page);
        }
    }

    /** What the interests in one job add up to. */
    private record Demand(boolean prefetch, long order)
    {
        Demand and(Demand other)
        {
            return new Demand(prefetch && other.prefetch, Math.max(order, other.order));
        }

        boolean runsBefore(Demand other)
        {
            return prefetch != other.prefetch ? !prefetch : order > other.order;
        }
    }

    // ---- requests -----------------------------------------------------------

    public Outcome request(int chapterId, String filename, String workflow, boolean prefetch, Duration wait)
            throws InterruptedException
    {
        return request(chapterId, filename, workflow, prefetch, wait, Asker.ANONYMOUS);
    }

    /**
     * Supersedes whatever the asker asked for before, unless this request is older than one it already made;
     * then it is answered and changes nothing.
     *
     * @param prefetch the page is wanted for later, not shown now
     */
    public Outcome request(int chapterId, String filename, String workflow, boolean prefetch, Duration wait,
                           Asker asker) throws InterruptedException
    {
        if (!WorkflowCatalog.isValidName(workflow))
        {
            answered(asker, Plan.NONE);
            return new Failed(Problem.WORKFLOW_NOT_FOUND, "There is no workflow called '" + workflow + "'.");
        }
        Optional<Path> source = pageFile(chapterId, filename);
        if (source.isEmpty())
        {
            answered(asker, Plan.NONE);
            return new Failed(Problem.PAGE_NOT_FOUND, "Chapter " + chapterId + " has no page " + filename + ".");
        }
        PageVersion page;
        try
        {
            page = PageVersion.of(source.get());
        }
        catch (IOException e)
        {
            answered(asker, Plan.NONE);
            return new Failed(Problem.PAGE_NOT_FOUND, "Page " + filename + " cannot be read: " + e.getMessage());
        }
        var plan = new Plan(chapterId, workflow, Set.copyOf(asker.plan()));
        Optional<Path> cached = cached(chapterId, filename, page, workflow);
        if (cached.isPresent())
        {
            answered(asker, plan);
            return new Ready(cached.get());
        }
        var key = new Key(chapterId, filename, page.modifiedMillis(), page.size(), workflow);
        Interest interest;
        Job job;
        lock.lock();
        try
        {
            long now = System.currentTimeMillis();
            forgetOldFailures(now);
            interest = register(key, prefetch, !wait.isZero(), asker, plan, now);
            // A request the asker has moved on from starts nothing; it only hears how a job is doing.
            job = interest == null ? jobs.get(key) : jobs.computeIfAbsent(key, Job::new);
            if (interest != null)
            {
                stopUnwanted();
                startDispatcher();
                wake.signalAll();
            }
        }
        finally
        {
            lock.unlock();
        }
        if (job == null)
        {
            return new Pending(Progress.queued(0));
        }
        if (interest == null || wait.isZero())
        {
            return job.done.isDone() ? job.done.getNow(null) : new Pending(progressOf(job));
        }
        try
        {
            return job.done.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e)
        {
            return new Pending(progressOf(job));
        }
        catch (ExecutionException e)
        {
            // Never: a failure also completes normally.
            return new Failed(Problem.FAILED, String.valueOf(e.getCause()));
        }
        finally
        {
            release(interest);
        }
    }

    /** {@code seq} is higher than any earlier request, so one still on its way starts nothing. */
    public void leave(Asker asker)
    {
        answered(asker, Plan.NONE);
    }

    /**
     * Asks for the listing first, or a stale version would keep serving an old export's results. That call does
     * not wait for ComfyUI, and when it is down the last known version still finds the result.
     */
    private Optional<Path> cached(int chapterId, String filename, PageVersion page, String workflow)
            throws InterruptedException
    {
        catalog.listing();
        return catalog.knownVersion(workflow).flatMap(version -> cache.find(chapterId, filename, page, workflow, version));
    }

    /** Called with the lock held. Null, changing nothing, when the asker has already asked for something later. */
    private Interest register(Key key, boolean prefetch, boolean waiting, Asker asker, Plan plan, long now)
    {
        forgetIdleInterests(now);
        // Anonymous requests for one page share an interest, so polling a long run does not pile them up.
        String id = asker.viewer() != null ? asker.viewer() : "#" + prefetch + key;
        Interest current = interests.get(id);
        if (current != null && asker.seq() < current.seq)
        {
            return null;
        }
        if (current == null || current.seq != asker.seq() || current.prefetch != prefetch || !key.equals(current.key))
        {
            current = new Interest(id, key, asker.seq(), prefetch, ++sequence, plan);
            interests.put(id, current);
        }
        current.askedAtMillis = now;
        if (waiting)
        {
            current.held++;
        }
        return current;
    }

    /** Nothing the asker asked for before is wanted any more, except a running page still in {@code plan}. */
    private void answered(Asker asker, Plan plan)
    {
        if (asker.viewer() == null)
        {
            return;
        }
        lock.lock();
        try
        {
            Interest current = interests.get(asker.viewer());
            if (current == null || asker.seq() >= current.seq)
            {
                var answered = new Interest(asker.viewer(), null, asker.seq(), false, ++sequence, plan);
                answered.askedAtMillis = System.currentTimeMillis();
                interests.put(asker.viewer(), answered);
                stopUnwanted();
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * The grace until the viewer asks again starts now, but only while this is still what the asker wants: a
     * request it moved on from must not make its page wanted again.
     */
    private void release(Interest interest)
    {
        lock.lock();
        try
        {
            if (interests.get(interest.asker) == interest)
            {
                interest.held--;
                interest.askedAtMillis = System.currentTimeMillis();
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    /** Called with the lock held. Every interest counts, however long silent (see the class comment). */
    private void stopUnwanted()
    {
        Job job = runningJob;
        if (job == null || job.cancelled || job.committed
                || interests.values().stream().anyMatch(interest -> interest.wants(job.key)))
        {
            return;
        }
        job.cancelled = true;
        // Removed now, so a new request starts afresh instead of joining a run that ends without a result.
        jobs.remove(job.key, job);
        // ComfyUiClient.run turns the interrupt into a queue delete + interrupt in ComfyUI.
        job.thread.interrupt();
        log.debug("Stopped page {} of chapter {}: nobody wants it any more", job.key.filename(), job.key.chapterId());
    }

    private Progress progressOf(Job job)
    {
        lock.lock();
        try
        {
            long now = System.currentTimeMillis();
            if (job.done.isDone())
            {
                return job.progress;
            }
            if (job.running)
            {
                return estimated(job, now);
            }
            Map<Key, Demand> demand = demand(now);
            Demand mine = demand.get(job.key);
            int ahead = 0;
            for (Job other : jobs.values())
            {
                Demand theirs = demand.get(other.key);
                if (other != job && !other.done.isDone()
                        && (other.running || theirs != null && (mine == null || theirs.runsBefore(mine))))
                {
                    ahead++;
                }
            }
            return Progress.queued(ahead);
        }
        finally
        {
            lock.unlock();
        }
    }

    /** Called with the lock held. */
    private Progress estimated(Job job, long now)
    {
        Progress progress = job.progress;
        Deque<RunTime> runs = job.version == null ? null : runTimes.get(job.version);
        if (progress.stage() != Progress.Stage.RUNNING || job.startedAtMillis == 0 || runs == null)
        {
            return progress;
        }
        return progress.withEstimate(Progress.Estimate.of(List.copyOf(runs), job.pixels, now - job.startedAtMillis));
    }

    private void rememberRunTime(String version, long startedAtMillis, long pixels)
    {
        if (startedAtMillis == 0)
        {
            return;
        }
        long took = System.currentTimeMillis() - startedAtMillis;
        if (took < MIN_RUN_TIME_MILLIS)
        {
            return;
        }
        lock.lock();
        try
        {
            Deque<RunTime> runs = runTimes.computeIfAbsent(version, key -> new ArrayDeque<>());
            runs.addLast(new RunTime(took, pixels));
            while (runs.size() > RUN_TIMES_KEPT)
            {
                runs.removeFirst();
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    // ---- the dispatcher -----------------------------------------------------

    /** Called with the lock held. Lazy, so an app that never processes has no thread. */
    private void startDispatcher()
    {
        if (dispatcher == null && !stopping)
        {
            dispatcher = Thread.ofPlatform().daemon().name("comfyui-dispatcher").start(this::dispatch);
        }
    }

    @PreDestroy
    void shutdown()
    {
        Thread dispatching;
        Thread job;
        lock.lock();
        try
        {
            stopping = true;
            dispatching = dispatcher;
            job = runningJob == null ? null : runningJob.thread;
        }
        finally
        {
            lock.unlock();
        }
        if (dispatching != null)
        {
            dispatching.interrupt();
        }
        if (job != null)
        {
            // Cancels the prompt in ComfyUI so it stops holding the GPU; after the client closes it would be too late.
            job.interrupt();
            try
            {
                job.join(STOP_WAIT_MILLIS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Takes the next job only once the last one's thread is done, so a stopped prompt leaves ComfyUI's queue first. */
    private void dispatch()
    {
        lock.lock();
        try
        {
            while (!stopping)
            {
                Job job;
                while ((job = takeNext()) == null)
                {
                    wake.await();
                }
                var taken = job;
                // Both set under the lock the job's thread needs to end, so stopUnwanted always has a thread to
                // interrupt, and the thread cannot end before it is known.
                job.thread = Thread.ofPlatform().daemon().name("comfyui-job").start(() -> run(taken));
                runningJob = job;
                while (job.running)
                {
                    wake.await();
                }
            }
        }
        catch (InterruptedException e)
        {
            // The app is shutting down; shutdown() stops the job running.
        }
        finally
        {
            lock.unlock();
        }
    }

    /** Called with the lock held. Drops every job no longer wanted. */
    private Job takeNext()
    {
        if (stopping)
        {
            return null;
        }
        long now = System.currentTimeMillis();
        forgetOldFailures(now);
        forgetIdleInterests(now);
        Map<Key, Demand> demand = demand(now);
        Job next = null;
        Demand best = null;
        for (Iterator<Job> it = jobs.values().iterator(); it.hasNext(); )
        {
            Job job = it.next();
            if (job.running || job.done.isDone())
            {
                continue;
            }
            Demand wanted = demand.get(job.key);
            if (wanted == null)
            {
                it.remove();
                log.debug("Dropped page {} of chapter {}: nobody is waiting for it any more", job.key.filename(),
                        job.key.chapterId());
                continue;
            }
            if (best == null || wanted.runsBefore(best))
            {
                next = job;
                best = wanted;
            }
        }
        if (next != null)
        {
            next.running = true;
        }
        return next;
    }

    /** Called with the lock held. */
    private Map<Key, Demand> demand(long now)
    {
        var demand = new HashMap<Key, Demand>();
        for (Interest interest : interests.values())
        {
            if (interest.key != null && isLive(interest, now))
            {
                demand.merge(interest.key, new Demand(interest.prefetch, interest.order), Demand::and);
            }
        }
        return demand;
    }

    private boolean isLive(Interest interest, long now)
    {
        return interest.held > 0 || now - interest.askedAtMillis < appProperties.getComfyui().getAbandonAfterMillis();
    }

    /** Called with the lock held. An interest in the running page stays, however long silent (see the class comment). */
    private void forgetIdleInterests(long now)
    {
        interests.values().removeIf(interest -> !isLive(interest, now)
                && (runningJob == null || !interest.wants(runningJob.key)));
    }

    /** Called with the lock held. */
    private void forgetOldFailures(long now)
    {
        jobs.values().removeIf(job -> job.done.isDone() && now - job.finishedAtMillis > FAILURE_RETENTION_MILLIS);
    }

    /** On the job's own thread. */
    private void run(Job job)
    {
        Outcome outcome;
        try
        {
            outcome = process(job);
        }
        catch (InterruptedException e)
        {
            // Stopped as unwanted (see below), or shutting down.
            outcome = new Failed(Problem.FAILED, "The app is shutting down.");
        }
        catch (RuntimeException | Error e)
        {
            // Errors too (OutOfMemoryError): escaping would leave the job running for ever and block the dispatcher.
            log.error("Processing page {} of chapter {} with '{}' failed", job.key.filename(), job.key.chapterId(),
                    job.key.workflow(), e);
            outcome = new Failed(Problem.FAILED, "Processing the page failed: " + e);
        }
        lock.lock();
        try
        {
            if (job.cancelled && !(outcome instanceof Ready))
            {
                // Not a failure: a request still waiting was left by its viewer, which asks again if it was not.
                outcome = new Pending(Progress.queued(0));
            }
            job.running = false;
            job.finishedAtMillis = System.currentTimeMillis();
            if (runningJob == job)
            {
                runningJob = null;
            }
            // Only a failure is kept, for pollers still to come; a result lives in the cache.
            if (outcome instanceof Ready || job.cancelled)
            {
                jobs.remove(job.key, job);
            }
            // Under the lock with running = false: takeNext starts any job neither running nor done, so completing
            // after the unlock would let the dispatcher run the failed page again.
            job.done.complete(outcome);
            wake.signalAll();
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * On the job's own thread. An earlier interrupt is cleared: the run is over, and it would only fail the write
     * of a page already made ({@code Files.write} is interruptible). Once committed, no new interrupt is sent.
     */
    private void commit(Job job)
    {
        lock.lock();
        try
        {
            job.committed = true;
        }
        finally
        {
            lock.unlock();
        }
        Thread.interrupted();
    }

    private Outcome process(Job job) throws InterruptedException
    {
        Key key = job.key;
        if (launcher.isStarting())
        {
            job.progress = Progress.starting();
            launcher.awaitStartup(Duration.ofSeconds(Math.max(1, appProperties.getComfyui().getStartupTimeoutSeconds())));
        }
        try
        {
            ApiWorkflow workflow = catalog.workflow(key.workflow());
            job.version = workflow.version();
            Optional<Path> source = pageFile(key.chapterId(), key.filename());
            if (source.isEmpty())
            {
                return new Failed(Problem.PAGE_NOT_FOUND, "Page " + key.filename() + " is gone.");
            }
            PageVersion page = PageVersion.of(source.get());
            Optional<Path> cached = cache.find(key.chapterId(), key.filename(), page, workflow.name(), workflow.version());
            if (cached.isPresent())
            {
                return new Ready(cached.get());
            }
            Uploaded uploaded = upload(key, source.get());
            job.pixels = uploaded.pixels();
            boolean websocketOutput = client.supportsWebsocketOutput();
            job.progress = Progress.waiting(0);
            byte[] png = client.run(workflow, workflow.prepare(uploaded.reference(), websocketOutput), websocketOutput,
                    new ComfyUiClient.RunListener()
                    {
                        @Override
                        public void waiting(int queueRemaining)
                        {
                            job.progress = Progress.waiting(Math.max(0, queueRemaining - 1));
                        }

                        @Override
                        public void running(String node, int value, int max)
                        {
                            if (job.startedAtMillis == 0)
                            {
                                job.startedAtMillis = System.currentTimeMillis();
                            }
                            job.progress = Progress.running(node, value, max);
                        }

                        @Override
                        public void finished()
                        {
                            commit(job);
                        }
                    });
            rememberRunTime(workflow.version(), job.startedAtMillis, job.pixels);
            return new Ready(cache.store(key.chapterId(), key.filename(), page, workflow.name(), workflow.version(), png));
        }
        catch (ComfyUiException e)
        {
            String message = e.getMessage();
            if (e.reason() == ComfyUiException.Reason.UNREACHABLE)
            {
                message += " Start it, or check the address in Settings - ComfyUI.";
            }
            return new Failed(Problem.of(e.reason()), message);
        }
        catch (IOException e)
        {
            log.warn("Could not process page {} of chapter {}", key.filename(), key.chapterId(), e);
            return new Failed(Problem.FAILED, "Could not read the page or store its result: " + e.getMessage());
        }
    }

    /**
     * Named after its content, so ComfyUI writes a page to disk once whatever workflows run on it. JPEG XL goes
     * as the transcode cache's PNG, since ComfyUI cannot read JPEG XL.
     */
    private Uploaded upload(Key key, Path source) throws IOException, ComfyUiException, InterruptedException
    {
        byte[] image;
        String extension;
        if (JxlTranscoder.isJxl(key.filename()))
        {
            // On its own thread, waited for interruptibly: the decode may be shared with a browser request, so
            // stopping this job must neither kill djxl under it nor wait unstoppably for it.
            CompletableFuture<Optional<Path>> decoding = CompletableFuture.supplyAsync(
                    () -> jxlTranscoder.pngFor(key.chapterId(), key.filename()),
                    Thread.ofVirtual().name("comfyui-jxl-decode")::start);
            Optional<Path> decoded;
            try
            {
                decoded = decoding.get();
            }
            catch (ExecutionException e)
            {
                throw new IOException("the JPEG XL page could not be decoded to PNG", e.getCause());
            }
            Path png = decoded
                    .orElseThrow(() -> new IOException("the JPEG XL page could not be decoded to PNG (is djxl in bin/?)"));
            image = Files.readAllBytes(png);
            extension = "png";
        }
        else
        {
            image = Files.readAllBytes(source);
            extension = ImageDirectory.extension(key.filename()).toLowerCase(Locale.ROOT);
        }
        String reference = client.uploadTempImage(image,
                "hentie-" + ApiWorkflow.sha256(image).substring(0, 32) + "." + extension);
        return new Uploaded(reference, ImageSize.of(image).map(ImageSize::pixels).orElse(0L));
    }

    private Optional<Path> pageFile(int chapterId, String filename)
    {
        return ImageDirectory.isImage(filename) ? imageDirectory.pageFile(chapterId, filename) : Optional.empty();
    }
}

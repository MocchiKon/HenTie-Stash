package io.github.mocchikon.hentie.scrapper.gallerydl;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.ProcessTrees;
import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * <b>The only class that starts gallery-dl.</b> It finds the executable (bundled, on {@code PATH}, or a configured
 * command), runs it and makes sure nothing it started outlives its run.
 * <p>
 * <b>gallery-dl is a one-file executable</b> (PyInstaller): a bootloader unpacks it into the temp folder and starts
 * the real program as its child. So a run is stopped by killing its <b>children first</b>, after which the
 * bootloader exits and removes what it unpacked; killing the bootloader first would leave both the program and
 * the unpacked copy behind. The temp folder is one of the app's own, so what a hard kill leaves there is removed
 * at the next start.
 * <p>
 * <b>Nothing may depend on a graceful stop</b>: a run that outlives a killed app keeps writing into its own run
 * folder, which nothing reads, and is killed at the next start through the record of its pid and start time.
 */
@Component
@RequiredArgsConstructor
public class GalleryDlTool
{
    private static final Logger log = LoggerFactory.getLogger(GalleryDlTool.class);

    static final String WINDOWS_EXECUTABLE = "gallery-dl.exe";
    static final String LINUX_EXECUTABLE = "gallery-dl.bin";
    static final String SYSTEM_EXECUTABLE = "gallery-dl";

    /** Lines of stderr kept for messages, of each kind ({@link StderrTail}); the log carries the rest at DEBUG. */
    private static final int STDERR_LINES_KEPT = 200;

    /** A {@code -j} answer is one JSON document; anything bigger is not a gallery's metadata. */
    private static final int MAX_COLLECTED_CHARS = 32 * 1024 * 1024;

    /** How long the bootloader gets to exit by itself once its child is gone, which lets it clean up. */
    private static final Duration BOOTLOADER_GRACE = Duration.ofSeconds(2);

    /** How long a reader may stay silent after the process ended before it is taken as held open by a grandchild. */
    private static final Duration READER_IDLE_LIMIT = Duration.ofSeconds(5);

    private static final String RUN_RECORD_PREFIX = "running-";

    private final AppProperties appProperties;
    private final SettingsService settingsService;

    /** Runs take the read side, an update the write side: an executable must not be replaced while it runs. */
    private final ReentrantReadWriteLock updateLock = new ReentrantReadWriteLock();

    private final List<Process> running = new ArrayList<>();

    private volatile CachedVersion cachedVersion;
    private volatile boolean versionCheckRunning;

    @RequiredArgsConstructor
    public enum Origin
    {
        BUNDLED("bundled with the app"),
        SYSTEM("installed on this system"),
        CONFIGURED("configured by app.gallery-dl.command");

        @Getter
        @Accessors(fluent = true)
        private final String label;
    }

    /** @param file the executable for a bundled copy, else null */
    public record Executable(List<String> command, Origin origin, Path file)
    {
        public String describe()
        {
            return String.join(" ", command) + " (" + origin.label() + ")";
        }
    }

    /** @param stdout everything printed, when the caller asked for it collected; else null */
    public record Result(int exitCode, List<String> stderr, String stdout)
    {
    }

    /** What Settings shows. {@code version} and {@code problem} are both null while a check is under way. */
    public record Status(Executable executable, String version, String problem, boolean checking)
    {
        public boolean updatable()
        {
            return executable != null && executable.origin() == Origin.BUNDLED;
        }
    }

    public record UpdateOutcome(boolean updated, String message, List<String> output)
    {
    }

    /**
     * How long a version is trusted when no file of the app's tells whether it changed: a gallery-dl on the
     * {@code PATH} or configured can be upgraded while the app runs.
     */
    private static final Duration UNTRACKED_VERSION_TTL = Duration.ofMinutes(5);

    private record CachedVersion(List<String> command, long fileModified, String version, String problem,
                                 Instant checkedAt)
    {
        /** A failed check may have been a slow start or an update in progress, so it is never trusted. */
        boolean current(Executable executable)
        {
            return problem == null && (executable.file() != null
                    || Duration.between(checkedAt, Instant.now()).compareTo(UNTRACKED_VERSION_TTL) < 0);
        }
    }

    // ---- the executable ------------------------------------------------------------------------------------

    /**
     * Not cached: the Settings choice can change between two runs, and an executable just put in place should be
     * found without a restart.
     *
     * @throws GalleryDlException NOT_RUNNABLE when the bundled copy is missing (macOS has none)
     */
    public Executable executable() throws GalleryDlException
    {
        // Blank entries dropped: a stray comma in the property would otherwise start the command with an empty
        // argument.
        List<String> configured = appProperties.getGalleryDl().getCommand() == null ? List.of()
                : appProperties.getGalleryDl().getCommand().stream().filter(StringUtils::isNotBlank).toList();
        if (!configured.isEmpty())
        {
            return new Executable(configured, Origin.CONFIGURED, null);
        }
        if (settingsService.isSystemGalleryDlEnabled())
        {
            return new Executable(List.of(SYSTEM_EXECUTABLE), Origin.SYSTEM, null);
        }
        Path bundled = bundledPath();
        if (!Files.isRegularFile(bundled))
        {
            throw new GalleryDlException(GalleryDlException.Kind.NOT_RUNNABLE, "gallery-dl is not bundled for this "
                    + "system (" + bundled + " is missing). Install gallery-dl and tick \"Use the gallery-dl "
                    + "installed on this system\" in Settings.");
        }
        // Git checkouts and unpacked distributions can lose the executable bit.
        var file = bundled.toFile();
        if (!file.canExecute())
        {
            file.setExecutable(true);   // NOSONAR - best effort; a failure surfaces as the start failing
        }
        return new Executable(List.of(bundled.toString()), Origin.BUNDLED, bundled);
    }

    Path bundledPath()
    {
        Path binDir = Paths.get(appProperties.getGalleryDl().getBinDir()).toAbsolutePath().normalize();
        return SystemUtils.IS_OS_WINDOWS ? binDir.resolve("win").resolve(WINDOWS_EXECUTABLE)
                       : binDir.resolve("linux").resolve(LINUX_EXECUTABLE);
    }

    /** The app's own temp folder for gallery-dl, dot-named so the data folder's listing never takes it for a chapter. */
    Path tempDir()
    {
        return Paths.get(appProperties.getDataDir()).toAbsolutePath().normalize().resolve(".gallery-dl");
    }

    // ---- running -------------------------------------------------------------------------------------------

    /**
     * Starts gallery-dl with {@code args} and waits for it. stdout goes to {@code stdoutLines} line by line as it
     * arrives, or is collected into the result when that is null; stderr is kept as the last lines.
     * {@code stdoutLines} is never called once this returns or throws, so the caller may remove what it wrote.
     *
     * @param idleTimeout the run is killed after this long without a line on either stream
     * @param timeout     the run is killed after this long in any case; null for no limit
     * @throws GalleryDlException     NOT_RUNNABLE when it cannot start, UPDATING while an update runs, OTHER on a
     *                                timeout or a failing listener
     * @throws InterruptedIOException when the calling thread is interrupted; the run is killed first
     */
    public Result run(List<String> args, Consumer<String> stdoutLines, Duration idleTimeout, Duration timeout)
            throws IOException
    {
        Lock lock = updateLock.readLock();
        if (!lock.tryLock())
        {
            throw new GalleryDlException(GalleryDlException.Kind.UPDATING,
                    "gallery-dl is being updated; the download is retried once that has finished.");
        }
        try
        {
            return runUnlocked(executable(), args, stdoutLines, idleTimeout, timeout);
        }
        finally
        {
            lock.unlock();
        }
    }

    private Result runUnlocked(Executable executable, List<String> args, Consumer<String> stdoutLines,
                               Duration idleTimeout, Duration timeout) throws IOException
    {
        var command = new ArrayList<>(executable.command());
        command.addAll(args);
        Process process = start(command, executable);
        Path record = recordRun(process);
        var activity = new Activity();
        var stderr = new StderrTail();
        var collected = stdoutLines == null ? new StringBuilder() : null;
        var listenerFailure = new IOException[1];
        // Guards collected and listenerFailure. Closed before this method ends, so a line read after a timeout, an
        // interrupt or a reader outlasting its join reaches nobody; a line being handled is waited for.
        var listening = new boolean[]{true};
        Thread outReader = Thread.ofVirtual().name("gallery-dl-stdout").start(() ->
                readLines(process.getInputStream(), activity, line ->
                {
                    synchronized (listening)
                    {
                        if (!listening[0] || listenerFailure[0] != null)
                        {
                            return;
                        }
                        if (collected != null)
                        {
                            if (collected.length() < MAX_COLLECTED_CHARS)
                            {
                                collected.append(line).append('\n');
                            }
                            return;
                        }
                        try
                        {
                            stdoutLines.accept(line);
                        }
                        catch (RuntimeException e)
                        {
                            listenerFailure[0] = e.getCause() instanceof IOException io ? io : new IOException(e);
                            killTree(process);
                        }
                    }
                }));
        Thread errReader = Thread.ofVirtual().name("gallery-dl-stderr").start(() ->
                readLines(process.getErrorStream(), activity, line ->
                {
                    log.debug("gallery-dl: {}", line);
                    stderr.add(line);
                }));
        try
        {
            waitFor(process, activity, idleTimeout, timeout);
            // The readers end with the pipes; waited for only while lines still arrive or are being handled, since
            // a grandchild can inherit a pipe and keep it open. A cut would drop pages gallery-dl finished.
            joinWhileActive(outReader, activity);
            joinWhileActive(errReader, activity);
        }
        catch (InterruptedException e)
        {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while gallery-dl was running");
        }
        catch (IOException e)
        {
            killTree(process);
            throw e;
        }
        finally
        {
            synchronized (listening)
            {
                listening[0] = false;
            }
            forget(process, record);
        }
        if (listenerFailure[0] != null)
        {
            throw listenerFailure[0];
        }
        return new Result(process.exitValue(), stderr.lines(), collected == null ? null : collected.toString());
    }

    private Process start(List<String> command, Executable executable) throws GalleryDlException
    {
        var builder = new ProcessBuilder(command);
        Path temp = tempDir();
        try
        {
            Files.createDirectories(temp);
        }
        catch (IOException e)
        {
            throw new GalleryDlException(GalleryDlException.Kind.NOT_RUNNABLE,
                    "Could not create gallery-dl's temp folder " + temp + ": " + e.getMessage(), e);
        }
        Map<String, String> env = builder.environment();
        // Paths and JSON in UTF-8 whatever the console's code page.
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        // The one-file executable unpacks itself here, and gallery-dl copies a browser's cookie database here.
        env.put("TMP", temp.toString());
        env.put("TEMP", temp.toString());
        env.put("TMPDIR", temp.toString());
        builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        try
        {
            Process process = builder.start();
            synchronized (running)
            {
                running.add(process);
            }
            return process;
        }
        catch (IOException e)
        {
            throw new GalleryDlException(GalleryDlException.Kind.NOT_RUNNABLE, "Could not start gallery-dl ("
                    + executable.describe() + "): " + e.getMessage() + (executable.origin() == Origin.SYSTEM
                    ? ". Is gallery-dl installed and on the PATH? Settings can switch back to the bundled copy."
                    : ""), e);
        }
    }

    private static java.io.File nullDevice()
    {
        return new java.io.File(SystemUtils.IS_OS_WINDOWS ? "NUL" : "/dev/null");
    }

    /** Last time either stream printed something. */
    private static final class Activity
    {
        private volatile long lastNanos = System.nanoTime();

        void touch()
        {
            lastNanos = System.nanoTime();
        }

        Duration idle()
        {
            return Duration.ofNanos(System.nanoTime() - lastNanos);
        }
    }

    /**
     * The last lines of stderr, with gallery-dl's errors and warnings kept apart from the rest: {@code -v} prints a
     * line per HTTP request, which would push out the error lines a failure is classified by
     * ({@link GalleryDl#classify}), and an early failed page would then look like a killed run.
     */
    private static final class StderrTail
    {
        private record Line(long seq, String text)
        {
        }

        private final Deque<Line> reported = new ArrayDeque<>();
        private final Deque<Line> other = new ArrayDeque<>();
        private long seq;

        synchronized void add(String line)
        {
            Deque<Line> kept = GalleryDl.LOG_LINE.matcher(line.strip()).matches() ? reported : other;
            kept.addLast(new Line(seq++, line));
            if (kept.size() > STDERR_LINES_KEPT)
            {
                kept.removeFirst();
            }
        }

        /** In the order they were printed. */
        synchronized List<String> lines()
        {
            return Stream.concat(reported.stream(), other.stream())
                    .sorted(Comparator.comparingLong(Line::seq))
                    .map(Line::text)
                    .toList();
        }
    }

    private static void readLines(InputStream stream, Activity activity, Consumer<String> sink)
    {
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                activity.touch();
                sink.accept(line);
                activity.touch();
            }
        }
        catch (IOException e)
        {
            // The process was killed or ended; whatever it printed has been passed on.
        }
    }

    private static void joinWhileActive(Thread reader, Activity activity) throws InterruptedException
    {
        while (!reader.join(Duration.ofSeconds(1)) && activity.idle().compareTo(READER_IDLE_LIMIT) < 0)
        {
            // Still reading or handling lines.
        }
    }

    private void waitFor(Process process, Activity activity, Duration idleTimeout, Duration timeout)
            throws InterruptedException, GalleryDlException
    {
        Instant deadline = timeout == null ? null : Instant.now().plus(timeout);
        while (!process.waitFor(1, TimeUnit.SECONDS))
        {
            if (idleTimeout != null && activity.idle().compareTo(idleTimeout) > 0)
            {
                killTree(process);
                throw new GalleryDlException(GalleryDlException.Kind.OTHER, "gallery-dl printed nothing for "
                        + idleTimeout.toSeconds() + " s and was stopped.");
            }
            if (deadline != null && Instant.now().isAfter(deadline))
            {
                killTree(process);
                throw new GalleryDlException(GalleryDlException.Kind.OTHER, "gallery-dl did not finish within "
                        + timeout.toSeconds() + " s and was stopped.");
            }
        }
    }

    /** Children first, so the one-file bootloader sees its child end and cleans up after it. */
    static void killTree(Process process)
    {
        killTree(process.toHandle());
    }

    static void killTree(ProcessHandle root)
    {
        ProcessTrees.killTree(root, true, BOOTLOADER_GRACE);
    }

    // ---- what outlives the app -----------------------------------------------------------------------------

    /** pid and start time, so the next start can tell this process from a later one that got the same pid. */
    private Path recordRun(Process process)
    {
        long startMillis = ProcessTrees.startMillis(process.toHandle());
        Path record = tempDir().resolve(RUN_RECORD_PREFIX + process.pid());
        try
        {
            Files.writeString(record, Long.toString(startMillis));
            return record;
        }
        catch (IOException e)
        {
            log.warn("Could not record the gallery-dl run (pid {}): {}", process.pid(), e.toString());
            return null;
        }
    }

    private void forget(Process process, Path record)
    {
        synchronized (running)
        {
            running.remove(process);
        }
        if (record != null)
        {
            try
            {
                Files.deleteIfExists(record);
            }
            catch (IOException e)
            {
                log.debug("Could not delete {}: {}", record, e.toString());
            }
        }
    }

    /**
     * Before the download worker starts (it starts on {@code ApplicationReadyEvent}): a run left by a killed app
     * is stopped, then everything in the temp folder goes - nothing of ours is running any more.
     */
    @PostConstruct
    void removeLeftovers()
    {
        Path temp = tempDir();
        if (Files.isDirectory(temp))
        {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(temp))
            {
                for (Path entry : entries)
                {
                    String name = entry.getFileName().toString();
                    if (name.startsWith(RUN_RECORD_PREFIX) && Files.isRegularFile(entry))
                    {
                        stopLeftover(entry, name.substring(RUN_RECORD_PREFIX.length()));
                    }
                }
            }
            catch (IOException e)
            {
                log.warn("Could not look for gallery-dl leftovers in {}: {}", temp, e.toString());
            }
            ImageService.deleteRecursively(temp);
        }
        // Left by a self-update on Windows when its delayed delete did not run.
        try
        {
            Files.deleteIfExists(Paths.get(bundledPath() + ".old"));
        }
        catch (IOException e)
        {
            log.debug("Could not delete an old gallery-dl executable: {}", e.toString());
        }
    }

    private static void stopLeftover(Path record, String pidText)
    {
        try
        {
            long pid = Long.parseLong(pidText);
            long startMillis = Long.parseLong(Files.readString(record).strip());
            ProcessTrees.stillRunning(pid, startMillis)
                    .ifPresent(handle ->
                    {
                        log.info("Stopping gallery-dl (pid {}) left running by a previous start of the app", pid);
                        killTree(handle);
                    });
        }
        catch (IOException | NumberFormatException e)
        {
            log.debug("Ignoring the unreadable gallery-dl record {}: {}", record, e.toString());
        }
    }

    /** A graceful stop: the download worker no longer waits for the item, so its run must not go on alone. */
    @PreDestroy
    void stopRunning()
    {
        List<Process> processes;
        synchronized (running)
        {
            processes = List.copyOf(running);
        }
        processes.forEach(GalleryDlTool::killTree);
    }

    // ---- version and update --------------------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void checkVersionInBackground()
    {
        refreshVersionAsync();
    }

    /**
     * Never waits for gallery-dl: a one-file executable takes a few seconds to start on Windows, and Settings must
     * not. An unknown or outdated answer starts a check and says so.
     */
    public Status status()
    {
        Executable executable;
        try
        {
            executable = executable();
        }
        catch (GalleryDlException e)
        {
            return new Status(null, null, e.getMessage(), false);
        }
        CachedVersion cached = cachedVersion;
        if (cached != null && cached.command().equals(executable.command())
                && cached.fileModified() == modified(executable))
        {
            // The last answer is shown while a check that may change it runs.
            if (!cached.current(executable))
            {
                refreshVersionAsync();
            }
            return new Status(executable, cached.version(), cached.problem(), false);
        }
        refreshVersionAsync();
        return new Status(executable, null, null, true);
    }

    public void refreshVersionAsync()
    {
        synchronized (this)
        {
            if (versionCheckRunning)
            {
                return;
            }
            versionCheckRunning = true;
        }
        Thread.ofVirtual().name("gallery-dl-version").start(() ->
        {
            try
            {
                refreshVersion();
            }
            finally
            {
                versionCheckRunning = false;
            }
        });
    }

    /** Waits for the answer; for tests, and after an update. */
    public void refreshVersion()
    {
        Executable executable;
        try
        {
            executable = executable();
        }
        catch (GalleryDlException e)
        {
            cachedVersion = null;
            return;
        }
        String version = null;
        String problem;
        try
        {
            Result result = run(List.of("--version"), null,
                    null, Duration.ofSeconds(Math.max(1, appProperties.getGalleryDl().getVersionTimeoutSeconds())));
            String out = StringUtils.strip(result.stdout());
            if (result.exitCode() == 0 && StringUtils.isNotEmpty(out))
            {
                version = out.lines().reduce((first, second) -> second).orElse(out).strip();
                problem = null;
            }
            else
            {
                problem = GalleryDl.describeExit(result.exitCode(), result.stderr());
            }
        }
        catch (IOException e)
        {
            problem = e.getMessage();
        }
        cachedVersion = new CachedVersion(executable.command(), modified(executable), version, problem,
                Instant.now());
    }

    private static long modified(Executable executable)
    {
        if (executable.file() == null)
        {
            return 0L;
        }
        try
        {
            return Files.getLastModifiedTime(executable.file()).toMillis();
        }
        catch (IOException e)
        {
            return -1L;
        }
    }

    /**
     * Runs the bundled copy's own updater ({@code -U}), which fetches the latest release from gallery-dl's site.
     * Refused while a download uses gallery-dl: an executable cannot be replaced while it runs (Windows), and the
     * download would run half on each version.
     */
    public UpdateOutcome update()
    {
        Executable executable;
        try
        {
            executable = executable();
        }
        catch (GalleryDlException e)
        {
            return new UpdateOutcome(false, e.getMessage(), List.of());
        }
        if (executable.origin() != Origin.BUNDLED)
        {
            return new UpdateOutcome(false, "Only the gallery-dl bundled with the app is updated from here; update "
                    + "the one " + executable.origin().label() + " the way it was installed.", List.of());
        }
        Lock lock = updateLock.writeLock();
        if (!lockForUpdate(lock))
        {
            return new UpdateOutcome(false, "A download is using gallery-dl right now. Pause the download queue "
                    + "or wait for the current item, then try again.", List.of());
        }
        try
        {
            String before = Optional.ofNullable(cachedVersion).map(CachedVersion::version).orElse(null);
            Result result = runUnlocked(executable, List.of("-U"), null, null,
                    Duration.ofSeconds(Math.max(1, appProperties.getGalleryDl().getUpdateTimeoutSeconds())));
            List<String> output = new ArrayList<>();
            if (result.stdout() != null)
            {
                result.stdout().lines().filter(StringUtils::isNotBlank).forEach(output::add);
            }
            output.addAll(result.stderr());
            output = output.size() > 30 ? output.subList(output.size() - 30, output.size()) : output;
            lock.unlock();
            lock = null;
            refreshVersion();
            String after = Optional.ofNullable(cachedVersion).map(CachedVersion::version).orElse(null);
            if (result.exitCode() != 0)
            {
                return new UpdateOutcome(false, "gallery-dl could not update itself ("
                        + GalleryDl.describeExit(result.exitCode(), result.stderr()) + ").", output);
            }
            String message = after == null ? "gallery-dl ran its update."
                    : Objects.equals(before, after) ? "gallery-dl " + after + " is the latest version."
                    : "gallery-dl updated" + (before == null ? "" : " from " + before) + " to " + after + ".";
            log.info("{}", message);
            return new UpdateOutcome(true, message, output);
        }
        catch (IOException e)
        {
            return new UpdateOutcome(false, "gallery-dl could not update itself: " + e.getMessage(), List.of());
        }
        finally
        {
            if (lock != null)
            {
                lock.unlock();
            }
        }
    }

    /**
     * A version check holds the read lock for seconds and ends by itself (opening Settings starts one), so it is
     * waited for; a download is not, since it can run for an hour.
     */
    private boolean lockForUpdate(Lock lock)
    {
        if (lock.tryLock())
        {
            return true;
        }
        if (!versionCheckRunning)
        {
            return false;
        }
        try
        {
            return lock.tryLock(Math.max(1, appProperties.getGalleryDl().getVersionTimeoutSeconds()), TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Whether an update holds gallery-dl, so a run would be refused (UPDATING) without starting. */
    public boolean isUpdating()
    {
        return updateLock.isWriteLocked();
    }

    /** For tests: whether a run is in progress. */
    boolean isRunning()
    {
        synchronized (running)
        {
            return !running.isEmpty();
        }
    }
}

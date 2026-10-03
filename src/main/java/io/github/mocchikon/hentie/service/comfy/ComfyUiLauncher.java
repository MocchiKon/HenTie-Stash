package io.github.mocchikon.hentie.service.comfy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.LibraryBusyException;
import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Starts ComfyUI with the user's own start script, and stops only what it started.
 *
 * <p><b>A script, not a command line the app builds</b>: every kind of install starts differently, and only
 * its owner knows how. It runs in its own folder, because portable launchers use relative paths.
 *
 * <p><b>Never a second ComfyUI</b>: starting is skipped when something already answers. Stopping kills the
 * whole process tree, because the script is usually a shell whose child is the Python holding the GPU.
 *
 * <p><b>Only a script still running can be stopped.</b> One that starts ComfyUI in the background returns
 * with 0; the address then decides, and a ComfyUI that answers is {@link State#DETACHED}, not a failed start.
 *
 * <p><b>Nothing the app writes may become the script</b> ({@link #problemWith}): uploads land in the app's
 * folders, and a shell would run any text file.
 *
 * <p><b>A killed app cannot stop anything</b>, so the launch is recorded as pid + start time and the next app
 * start stops it. The start time matters because pids are reused. From Settings the record is written on a
 * thread of its own ({@link #persistLaunchRecordLater}): the process has changed by then, so a busy library must
 * neither refuse the request nor keep it waiting.
 *
 * <p><b>The output is always drained</b>, or the child blocks on a full pipe. The last {@value #CONSOLE_LINES}
 * lines are kept for Settings, the one place a failed start can explain itself: the app's own log carries
 * ComfyUI's output only at DEBUG, and the app is often used from another device.
 */
@Service
@RequiredArgsConstructor
public class ComfyUiLauncher
{
    private static final Logger log = LoggerFactory.getLogger(ComfyUiLauncher.class);

    /** A logger of its own, so ComfyUI's output can be turned up without the app's. */
    private static final Logger comfyOutput = LoggerFactory.getLogger("comfyui");

    static final int CONSOLE_LINES = 200;

    /** A progress bar redrawn without a newline would otherwise become one enormous line. */
    private static final int MAX_LINE_LENGTH = 1000;

    /** Terminal colour and cursor codes, which are noise on the Settings page. */
    private static final Pattern TERMINAL_CODES = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");

    private static final Duration STOP_GRACE = Duration.ofSeconds(10);

    private static final long READY_POLL_MILLIS = 1_000;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final ComfyUiClient client;
    private final SettingsService settingsService;
    private final AppProperties appProperties;
    private final ScratchSpace scratchSpace;
    private final WriteGate writeGate;

    /** Guards every field below, and is what {@link #awaitStartup} waits on. */
    private final Object lock = new Object();

    private final Deque<String> console = new ArrayDeque<>();
    private State state = State.NOT_LAUNCHED;
    /** Null once the script has ended, however ComfyUI fares. */
    private Process process;
    /** Kept after the script ends, so a startup watch can tell whether it still follows the latest launch. */
    private Process launched;
    private Integer exitCode;
    private String message;
    private boolean stopping;
    /**
     * {@code <pid>:<start millis>}, blank for none. Set under the lock but written to Settings outside it
     * ({@link #persistLaunchRecord}), so {@link #status} never waits for SQLite's writer.
     */
    private String launchRecord = "";

    /**
     * Keeps the Settings writes in order; guards {@link #persistedRecord}. A lock, not a monitor: it is held while the
     * write waits for the library, and a virtual thread (the startup ones) blocked in {@code synchronized} would pin
     * its carrier for as long. {@link #shutdown} can also give up waiting for it.
     */
    private final ReentrantLock recordLock = new ReentrantLock();
    /** Blank until {@link #stopLeftoverFromLastRun} has read the previous run's record. */
    private String persistedRecord = "";

    @Getter
    @RequiredArgsConstructor
    public enum State
    {
        /** ComfyUI may still be running, started by the user. */
        NOT_LAUNCHED("Not started by the app"),
        STARTING("Starting"),
        RUNNING("Running (started by the app)"),
        NOT_ANSWERING("Started by the app, but not answering"),
        /** The script returned while starting it, so the app has nothing to stop it by. */
        DETACHED("Running, but the start script let go of it"),
        EXITED("Exited"),
        FAILED("Could not be started");

        private final String displayName;
    }

    public record Status(State state, Long pid, Integer exitCode, String message, List<String> console) {}

    // ---- lifecycle ---------------------------------------------------------

    /**
     * At startup, not on first use, because ComfyUI can take minutes to load. Off the event thread, since
     * stopping a leftover can wait 10 s and the other ready listeners must not wait behind it. The leftover goes
     * first, so its address is free.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady()
    {
        Thread.ofVirtual().name("comfyui-startup").start(() ->
        {
            stopLeftoverFromLastRun();
            if (!settingsService.isComfyUiAutostart())
            {
                return;
            }
            try
            {
                log.info("ComfyUI autostart: {}", start());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Writes the record before returning: a thread of its own could still be waiting when the database closes. It
     * waits only as long as a request would, so a library held by another program cannot keep the app from
     * stopping. A record left unwritten names a process that has ended, which the next start leaves alone.
     */
    @PreDestroy
    public void shutdown()
    {
        stopProcess();
        try
        {
            if (!recordLock.tryLock(appProperties.getWrites().getRequestWaitMillis(), TimeUnit.MILLISECONDS))
            {
                log.warn("Could not record that ComfyUI was stopped: an earlier record is still waiting for the "
                        + "library");
                return;
            }
            try
            {
                writeGate.withinRequestBudget(this::writeLaunchRecord);
            }
            finally
            {
                recordLock.unlock();
            }
        }
        catch (LibraryBusyException e)
        {
            log.warn("Could not record that ComfyUI was stopped: {}", e.getMessage());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    // ---- start / stop ------------------------------------------------------

    /** @return what happened, in words for the Settings page */
    public String start() throws InterruptedException
    {
        synchronized (lock)
        {
            if (isLaunchUnderway())
            {
                return "ComfyUI is already running - the app started it.";
            }
        }
        // Outside the lock: a network call status() must not wait for.
        if (client.isReachable())
        {
            return "ComfyUI already answers at " + client.baseUrl() + ", so nothing was started.";
        }
        try
        {
            synchronized (lock)
            {
                if (isLaunchUnderway())
                {
                    return "ComfyUI is already running - the app started it.";
                }
                Optional<Path> script = scriptPath(settingsService.getComfyUiStartScript());
                if (script.isEmpty())
                {
                    return fail("No start script is set - enter the script that starts ComfyUI in Settings.");
                }
                Optional<String> problem = problemWith(script.get());
                if (problem.isPresent())
                {
                    return fail(StringUtils.capitalize(problem.get()) + ".");
                }
                return launch(script.get());
            }
        }
        finally
        {
            persistLaunchRecordLater();
        }
    }

    /**
     * A script in a folder the app writes into is refused, so no upload can be run; outside Windows it must
     * carry its executable bit, which no file the app writes has. Checked on saving and on every start.
     *
     * @return the problem as a clause ("the start script ... does not exist"), empty when it may run
     */
    public Optional<String> problemWith(Path script)
    {
        if (!Files.isRegularFile(script))
        {
            return Optional.of("the start script " + script + " does not exist");
        }
        if (scratchSpace.isInAppFolder(script))
        {
            return Optional.of("the start script " + script + " is inside a folder the app writes into (the data"
                    + " folder or a temporary-files folder) - keep it outside them, so nothing the app stores can"
                    + " be run");
        }
        if (!WINDOWS && !Files.isExecutable(script))
        {
            return Optional.of("the start script " + script + " is not executable - make it so with chmod +x");
        }
        return Optional.empty();
    }

    public String stop()
    {
        String answer = stopProcess();
        persistLaunchRecordLater();
        return answer;
    }

    private String stopProcess()
    {
        Process running;
        synchronized (lock)
        {
            running = process;
            if (running == null && (state == State.DETACHED || state == State.STARTING))
            {
                return "The start script returned while ComfyUI went on running, so the app has nothing to stop it "
                        + "by - stop it yourself, and keep it in the foreground in the script (see COMFYUI.md).";
            }
            if (running == null)
            {
                return "The app did not start ComfyUI, so it was left alone.";
            }
            stopping = true;
        }
        killTree(running.toHandle());
        synchronized (lock)
        {
            if (process == running)
            {
                process = null;
                state = State.NOT_LAUNCHED;
                message = "Stopped.";
                forgetLaunch();
                lock.notifyAll();
            }
            stopping = false;
        }
        return "ComfyUI was stopped.";
    }

    // ---- what the rest of the app asks -------------------------------------

    public Status status()
    {
        synchronized (lock)
        {
            return new Status(state, process == null ? null : process.pid(), exitCode, message, List.copyOf(console));
        }
    }

    public boolean isStarting()
    {
        synchronized (lock)
        {
            return state == State.STARTING;
        }
    }

    /** So a page asked for right after startup waits for ComfyUI instead of failing with "cannot connect". */
    public void awaitStartup(Duration max) throws InterruptedException
    {
        long deadline = System.nanoTime() + max.toNanos();
        synchronized (lock)
        {
            while (state == State.STARTING)
            {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMillis <= 0)
                {
                    return;
                }
                lock.wait(remainingMillis);
            }
        }
    }

    // ---- plumbing ----------------------------------------------------------

    /**
     * A batch file runs directly because {@code CreateProcess} passes it to {@code cmd.exe} with correct quoting
     * for spaces and parentheses, which {@code cmd /c} would not. Elsewhere never through {@code /bin/sh}, which
     * would run any text file.
     */
    static List<String> command(Path script, boolean windows)
    {
        String file = script.toAbsolutePath().toString();
        if (windows && script.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ps1"))
        {
            return List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", file);
        }
        return List.of(file);
    }

    /** Empty for a blank or malformed value; surrounding quotes are allowed. */
    public static Optional<Path> scriptPath(String value)
    {
        if (StringUtils.isBlank(value))
        {
            return Optional.empty();
        }
        try
        {
            return Optional.of(Paths.get(StringUtils.strip(value, " \t\"")).toAbsolutePath().normalize());
        }
        catch (InvalidPathException e)
        {
            return Optional.empty();
        }
    }

    /** Called with {@link #lock} held. */
    private String launch(Path script)
    {
        List<String> command = command(script, WINDOWS);
        var builder = new ProcessBuilder(command)
                .directory(script.getParent().toFile())
                .redirectErrorStream(true);
        builder.environment().put("PYTHONUNBUFFERED", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        console.clear();
        appendLine("> " + String.join(" ", command));
        Process started;
        try
        {
            started = builder.start();
        }
        catch (IOException e)
        {
            return fail("Could not run " + script + ": " + e.getMessage());
        }
        try
        {
            // A launcher that ends in "pause" waits for a key; end of input is that key.
            started.getOutputStream().close();
        }
        catch (IOException ignored)
        {
            // nothing reads it anyway
        }
        process = started;
        launched = started;
        state = State.STARTING;
        exitCode = null;
        message = null;
        rememberLaunch(started);
        drain(started);
        started.onExit().thenAccept(this::exited);
        Thread.ofVirtual().name("comfyui-startup").start(() -> watchStartup(started));
        log.info("Started ComfyUI (pid {}) with {}", started.pid(), script);
        return "Starting ComfyUI - it can take a minute before it answers.";
    }

    /** A script that returned with 0 is still followed: it may have started ComfyUI in the background. */
    private void watchStartup(Process started)
    {
        long deadline = System.currentTimeMillis() + Math.max(1, appProperties.getComfyui().getStartupTimeoutSeconds()) * 1000L;
        try
        {
            while ((started.isAlive() || started.exitValue() == 0) && System.currentTimeMillis() < deadline)
            {
                if (client.isReachable())
                {
                    answered(started);
                    return;
                }
                Thread.sleep(READY_POLL_MILLIS);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (lock)
        {
            if (launched != started || state != State.STARTING)
            {
                return;
            }
            if (process == started && started.isAlive())
            {
                state = State.NOT_ANSWERING;
                message = "ComfyUI was started but does not answer at " + client.baseUrl() + " after "
                        + appProperties.getComfyui().getStartupTimeoutSeconds() + " s. Check that the address in "
                        + "Settings matches the one ComfyUI prints below.";
            }
            else
            {
                // Nothing ever answered, so ComfyUI exited. exited() replaces this with a non-zero code.
                state = State.EXITED;
                message = "ComfyUI exited with code 0 before it answered - its last output is below.";
            }
            lock.notifyAll();
        }
    }

    private void answered(Process started)
    {
        try
        {
            answeredLocked(started);
        }
        finally
        {
            persistLaunchRecord();
        }
    }

    private void answeredLocked(Process started)
    {
        synchronized (lock)
        {
            if (launched != started || state != State.STARTING)
            {
                return;
            }
            if (process == started && started.isAlive())
            {
                state = State.RUNNING;
            }
            else
            {
                if (process == started)
                {
                    // Ended before exited() ran; it will find this launch handled.
                    process = null;
                    exitCode = started.exitValue();
                    forgetLaunch();
                }
                state = State.DETACHED;
                message = "ComfyUI answers, but the start script returned while starting it, so the app cannot "
                        + "stop it. Keep ComfyUI in the foreground in the script - see COMFYUI.md.";
            }
            lock.notifyAll();
        }
    }

    private void exited(Process ended)
    {
        boolean reported;
        synchronized (lock)
        {
            if (process != ended || stopping)
            {
                return;
            }
            boolean wasAnswering = state == State.RUNNING;
            process = null;
            exitCode = ended.exitValue();
            forgetLaunch();
            // A 0 while starting may be a background start; watchStartup decides by the address.
            reported = exitCode != 0 || state != State.STARTING;
            if (reported)
            {
                state = State.EXITED;
                message = "ComfyUI exited with code " + exitCode + (wasAnswering ? "" : " before it answered")
                        + " - its last output is below.";
                lock.notifyAll();
            }
        }
        persistLaunchRecord();
        if (reported)
        {
            log.warn("ComfyUI started by the app exited with code {}", ended.exitValue());
        }
    }

    private void drain(Process started)
    {
        Thread.ofVirtual().name("comfyui-output").start(() ->
        {
            try (var reader = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    comfyOutput.debug(line);
                    synchronized (lock)
                    {
                        appendLine(line);
                    }
                }
            }
            catch (IOException ignored)
            {
                // the process is gone; its output with it
            }
        });
    }

    /** Called with {@link #lock} held. */
    private void appendLine(String line)
    {
        console.addLast(StringUtils.abbreviate(TERMINAL_CODES.matcher(line).replaceAll(""), MAX_LINE_LENGTH));
        while (console.size() > CONSOLE_LINES)
        {
            console.removeFirst();
        }
    }

    /** Called with {@link #lock} held. */
    private String fail(String reason)
    {
        state = State.FAILED;
        message = reason;
        lock.notifyAll();
        return reason;
    }

    /** Called with {@link #lock} held. Includes a script that returned while ComfyUI may still be coming up. */
    private boolean isLaunchUnderway()
    {
        return process != null && process.isAlive() || state == State.STARTING;
    }

    /** Called with {@link #lock} held; {@link #persistLaunchRecord} writes it. */
    private void rememberLaunch(Process started)
    {
        long startMillis = started.toHandle().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        launchRecord = started.pid() + ":" + startMillis;
    }

    /** Called with {@link #lock} held; {@link #persistLaunchRecord} writes it. */
    private void forgetLaunch()
    {
        launchRecord = "";
    }

    /**
     * For a request thread. A thread that serves no request waits for the library as long as it must, so the record
     * is written even behind a long merge, and a later write catches up with anything written meanwhile. A platform
     * thread, since waiting for the library is all it does: a virtual one would pin its carrier while SQLite's native
     * code waits for the lock.
     */
    private void persistLaunchRecordLater()
    {
        Thread.ofPlatform().daemon().name("comfyui-record").start(() ->
        {
            try
            {
                persistLaunchRecord();
            }
            catch (RuntimeException e)
            {
                // The next write stores the latest record, so nothing is lost for good unless the app dies first.
                log.warn("Could not record the ComfyUI the app launched", e);
            }
        });
    }

    /** Called without {@link #lock}. */
    private void persistLaunchRecord()
    {
        recordLock.lock();
        try
        {
            writeLaunchRecord();
        }
        finally
        {
            recordLock.unlock();
        }
    }

    /**
     * Called with {@link #recordLock} held. Reads the latest value at write time, so racing writes cannot store an
     * older one.
     */
    private void writeLaunchRecord()
    {
        String record;
        synchronized (lock)
        {
            record = launchRecord;
        }
        if (!record.equals(persistedRecord))
        {
            settingsService.setLaunchedComfyUi(record);
            persistedRecord = record;
        }
    }

    /** Only a process whose start time matches the record: a reused pid could belong to anything. */
    void stopLeftoverFromLastRun()
    {
        String record = settingsService.getLaunchedComfyUi();
        if (StringUtils.isBlank(record))
        {
            return;
        }
        String[] parts = record.split(":");
        try
        {
            long pid = Long.parseLong(parts[0]);
            long startMillis = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            if (startMillis > 0)
            {
                ProcessHandle.of(pid)
                        .filter(handle -> handle.info().startInstant().map(Instant::toEpochMilli).orElse(-1L) == startMillis)
                        .ifPresent(handle ->
                        {
                            log.info("Stopping the ComfyUI (pid {}) the app started before it was last closed", pid);
                            killTree(handle);
                        });
            }
        }
        catch (NumberFormatException e)
        {
            log.warn("Ignoring an unreadable record of a launched ComfyUI: {}", record);
        }
        recordLock.lock();
        try
        {
            persistedRecord = record;
        }
        finally
        {
            recordLock.unlock();
        }
        synchronized (lock)
        {
            // Start may have been pressed meanwhile; keep that launch's record, or a later kill leaves it unrecorded.
            if (launched == null)
            {
                forgetLaunch();
            }
        }
        persistLaunchRecord();
    }

    /** Descendants are listed before anything is stopped: once a parent is gone, its children cannot be found. */
    static void killTree(ProcessHandle root)
    {
        List<ProcessHandle> tree = new ArrayList<>(root.descendants().toList());
        tree.add(root);
        tree.forEach(ProcessHandle::destroy);
        try
        {
            CompletableFuture.allOf(tree.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new))
                    .get(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException | ExecutionException e)
        {
            tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }
        catch (InterruptedException e)
        {
            tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            Thread.currentThread().interrupt();
        }
    }
}

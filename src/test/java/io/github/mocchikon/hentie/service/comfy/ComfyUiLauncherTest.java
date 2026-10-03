package io.github.mocchikon.hentie.service.comfy;

import io.github.mocchikon.hentie.FakeComfyUi;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Uses real processes, because what is under test is how the OS runs a script and what it leaves behind: a
 * child that keeps going, output that must be drained, a pid recorded for a killed run.
 */
class ComfyUiLauncherTest
{
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Nothing listens here, so the launcher's "does something already answer" check says no. */
    private static final String DEAD_ADDRESS = "http://127.0.0.1:1";

    @TempDir Path tmp;

    private final AppProperties appProperties = new AppProperties();
    private final SettingsService settingsService = mock(SettingsService.class);
    private final ScratchSpace scratchSpace = mock(ScratchSpace.class);
    private final Map<String, String> settings = new HashMap<>();
    private ComfyUiClient client;
    private ComfyUiLauncher launcher;

    @BeforeEach
    void setUp()
    {
        appProperties.getComfyui().setConnectTimeoutSeconds(1);
        appProperties.getComfyui().setStartupTimeoutSeconds(60);
        settings.put(SettingsService.COMFYUI_URL, DEAD_ADDRESS);
        when(settingsService.getComfyUiUrl()).thenAnswer(call -> settings.get(SettingsService.COMFYUI_URL));
        when(settingsService.getComfyUiStartScript())
                .thenAnswer(call -> settings.getOrDefault(SettingsService.COMFYUI_START_SCRIPT, ""));
        when(settingsService.getLaunchedComfyUi())
                .thenAnswer(call -> settings.getOrDefault(SettingsService.COMFYUI_LAUNCHED, ""));
        doAnswer(call -> settings.put(SettingsService.COMFYUI_LAUNCHED, call.getArgument(0)))
                .when(settingsService).setLaunchedComfyUi(anyString());
        client = new ComfyUiClient(appProperties, settingsService);
        launcher = new ComfyUiLauncher(client, settingsService, appProperties, scratchSpace,
                new WriteGate(appProperties));
    }

    @AfterEach
    void tearDown()
    {
        launcher.stop();
        client.close();
    }

    @Test
    void shouldRunTheScriptInItsOwnFolderAndKeepItsOutput() throws Exception
    {
        // GIVEN
        Path script = longRunningScript();
        settings.put(SettingsService.COMFYUI_START_SCRIPT, script.toString());

        // WHEN
        String answer = launcher.start();

        // THEN it is starting, recorded by pid and start time, and its output is collected as printed.
        assertThat(answer).startsWith("Starting ComfyUI");
        ComfyUiLauncher.Status status = launcher.status();
        assertThat(status.state()).isEqualTo(ComfyUiLauncher.State.STARTING);
        String record = status.pid() + ":"
                + ProcessHandle.of(status.pid()).orElseThrow().info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        await(() -> record.equals(settings.get(SettingsService.COMFYUI_LAUNCHED)));
        await(() -> launcher.status().console().stream().anyMatch(line -> line.contains("second line")));
        // The portable launchers use paths relative to their own folder, so that is where the script runs.
        String cwd = launcher.status().console().stream().filter(line -> line.startsWith("cwd="))
                .findFirst().orElseThrow().substring(4).strip();
        assertThat(Path.of(cwd).toRealPath()).isEqualTo(script.getParent().toRealPath());
    }

    /** The script's process is a shell; the ComfyUI it runs is its child, and must not outlive it. */
    @Test
    void shouldStopTheWholeProcessTree() throws Exception
    {
        // GIVEN a running script whose child is up.
        settings.put(SettingsService.COMFYUI_START_SCRIPT, longRunningScript().toString());
        launcher.start();
        ProcessHandle root = ProcessHandle.of(launcher.status().pid()).orElseThrow();
        await(() -> root.descendants().findAny().isPresent());
        List<ProcessHandle> tree = root.descendants().toList();

        // WHEN
        String answer = launcher.stop();

        // THEN
        assertThat(answer).isEqualTo("ComfyUI was stopped.");
        await(() -> !root.isAlive() && tree.stream().noneMatch(ProcessHandle::isAlive));
        assertThat(launcher.status().state()).isEqualTo(ComfyUiLauncher.State.NOT_LAUNCHED);
        assertThat(launcher.status().pid()).isNull();
        await(() -> settings.get(SettingsService.COMFYUI_LAUNCHED).isEmpty());
    }

    /** The process has started by the time the record is written, so a busy library must not hold the answer up. */
    @Test
    void shouldAnswerBeforeTheRecordIsWrittenAndWriteItOnceTheLibraryIsFree() throws Exception
    {
        // GIVEN a library that takes no write until released.
        var libraryFree = new CountDownLatch(1);
        doAnswer(call ->
        {
            libraryFree.await();
            return settings.put(SettingsService.COMFYUI_LAUNCHED, call.getArgument(0));
        }).when(settingsService).setLaunchedComfyUi(anyString());
        settings.put(SettingsService.COMFYUI_START_SCRIPT, longRunningScript().toString());

        // WHEN
        String answer = launcher.start();

        // THEN it is started and answered for, with the record still to come...
        assertThat(answer).startsWith("Starting ComfyUI");
        assertThat(launcher.status().state()).isEqualTo(ComfyUiLauncher.State.STARTING);
        assertThat(settings.get(SettingsService.COMFYUI_LAUNCHED)).isNull();

        // ...which is written once the library is free.
        libraryFree.countDown();
        long pid = launcher.status().pid();
        await(() -> settings.getOrDefault(SettingsService.COMFYUI_LAUNCHED, "").startsWith(pid + ":"));
        // A child started after the tear-down listed the tree would outlive it and keep the folder in use.
        await(() -> ProcessHandle.of(pid).orElseThrow().descendants().findAny().isPresent());
    }

    /** Another program may hold the library for good, and the app must still stop. */
    @Test
    @Timeout(60)
    void shouldStopComfyUiAndReturnFromShutdownWhileTheRecordStillWaitsForTheLibrary() throws Exception
    {
        // GIVEN a launch whose record is being written into a library that takes no write until released.
        appProperties.getWrites().setRequestWaitMillis(200);
        var writing = new CountDownLatch(1);
        var libraryFree = new CountDownLatch(1);
        doAnswer(call ->
        {
            writing.countDown();
            libraryFree.await();
            return settings.put(SettingsService.COMFYUI_LAUNCHED, call.getArgument(0));
        }).when(settingsService).setLaunchedComfyUi(anyString());
        settings.put(SettingsService.COMFYUI_START_SCRIPT, longRunningScript().toString());
        launcher.start();
        ProcessHandle root = ProcessHandle.of(launcher.status().pid()).orElseThrow();
        writing.await();
        try
        {
            // WHEN
            launcher.shutdown();

            // THEN it returned without the record, and ComfyUI was stopped anyway.
            await(() -> !root.isAlive());
            assertThat(settings.get(SettingsService.COMFYUI_LAUNCHED)).isNull();
        }
        finally
        {
            libraryFree.countDown();
        }
    }

    @Test
    void shouldReportAScriptThatExitsWithItsCodeAndLastOutput() throws Exception
    {
        // GIVEN
        settings.put(SettingsService.COMFYUI_START_SCRIPT, script("fail", WINDOWS
                ? "@echo off\r\necho failing now\r\nexit /b 3\r\n"
                : "#!/bin/sh\necho failing now\nexit 3\n").toString());

        // WHEN
        launcher.start();

        // THEN
        await(() -> launcher.status().state() == ComfyUiLauncher.State.EXITED);
        ComfyUiLauncher.Status status = launcher.status();
        assertThat(status.exitCode()).isEqualTo(3);
        assertThat(status.message()).contains("exited with code 3").contains("before it answered");
        assertThat(status.console()).anyMatch(line -> line.contains("failing now"));
        await(this::launchRecordCleared);
    }

    /** ComfyUI colours its log for a console; on the Settings page the codes would only be noise. */
    @Test
    void shouldDropTerminalColourCodesFromTheOutput() throws Exception
    {
        // GIVEN a script printing a line the way ComfyUI's logger colours it.
        settings.put(SettingsService.COMFYUI_START_SCRIPT, script("colour", WINDOWS
                ? "@echo off\r\necho \u001B[32m[INFO]\u001B[0m To see the GUI go to: x\r\n"
                : "#!/bin/sh\nprintf '\\033[32m[INFO]\\033[0m To see the GUI go to: x\\n'\n").toString());

        // WHEN
        launcher.start();

        // THEN
        await(() -> launcher.status().console().contains("[INFO] To see the GUI go to: x"));
        assertThat(launcher.status().console()).noneMatch(line -> line.contains(""));
    }

    /**
     * A script that starts ComfyUI in the background returns at once with code 0. That is not ComfyUI exiting,
     * but the app has nothing left to stop it by.
     */
    @Test
    void shouldReportAComfyUiItsScriptLetGoOfAsDetached() throws Exception
    {
        try (var comfy = new FakeComfyUi())
        {
            // GIVEN a script that returned at once, and nothing answering yet.
            settings.put(SettingsService.COMFYUI_START_SCRIPT, returningScript().toString());
            launcher.start();
            await(() -> launcher.status().pid() == null);
            assertThat(launcher.status().state()).isEqualTo(ComfyUiLauncher.State.STARTING);
            await(this::launchRecordCleared);

            // WHEN ComfyUI starts answering at the address.
            settings.put(SettingsService.COMFYUI_URL, comfy.url());

            // THEN it is running, out of the app's hands, and stopping says so.
            await(() -> launcher.status().state() == ComfyUiLauncher.State.DETACHED);
            ComfyUiLauncher.Status status = launcher.status();
            assertThat(status.pid()).isNull();
            assertThat(status.exitCode()).isZero();
            assertThat(status.message()).contains("start script returned").contains("COMFYUI.md");
            assertThat(launcher.stop()).contains("nothing to stop it by");
        }
    }

    @Test
    void shouldReportAScriptThatReturnedCleanlyWithNothingEverAnsweringAsExited() throws Exception
    {
        // GIVEN
        appProperties.getComfyui().setStartupTimeoutSeconds(2);
        settings.put(SettingsService.COMFYUI_START_SCRIPT, returningScript().toString());

        // WHEN
        launcher.start();

        // THEN
        await(() -> launcher.status().state() == ComfyUiLauncher.State.EXITED);
        ComfyUiLauncher.Status status = launcher.status();
        assertThat(status.exitCode()).isZero();
        assertThat(status.message()).contains("exited with code 0 before it answered");
        await(this::launchRecordCleared);
    }

    /** The ComfyUI a returned script started may still be loading; a second one must not start on its address. */
    @Test
    void shouldNotLaunchAgainWhileAReturnedScriptsComfyUiMayStillBeComingUp() throws Exception
    {
        // GIVEN a script that returned, its ComfyUI not answering yet.
        settings.put(SettingsService.COMFYUI_START_SCRIPT, returningScript().toString());
        launcher.start();
        await(() -> launcher.status().pid() == null);

        // WHEN
        String again = launcher.start();

        // THEN
        assertThat(again).contains("already running");
        assertThat(launcher.status().state()).isEqualTo(ComfyUiLauncher.State.STARTING);
    }

    @Test
    void shouldReportRunningOnceTheAddressAnswers() throws Exception
    {
        try (var comfy = new FakeComfyUi())
        {
            // GIVEN a launch that is starting, not answering yet.
            settings.put(SettingsService.COMFYUI_START_SCRIPT, longRunningScript().toString());
            launcher.start();
            assertThat(launcher.status().state()).isEqualTo(ComfyUiLauncher.State.STARTING);

            // WHEN ComfyUI starts answering at the address.
            settings.put(SettingsService.COMFYUI_URL, comfy.url());

            // THEN
            await(() -> launcher.status().state() == ComfyUiLauncher.State.RUNNING);
            launcher.awaitStartup(Duration.ofSeconds(1));
        }
    }

    /** Never a second ComfyUI: one the user started is used, and nothing is launched beside it. */
    @Test
    void shouldNotStartAnythingWhenComfyUiAlreadyAnswers() throws Exception
    {
        try (var comfy = new FakeComfyUi())
        {
            // GIVEN
            settings.put(SettingsService.COMFYUI_URL, comfy.url());
            settings.put(SettingsService.COMFYUI_START_SCRIPT, longRunningScript().toString());

            // WHEN
            String answer = launcher.start();

            // THEN
            assertThat(answer).contains("already answers at " + comfy.url());
            assertThat(launcher.status().pid()).isNull();
            assertThat(launcher.status().state()).isEqualTo(ComfyUiLauncher.State.NOT_LAUNCHED);
            verify(settingsService, never()).setLaunchedComfyUi(anyString());
            // ...and stopping leaves a ComfyUI the app did not start alone.
            assertThat(launcher.stop()).contains("left alone");
        }
    }

    @Test
    void shouldRefuseAMissingOrUnsetScript() throws Exception
    {
        assertThat(launcher.start()).contains("No start script is set");

        settings.put(SettingsService.COMFYUI_START_SCRIPT, tmp.resolve("nowhere.bat").toString());
        assertThat(launcher.start()).contains("does not exist");
        assertThat(launcher.status().state()).isEqualTo(ComfyUiLauncher.State.FAILED);
    }

    /** What users upload and sources send lives in the app's folders; none of it may be run. */
    @Test
    void shouldRefuseAScriptInsideAFolderTheAppWritesInto() throws Exception
    {
        // GIVEN
        Path script = longRunningScript();
        when(scratchSpace.isInAppFolder(script)).thenReturn(true);
        settings.put(SettingsService.COMFYUI_START_SCRIPT, script.toString());

        // WHEN
        String answer = launcher.start();

        // THEN
        assertThat(answer).contains("inside a folder the app writes into");
        assertThat(launcher.status().state()).isEqualTo(ComfyUiLauncher.State.FAILED);
        assertThat(launcher.status().pid()).isNull();
        verify(settingsService, never()).setLaunchedComfyUi(anyString());
    }

    /** Handed to a shell, any text file would run as a script - an upload included, which never has the bit. */
    @Test
    void shouldRefuseAScriptWithoutTheExecutableBitOnPosix() throws Exception
    {
        Assumptions.assumeTrue(tmp.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX only");
        // GIVEN
        Path script = Files.writeString(tmp.resolve("plain.sh"), "#!/bin/sh\nsleep 120\n");
        settings.put(SettingsService.COMFYUI_START_SCRIPT, script.toString());

        // WHEN
        String answer = launcher.start();

        // THEN
        assertThat(answer).contains("is not executable");
        assertThat(launcher.status().state()).isEqualTo(ComfyUiLauncher.State.FAILED);
        assertThat(launcher.status().pid()).isNull();
    }

    /** Only a process whose start time matches the record is stopped, because the OS reuses pids. */
    @Test
    void shouldStopWhatAKilledRunLeftButNothingWithAReusedPid() throws Exception
    {
        // GIVEN two processes: one recorded exactly, one recorded with another start time.
        Process leftover = new ProcessBuilder(ComfyUiLauncher.command(longRunningScript(), WINDOWS)).start();
        Process stranger = new ProcessBuilder(ComfyUiLauncher.command(longRunningScript(), WINDOWS)).start();
        try
        {
            settings.put(SettingsService.COMFYUI_LAUNCHED, leftover.pid() + ":" + startMillis(leftover));

            // WHEN
            launcher.stopLeftoverFromLastRun();

            // THEN
            await(() -> !leftover.isAlive());
            assertThat(settings.get(SettingsService.COMFYUI_LAUNCHED)).isEmpty();

            // WHEN the record names a pid whose process started at another time
            settings.put(SettingsService.COMFYUI_LAUNCHED, stranger.pid() + ":" + (startMillis(stranger) - 5_000));
            launcher.stopLeftoverFromLastRun();

            // THEN it is not touched, and the record is dropped all the same.
            assertThat(stranger.isAlive()).isTrue();
            assertThat(settings.get(SettingsService.COMFYUI_LAUNCHED)).isEmpty();
        }
        finally
        {
            ComfyUiLauncher.killTree(leftover.toHandle());
            ComfyUiLauncher.killTree(stranger.toHandle());
        }
    }

    @Test
    void shouldRunEachKindOfScriptTheWayItsPlatformDoes()
    {
        Path bat = tmp.resolve("run.bat");
        Path ps1 = tmp.resolve("Run.PS1");
        Path sh = tmp.resolve("run.sh");

        assertThat(ComfyUiLauncher.command(bat, true)).containsExactly(bat.toAbsolutePath().toString());
        assertThat(ComfyUiLauncher.command(ps1, true)).containsExactly("powershell.exe", "-NoProfile",
                "-ExecutionPolicy", "Bypass", "-File", ps1.toAbsolutePath().toString());
        // Never through a shell, which would run any text file: by its executable bit and its #! line.
        assertThat(ComfyUiLauncher.command(sh, false)).containsExactly(sh.toAbsolutePath().toString());
    }

    @Test
    void shouldRunAnExecutableScriptDirectlyOnPosix() throws IOException
    {
        Assumptions.assumeTrue(tmp.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX only");
        Path script = Files.writeString(tmp.resolve("comfy"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));

        assertThat(ComfyUiLauncher.command(script, false)).containsExactly(script.toAbsolutePath().toString());
    }

    @Test
    void shouldForgiveQuotesAroundAPastedScriptPath()
    {
        assertThat(ComfyUiLauncher.scriptPath("  \"" + tmp.resolve("a b").resolve("run.bat") + "\"  "))
                .contains(tmp.resolve("a b").resolve("run.bat").toAbsolutePath().normalize());
        assertThat(ComfyUiLauncher.scriptPath("   ")).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------

    /** Prints its folder and two lines, then runs a child that sleeps - the shape of a real launcher. */
    private Path longRunningScript() throws IOException
    {
        return script("comfy", WINDOWS
                ? "@echo off\r\necho cwd=%CD%\r\necho first line\r\necho second line\r\nping -n 120 127.0.0.1 > nul\r\n"
                : "#!/bin/sh\necho \"cwd=$(pwd)\"\necho first line\necho second line\nsleep 120\n");
    }

    /** Prints a line and returns with code 0, as a script that starts ComfyUI in the background does. */
    private Path returningScript() throws IOException
    {
        return script("background", WINDOWS
                ? "@echo off\r\necho started in the background\r\n"
                : "#!/bin/sh\necho started in the background\n");
    }

    /** A script in a folder of its own, executable where that is a file's own bit. */
    private Path script(String name, String content) throws IOException
    {
        Path folder = Files.createDirectories(tmp.resolve("ComfyUI install"));
        Path script = Files.writeString(folder.resolve(name + (WINDOWS ? ".bat" : ".sh")), content);
        if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix"))
        {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        }
        return script;
    }

    private static long startMillis(Process process)
    {
        return process.toHandle().info().startInstant().map(Instant::toEpochMilli).orElseThrow();
    }

    /**
     * Awaited, not asserted: the record is written after the state changes, possibly on another thread. And a launch
     * that ends before its record was ever written writes nothing, since there is no change to store.
     */
    private boolean launchRecordCleared()
    {
        return settings.getOrDefault(SettingsService.COMFYUI_LAUNCHED, "").isEmpty();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean())
        {
            if (System.currentTimeMillis() > deadline)
            {
                fail("condition not met within 20 s");
            }
            Thread.sleep(50);
        }
    }
}

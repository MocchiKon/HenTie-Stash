package io.github.mocchikon.hentie.service.compress;

import io.github.mocchikon.hentie.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Driven with ordinary OS commands rather than the image tools, so it runs on every platform. */
class ImageToolRunnerTest
{
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /**
     * The hanging tool keeps its output open: reading that output to the end before the timed wait would
     * block for as long as the tool lives, and a stuck encoder would hold the download worker for ever.
     */
    @Test
    void shouldKillAToolThatRunsPastTheTimeoutEvenWhileItKeepsItsOutputOpen()
    {
        // GIVEN a one-second timeout and a command that runs for half a minute.
        var runner = runnerWithTimeout(1);
        List<String> hangs = WINDOWS
                ? List.of("ping", "-n", "30", "127.0.0.1")
                : List.of("sleep", "30");

        // WHEN
        long startedAt = System.nanoTime();
        boolean succeeded = runner.run(hangs);
        long tookMillis = (System.nanoTime() - startedAt) / 1_000_000;

        // THEN it is reported as failed, long before the command would have finished on its own.
        assertThat(succeeded).isFalse();
        assertThat(tookMillis).isLessThan(15_000);
    }

    @Test
    void shouldReportSuccessWhenTheToolExitsWithZero()
    {
        // GIVEN
        var runner = runnerWithTimeout(30);

        // WHEN + THEN
        assertThat(runner.run(shell("exit 0"))).isTrue();
    }

    /** A failure, never an exception. */
    @Test
    void shouldReportFailureWhenTheToolExitsWithNonZero()
    {
        // GIVEN
        var runner = runnerWithTimeout(30);

        // WHEN + THEN
        assertThat(runner.run(shell("echo about to fail && exit 3"))).isFalse();
    }

    /** A failure too, not an exception. */
    @Test
    void shouldReportFailureWhenTheToolDoesNotExist()
    {
        // GIVEN
        var runner = runnerWithTimeout(30);

        // WHEN + THEN
        assertThat(runner.run(List.of("hentie-no-such-image-tool"))).isFalse();
    }

    private static ImageToolRunner runnerWithTimeout(int seconds)
    {
        var properties = new AppProperties();
        properties.getImageCompression().setTimeoutSeconds(seconds);
        return new ImageToolRunner(properties);
    }

    private static List<String> shell(String script)
    {
        return WINDOWS ? List.of("cmd", "/c", script) : List.of("sh", "-c", script);
    }
}

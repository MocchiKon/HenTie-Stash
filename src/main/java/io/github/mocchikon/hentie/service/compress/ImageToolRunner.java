package io.github.mocchikon.hentie.service.compress;

import io.github.mocchikon.hentie.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The only place in the app that starts a process. It never throws: any failure means "keep the original
 * image", so a download never fails because a codec did. A timeout kills a hung tool, which would otherwise
 * hold the worker thread for ever.
 */
@Component
@RequiredArgsConstructor
public class ImageToolRunner
{
    private static final Logger log = LoggerFactory.getLogger(ImageToolRunner.class);

    /** Tool chatter kept in the failure log line; enough to see the real error, not a whole dump. */
    private static final int MAX_LOGGED_OUTPUT = 2000;

    /** How long the failure log line waits for the tool's output after the tool has exited. */
    private static final int OUTPUT_GRACE_SECONDS = 5;

    private final AppProperties appProperties;

    public boolean run(List<String> command)
    {
        var builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = null;
        try
        {
            process = builder.start();
            // Drain on another thread, never before waitFor: a read to EOF returns only when the tool exits,
            // so reading first would disable the timeout for exactly the tool that hangs.
            CompletableFuture<String> output = drainAsync(process.getInputStream());
            if (!process.waitFor(timeoutSeconds(), TimeUnit.SECONDS))
            {
                // Killing the tool closes the pipe, which lets the drain finish.
                process.destroyForcibly();
                output.cancel(true);
                log.error("Image tool timed out after {}s and was killed: {}", timeoutSeconds(),
                        String.join(" ", command));
                return false;
            }
            int exit = process.exitValue();
            if (exit != 0)
            {
                String text = outputOf(output);
                log.error("Image tool failed with exit code {}: {}{}", exit, String.join(" ", command),
                        StringUtils.isBlank(text) ? "" : System.lineSeparator()
                                + StringUtils.abbreviate(text.strip(), MAX_LOGGED_OUTPUT));
            }
            return exit == 0;
        }
        catch (IOException e)
        {
            // Usually the tool is missing (no bundled binary, or system tools not installed).
            log.error("Could not run image tool {} - {}", String.join(" ", command), e.toString());
            return false;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            if (process != null)
            {
                process.destroyForcibly();
            }
            return false;
        }
    }

    /** Reads while the tool runs: a tool that fills an unread pipe buffer blocks for ever. */
    private static CompletableFuture<String> drainAsync(InputStream stream)
    {
        var result = new CompletableFuture<String>();
        Thread.ofVirtual().name("image-tool-output").start(() ->
        {
            try (stream)
            {
                result.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            catch (IOException e)
            {
                result.complete("");
            }
        });
        return result;
    }

    /** Bounded even after exit: a grandchild process can inherit the pipe and keep it open. */
    private static String outputOf(CompletableFuture<String> output) throws InterruptedException
    {
        try
        {
            return output.get(OUTPUT_GRACE_SECONDS, TimeUnit.SECONDS);
        }
        catch (ExecutionException | TimeoutException e)
        {
            return "";
        }
    }

    private int timeoutSeconds()
    {
        return Math.max(1, appProperties.getImageCompression().getTimeoutSeconds());
    }
}

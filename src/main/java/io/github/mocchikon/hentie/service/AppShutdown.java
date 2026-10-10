package io.github.mocchikon.hentie.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Settings &rarr; <b>"Shut down"</b>, a graceful stop from any device, and the stop after too many wrong passwords.
 * Ctrl+C in the console is the other one; closing the console window is not, since Windows kills the process a few
 * seconds later.
 */
@Service
@RequiredArgsConstructor
public class AppShutdown
{
    private static final Logger log = LoggerFactory.getLogger(AppShutdown.class);

    /**
     * Once shutdown starts the server takes no new requests, so the browser first gets a moment to fetch
     * what the page it was just sent needs.
     */
    private static final Duration HEAD_START = Duration.ofSeconds(1);

    private final ApplicationContext context;
    private final AtomicBoolean requested = new AtomicBoolean();

    /**
     * Returns at once. The shutdown runs on a thread of its own because closing the context waits for the
     * requests still running: on the request thread it would wait for itself until the timeout. The thread is
     * not a daemon, so the JVM cannot exit halfway through the close.
     */
    public void shutDownSoon(String why)
    {
        if (!requested.compareAndSet(false, true))
        {
            return;
        }
        log.warn("Shutting down {}", why);
        Thread.ofPlatform().name("app-shutdown").daemon(false).start(() ->
        {
            try
            {
                Thread.sleep(HEAD_START);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            System.exit(SpringApplication.exit(context));
        });
    }
}

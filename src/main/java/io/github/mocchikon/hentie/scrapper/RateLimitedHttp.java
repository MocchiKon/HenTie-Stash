package io.github.mocchikon.hentie.scrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Sending to a site that limits its rate: wait for the endpoint's turn, and sit out a 429 or 503 that says how long
 * to wait. Shared, so every source slows down the same way when asked to.
 * <p>
 * <b>A refusal holds the endpoint's {@link RequestPacer} back</b>, so every caller of it slows down, not only the
 * one that was refused. Sites count their limits per IP, so the user browsing the site meanwhile uses them up too,
 * and a 429 is expected now and then.
 */
public final class RateLimitedHttp
{
    private static final Logger log = LoggerFactory.getLogger(RateLimitedHttp.class);

    /** How often one request sits out a 429 before it fails. */
    public static final int MAX_RATE_LIMIT_WAITS = 5;

    /** For a 429 that does not say how long to wait: limits are usually counted per minute. */
    private static final Duration DEFAULT_RATE_LIMIT_WAIT = Duration.ofMinutes(1);

    /** Longer waits are cut to this and asked again, so one answer cannot stall the worker for hours. */
    private static final Duration MAX_RATE_LIMIT_WAIT = Duration.ofMinutes(15);

    private RateLimitedHttp()
    {
    }

    /**
     * A 503 is sat out only when it says how long to wait ({@code Retry-After}): without that it is an outage, not
     * a limit, and waiting a minute would only delay the failure.
     *
     * @param site named in the log and in the failure, e.g. {@code "nhentai"}
     */
    public static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, RequestPacer pacer,
                                            Duration interval, String site) throws IOException
    {
        return send(client, request, HttpResponse.BodyHandlers.ofByteArray(), body -> { }, pacer, interval, site);
    }

    /**
     * The body as a stream, for an answer that may be far bigger than wanted: the caller reads what it needs and
     * closes it, which drops the connection instead of downloading the rest.
     */
    public static HttpResponse<InputStream> sendStreaming(HttpClient client, HttpRequest request, RequestPacer pacer,
                                                          Duration interval, String site) throws IOException
    {
        return send(client, request, HttpResponse.BodyHandlers.ofInputStream(), RateLimitedHttp::closeQuietly, pacer,
                interval, site);
    }

    private static void closeQuietly(InputStream body)
    {
        try
        {
            body.close();
        }
        catch (IOException e)
        {
            // Only dropping an answer that is not wanted.
        }
    }

    private static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                            Consumer<T> discard, RequestPacer pacer, Duration interval, String site)
            throws IOException
    {
        for (int waits = 0; ; waits++)
        {
            HttpResponse<T> response;
            try
            {
                pacer.await(interval);
                response = client.send(request, handler);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for " + site);
            }
            int status = response.statusCode();
            Optional<Duration> asked = retryAfter(response.headers(), Instant.now());
            if (status != 429 && !(status == 503 && asked.isPresent()
                    && response.headers().firstValue("Retry-After").isPresent()))
            {
                return response;
            }
            discard.accept(response.body());
            if (waits >= MAX_RATE_LIMIT_WAITS)
            {
                throw new IOException(site + " still answered HTTP " + status + " for " + request.uri().getPath()
                        + " after waiting " + waits + " times; try again later.");
            }
            Duration wait = asked.orElse(DEFAULT_RATE_LIMIT_WAIT);
            if (wait.compareTo(MAX_RATE_LIMIT_WAIT) > 0)
            {
                wait = MAX_RATE_LIMIT_WAIT;
            }
            log.warn("{} asks to slow down (HTTP {} for {}); waiting {} s before asking again",
                    site, status, request.uri().getPath(), wait.toSeconds());
            pacer.holdOff(wait);
        }
    }

    /**
     * {@code Retry-After} in seconds or as a date; else a rate-limit reset, which sites send as seconds to wait or
     * as an epoch time in seconds or milliseconds (told apart by size).
     */
    public static Optional<Duration> retryAfter(HttpHeaders headers, Instant now)
    {
        Optional<String> retryAfter = headers.firstValue("Retry-After").map(String::strip);
        if (retryAfter.isPresent())
        {
            String value = retryAfter.get();
            if (value.matches("\\d{1,9}"))
            {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
            }
            try
            {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Optional.of(untilOrZero(now, at));
            }
            catch (DateTimeParseException e)
            {
                // Not a date either; try the reset headers.
            }
        }
        for (String name : List.of("X-RateLimit-Reset", "RateLimit-Reset"))
        {
            Optional<String> reset = headers.firstValue(name).map(String::strip).filter(v -> v.matches("\\d{1,15}"));
            if (reset.isPresent())
            {
                long value = Long.parseLong(reset.get());
                if (value > 1_000_000_000_000L)
                {
                    return Optional.of(untilOrZero(now, Instant.ofEpochMilli(value)));
                }
                if (value > 1_000_000_000L)
                {
                    return Optional.of(untilOrZero(now, Instant.ofEpochSecond(value)));
                }
                return Optional.of(Duration.ofSeconds(value));
            }
        }
        return Optional.empty();
    }

    private static Duration untilOrZero(Instant now, Instant at)
    {
        Duration until = Duration.between(now, at);
        return until.isNegative() ? Duration.ZERO : until;
    }

}

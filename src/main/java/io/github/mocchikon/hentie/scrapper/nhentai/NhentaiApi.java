package io.github.mocchikon.hentie.scrapper.nhentai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mocchikon.hentie.scrapper.GalleryNotFoundException;
import io.github.mocchikon.hentie.scrapper.RequestPacer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Everything that goes over the wire to nhentai: the endpoints, the API key and the rate limits. What the answers
 * mean is {@link NhentaiDownloader}'s business.
 * <p>
 * <b>Each endpoint has its own {@link RequestPacer}</b>, because nhentai limits each endpoint on its own. A 429
 * holds that pacer back as long as the answer asks, so every caller of the endpoint slows down, not only the one
 * that was refused; nhentai says to treat a 429 as the signal to back off. The limits count per IP, so the user
 * browsing nhentai meanwhile uses them up too, and a 429 is expected now and then.
 */
final class NhentaiApi
{
    private static final Logger log = LoggerFactory.getLogger(NhentaiApi.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The list rarely changes; nhentai only asks that no client hardcodes it. */
    private static final Duration SERVER_LIST_TTL = Duration.ofHours(1);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /** How often one request sits out a 429 before it fails. */
    static final int MAX_RATE_LIMIT_WAITS = 5;

    /** For a 429 that does not say how long to wait: nhentai's limits count per minute. */
    private static final Duration DEFAULT_RATE_LIMIT_WAIT = Duration.ofMinutes(1);

    /** Longer waits are cut to this and asked again, so one answer cannot stall the worker for hours. */
    private static final Duration MAX_RATE_LIMIT_WAIT = Duration.ofMinutes(15);

    private final NhentaiProperties properties;
    private final Supplier<String> apiKey;
    /** Never follows a redirect, which would send the API key to whatever host it names. */
    private final HttpClient api;
    /** Follows redirects: it carries no key. */
    private final HttpClient images;

    private final RequestPacer galleryPacer = new RequestPacer();
    private final RequestPacer favouritesPacer = new RequestPacer();
    private final RequestPacer serverListPacer = new RequestPacer();
    private final RequestPacer imagePacer = new RequestPacer();

    private volatile ServerList imageServers;

    /** Keyed by the API address, so a list from one never serves another (the tests switch fakes). */
    private record ServerList(String baseUrl, List<String> servers, long fetchedAtNanos)
    {
    }

    NhentaiApi(NhentaiProperties properties, Supplier<String> apiKey)
    {
        this.properties = properties;
        this.apiKey = apiKey;
        this.api = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.images = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    void close()
    {
        api.shutdownNow();
        images.shutdownNow();
    }

    /** Read on every call, so a key saved in Settings applies at once. */
    boolean hasApiKey()
    {
        return StringUtils.isNotBlank(apiKey.get());
    }

    /**
     * Sent with the API key when there is one: nhentai allows more requests a minute with it.
     *
     * @throws GalleryNotFoundException when nhentai has no such gallery
     */
    JsonNode gallery(String id) throws IOException
    {
        String key = currentKey();
        int perMinute = key == null
                ? properties.getGalleryRequestsPerMinute() : properties.getGalleryRequestsPerMinuteWithKey();
        HttpResponse<byte[]> response = send(api, apiRequest("/api/v2/galleries/" + id, key), galleryPacer,
                RequestPacer.interval(perMinute));
        if (response.statusCode() == 404)
        {
            throw new GalleryNotFoundException("nhentai has no gallery " + id + ".");
        }
        return json(response, "gallery " + id);
    }

    /** The favourites of the API key's owner, so it needs a key. */
    JsonNode favourites(int page) throws IOException
    {
        String key = currentKey();
        if (key == null)
        {
            throw new IOException("Listing your nhentai favourites needs your nhentai API key - set it in Settings.");
        }
        HttpResponse<byte[]> response = send(api, apiRequest("/api/v2/favorites?page=" + page, key), favouritesPacer,
                RequestPacer.interval(properties.getFavouritesRequestsPerMinute()));
        return json(response, "page " + page + " of your favourites");
    }

    /**
     * The servers a page path is appended to. A failed refresh keeps the last list: page paths outlive a list
     * that is merely old.
     */
    List<String> imageServers() throws IOException
    {
        String baseUrl = baseUrl();
        ServerList cached = imageServers;
        boolean usable = cached != null && cached.baseUrl().equals(baseUrl);
        if (usable && System.nanoTime() - cached.fetchedAtNanos() < SERVER_LIST_TTL.toNanos())
        {
            return cached.servers();
        }
        try
        {
            // Public: no key, and no limit is published for it.
            HttpResponse<byte[]> response = send(api, apiRequest("/api/v2/cdn", null), serverListPacer, Duration.ZERO);
            List<String> servers = new ArrayList<>();
            for (JsonNode server : json(response, "the list of image servers").path("image_servers"))
            {
                String address = StringUtils.removeEnd(StringUtils.strip(server.asText("")), "/");
                if (StringUtils.startsWithAny(address.toLowerCase(Locale.ROOT), "https://", "http://"))
                {
                    servers.add(address);
                }
            }
            if (servers.isEmpty())
            {
                throw new IOException("nhentai listed no image servers.");
            }
            ServerList fetched = new ServerList(baseUrl, List.copyOf(servers), System.nanoTime());
            imageServers = fetched;
            return fetched.servers();
        }
        catch (IOException e)
        {
            if (!usable || Thread.currentThread().isInterrupted())
            {
                throw e;
            }
            log.warn("Could not refresh nhentai's list of image servers ({}); keeping the one from before",
                    e.toString());
            return cached.servers();
        }
    }

    /**
     * Never sends the API key: the image servers need none, and their addresses come from nhentai's answers.
     *
     * @throws IOException also for an answer that is empty or plainly not an image, which would otherwise be
     *                     stored as a page
     */
    byte[] image(URI uri) throws IOException
    {
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
        {
            throw new IOException("Not a web address: " + uri);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout())
                .header("User-Agent", properties.getUserAgent())
                .GET()
                .build();
        HttpResponse<byte[]> response = send(images, request, imagePacer,
                Duration.ofMillis(Math.max(0, properties.getImageRequestIntervalMillis())));
        if (response.statusCode() != 200)
        {
            throw new IOException("The image server answered HTTP " + response.statusCode() + " for " + uri);
        }
        byte[] body = response.body();
        if (body == null || body.length == 0)
        {
            throw new IOException("The image server sent nothing for " + uri);
        }
        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (type.startsWith("text/") || type.contains("json"))
        {
            throw new IOException("The image server sent " + type + " instead of an image for " + uri);
        }
        return body;
    }

    // ---- plumbing ---------------------------------------------------------

    private String baseUrl()
    {
        return StringUtils.removeEnd(StringUtils.strip(properties.getBaseUrl()), "/");
    }

    private Duration requestTimeout()
    {
        return Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds()));
    }

    /** Null when none is set. Checked here, since a header cannot carry a line break. */
    private String currentKey() throws IOException
    {
        String key = StringUtils.trimToNull(apiKey.get());
        if (key != null && !key.chars().allMatch(c -> c > ' ' && c < 127))
        {
            throw new IOException("The nhentai API key in Settings contains characters an API key cannot have.");
        }
        return key;
    }

    private HttpRequest apiRequest(String pathAndQuery, String key)
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + pathAndQuery))
                .timeout(requestTimeout())
                .header("User-Agent", properties.getUserAgent())
                .header("Accept", "application/json")
                .GET();
        if (key != null)
        {
            builder.header("Authorization", "Key " + key);
        }
        return builder.build();
    }

    /** Waits for the endpoint's turn, and sits out a 429 as long as the answer asks, up to a limit. */
    private HttpResponse<byte[]> send(HttpClient client, HttpRequest request, RequestPacer pacer, Duration interval)
            throws IOException
    {
        for (int waits = 0; ; waits++)
        {
            HttpResponse<byte[]> response;
            try
            {
                pacer.await(interval);
                response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for nhentai");
            }
            if (response.statusCode() != 429)
            {
                return response;
            }
            if (waits >= MAX_RATE_LIMIT_WAITS)
            {
                throw new IOException("nhentai still answered 429 Too Many Requests for " + request.uri().getPath()
                        + " after waiting " + waits + " times; try again later.");
            }
            Duration wait = retryAfter(response.headers(), Instant.now()).orElse(DEFAULT_RATE_LIMIT_WAIT);
            if (wait.compareTo(MAX_RATE_LIMIT_WAIT) > 0)
            {
                wait = MAX_RATE_LIMIT_WAIT;
            }
            log.warn("nhentai asks to slow down (HTTP 429 for {}); waiting {} s before asking again",
                    request.uri().getPath(), wait.toSeconds());
            pacer.holdOff(wait);
        }
    }

    /**
     * {@code Retry-After} in seconds or as a date; else a rate-limit reset, which sites send as seconds to wait or
     * as an epoch time in seconds or milliseconds (told apart by size).
     */
    static Optional<Duration> retryAfter(HttpHeaders headers, Instant now)
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

    /** 401 and 403 are worded for the user: they are the key's fault, or the site's refusal, not a glitch. */
    private static JsonNode json(HttpResponse<byte[]> response, String what) throws IOException
    {
        int status = response.statusCode();
        if (status == 401)
        {
            throw new IOException("nhentai did not accept the API key (HTTP 401) when asked for " + what
                    + errorDetail(response) + ". Check the key in Settings.");
        }
        if (status != 200)
        {
            throw new IOException("nhentai answered HTTP " + status + " when asked for " + what
                    + errorDetail(response) + ".");
        }
        JsonNode node;
        try
        {
            node = JSON.readTree(response.body());
        }
        catch (IOException e)
        {
            throw new IOException("nhentai's answer for " + what + " is not JSON.", e);
        }
        if (node == null || !node.isObject())
        {
            throw new IOException("nhentai's answer for " + what + " is not a JSON object.");
        }
        return node;
    }

    /** nhentai explains an error as {@code {"error": "..."}}; anything else (a Cloudflare page) is left out. */
    private static String errorDetail(HttpResponse<byte[]> response)
    {
        try
        {
            JsonNode error = JSON.readTree(new String(response.body(), StandardCharsets.UTF_8)).path("error");
            return error.isTextual() && !error.textValue().isBlank()
                    ? " (" + StringUtils.abbreviate(error.textValue().strip(), 300) + ")" : "";
        }
        catch (IOException | RuntimeException e)
        {
            return "";
        }
    }
}

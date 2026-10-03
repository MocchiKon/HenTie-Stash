package io.github.mocchikon.hentie.scrapper.ehentai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mocchikon.hentie.scrapper.GalleryNotFoundException;
import io.github.mocchikon.hentie.scrapper.RateLimitedHttp;
import io.github.mocchikon.hentie.scrapper.RequestPacer;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Everything that goes over the wire to e-hentai's JSON API ({@code https://ehwiki.org/wiki/API}). What the answers
 * mean is {@link EhentaiDownloader}'s business.
 * <p>
 * <b>One pacer for every call</b>, at the rate the API's documentation allows ({@link EhentaiProperties}). It sends
 * no cookies: metadata needs none, and the user's cookies stay in their browser, read only by gallery-dl.
 */
final class EhentaiApi
{
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final EhentaiProperties properties;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final RequestPacer pacer = new RequestPacer();

    /** e-hentai answers a banned IP with this page instead of JSON. */
    static final class BannedException extends IOException
    {
        BannedException(String message)
        {
            super(message);
        }
    }

    EhentaiApi(EhentaiProperties properties)
    {
        this.properties = properties;
    }

    void close()
    {
        client.shutdownNow();
    }

    /**
     * One gallery's metadata ({@code gdata} with namespaced tags). One gallery per request: the pipeline handles one
     * gallery at a time, well under the 25 a request may ask for.
     *
     * @throws GalleryNotFoundException when e-hentai does not know the gid and token
     * @throws BannedException          when e-hentai banned this IP
     */
    JsonNode gallery(long gid, String token) throws IOException
    {
        ObjectNode body = JSON.createObjectNode();
        body.put("method", "gdata");
        ArrayNode pair = body.putArray("gidlist").addArray();
        pair.add(gid);
        pair.add(token);
        body.put("namespace", 1);
        HttpRequest request = HttpRequest.newBuilder(URI.create(StringUtils.strip(properties.getApiUrl())))
                .timeout(Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<byte[]> response = RateLimitedHttp.send(client, request, pacer,
                Duration.ofMillis(Math.max(0, properties.getApiRequestIntervalMillis())), "e-hentai");
        String text = new String(response.body(), StandardCharsets.UTF_8);
        JsonNode node;
        try
        {
            node = response.statusCode() == 200 ? JSON.readTree(text) : null;
        }
        catch (IOException e)
        {
            node = null;
        }
        // Only an answer that is no metadata is read as the ban page: a title or tag may hold the same words.
        if (node == null)
        {
            if (text.toLowerCase(Locale.ROOT).contains("temporarily banned"))
            {
                throw new BannedException("e-hentai has temporarily banned this IP address ("
                        + StringUtils.abbreviate(StringUtils.normalizeSpace(text), 300) + ")");
            }
            if (response.statusCode() != 200)
            {
                throw new IOException("e-hentai's API answered HTTP " + response.statusCode() + " for gallery " + gid
                        + "/" + token + ".");
            }
            throw new IOException("e-hentai's API answer for gallery " + gid + "/" + token + " is not JSON.");
        }
        if (node.hasNonNull("error") && !node.has("gmetadata"))
        {
            throw new IOException("e-hentai's API refused the request: " + node.path("error").asText());
        }
        JsonNode gallery = node.path("gmetadata").path(0);
        if (!gallery.isObject())
        {
            throw new IOException("e-hentai's API returned no metadata for gallery " + gid + "/" + token + ".");
        }
        if (gallery.hasNonNull("error"))
        {
            throw new GalleryNotFoundException("e-hentai has no gallery " + gid + "/" + token + " ("
                    + gallery.path("error").asText() + ").");
        }
        return gallery;
    }
}

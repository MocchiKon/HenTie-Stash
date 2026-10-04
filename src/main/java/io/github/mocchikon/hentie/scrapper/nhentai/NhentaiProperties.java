package io.github.mocchikon.hentie.scrapper.nhentai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.download.nhentai.*}. The nhentai source's own, not in {@code AppProperties}: a source's
 * configuration belongs to the source. As there, the field initializers are the only place a default is written.
 * <p>
 * The limits are the ones nhentai publishes per endpoint (its API docs at {@code /api/v2/docs}). Properties, so an
 * install can follow nhentai when it changes them, and the tests can lift them.
 */
@Component
@ConfigurationProperties(prefix = "app.download.nhentai")
@Data
public class NhentaiProperties
{
    /** Where the API is reached, never what links look like. A property so the tests can point it at a fake. */
    private String baseUrl = "https://nhentai.net";

    /** nhentai asks every client for a descriptive one: {@code AppName/version (contact or project URL)}. */
    private String userAgent = "HenTie/1.0";

    /** A gallery's details without an API key, per IP. 0 = no limit. */
    private int galleryRequestsPerMinute = 20;

    /** A gallery's details with an API key, per IP. 0 = no limit. */
    private int galleryRequestsPerMinuteWithKey = 45;

    /** Pages of the favourites list, per API key owner. 0 = no limit. */
    private int favouritesRequestsPerMinute = 15;

    /** Pages of a search, for subscriptions, without an API key, per IP. 0 = no limit. */
    private int searchRequestsPerMinute = 10;

    /** Pages of a search with an API key, per IP. 0 = no limit. */
    private int searchRequestsPerMinuteWithKey = 20;

    /**
     * Between two image requests. The image servers publish no number, only that rates "well beyond normal
     * browsing" get a client banned for a while; this keeps a long download near a reader's pace. 0 = no gap.
     */
    private long imageRequestIntervalMillis = 30;

    /** One request, an image included. */
    private int requestTimeoutSeconds = 60;
}

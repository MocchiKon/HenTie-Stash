package io.github.mocchikon.hentie.scrapper.ehentai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.download.ehentai.*}. The e-hentai source's own: a source's configuration belongs to the source. As in
 * {@code AppProperties}, the field initializers are the only place a default is written.
 */
@Component
@ConfigurationProperties(prefix = "app.download.ehentai")
@Data
public class EhentaiProperties
{
    /** Where the JSON API is reached, never what links look like. A property so the tests can point it at a fake. */
    private String apiUrl = "https://api.e-hentai.org/api.php";

    /**
     * Between two API requests. e-hentai's API documentation says "4-5 sequential requests usually okay before
     * having to wait for ~5 seconds": evenly spaced at 1.25 s, there are never more than 4 in any 5 seconds,
     * however the site counts. 0 = no gap.
     */
    private long apiRequestIntervalMillis = 1250;

    private int requestTimeoutSeconds = 60;

    /** Where e-hentai's search pages are fetched, never what links look like. A property so the tests can point it at a fake. */
    private String ehentaiUrl = "https://e-hentai.org";

    /** As {@link #ehentaiUrl}, for exhentai. */
    private String exhentaiUrl = "https://exhentai.org";

    /**
     * Between two search pages, of either domain: e-hentai counts both when it bans an IP for "excessive pageloads",
     * and gallery-dl itself waits 3-6 s between e-hentai's pages. Subscriptions list at download speed anyway. 0 = no
     * gap.
     */
    private long searchRequestIntervalMillis = 5000;

    /**
     * Sent with search pages. They are made for browsers, and a client naming itself stands out among them; the API
     * calls, which e-hentai documents for programs, keep the default.
     */
    private String browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0";

    /**
     * After a ban or a used-up image limit, how long every e-hentai item fails at once without asking the site:
     * requests during an IP ban can extend it, and the limit takes hours to recover.
     */
    private int cooldownMinutes = 60;
}

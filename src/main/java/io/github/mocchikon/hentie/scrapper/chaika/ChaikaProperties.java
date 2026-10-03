package io.github.mocchikon.hentie.scrapper.chaika;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.download.chaika.*}, the chaika source's own. As in {@code AppProperties}, the field initializers are the
 * only place a default is written.
 */
@Component
@ConfigurationProperties(prefix = "app.download.chaika")
@Data
public class ChaikaProperties
{
    /** Where the site is reached, never what links look like. A property so the tests can point it at a fake. */
    private String baseUrl = "https://panda.chaika.moe";

    /** Between two API requests. chaika publishes no limit; it is a small site. 0 = no gap. */
    private long apiRequestIntervalMillis = 1000;

    /** Between two reads from an archive (the index, a page), a reader's pace. 0 = no gap. */
    private long pageRequestIntervalMillis = 250;

    private int requestTimeoutSeconds = 120;

    /** A page larger than this is refused rather than inflated: a damaged or hostile archive could claim anything. */
    private long maxEntryBytes = 512L * 1024 * 1024;
}

package io.github.mocchikon.hentie.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * A subscription as the form edits it. Lengths are checked here because SQLite does not enforce {@code VARCHAR(n)};
 * what only the site can judge (the query, the site's own needs) is checked by the service through the source.
 */
@Data
public class SubscriptionForm
{
    /** Subscriptions look for new galleries this often at most; a site would see a client polling more often. */
    public static final int MIN_POLL_MINUTES = 10;

    private Integer id;

    @Size(max = 255, message = "Name must be at most 255 characters")
    private String name;

    @NotBlank(message = "Choose a site to search")
    @Size(max = 32, message = "Choose a site to search")
    private String source;

    @NotBlank(message = "Enter what to search for")
    @Size(max = 1000, message = "The search must be at most 1000 characters")
    private String query;

    @NotNull(message = "Enter how often to look for new galleries")
    @Min(value = MIN_POLL_MINUTES, message = "Look for new galleries every " + MIN_POLL_MINUTES + " minutes at most")
    @Max(value = 43200, message = "Look for new galleries at least every 30 days (43200 minutes)")
    private Integer pollMinutes = 60;

    /** 0 = never. */
    @NotNull(message = "Enter how often to re-check recent galleries, or 0 for never")
    @Min(value = 0, message = "Re-check every 0 hours (never) or more")
    @Max(value = 720, message = "Re-check at least every 30 days (720 hours)")
    private Integer recheckEveryHours = 24;

    @NotNull(message = "Enter how far back a re-check goes")
    @Min(value = 1, message = "A re-check goes back at least 1 hour")
    @Max(value = 720, message = "A re-check goes back at most 30 days (720 hours)")
    private Integer recheckDepthHours = 48;

    @Size(max = 64, message = "Choose one of the compression modes offered")
    private String compressionMode;

    private boolean avoidDuplicateTitles;

    @Size(max = 32, message = "Choose one of the browsers offered")
    private String cookiesBrowser;

    private boolean downloadOriginals;

    @Size(max = 20, message = "The delay must be at most 20 characters")
    private String requestDelay;
}

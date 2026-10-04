package io.github.mocchikon.hentie.entity;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * A saved search on a site whose galleries are queued for download on their own (see {@code service.subscription}).
 * <p>
 * <b>The walk is the head and tail of what was handled</b>: every gallery the search listed between
 * {@link #newestGalleryId} and {@link #oldestGalleryId} has been looked at, so a restart continues below the tail and
 * a check stops at the head. Galleries are compared by their position in the site's order, never by page, since
 * pages move while the site changes.
 * <p>
 * {@code @DynamicUpdate}: the runner moves the walk while the user may be editing the choices; an UPDATE that names
 * only what changed cannot undo the other's write.
 */
@Entity
@DynamicUpdate
@Table(name = "subscription")
@Getter
@Setter
public class Subscription
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** Optional; the query stands in for it. */
    @Column(name = "name", length = 255)
    private String name;

    /** A search site's key ({@code SearchSource.SearchSite}): stored, so a site's key is never renamed. */
    @Column(name = "source", nullable = false, length = 32)
    private String source;

    /** As the site's search takes it, normalized by the source. */
    @Column(name = "query", nullable = false, length = 1000)
    private String query;

    @Column(name = "poll_minutes", nullable = false)
    private int pollMinutes;

    /** 0 = never: each gallery is then judged once, when it first appears. */
    @Column(name = "recheck_every_hours", nullable = false)
    private int recheckEveryHours;

    @Column(name = "recheck_depth_hours", nullable = false)
    private int recheckDepthHours;

    /** A mode key; read when a gallery is queued, so an edit applies to what is queued from then on. */
    @Column(name = "compression_mode", nullable = false, length = 64,
            columnDefinition = "varchar(64) not null default 'NONE'")
    private String compressionMode = BuiltInCompressionMode.NONE.getKey();

    @Column(name = "avoid_duplicate_titles", nullable = false, columnDefinition = "boolean not null default 0")
    private boolean avoidDuplicateTitles;

    /** gallery-dl's choices, as on the Download page; read only by gallery-dl sources. */
    @Column(name = "cookies_browser", length = 32)
    private String cookiesBrowser;

    @Column(name = "download_originals", nullable = false, columnDefinition = "boolean not null default 0")
    private boolean downloadOriginals;

    @Column(name = "request_delay", nullable = false, length = 32)
    private String requestDelay;

    /** Paused when false: nothing is listed, but what it queued still downloads. */
    @Column(name = "enabled", nullable = false, columnDefinition = "boolean not null default 1")
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Bumped whenever the walk starts over (another source or query, "Start over"), so a page the runner fetched for
     * the old walk is dropped instead of moving the new one.
     */
    @Column(name = "revision", nullable = false, columnDefinition = "integer not null default 0")
    private int revision;

    // ---- the walk -------------------------------------------------------------------------------------------

    /** The head: never moves down, since the newest gallery may be deleted while it is the head. */
    @Column(name = "newest_gallery_id", length = 255)
    private String newestGalleryId;

    @Column(name = "oldest_gallery_id", length = 255)
    private String oldestGalleryId;

    /** The source's own way of continuing below the tail; a page number would point elsewhere once the site changes. */
    @Column(name = "oldest_cursor", length = 2000)
    private String oldestCursor;

    /** The walk below the tail found nothing older; only checks remain. */
    @Column(name = "reached_end", nullable = false, columnDefinition = "boolean not null default 0")
    private boolean reachedEnd;

    /** Set while a walk from the newest page down to {@link #catchUpStop} is under way; the head once it ends. */
    @Column(name = "catch_up_top", length = 255)
    private String catchUpTop;

    @Column(name = "catch_up_cursor", length = 2000)
    private String catchUpCursor;

    /** Where the catch-up ends: the head, or an older checkpoint for a re-check. Null: the end of the search. */
    @Column(name = "catch_up_stop", length = 255)
    private String catchUpStop;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_rechecked_at")
    private LocalDateTime lastRecheckedAt;

    /** As the site last counted the search's galleries, for "listed N of about M". */
    @Column(name = "result_total")
    private Integer resultTotal;

    // ---- what became of the galleries listed for the first time --------------------------------------------

    @Column(name = "queued_count", nullable = false, columnDefinition = "integer not null default 0")
    private int queuedCount;

    @Column(name = "in_library_count", nullable = false, columnDefinition = "integer not null default 0")
    private int inLibraryCount;

    @Column(name = "deleted_since_count", nullable = false, columnDefinition = "integer not null default 0")
    private int deletedSinceCount;

    @Column(name = "already_queued_count", nullable = false, columnDefinition = "integer not null default 0")
    private int alreadyQueuedCount;

    @Column(name = "blacklisted_count", nullable = false, columnDefinition = "integer not null default 0")
    private int blacklistedCount;

    // ---- the last failure -----------------------------------------------------------------------------------

    /** Null after a step that worked. */
    @Column(name = "last_error", length = 2000)
    private String lastError;

    /** With {@link #failedSteps}, how long the runner waits before the next try; stored, so a restart waits too. */
    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    @Column(name = "failed_steps", nullable = false, columnDefinition = "integer not null default 0")
    private int failedSteps;

    public int listedCount()
    {
        return queuedCount + inLibraryCount + deletedSinceCount + alreadyQueuedCount + blacklistedCount;
    }
}

package io.github.mocchikon.hentie.entity;

import java.time.LocalDateTime;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Half of the crash recovery: the row is deleted only after the last step, so a killed app finds it still
 * queued and re-runs it from the top (every step is idempotent). {@link DownloadStatus} is the other half.
 *
 * <p>No "in progress" state on purpose: one written before a power cut would be a lie. {@code attempts}
 * counts only failures, so a link that fails after every restart still runs out of them.
 */
@Entity
@Table(name = "download_queue", indexes = {
        // Serves both the worker's pending-in-order query and the failed list.
        @Index(name = "ix_download_queue__error_id", columnList = "error, id")
})
@Getter
@Setter
public class DownloadQueueItem
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "link", nullable = false, unique = true, length = 1000)
    private String link;

    /** Namespaced, e.g. {@code "mock:12"}. */
    @Column(name = "gallery_id")
    private String galleryId;

    /** Written only by a recorded failure, so it is null for a row that never failed. */
    @Column(name = "chapter_id")
    private Integer chapterId;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    /** Null while pending; non-null is the only "failed" marker. */
    @Column(name = "error", length = 2000)
    private String error;

    /**
     * Set only by "Retry ignoring image errors"; a plain retry clears it, so a briefly unreachable page is
     * never dropped silently. On the row because the retry runs later and must survive a restart.
     */
    @Column(name = "ignore_image_errors", nullable = false, columnDefinition = "boolean not null default 0")
    private boolean ignoreImageErrors = false;

    /**
     * Mode key picked on the paste form. Not read from Settings at run time, because that may have changed
     * by the time this row runs.
     */
    @Column(name = "compression_mode", nullable = false, length = 64,
            columnDefinition = "varchar(64) not null default 'NONE'")
    private String compressionMode = BuiltInCompressionMode.NONE.getKey();

    /**
     * Refuse a new gallery whose full title a chapter from another source already has. A paste replaces it;
     * retries keep it, except "Retry allowing duplicate title", which clears it - any other retry of a
     * refusal would only be refused again.
     */
    @Column(name = "avoid_duplicate_titles", nullable = false, columnDefinition = "boolean not null default 0")
    private boolean avoidDuplicateTitles = false;

    /**
     * Full-quality re-download: replace the pages the chapter has, uncompressed. An ordinary download never
     * overwrites a page, so it cannot undo a compression. Retry keeps it; a paste clears it.
     */
    @Column(name = "replace_pages", nullable = false, columnDefinition = "boolean not null default 0")
    private boolean replacePages = false;

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt = LocalDateTime.now();
}

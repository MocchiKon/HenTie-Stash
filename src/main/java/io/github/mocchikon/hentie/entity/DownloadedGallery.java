package io.github.mocchikon.hentie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Permanent record of a downloaded gallery, since successful queue rows are deleted. Not the same as "a
 * chapter with this gallery id exists": it outlives the chapter, so a subscription never brings back a gallery the
 * user deleted, and the duplicate-title check can refuse a gallery from another source after the chapter is gone.
 */
@Entity
@Table(name = "downloaded_gallery", indexes = {
        // The duplicate-title check's equality seek, as on chapter.
        @Index(name = "ix_downloaded_gallery__title_full_gallery_id", columnList = "title_full, gallery_id")
})
@Getter
@Setter
public class DownloadedGallery
{
    @Id
    @Column(name = "gallery_id", nullable = false)
    private String galleryId;

    /** May name a chapter deleted since. */
    @Column(name = "chapter_id", nullable = false)
    private Integer chapterId;

    /**
     * The chapter's full title when it was recorded, so the duplicate-title check sees a gallery whose chapter
     * was deleted since. Null for a record that never had its title filled, whose chapter is gone too.
     */
    @Column(name = "title_full")
    private String titleFull;

    /**
     * The chapter's language, canonical. Kept for a duplication rule that compares more than the title; nothing
     * reads it yet.
     */
    @Column(name = "language")
    private String language;

    @Column(name = "downloaded_at", nullable = false)
    private LocalDateTime downloadedAt = LocalDateTime.now();
}

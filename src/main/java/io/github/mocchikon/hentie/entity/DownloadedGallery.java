package io.github.mocchikon.hentie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Permanent record of a downloaded gallery, since successful queue rows are deleted. Not the same as "a
 * chapter with this gallery id exists": it outlives the chapter, so a subscription never brings back a gallery the
 * user deleted.
 */
@Entity
@Table(name = "downloaded_gallery")
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

    @Column(name = "downloaded_at", nullable = false)
    private LocalDateTime downloadedAt = LocalDateTime.now();
}

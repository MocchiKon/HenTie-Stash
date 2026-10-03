package io.github.mocchikon.hentie.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;

/**
 * {@code @DynamicUpdate}: an UPDATE names only the columns that changed, so SQLite rewrites only the indexes
 * holding them. A full-row UPDATE rewrites all of them for a one-field change (a status change on 20k chapters:
 * 1.2 s against 0.4 s).
 */
@Entity
@DynamicUpdate
@Table(name = "chapter", indexes = {
        // (status, id), since SQLite ends every index with the rowid: the DATE sort's (status, <sort>) and a
        // compound-search stream. A composite starting with status gives its rows in another order.
        @Index(name = "idx_chapter_status", columnList = "status"),
        @Index(name = "idx_chapter_upload_date", columnList = "upload_date"),
        // Status after the id: several statuses are filtered from the index while it is walked in sort order.
        @Index(name = "ix_chapter__score_id_status", columnList = "score, id, status"),
        @Index(name = "ix_chapter__page_num_id_status", columnList = "page_num, id, status"),
        @Index(name = "ix_chapter__disk_size_id_status", columnList = "disk_size, id, status"),
        @Index(name = "ix_chapter__status_score", columnList = "status, score"),
        @Index(name = "ix_chapter__status_page_num", columnList = "status, page_num"),
        @Index(name = "ix_chapter__status_disk_size", columnList = "status, disk_size"),
        // (language, id), for the same reason as (status).
        @Index(name = "ix_chapter__language", columnList = "language"),
        @Index(name = "ix_chapter__language_score", columnList = "language, score"),
        @Index(name = "ix_chapter__language_page_num", columnList = "language, page_num"),
        @Index(name = "ix_chapter__language_disk_size", columnList = "language, disk_size"),
        // The ids alone, far smaller than the table: what an exclusion-only search subtracts from.
        @Index(name = "ix_chapter__id", columnList = "id"),
        // The sweep walks unlinked chapters in key order so a family arrives together. Blocking-key seeks use
        // ix_chapter__condensed_match_key (V1), an expression index @Index cannot declare.
        @Index(name = "ix_chapter__match_key", columnList = "match_key, id"),
        // Also every lookup of a series' chapters: series_id leads it.
        @Index(name = "ix_chapter__series_match_key", columnList = "series_id, match_key, id"),
        // gallery_id is in the index so the "same title from another source" check never reads the row.
        @Index(name = "ix_chapter__title_full_gallery_id", columnList = "title_full, gallery_id")
})
@Getter
@Setter
public class Chapter
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "title_pretty", nullable = false)
    private String title;

    @Column(name = "title_full", nullable = false)
    private String titleFull;

    @Column(name = "native_title")
    private String nativeTitle;

    @Column(name = "upload_date", nullable = false)
    private LocalDate uploadDate;

    // ORDINAL: switching to STRING would break existing rows.
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "status", nullable = false)
    private Status status = Status.NEW;

    @Column(name = "gallery_id", unique = true)
    private String galleryId;

    @Column(nullable = false)
    private String language;

    // 1-10; the UI shows it as 0.5-5 stars.
    @Column(name = "score")
    private Short score;

    // Set by series operations only, not by the chapter edit form.
    @Column(name = "chapter_num")
    private Float chapterNum;

    // The DB default lets the H2 import insert rows without this column. Materialized for search; never
    // trust it without a resync (ChapterService.syncImageStats).
    @Column(name = "page_num", nullable = false, columnDefinition = "integer not null default 0")
    private Integer pageNum = 0;

    // Bytes; bigint because a chapter can exceed 2 GB. Default 0 for the same reason as page_num.
    @Column(name = "disk_size", nullable = false, columnDefinition = "bigint not null default 0")
    private Long diskSize = 0L;

    // ORDINAL; DEFAULT 0 = NONE ("not from a download") is right for every imported row. Not on ChapterForm:
    // an edit must never promote a half-downloaded chapter to complete.
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "download_status", nullable = false, columnDefinition = "tinyint not null default 0")
    private DownloadStatus downloadStatus = DownloadStatus.NONE;

    // Mode key of the last run that replaced a page; null means full quality (decides whether a full-quality
    // re-download is offered). Not on ChapterForm: it records what happened to the files.
    @Column(name = "compression_mode", length = 64)
    private String compressionMode;

    // Derived from titleFull by TitleKey on every title write. Default '' for the H2 import; "Match chapters"
    // backfills it.
    @Column(name = "match_key", nullable = false, columnDefinition = "varchar(255) not null default ''")
    private String matchKey = "";

    @ManyToOne
    @JoinColumn(name = "series_id")
    private Series series;

    // Two covering indexes per join table: (meta, chapter) drives the search semi-join, (chapter, meta) loads
    // a chapter's metadata. SQLite makes no index for a foreign key, so without the reverse one a detail view
    // full-scans the join table (~300 ms over 11M rows).
    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.REFRESH, CascadeType.MERGE})
    @JoinTable(name = "chapter_artists", joinColumns = {@JoinColumn(name = "chapter_id", nullable = false)},
            inverseJoinColumns = {@JoinColumn(name = "artist_id", nullable = false)},
            indexes = {
                    @Index(name = "ix_chapter_artists__artist_chapter", columnList = "artist_id, chapter_id"),
                    @Index(name = "ix_chapter_artists__chapter_artist", columnList = "chapter_id, artist_id")})
    private List<Artist> artists = new LinkedList<>();

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.REFRESH, CascadeType.MERGE})
    @JoinTable(name = "chapter_characters", joinColumns = {@JoinColumn(name = "chapter_id", nullable = false)},
            inverseJoinColumns = {@JoinColumn(name = "character_id", nullable = false)},
            indexes = {
                    @Index(name = "ix_chapter_characters__character_chapter", columnList = "character_id, chapter_id"),
                    @Index(name = "ix_chapter_characters__chapter_character", columnList = "chapter_id, character_id")})
    private List<Character> characters = new LinkedList<>();

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.REFRESH, CascadeType.MERGE})
    @JoinTable(name = "chapter_tags", joinColumns = {@JoinColumn(name = "chapter_id", nullable = false)},
            inverseJoinColumns = {@JoinColumn(name = "tag_id", nullable = false)},
            indexes = {
                    @Index(name = "ix_chapter_tags__tag_chapter", columnList = "tag_id, chapter_id"),
                    @Index(name = "ix_chapter_tags__chapter_tag", columnList = "chapter_id, tag_id")})
    private List<Tag> tags = new LinkedList<>();

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.REFRESH, CascadeType.MERGE})
    @JoinTable(name = "chapter_parodies", joinColumns = {@JoinColumn(name = "chapter_id", nullable = false)},
            inverseJoinColumns = {@JoinColumn(name = "parody_id", nullable = false)},
            indexes = {
                    @Index(name = "ix_chapter_parodies__parody_chapter", columnList = "parody_id, chapter_id"),
                    @Index(name = "ix_chapter_parodies__chapter_parody", columnList = "chapter_id, parody_id")})
    private List<Parody> parodies = new LinkedList<>();

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.REFRESH, CascadeType.MERGE})
    @JoinTable(name = "chapter_groups", joinColumns = {@JoinColumn(name = "chapter_id", nullable = false)},
            inverseJoinColumns = {@JoinColumn(name = "group_id", nullable = false)},
            indexes = {
                    @Index(name = "ix_chapter_groups__group_chapter", columnList = "group_id, chapter_id"),
                    @Index(name = "ix_chapter_groups__chapter_group", columnList = "chapter_id, group_id")})
    private List<Group> groups = new LinkedList<>();

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.REFRESH, CascadeType.MERGE})
    @JoinTable(name = "chapter_categories", joinColumns = {@JoinColumn(name = "chapter_id", nullable = false)},
            inverseJoinColumns = {@JoinColumn(name = "category_id", nullable = false)},
            indexes = {
                    @Index(name = "ix_chapter_categories__category_chapter", columnList = "category_id, chapter_id"),
                    @Index(name = "ix_chapter_categories__chapter_category", columnList = "chapter_id, category_id")})
    private List<Category> categories = new LinkedList<>();

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Chapter chapter = (Chapter) o;

        if (id != null ? !id.equals(chapter.id) : chapter.id != null) return false;
        return titleFull != null ? titleFull.equals(chapter.titleFull) : chapter.titleFull == null;
    }

    @Override
    public int hashCode()
    {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (titleFull != null ? titleFull.hashCode() : 0);
        return result;
    }
}
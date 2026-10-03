package io.github.mocchikon.hentie.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;

/**
 * Descriptive fields are optional overrides; left empty, the value is derived from the chapters. Everything
 * search reads is materialized here by {@code SeriesService.recomputeDerived}, so a series search never
 * joins out to chapters.
 * <p>
 * The metadata collections are unidirectional so the shared Tag/Artist/... entities stay untouched.
 * <p>
 * {@code @DynamicUpdate}: {@code recomputeDerived} rewrites the score and totals on every chapter change, and a
 * full-row UPDATE would rewrite every index of the row with them.
 */
@Entity
@DynamicUpdate
@Table(name = "series", indexes = {
        // (status, id), since SQLite ends every index with the rowid: the DATE sort's (status, <sort>).
        @Index(name = "idx_series_status", columnList = "status"),
        @Index(name = "idx_series_created_date", columnList = "created_date"),
        // Status after the id: several statuses are filtered from the index while it is walked in sort order.
        @Index(name = "ix_series__score_id_status", columnList = "score, id, status"),
        @Index(name = "ix_series__page_num_id_status", columnList = "page_num, id, status"),
        @Index(name = "ix_series__disk_size_id_status", columnList = "disk_size, id, status"),
        @Index(name = "ix_series__status_score", columnList = "status, score"),
        @Index(name = "ix_series__status_page_num", columnList = "status, page_num"),
        @Index(name = "ix_series__status_disk_size", columnList = "status, disk_size"),
        // The ids alone, far smaller than the table: what an exclusion-only search subtracts from.
        @Index(name = "ix_series__id", columnList = "id"),
        // Candidate seeks for matching; without them lookup would load every series.
        @Index(name = "ix_series__match_key", columnList = "match_key, id"),
        @Index(name = "ix_series__match_block", columnList = "match_block, id")
})
@Getter
@Setter
public class Series
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // Falls back to titleFull when left blank.
    @Column(name = "title_pretty", nullable = false)
    private String title;

    @Column(name = "title_full", nullable = false)
    private String titleFull;

    @Column(name = "native_title")
    private String nativeTitle;

    // Set on creation only; the date filter of series search.
    @Column(name = "created_date", nullable = false)
    private LocalDate createdDate;

    // Always the effective score (see scoreSource), so the search filter agrees with what is displayed.
    @Column(name = "score")
    private Short score;

    // ORDINAL: never reorder ScoreSource.
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "score_source", nullable = false)
    private ScoreSource scoreSource = ScoreSource.DERIVED;

    // ORDINAL: switching to STRING would break existing rows.
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "status", nullable = false)
    private Status status = Status.NEW;

    // Always the sum over the chapters (no override), materialized for search.
    @Column(name = "page_num", nullable = false, columnDefinition = "integer not null default 0")
    private Integer pageNum = 0;

    @Column(name = "disk_size", nullable = false, columnDefinition = "bigint not null default 0")
    private Long diskSize = 0L;

    // Derived from titleFull and rewritten on every title write, so a renamed series matches under its new name.
    @Column(name = "match_key", nullable = false, columnDefinition = "varchar(255) not null default ''")
    private String matchKey = "";

    @Column(name = "match_block", nullable = false, columnDefinition = "varchar(8) not null default ''")
    private String matchBlock = "";

    @OneToMany(mappedBy = "series", cascade = {CascadeType.PERSIST, CascadeType.REFRESH, CascadeType.MERGE})
    // No orphanRemoval / REMOVE cascade: deleting a series or dropping a chapter only unlinks it.
    private List<Chapter> chapters = new LinkedList<>();

    // Indexed both ways: merge/remove rewrites these by meta id, rebuilds go by series id, and SQLite makes
    // no index for a foreign key.
    @ManyToMany
    @JoinTable(name = "series_tags", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "tag_id")},
            indexes = {
                    @Index(name = "ix_series_tags__tag_series", columnList = "tag_id, series_id"),
                    @Index(name = "ix_series_tags__series_tag", columnList = "series_id, tag_id")})
    private List<Tag> tags = new LinkedList<>();

    @ManyToMany
    @JoinTable(name = "series_artists", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "artist_id")},
            indexes = {
                    @Index(name = "ix_series_artists__artist_series", columnList = "artist_id, series_id"),
                    @Index(name = "ix_series_artists__series_artist", columnList = "series_id, artist_id")})
    private List<Artist> artists = new LinkedList<>();

    @ManyToMany
    @JoinTable(name = "series_characters", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "character_id")},
            indexes = {
                    @Index(name = "ix_series_characters__character_series", columnList = "character_id, series_id"),
                    @Index(name = "ix_series_characters__series_character", columnList = "series_id, character_id")})
    private List<Character> characters = new LinkedList<>();

    @ManyToMany
    @JoinTable(name = "series_parodies", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "parody_id")},
            indexes = {
                    @Index(name = "ix_series_parodies__parody_series", columnList = "parody_id, series_id"),
                    @Index(name = "ix_series_parodies__series_parody", columnList = "series_id, parody_id")})
    private List<Parody> parodies = new LinkedList<>();

    @ManyToMany
    @JoinTable(name = "series_groups", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "group_id")},
            indexes = {
                    @Index(name = "ix_series_groups__group_series", columnList = "group_id, series_id"),
                    @Index(name = "ix_series_groups__series_group", columnList = "series_id, group_id")})
    private List<Group> groups = new LinkedList<>();

    @ManyToMany
    @JoinTable(name = "series_categories", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "category_id")},
            indexes = {
                    @Index(name = "ix_series_categories__category_series", columnList = "category_id, series_id"),
                    @Index(name = "ix_series_categories__series_category", columnList = "series_id, category_id")})
    private List<Category> categories = new LinkedList<>();

    // Effective (override-else-derived) metadata, for search only: one semi-join per facet, no join to
    // chapters. Display derives on the fly because it needs per-value counts these tables lack.
    @ManyToMany
    @JoinTable(name = "series_effective_tags", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "tag_id")},
            indexes = {
                    @Index(name = "ix_series_eff_tags__tag_series", columnList = "tag_id, series_id"),
                    @Index(name = "ix_series_eff_tags__series_tag", columnList = "series_id, tag_id")})
    private List<Tag> effectiveTags = new LinkedList<>();

    @ManyToMany
    @JoinTable(name = "series_effective_artists", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "artist_id")},
            indexes = {
                    @Index(name = "ix_series_eff_artists__artist_series", columnList = "artist_id, series_id"),
                    @Index(name = "ix_series_eff_artists__series_artist", columnList = "series_id, artist_id")})
    private List<Artist> effectiveArtists = new LinkedList<>();

    @ManyToMany
    @JoinTable(name = "series_effective_characters", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "character_id")},
            indexes = {
                    @Index(name = "ix_series_eff_characters__character_series", columnList = "character_id, series_id"),
                    @Index(name = "ix_series_eff_characters__series_character", columnList = "series_id, character_id")})
    private List<Character> effectiveCharacters = new LinkedList<>();

    @ManyToMany
    @JoinTable(name = "series_effective_parodies", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "parody_id")},
            indexes = {
                    @Index(name = "ix_series_eff_parodies__parody_series", columnList = "parody_id, series_id"),
                    @Index(name = "ix_series_eff_parodies__series_parody", columnList = "series_id, parody_id")})
    private List<Parody> effectiveParodies = new LinkedList<>();

    @ManyToMany
    @JoinTable(name = "series_effective_groups", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "group_id")},
            indexes = {
                    @Index(name = "ix_series_eff_groups__group_series", columnList = "group_id, series_id"),
                    @Index(name = "ix_series_eff_groups__series_group", columnList = "series_id, group_id")})
    private List<Group> effectiveGroups = new LinkedList<>();

    @ManyToMany
    @JoinTable(name = "series_effective_categories", joinColumns = {@JoinColumn(name = "series_id")},
            inverseJoinColumns = {@JoinColumn(name = "category_id")},
            indexes = {
                    @Index(name = "ix_series_eff_categories__category_series", columnList = "category_id, series_id"),
                    @Index(name = "ix_series_eff_categories__series_category", columnList = "series_id, category_id")})
    private List<Category> effectiveCategories = new LinkedList<>();

    // Always derived from the chapters.
    @ElementCollection
    @CollectionTable(name = "series_effective_languages", joinColumns = @JoinColumn(name = "series_id"),
            indexes = {
            // With series_id, one language's series come out in id order: no temporary B-tree to count them.
            @Index(name = "ix_series_eff_languages__language_series", columnList = "language, series_id"),
            @Index(name = "idx_series_eff_lang_series_id", columnList = "series_id")
    })
    @Column(name = "language")
    private List<String> effectiveLanguages = new LinkedList<>();

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Series series = (Series) o;

        if (id != null ? !id.equals(series.id) : series.id != null) return false;
        return titleFull != null ? titleFull.equals(series.titleFull) : series.titleFull == null;
    }

    @Override
    public int hashCode()
    {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (titleFull != null ? titleFull.hashCode() : 0);
        return result;
    }
}
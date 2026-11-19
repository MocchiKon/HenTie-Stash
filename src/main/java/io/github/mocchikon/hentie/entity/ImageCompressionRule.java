package io.github.mocchikon.hentie.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Which Image Compression mode a gallery queued "By rules" gets, decided by its chapter's metadata. Its conditions
 * are rows of {@code image_compression_rule_<kind>} (see {@code MetadataType.getCompressionRuleJoinTable}), read and
 * written with native SQL: a page of rules loads them in one query per kind, where six mapped collections would cost
 * six queries per rule.
 * <p>
 * <b>{@link #conditionCount} is what a gallery must match, not a cache of the condition rows.</b> A removed value
 * takes its condition row along (the foreign key cascades), but not the count, so the rule never matches again
 * rather than matching every gallery that has its other values: "artist X and tag Y" must not become "artist X".
 */
@Entity
@Table(name = "image_compression_rule", indexes = {
        // The rules page, in the order a decision ranks them.
        @Index(name = "ix_image_compression_rule__priority_id", columnList = "priority, id"),
        // Every decision reads the rules without conditions, which match every gallery.
        @Index(name = "ix_image_compression_rule__condition_count", columnList = "condition_count")
})
@Getter
@Setter
public class ImageCompressionRule
{
    /** What a new rule starts at; 0 is the highest, so 1 leaves room to put one above the rest. */
    public static final int DEFAULT_PRIORITY = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** 0 is the highest: of the rules a gallery matches, only those with the lowest number count. */
    @Column(name = "priority", nullable = false, columnDefinition = "integer not null default 1")
    private int priority = DEFAULT_PRIORITY;

    /** A mode key, never {@code RULES}; one that no longer resolves (a deleted mode) means None. */
    @Column(name = "compression_mode", nullable = false, length = 64)
    private String compressionMode;

    /** A chapter already in the library whose pages a worse mode compressed is downloaded again in this one. */
    @Column(name = "allow_upgrade", nullable = false, columnDefinition = "boolean not null default 0")
    private boolean allowUpgrade;

    /** How many values the rule was saved with; 0 = every gallery. See the class comment. */
    @Column(name = "condition_count", nullable = false, columnDefinition = "integer not null default 0")
    private int conditionCount;
}

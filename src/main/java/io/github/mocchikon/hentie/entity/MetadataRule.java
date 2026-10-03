package io.github.mocchikon.hentie.entity;

import io.github.mocchikon.hentie.dto.MetadataType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A delete, merge or rename kept as a standing decision, so the next import does not bring the name back.
 * Matched on the name, because after a delete or merge the name is all that is left.
 *
 * <p>{@link #targetId} is an id, not a name, so renaming the target carries the rule with it and rules stay
 * one hop deep (no chains, no cycles).
 *
 * <p>Invariant: <b>a ruled-out name has no row</b>.
 */
@Entity
@Table(name = "metadata_rule", indexes = {
        @Index(name = "ix_metadata_rule__type_id", columnList = "type, id"),
        // For fixing rules when their target is removed or merged away.
        @Index(name = "ix_metadata_rule__type_target", columnList = "type, target_id"),
        // One name, one fate: recording a rule again replaces the old one.
        @Index(name = "ux_metadata_rule__type_source", columnList = "type, source_name_lower", unique = true)
})
@Getter
@Setter
public class MetadataRule
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** Stored by name - not a hot path. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private MetadataType type;

    /**
     * The only spelling kept: matching is case-insensitive, so showing another capitalisation on the rules
     * page would suggest a differently-cased name slips past the rule.
     */
    @Column(name = "source_name_lower", nullable = false)
    private String sourceNameLower;

    /**
     * {@code null} = drop the name. Not a foreign key, because the table it points into depends on {@link #type}.
     */
    @Column(name = "target_id")
    private Integer targetId;

    public boolean isBlocking()
    {
        return targetId == null;
    }
}

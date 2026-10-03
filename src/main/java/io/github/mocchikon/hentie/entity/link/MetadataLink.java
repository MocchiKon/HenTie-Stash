package io.github.mocchikon.hentie.entity.link;

import java.io.Serializable;
import java.util.Objects;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * Lets a search sub-query root on a join table, which gives a bare covering-index semi-join. Rooting on
 * {@code Chapter}/{@code Series} instead re-joins the owner and does a PK lookup per matched row (~750k
 * wasted lookups for a tag on half the rows).
 * <p>
 * {@code @Subselect}-mapped, so these generate no DDL and do not conflict with the {@code @ManyToMany} that
 * owns each table.
 */
@MappedSuperclass
@Immutable
@IdClass(MetadataLink.Key.class)
@Getter
public abstract class MetadataLink
{
    @Id
    @Column(name = "owner_id")
    private Integer ownerId;

    @Id
    @Column(name = "meta_id")
    private Integer metaId;

    /** Never loaded by id, but every entity needs an {@code @Id}. */
    public static class Key implements Serializable
    {
        private Integer ownerId;
        private Integer metaId;

        public Key()
        {
        }

        public Key(Integer ownerId, Integer metaId)
        {
            this.ownerId = ownerId;
            this.metaId = metaId;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(ownerId, key.ownerId) && Objects.equals(metaId, key.metaId);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(ownerId, metaId);
        }
    }
}

package io.github.mocchikon.hentie.entity.link;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * Turns an FTS5 lookup into a plain JPA semi-join: FTS5 treats {@code <fts-table> = ?} as {@code MATCH ?}.
 * <p>
 * A semi-join, not ids resolved in Java: an id set would hit SQLite's bind-variable limit and need a cap,
 * above which a {@code LIKE} fallback would fold ASCII case only.
 */
@MappedSuperclass
@Immutable
@Getter
public abstract class TitleMatch
{
    @Id
    @Column(name = "owner_id")
    private Integer ownerId;

    /** The FTS5 table-name column; comparing it to a phrase is the {@code MATCH}. Never selected. */
    @Column(name = "match_query")
    private String matchQuery;
}

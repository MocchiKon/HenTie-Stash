package io.github.mocchikon.hentie.entity.link;

import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

import jakarta.persistence.Entity;

/**
 * Synchronized on {@code chapter} too: triggers on it feed the index, so a pending chapter write must be
 * flushed before a title search.
 */
@Entity
@Subselect("select rowid as owner_id, chapter_fts as match_query from chapter_fts")
@Synchronize({"chapter", "chapter_fts"})
public class ChapterTitleMatch extends TitleMatch
{
}

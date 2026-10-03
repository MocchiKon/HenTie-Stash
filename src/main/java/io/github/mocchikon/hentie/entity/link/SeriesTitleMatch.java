package io.github.mocchikon.hentie.entity.link;

import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

import jakarta.persistence.Entity;

/** Synchronized on {@code series} for the reason given at {@link ChapterTitleMatch}. */
@Entity
@Subselect("select rowid as owner_id, series_fts as match_query from series_fts")
@Synchronize({"series", "series_fts"})
public class SeriesTitleMatch extends TitleMatch
{
}

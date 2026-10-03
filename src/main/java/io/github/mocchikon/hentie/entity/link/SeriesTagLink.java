package io.github.mocchikon.hentie.entity.link;

import jakarta.persistence.Entity;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

@Entity
@Subselect("select series_id as owner_id, tag_id as meta_id from series_effective_tags")
@Synchronize("series_effective_tags")
public class SeriesTagLink extends MetadataLink
{
}

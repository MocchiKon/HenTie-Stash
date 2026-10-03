package io.github.mocchikon.hentie.entity.link;

import jakarta.persistence.Entity;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

@Entity
@Subselect("select series_id as owner_id, group_id as meta_id from series_effective_groups")
@Synchronize("series_effective_groups")
public class SeriesGroupLink extends MetadataLink
{
}

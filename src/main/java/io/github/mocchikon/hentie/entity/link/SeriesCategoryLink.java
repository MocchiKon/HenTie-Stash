package io.github.mocchikon.hentie.entity.link;

import jakarta.persistence.Entity;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

@Entity
@Subselect("select series_id as owner_id, category_id as meta_id from series_effective_categories")
@Synchronize("series_effective_categories")
public class SeriesCategoryLink extends MetadataLink
{
}

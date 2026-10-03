package io.github.mocchikon.hentie.entity.link;

import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

import jakarta.persistence.Entity;

@Entity
@Subselect("select series_id as owner_id, artist_id as meta_id from series_effective_artists")
@Synchronize("series_effective_artists")
public class SeriesArtistLink extends MetadataLink
{
}

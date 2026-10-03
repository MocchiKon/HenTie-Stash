package io.github.mocchikon.hentie.entity.link;

import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

import jakarta.persistence.Entity;

@Entity
@Subselect("select chapter_id as owner_id, artist_id as meta_id from chapter_artists")
@Synchronize("chapter_artists")
public class ChapterArtistLink extends MetadataLink
{
}

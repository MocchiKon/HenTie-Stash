package io.github.mocchikon.hentie.entity.link;

import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

import jakarta.persistence.Entity;

@Entity
@Subselect("select chapter_id as owner_id, parody_id as meta_id from chapter_parodies")
@Synchronize("chapter_parodies")
public class ChapterParodyLink extends MetadataLink
{
}

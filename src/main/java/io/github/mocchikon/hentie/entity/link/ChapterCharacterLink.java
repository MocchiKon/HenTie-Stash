package io.github.mocchikon.hentie.entity.link;

import jakarta.persistence.Entity;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

@Entity
@Subselect("select chapter_id as owner_id, character_id as meta_id from chapter_characters")
@Synchronize("chapter_characters")
public class ChapterCharacterLink extends MetadataLink
{
}

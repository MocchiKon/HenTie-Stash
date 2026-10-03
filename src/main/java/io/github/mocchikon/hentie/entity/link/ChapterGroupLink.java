package io.github.mocchikon.hentie.entity.link;

import jakarta.persistence.Entity;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

@Entity
@Subselect("select chapter_id as owner_id, group_id as meta_id from chapter_groups")
@Synchronize("chapter_groups")
public class ChapterGroupLink extends MetadataLink
{
}

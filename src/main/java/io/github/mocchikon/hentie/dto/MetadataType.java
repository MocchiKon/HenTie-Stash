package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.entity.Character;

/** Carries each kind's JPA/SQL names so the service layer can treat all six the same way. */
public enum MetadataType
{
    TAG("Tags", "tag", Tag.class, "name", "chapter_tags", "series_tags", "series_effective_tags", "tag_id", "tagIds"),
    ARTIST("Artists", "artist", Artist.class, "name", "chapter_artists", "series_artists", "series_effective_artists", "artist_id", "artistIds"),
    CHARACTER("Characters", "character", Character.class, "name", "chapter_characters", "series_characters", "series_effective_characters", "character_id", "characterIds"),
    PARODY("Parodies", "parody", Parody.class, "title", "chapter_parodies", "series_parodies", "series_effective_parodies", "parody_id", "parodyIds"),
    GROUP("Groups", "group", Group.class, "name", "chapter_groups", "series_groups", "series_effective_groups", "group_id", "groupIds"),
    CATEGORY("Categories", "category", Category.class, "name", "chapter_categories", "series_categories", "series_effective_categories", "category_id", "categoryIds");

    private final String displayName;
    private final String key;
    private final Class<?> entityClass;
    private final String nameProperty;
    private final String chapterJoinTable;
    private final String seriesJoinTable;
    private final String seriesEffectiveJoinTable;
    private final String fkColumn;
    private final String searchParam;

    MetadataType(String displayName, String key, Class<?> entityClass, String nameProperty,
                 String chapterJoinTable, String seriesJoinTable, String seriesEffectiveJoinTable,
                 String fkColumn, String searchParam)
    {
        this.displayName = displayName;
        this.key = key;
        this.entityClass = entityClass;
        this.nameProperty = nameProperty;
        this.chapterJoinTable = chapterJoinTable;
        this.seriesJoinTable = seriesJoinTable;
        this.seriesEffectiveJoinTable = seriesEffectiveJoinTable;
        this.fkColumn = fkColumn;
        this.searchParam = searchParam;
    }

    public static MetadataType fromKey(String key)
    {
        for (MetadataType type : values())
        {
            if (type.key.equalsIgnoreCase(key))
            {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown metadata type: " + key);
    }

    public String getDisplayName() { return displayName; }
    public String getKey() { return key; }
    public Class<?> getEntityClass() { return entityClass; }
    public String getEntityName() { return entityClass.getSimpleName(); }
    public String getNameProperty() { return nameProperty; }
    public String getChapterJoinTable() { return chapterJoinTable; }
    public String getSeriesJoinTable() { return seriesJoinTable; }
    /** Merge must move these links too: left behind, the cascade deletes them with the source and series search goes stale. */
    public String getSeriesEffectiveJoinTable() { return seriesEffectiveJoinTable; }
    public String getFkColumn() { return fkColumn; }
    public String getSearchParam() { return searchParam; }
}

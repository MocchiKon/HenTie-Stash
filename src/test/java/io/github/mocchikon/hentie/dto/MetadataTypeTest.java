package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.Group;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link MetadataType#fromKey(String)} drives every {@code /manage} and autocomplete route. */
class MetadataTypeTest
{
    @Test
    void shouldResolveTypeWhenKeyDiffersInCase()
    {
        // WHEN
        MetadataType lower = MetadataType.fromKey("tag");
        MetadataType upper = MetadataType.fromKey("TAG");
        MetadataType artist = MetadataType.fromKey("Artist");
        MetadataType group = MetadataType.fromKey("group");
        MetadataType category = MetadataType.fromKey("Category");

        // THEN
        assertThat(lower).isEqualTo(MetadataType.TAG);
        assertThat(upper).isEqualTo(MetadataType.TAG);
        assertThat(artist).isEqualTo(MetadataType.ARTIST);
        assertThat(group).isEqualTo(MetadataType.GROUP);
        assertThat(category).isEqualTo(MetadataType.CATEGORY);
    }

    @Test
    void shouldThrowWhenKeyIsUnknown()
    {
        // WHEN + THEN
        assertThatThrownBy(() -> MetadataType.fromKey("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void shouldThrowWhenKeyIsNull()
    {
        // WHEN + THEN
        assertThatThrownBy(() -> MetadataType.fromKey(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnSimpleClassNameWhenGettingEntityName()
    {
        // WHEN
        Class<?> groupClass = MetadataType.GROUP.getEntityClass();
        String groupName = MetadataType.GROUP.getEntityName();
        String characterName = MetadataType.CHARACTER.getEntityName();

        // THEN
        assertThat(groupClass).isEqualTo(Group.class);
        assertThat(groupName).isEqualTo("Group");
        // io.github.mocchikon.hentie.entity.Character, not java.lang.Character.
        assertThat(characterName).isEqualTo("Character");
    }

    @Test
    void shouldCarryJoinTableCoordinatesWhenIteratingEveryType()
    {
        // WHEN + THEN
        for (MetadataType type : MetadataType.values())
        {
            assertThat(type.getChapterJoinTable()).isNotBlank();
            assertThat(type.getSeriesJoinTable()).isNotBlank();
            assertThat(type.getFkColumn()).isNotBlank();
            assertThat(type.getSearchParam()).isNotBlank();
            assertThat(type.getNameProperty()).isNotBlank();
        }
        // Parody names live in a "title" column, unlike the others' "name".
        assertThat(MetadataType.PARODY.getNameProperty()).isEqualTo("title");
        assertThat(MetadataType.TAG.getNameProperty()).isEqualTo("name");
    }
}

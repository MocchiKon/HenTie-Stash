package io.github.mocchikon.hentie.scrapper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EhTagsTest
{
    @Test
    void shouldFileEachNamespaceUnderItsKindAndKeepTheTagNamespaces()
    {
        // WHEN
        EhTags.Routed routed = EhTags.route(List.of("parody:touhou project", "character:reimu hakurei",
                "group:handful happiness", "artist:nanahara fuyuki", "cosplayer:someone", "female:big breasts",
                "male:glasses", "other:full color", "mixed:group", "reclass:doujinshi", "language:english",
                "language:translated", "plain tag"));

        // THEN tags keep their namespace, for the metadata service to turn into a name.
        assertThat(routed.artists()).containsExactly("nanahara fuyuki", "someone");
        assertThat(routed.groups()).containsExactly("handful happiness");
        assertThat(routed.parodies()).containsExactly("touhou project");
        assertThat(routed.characters()).containsExactly("reimu hakurei");
        assertThat(routed.tags()).containsExactly("female:big breasts", "male:glasses", "other:full color",
                "mixed:group", "plain tag");
        assertThat(routed.language()).isEqualTo("english");
    }

    @Test
    void shouldTakeJapaneseWhenAGalleryHasNoLanguageTag()
    {
        assertThat(EhTags.route(List.of("female:x")).language()).isEqualTo("Japanese");
        // Tags filed as languages that name none.
        assertThat(EhTags.route(List.of("language:text cleaned")).language()).isEqualTo("Japanese");
        assertThat(EhTags.route(List.of("language:translated", "language:rewrite")).language()).isEqualTo("Japanese");
    }

    @Test
    void shouldSkipTagsThatAreNoLanguageWhenPickingOne()
    {
        assertThat(EhTags.route(List.of("language:translated", "language:chinese")).language()).isEqualTo("chinese");
        // Nothing known: the first one, so the import's refusal can name it.
        assertThat(EhTags.route(List.of("language:speechless")).language()).isEqualTo("speechless");
    }
}

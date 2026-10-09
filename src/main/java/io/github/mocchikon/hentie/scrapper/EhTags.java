package io.github.mocchikon.hentie.scrapper;

import io.github.mocchikon.hentie.service.LanguageService;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * Turns e-hentai's namespaced tags ({@code "artist:x"}, {@code "female:y"}) into the app's metadata kinds, for every
 * source whose metadata is e-hentai's (e-hentai itself, chaika). Tags keep their {@code female:}/{@code male:}/
 * {@code other:} namespaces here: turning those into a tag name is the metadata service's job, for every source
 * alike ({@code MetadataService.canonical}).
 */
public final class EhTags
{
    /** e-hentai tags no Japanese gallery: Japanese is what a gallery without a language tag is in. */
    public static final String DEFAULT_LANGUAGE = "Japanese";

    private EhTags()
    {
    }

    /**
     * @param language the first language the app knows; {@link #DEFAULT_LANGUAGE} without a {@code language:} tag;
     *                 the first one when none is known ({@code speechless}), so the import's log names it
     */
    public record Routed(Set<String> tags, Set<String> artists, Set<String> groups, Set<String> parodies,
                         Set<String> characters, String language)
    {
    }

    /**
     * A gallery's metadata from its namespaced tags and its e-hentai category, the same for every source that has
     * e-hentai's; the source adds the titles and the pages, which it reads its own way.
     */
    public static GalleryData.GalleryDataBuilder galleryData(String id, Collection<String> namespacedTags,
                                                             String category)
    {
        Routed routed = route(namespacedTags);
        Set<String> categories = new LinkedHashSet<>();
        if (category != null)
        {
            categories.add(category);
        }
        return GalleryData.builder()
                .id(id)
                .language(routed.language())
                .tags(routed.tags())
                .artists(routed.artists())
                .groups(routed.groups())
                .parodies(routed.parodies())
                .characters(routed.characters())
                .categories(categories);
    }

    /**
     * {@code cosplayer:} counts as an artist: in a cosplay gallery the cosplayer is its creator. {@code reclass:}
     * is dropped, since the gallery's category already says what it was reclassified to. Anything else, with or
     * without a namespace, is a tag.
     */
    public static Routed route(Collection<String> namespacedTags)
    {
        var tags = new LinkedHashSet<String>();
        var artists = new LinkedHashSet<String>();
        var groups = new LinkedHashSet<String>();
        var parodies = new LinkedHashSet<String>();
        var characters = new LinkedHashSet<String>();
        List<String> languages = new ArrayList<>();
        for (String raw : namespacedTags)
        {
            String tag = StringUtils.strip(raw);
            if (StringUtils.isEmpty(tag))
            {
                continue;
            }
            int colon = tag.indexOf(':');
            String namespace = colon < 0 ? "" : tag.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String name = colon < 0 ? tag : tag.substring(colon + 1).strip();
            if (name.isEmpty())
            {
                continue;
            }
            switch (namespace)
            {
                case "artist", "cosplayer" -> artists.add(name);
                case "group" -> groups.add(name);
                case "parody" -> parodies.add(name);
                case "character" -> characters.add(name);
                case "language" -> languages.add(name);
                case "reclass" ->
                {
                    // The category already reflects it.
                }
                default -> tags.add(tag);
            }
        }
        return new Routed(tags, artists, groups, parodies, characters, language(languages));
    }

    /** e-hentai files these as languages, but they name none: a gallery with only these has no language tag. */
    private static final Set<String> NOT_LANGUAGES = Set.of("translated", "rewrite", "text cleaned");

    /** The first real language wins. */
    static String language(List<String> languages)
    {
        List<String> named = languages.stream()
                .filter(l -> !NOT_LANGUAGES.contains(l.toLowerCase(Locale.ROOT)))
                .toList();
        if (named.isEmpty())
        {
            return DEFAULT_LANGUAGE;
        }
        return named.stream().filter(LanguageService::isKnown).findFirst().orElse(named.getFirst());
    }
}

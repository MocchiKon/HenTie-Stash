package io.github.mocchikon.hentie.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import lombok.NoArgsConstructor;

/**
 * The known languages, the one source for validation and edit-form autocomplete. The search form uses
 * {@link SearchService#languages} instead, so it offers only languages some chapter really has.
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class LanguageService
{
    private static final List<String> PRIORITY = List.of("English", "Japanese", "Chinese");

    public static final List<String> LANGUAGES;

    static
    {
        Set<String> prioritySet = new HashSet<>(PRIORITY);

        List<String> rest = Arrays.stream(Locale.getISOLanguages())
                .map(code -> Locale.of(code).getDisplayLanguage(Locale.ENGLISH))
                .filter(name -> !name.isBlank() && !prioritySet.contains(name))
                .distinct()
                .sorted()
                .toList();

        List<String> all = new ArrayList<>(PRIORITY.size() + rest.size());
        all.addAll(PRIORITY);
        all.addAll(rest);
        LANGUAGES = List.copyOf(all);
    }

    public static List<String> search(String query, int limit)
    {
        if (query == null || query.isBlank())
        {
            return LANGUAGES.subList(0, Math.min(limit, LANGUAGES.size()));
        }
        String lower = query.strip().toLowerCase();
        return LANGUAGES.stream()
                .filter(l -> l.toLowerCase().contains(lower))
                .limit(limit)
                .toList();
    }

    public static boolean isKnown(String language)
    {
        if (language == null)
        {
            return false;
        }
        String stripped = language.strip();
        return LANGUAGES.stream().anyMatch(l -> l.equalsIgnoreCase(stripped));
    }

    /**
     * Language is searched by exact value, so {@code "english"} and {@code "English"} would be two facets
     * that never see each other's chapters. Everything that writes a language must go through here.
     * An unknown language comes back stripped; validation rejects it separately.
     */
    public static String canonical(String language)
    {
        if (language == null)
        {
            return null;
        }
        String stripped = language.strip();
        return LANGUAGES.stream()
                .filter(l -> l.equalsIgnoreCase(stripped))
                .findFirst()
                .orElse(stripped);
    }
}

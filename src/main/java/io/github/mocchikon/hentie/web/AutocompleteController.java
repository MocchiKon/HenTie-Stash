package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.OptionDto;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.SearchService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
public class AutocompleteController
{
    private static final int LIMIT = 10;

    private final MetadataService metadataService;
    private final SearchService searchService;

    public AutocompleteController(MetadataService metadataService, SearchService searchService)
    {
        this.metadataService = metadataService;
        this.searchService = searchService;
    }

    /** For the search form only. */
    @GetMapping("/api/autocomplete/{type}")
    public List<OptionDto> autocomplete(@PathVariable String type, @RequestParam(required = false) String q)
    {
        if ("language".equalsIgnoreCase(type))
        {
            String needle = q == null ? "" : MetadataService.normalize(q);
            return searchService.languages().stream()
                    .filter(lang -> needle.isEmpty() || lang.toLowerCase(Locale.ROOT).contains(needle))
                    .limit(LIMIT)
                    // A language has no id: its label is the value.
                    .map(lang -> new OptionDto(null, lang))
                    .toList();
        }
        if (StringUtils.isBlank(type))
        {
            return List.of();
        }
        return metadataService.autocomplete(MetadataType.fromKey(type), q, LIMIT);
    }
}

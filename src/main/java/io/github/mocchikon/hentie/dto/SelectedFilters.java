package io.github.mocchikon.hentie.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** Labels for the ids in a {@link SearchCriteria}, so pre-selected tokens are rendered server-side. */
@Getter
@Builder
public class SelectedFilters
{
    private List<OptionDto> tags;
    private List<OptionDto> artists;
    private List<OptionDto> characters;
    private List<OptionDto> parodies;
    private List<OptionDto> groups;
    private List<OptionDto> categories;

    private List<OptionDto> excludedTags;
    private List<OptionDto> excludedArtists;
    private List<OptionDto> excludedCharacters;
    private List<OptionDto> excludedParodies;
    private List<OptionDto> excludedGroups;
    private List<OptionDto> excludedCategories;
}

package io.github.mocchikon.hentie.dto;

/**
 * {@code targetName} is resolved from the target id (null = the name is dropped), so it is always the name
 * the rule produces now.
 */
public record MetadataRuleDto(Integer id, String sourceName, String targetName)
{
    public boolean blocking()
    {
        return targetName == null;
    }
}

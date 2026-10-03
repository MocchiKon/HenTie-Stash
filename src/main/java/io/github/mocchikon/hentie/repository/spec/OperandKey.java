package io.github.mocchikon.hentie.repository.spec;

import io.github.mocchikon.hentie.dto.MetadataType;

/**
 * Names one included value of a search - a metadata value, the title term, or (series) the languages - so
 * the planner can tell the specs which one a query should start from.
 */
public record OperandKey(String facet, Integer id)
{
    public static final OperandKey TITLE = new OperandKey("title", null);
    public static final OperandKey LANGUAGES = new OperandKey("languages", null);

    public static OperandKey of(MetadataType type, Integer id)
    {
        return new OperandKey(type.getKey(), id);
    }
}

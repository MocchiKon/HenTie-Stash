package io.github.mocchikon.hentie.scrapper;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

/** Reading the sites' JSON answers the same way in every source. */
public final class JsonFields
{
    private JsonFields()
    {
    }

    /** Null for a missing, blank or non-text field: a number or an object where text belongs is no title. */
    public static String text(JsonNode node, String field)
    {
        JsonNode value = node.path(field);
        return value.isTextual() ? StringUtils.stripToNull(value.textValue()) : null;
    }
}

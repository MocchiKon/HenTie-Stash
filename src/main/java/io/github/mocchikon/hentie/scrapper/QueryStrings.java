package io.github.mocchikon.hentie.scrapper;

import org.apache.commons.lang3.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The part of a pasted search address after "?", read the same way for every source. */
public final class QueryStrings
{
    /**
     * A scheme, {@code www.}, or a host followed by a path. A bare {@code host.tld} is left out: search text may hold a
     * dot ({@code vol.2}), and as a search it at worst finds nothing.
     */
    private static final Pattern ADDRESS = Pattern.compile(
            "(?:[a-z][a-z0-9+.-]*://|www\\.|[a-z0-9-]+(?:\\.[a-z0-9-]+)+/)\\S*", Pattern.CASE_INSENSITIVE);

    private QueryStrings()
    {
    }

    /**
     * Name and value of each pair, decoded, in their order; the names stripped.
     *
     * @throws IllegalArgumentException for a pair that does not decode, worded for the user
     */
    /**
     * Whether pasted text is a web address rather than search text. A source refuses one that is not its own site's,
     * which it would otherwise search for as text and never find.
     */
    public static boolean isAddress(String text)
    {
        return ADDRESS.matcher(StringUtils.strip(StringUtils.defaultString(text))).matches();
    }

    public static List<Map.Entry<String, String>> parameters(String queryString)
    {
        var pairs = new ArrayList<Map.Entry<String, String>>();
        for (String pair : StringUtils.defaultString(queryString).split("&"))
        {
            if (pair.isBlank())
            {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try
            {
                pairs.add(Map.entry(URLDecoder.decode(name, StandardCharsets.UTF_8).strip(),
                        URLDecoder.decode(value, StandardCharsets.UTF_8)));
            }
            catch (IllegalArgumentException e)
            {
                throw new IllegalArgumentException("\"" + StringUtils.abbreviate(pair, 60)
                        + "\" is not a part of a search address.", e);
            }
        }
        return pairs;
    }
}

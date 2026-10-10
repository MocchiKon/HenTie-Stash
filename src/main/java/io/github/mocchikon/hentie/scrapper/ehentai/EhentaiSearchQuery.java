package io.github.mocchikon.hentie.scrapper.ehentai;

import io.github.mocchikon.hentie.scrapper.QueryStrings;
import org.apache.commons.lang3.StringUtils;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An e-hentai search as a subscription keeps it: the part of a search address after "?", rebuilt from its filters
 * alone. Paging is the walk's own (a pasted {@code next} would start it in the middle), and every value is encoded
 * here, so nothing typed reaches the request as it was typed.
 */
final class EhentaiSearchQuery
{
    /** Paging, which the walk does itself, and {@code inline_set}, which changes the account's display settings. */
    private static final Set<String> DROPPED = Set.of("next", "prev", "page", "jump", "seek", "range", "inline_set");

    /** The search's filters: {@code f_search}, {@code f_cats}, {@code f_srdd}, ... and the advanced-search switch. */
    private static final Pattern FILTER = Pattern.compile("f_[a-z_]{1,20}|advsearch");

    private static final Pattern ADDRESS = Pattern.compile(
            "(?:https?://)?(?:[a-z0-9-]+\\.)*(?:e-hentai|exhentai)\\.org(/[^?#]*)?(?:\\?([^#]*))?(?:#.*)?",
            Pattern.CASE_INSENSITIVE);

    /** A tag's own page, {@code /tag/female:big+breasts}: the search for that tag. */
    private static final Pattern TAG_PATH = Pattern.compile("/tag/([^/]+)/?");

    private EhentaiSearchQuery()
    {
    }

    /**
     * From a pasted search address, the part after its "?", or the search text alone ({@code female:x$}), which
     * becomes {@code f_search}.
     *
     * @throws IllegalArgumentException for anything else, worded for the user
     */
    static String normalized(String raw)
    {
        String text = StringUtils.strip(StringUtils.defaultString(raw));
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        Matcher address = ADDRESS.matcher(text);
        if (address.matches())
        {
            String path = StringUtils.defaultString(address.group(1));
            Matcher tag = TAG_PATH.matcher(path);
            if (tag.matches())
            {
                pairs.add(Map.entry("f_search", tagSearch(URLDecoder.decode(tag.group(1).replace("+", "%2B"),
                        StandardCharsets.UTF_8))));
            }
            else if (!path.isEmpty() && !path.equals("/"))
            {
                throw new IllegalArgumentException("Paste the address of a search (e-hentai.org/?f_search=...) or of "
                        + "a tag's page, not of " + StringUtils.abbreviate(path, 60) + ".");
            }
            pairs.addAll(QueryStrings.parameters(address.group(2)));
        }
        else if (QueryStrings.isAddress(text))
        {
            throw new IllegalArgumentException("That is not an address on e-hentai.org or exhentai.org. Paste the "
                    + "address of a search or a tag's page there, or type the search itself, e.g. "
                    + "parody:\"genshin impact$\".");
        }
        else if (text.contains("="))
        {
            pairs.addAll(QueryStrings.parameters(StringUtils.removeStart(text, "?")));
        }
        else if (!text.isEmpty())
        {
            pairs.add(Map.entry("f_search", text));
        }

        var kept = new ArrayList<String>();
        boolean filters = false;
        for (Map.Entry<String, String> pair : pairs)
        {
            String name = pair.getKey().toLowerCase(Locale.ROOT);
            if (DROPPED.contains(name))
            {
                continue;
            }
            if (!FILTER.matcher(name).matches())
            {
                throw new IllegalArgumentException("e-hentai's search has no filter called \""
                        + StringUtils.abbreviate(pair.getKey(), 40) + "\"; only its f_... filters can be kept.");
            }
            filters |= !pair.getValue().isBlank();
            kept.add(name + "=" + URLEncoder.encode(pair.getValue(), StandardCharsets.UTF_8));
        }
        if (!filters)
        {
            throw new IllegalArgumentException("Give the search something to look for, e.g. "
                    + "f_search=female:\"big breasts$\" language:english$, or paste a search's address.");
        }
        return String.join("&", kept);
    }

    /** As gallery-dl turns a tag page into a search: an exact tag, its spaces written as {@code +}. */
    private static String tagSearch(String tag)
    {
        int colon = tag.lastIndexOf(':');
        if (tag.contains("+"))
        {
            String namespace = colon < 0 ? "" : tag.substring(0, colon + 1);
            return namespace + "\"" + tag.substring(colon + 1).replace('+', ' ') + "$\"";
        }
        return tag + "$";
    }
}

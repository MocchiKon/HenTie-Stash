package io.github.mocchikon.hentie.service.export;

import io.github.mocchikon.hentie.entity.Metadata;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * The {@code ComicInfo.xml} a CBZ carries, which Komga, Kavita, Mihon and most readers take a book's title, number
 * and metadata from.
 *
 * <p><b>Elements are written in the schema's order</b> (ComicInfo 2.1), and only those with a value: readers that
 * validate reject an element out of order, and an empty one would overwrite what a reader knows from elsewhere.
 * The mapping for values the schema has no place for: artists are the writers (a doujinshi's artist is its
 * author), groups the publisher (a circle publishes its own work), parodies the series group (Komga makes a
 * collection of each).
 *
 * @param number   formatted, since readers sort {@code 2.10} after {@code 2.9}
 * @param score    1-10, written as the 0-5 community rating the UI shows
 */
public record ComicInfo(String title, String series, String number, Integer count, LocalDate date,
                        List<String> artists, List<String> groups, List<String> categories, List<String> tags,
                        List<String> characters, List<String> parodies, String languageIso, String web,
                        Integer pageCount, Integer score)
{
    public static final String FILE_NAME = "ComicInfo.xml";

    /** English display name → ISO 639-1 code, the form the library stores languages in. */
    private static final Map<String, String> ISO_CODES = isoCodes();

    public ComicInfo withPageCount(int pages)
    {
        return new ComicInfo(title, series, number, count, date, artists, groups, categories, tags, characters,
                parodies, languageIso, web, pages, score);
    }

    public static List<String> names(Collection<? extends Metadata> values)
    {
        return values.stream().map(Metadata::getName).distinct().sorted().toList();
    }

    /** Null for a language without a two-letter code, which the schema cannot hold. */
    public static String isoCode(String language)
    {
        return language == null ? null : ISO_CODES.get(language.strip().toLowerCase(Locale.ROOT));
    }

    public byte[] toXml()
    {
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<ComicInfo xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" ")
                .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        element(xml, "Title", title);
        element(xml, "Series", series);
        element(xml, "Number", number);
        element(xml, "Count", count);
        if (date != null)
        {
            element(xml, "Year", date.getYear());
            element(xml, "Month", date.getMonthValue());
            element(xml, "Day", date.getDayOfMonth());
        }
        element(xml, "Writer", joined(artists));
        element(xml, "Publisher", joined(groups));
        element(xml, "Genre", joined(categories));
        element(xml, "Tags", joined(tags));
        element(xml, "Web", web);
        element(xml, "PageCount", pageCount);
        element(xml, "LanguageISO", languageIso);
        element(xml, "Characters", joined(characters));
        element(xml, "SeriesGroup", joined(parodies));
        element(xml, "AgeRating", "Adults Only 18+");
        element(xml, "CommunityRating", score == null ? null : String.valueOf(score / 2.0));
        return xml.append("</ComicInfo>\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String joined(List<String> values)
    {
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }

    private static void element(StringBuilder xml, String name, Object value)
    {
        if (value == null || value.toString().isBlank())
        {
            return;
        }
        xml.append("  <").append(name).append('>').append(escape(value.toString())).append("</").append(name)
                .append(">\n");
    }

    /** Also drops characters XML 1.0 cannot carry at all, which a scraped title may contain. */
    static String escape(String text)
    {
        var out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            switch (c)
            {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default ->
                {
                    boolean allowed = c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
                            || (c >= 0xE000 && c <= 0xFFFD) || c >= 0x10000;
                    if (allowed)
                    {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.toString();
    }

    private static Map<String, String> isoCodes()
    {
        var codes = new HashMap<String, String>();
        for (String code : Locale.getISOLanguages())
        {
            String name = Locale.of(code).getDisplayLanguage(Locale.ENGLISH);
            if (!name.isBlank())
            {
                // The first code wins, as in LanguageService, which keeps the first of duplicate names.
                codes.putIfAbsent(name.toLowerCase(Locale.ROOT), code);
            }
        }
        return Map.copyOf(codes);
    }
}

package io.github.mocchikon.hentie.scrapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A local stand-in for a real source, so the pipeline runs with no network. Reads
 * {@code <mock-dir>/<id>.json} for {@code mock:<id>}.
 *
 * <p>The JSON mirrors the real source's shape, so swapping it in changes only the fetch.
 */
@Component
public class MockDataDownloader implements PageDownloader
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Its own property, not in {@code AppProperties}: a source's configuration belongs to the source's class. */
    private final Path mockDir;

    public MockDataDownloader(@Value("${app.download.mock-dir:./mock_server}") String mockDir)
    {
        this.mockDir = Paths.get(mockDir).toAbsolutePath().normalize();
    }

    @Override
    public String sourcePrefix()
    {
        return "mock";
    }

    /** The prefix matches case-insensitively; nothing after the colon is not a resource. */
    @Override
    public boolean accepts(String link)
    {
        return !extractId(link).isEmpty();
    }

    @Override
    public String resourceId(String link)
    {
        return extractId(link);
    }

    @Override
    public String link(String resourceId)
    {
        return sourcePrefix() + ":" + resourceId;
    }

    private String extractId(String link)
    {
        if (link == null)
        {
            return "";
        }
        String trimmed = link.strip();
        String marker = sourcePrefix() + ":";
        if (trimmed.length() <= marker.length() || !trimmed.regionMatches(true, 0, marker, 0, marker.length()))
        {
            return "";
        }
        return trimmed.substring(marker.length()).strip();
    }

    @Override
    public GalleryData downloadGalleryInfo(String id)
    {
        // Only a bare id may become a path ("../../etc/passwd" is not a gallery).
        if (!id.matches("[A-Za-z0-9_-]+"))
        {
            throw new GalleryNotFoundException("Not a valid mock gallery id: '" + id + "'");
        }
        Path file = mockDir.resolve(id + ".json");
        if (!Files.isRegularFile(file))
        {
            throw new GalleryNotFoundException("No such mock gallery: " + file);
        }

        JsonNode root;
        try
        {
            root = MAPPER.readTree(Files.readString(file));
        }
        catch (IOException e)
        {
            // Unreadable rather than absent: transient, so the queue retries.
            throw new UncheckedIOException("Could not read mock gallery " + file, e);
        }

        JsonNode title = root.path("title");
        return GalleryData.builder()
                .id(id)
                .fullTitle(text(title, "english"))
                .prettyTitle(text(title, "pretty"))
                .japaneseTitle(text(title, "japanese"))
                .language(firstTag(root, "language"))
                .tags(tags(root, "tag"))
                .artists(tags(root, "artist"))
                .groups(tags(root, "group"))
                .parodies(tags(root, "parody"))
                .characters(tags(root, "character"))
                .categories(tags(root, "category"))
                .pageUrls(pageUrls(root))
                .build();
    }

    @Override
    public byte[] downloadPage(URI url) throws IOException
    {
        Path file;
        try
        {
            file = Paths.get(url).toAbsolutePath().normalize();
        }
        catch (IllegalArgumentException | FileSystemNotFoundException e)
        {
            throw new IOException("Not a mock page URL: " + url, e);
        }
        if (!file.startsWith(mockDir))
        {
            throw new IOException("Mock page outside the mock directory: " + file);
        }
        return Files.readAllBytes(file);
    }

    /**
     * A stable sort by {@code number} that drops nothing: the caller numbers pages by position, so a page
     * lost to a duplicate number (as a map keyed on it would do) shifts every page after it.
     */
    private LinkedHashSet<URI> pageUrls(JsonNode root)
    {
        var numbered = new ArrayList<NumberedPage>();
        int fallback = 0;
        for (JsonNode page : root.path("pages"))
        {
            fallback++;
            String path = text(page, "path");
            if (path == null)
            {
                continue;
            }
            numbered.add(new NumberedPage(page.path("number").asInt(fallback),
                    mockDir.resolve(path).normalize().toUri()));
        }
        numbered.sort(Comparator.comparingInt(NumberedPage::number));
        var urls = new LinkedHashSet<URI>();
        numbered.forEach(page -> urls.add(page.uri()));
        return urls;
    }

    private record NumberedPage(int number, URI uri)
    {
    }

    private static Set<String> tags(JsonNode root, String type)
    {
        var names = new LinkedHashSet<String>();
        for (JsonNode tag : root.path("tags"))
        {
            String name = text(tag, "name");
            if (name != null && tag.path("type").asText("").equalsIgnoreCase(type))
            {
                names.add(name);
            }
        }
        return names;
    }

    private static String firstTag(JsonNode root, String type)
    {
        return tags(root, type).stream().findFirst().orElse(null);
    }

    /** The source writes a literal {@code "null"} for "not set". */
    private static String text(JsonNode node, String field)
    {
        String value = node.path(field).asText("").strip();
        return value.isEmpty() || value.equalsIgnoreCase("null") ? null : value;
    }
}

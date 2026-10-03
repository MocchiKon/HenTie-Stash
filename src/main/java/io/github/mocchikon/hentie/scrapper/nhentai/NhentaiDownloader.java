package io.github.mocchikon.hentie.scrapper.nhentai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mocchikon.hentie.scrapper.*;
import io.github.mocchikon.hentie.service.LanguageService;
import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static io.github.mocchikon.hentie.scrapper.JsonFields.text;

/**
 * nhentai.net, through its API v2 ({@code https://nhentai.net/api/v2/docs}).
 * <p>
 * <b>Only page images are fetched.</b> A gallery also lists a cover, a thumbnail and a thumbnail per page; none
 * of them is a page.
 * <p>
 * <b>Page paths are used exactly as nhentai returns them</b>, on one of the image servers it lists. nhentai asks
 * for both: the servers reject paths that were made up (another extension, a guessed number), and a client that
 * keeps asking for such paths is banned for longer.
 */
@Component
public class NhentaiDownloader implements FavouritesSource, PageDownloader
{
    static final String PREFIX = "nhentai";

    /**
     * nhentai.net or a subdomain of it, then {@code /g/<id>} and anything after it (a page, a query), so a link
     * copied from any page of a gallery works. Leading zeros are dropped, so one gallery has one gallery id.
     */
    private static final Pattern GALLERY_URL = Pattern.compile(
            "(?:https?://)?(?:[a-z0-9-]+\\.)*nhentai\\.net/g/0*(\\d{1,12})(?:[/?#].*)?", Pattern.CASE_INSENSITIVE);

    /** The form the Download page names for every source. */
    private static final Pattern PREFIXED_ID = Pattern.compile(PREFIX + ":\\s*0*(\\d{1,12})", Pattern.CASE_INSENSITIVE);

    /** A pipe with whitespace on both sides. One that belongs to a name touches a letter or another pipe. */
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile("(?<=\\s)\\|(?=\\s)");

    private final NhentaiApi api;

    public NhentaiDownloader(NhentaiProperties properties, SettingsService settingsService)
    {
        this.api = new NhentaiApi(properties, settingsService::getNhentaiApiKey);
    }

    @PreDestroy
    void close()
    {
        api.close();
    }

    @Override
    public String sourcePrefix()
    {
        return PREFIX;
    }

    @Override
    public boolean accepts(String link)
    {
        return idIn(link) != null;
    }

    @Override
    public String resourceId(String link)
    {
        return idIn(link);
    }

    /** Always the site's own address, whatever {@code base-url} the API is reached at, so it parses back. */
    @Override
    public String link(String resourceId)
    {
        return "https://nhentai.net/g/" + resourceId + "/";
    }

    @Override
    public String linkExample()
    {
        return "https://nhentai.net/g/123456/";
    }

    @Override
    public String pageLinkTemplate()
    {
        return link(RESOURCE_ID);
    }

    private static String idIn(String link)
    {
        return DataDownloader.matchLink(link, PREFIXED_ID, GALLERY_URL).map(m -> m.group(1)).orElse(null);
    }

    // ---- a gallery ---------------------------------------------------------

    @Override
    public GalleryData downloadGalleryInfo(String id)
    {
        // Only a number may become part of the API's path.
        if (!id.matches("\\d{1,12}"))
        {
            throw new GalleryNotFoundException("Not an nhentai gallery id: '" + id + "'");
        }
        try
        {
            JsonNode gallery = api.gallery(id);
            JsonNode title = gallery.path("title");
            Map<String, Set<String>> tags = tagsByType(gallery.path("tags"));
            return GalleryData.builder()
                    .id(id)
                    .fullTitle(text(title, "english"))
                    .prettyTitle(text(title, "pretty"))
                    .japaneseTitle(text(title, "japanese"))
                    .language(language(tags.getOrDefault("language", Set.of())))
                    .tags(tags.getOrDefault("tag", Set.of()))
                    .artists(splitArtists(tags.getOrDefault("artist", Set.of())))
                    .groups(tags.getOrDefault("group", Set.of()))
                    .parodies(tags.getOrDefault("parody", Set.of()))
                    .characters(tags.getOrDefault("character", Set.of()))
                    .categories(tags.getOrDefault("category", Set.of()))
                    .pageUrls(pageUrls(id, gallery.path("pages")))
                    .build();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e.getMessage(), e);
        }
    }

    @Override
    public byte[] downloadPage(URI url) throws IOException
    {
        return api.image(url);
    }

    /**
     * In page order, all on one image server picked at random for each gallery: galleries spread over the
     * servers as the site's reader spreads them, and a retried item may get another server than the one that
     * failed it.
     * <p>
     * A page without a path fails the gallery rather than being left out: pages are numbered by position, so
     * every page after it would get the wrong number.
     */
    private LinkedHashSet<URI> pageUrls(String id, JsonNode pages) throws IOException
    {
        if (pages.isEmpty())
        {
            // The pipeline treats a gallery without pages as not ready yet; no server list is needed for that.
            return new LinkedHashSet<>();
        }
        List<String> servers = api.imageServers();
        String server = servers.get(ThreadLocalRandom.current().nextInt(servers.size()));
        var numbered = new ArrayList<NumberedPage>();
        int position = 0;
        for (JsonNode page : pages)
        {
            position++;
            String path = text(page, "path");
            if (path == null)
            {
                throw new IOException("nhentai lists page " + position + " of gallery " + id + " without an image.");
            }
            numbered.add(new NumberedPage(page.path("number").asInt(position), imageUri(server, path)));
        }
        // Stable, and nothing dropped: the order is the source's own numbering.
        numbered.sort(Comparator.comparingInt(NumberedPage::number));
        var urls = new LinkedHashSet<URI>();
        numbered.forEach(page -> urls.add(page.uri()));
        return urls;
    }

    private record NumberedPage(int number, URI uri)
    {
    }

    /** Relative paths go on the server; nhentai may also give a full address, which is taken as it is. */
    private static URI imageUri(String server, String path) throws IOException
    {
        String address = StringUtils.startsWithAny(path.toLowerCase(Locale.ROOT), "https://", "http://")
                ? path : server + "/" + StringUtils.removeStart(path, "/");
        try
        {
            return URI.create(address);
        }
        catch (IllegalArgumentException e)
        {
            throw new IOException("nhentai gave a page address that is not one: " + address, e);
        }
    }

    /** In the order nhentai lists them. */
    private static Map<String, Set<String>> tagsByType(JsonNode tags)
    {
        var byType = new LinkedHashMap<String, Set<String>>();
        for (JsonNode tag : tags)
        {
            String type = text(tag, "type");
            String name = text(tag, "name");
            if (type != null && name != null)
            {
                byType.computeIfAbsent(type.toLowerCase(Locale.ROOT), t -> new LinkedHashSet<>()).add(name);
            }
        }
        return byType;
    }

    /**
     * nhentai files "translated", "rewrite", "speechless" and the like as languages too, sometimes before the real
     * one, so the first language the app knows wins. Without one, the first tag is kept, so the refusal to import
     * names it.
     */
    static String language(Collection<String> languageTags)
    {
        return languageTags.stream()
                .filter(LanguageService::isKnown)
                .findFirst()
                .orElseGet(() -> languageTags.stream().findFirst().orElse(null));
    }

    /**
     * nhentai sometimes files two artists as one, {@code "name1 | name2"}. Only a pipe with whitespace on both
     * sides separates, and only when every part is a name, so a name with pipes of its own ({@code "|||naka|||"})
     * stays whole.
     */
    static Set<String> splitArtists(Collection<String> names)
    {
        var artists = new LinkedHashSet<String>();
        for (String name : names)
        {
            List<String> parts = Arrays.stream(ARTIST_SEPARATOR.split(name, -1)).map(String::strip).toList();
            if (parts.size() > 1 && parts.stream().noneMatch(String::isEmpty))
            {
                artists.addAll(parts);
            }
            else
            {
                artists.add(name);
            }
        }
        return artists;
    }

    // ---- favourites --------------------------------------------------------

    /** The list is the API key owner's, so it needs a key. */
    @Override
    public boolean canListFavourites()
    {
        return api.hasApiKey();
    }

    @Override
    public FavouritesPage favourites(int page)
    {
        JsonNode body;
        try
        {
            body = api.favourites(page);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e.getMessage(), e);
        }
        var ids = new ArrayList<String>();
        for (JsonNode gallery : body.path("result"))
        {
            long id = gallery.path("id").asLong(0);
            if (id > 0)
            {
                ids.add(Long.toString(id));
            }
        }
        return new FavouritesPage(ids, Math.max(0, body.path("num_pages").asInt(0)));
    }
}

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
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
public class NhentaiDownloader implements FavouritesSource, PageDownloader, SearchSource
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

    /** A search on the site, pasted whole: its {@code q} is the query. */
    private static final Pattern SEARCH_URL = Pattern.compile(
            "(?:https?://)?(?:[a-z0-9-]+\\.)*nhentai\\.net/search/?\\?([^#]*)(?:#.*)?", Pattern.CASE_INSENSITIVE);

    /** Any other page of the site: nhentai would search for its address as text and find nothing. */
    private static final Pattern SITE_ADDRESS = Pattern.compile(
            "(?:https?://)?(?:[a-z0-9-]+\\.)*nhentai\\.net(?:[/?#].*)?", Pattern.CASE_INSENSITIVE);

    /** The filter a subscription adds itself, so the user's own would clash with it. */
    private static final Pattern UPLOADED_TERM = Pattern.compile("(?:^|[\\s-])uploaded:", Pattern.CASE_INSENSITIVE);

    private final NhentaiApi api;
    /** Per query, see {@link Place}. */
    private final Map<String, Place> places = new ConcurrentHashMap<>();

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
                    .artists(tags.getOrDefault("artist", Set.of()))
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
     * one, so the first language the app knows wins and the others are ignored. Without one, the first tag is kept,
     * so the import's log names what the gallery has.
     */
    static String language(Collection<String> languageTags)
    {
        return languageTags.stream()
                .filter(LanguageService::isKnown)
                .findFirst()
                .orElseGet(() -> languageTags.stream().findFirst().orElse(null));
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

    // ---- subscriptions -----------------------------------------------------------------------------------------

    @Override
    public List<SearchSite> searchSites()
    {
        return List.of(new SearchSite(PREFIX, "nhentai", "What you would type into nhentai's search, e.g. artist:name "
                + "or tag:\"big breasts\" language:english - or paste a search's address. Leave out uploaded: "
                + "(the subscription looks for new galleries itself)."));
    }

    /** The text nhentai's search box takes; a pasted search address gives its {@code q}. */
    @Override
    public String normalizedQuery(String site, String query)
    {
        String text = StringUtils.strip(StringUtils.defaultString(query));
        Matcher url = SEARCH_URL.matcher(text);
        if (url.matches())
        {
            text = queryParameter(url.group(1), "q");
        }
        else if (SITE_ADDRESS.matcher(text).matches())
        {
            throw new IllegalArgumentException("Paste the address of a search (nhentai.net/search/?q=...), not of "
                    + "another nhentai page. For a tag's or an artist's page, type its search instead, e.g. "
                    + "tag:\"big breasts\" or artist:name.");
        }
        else if (QueryStrings.isAddress(text))
        {
            throw new IllegalArgumentException("That is not an address on nhentai.net. Paste the address of a search "
                    + "there (nhentai.net/search/?q=...), or type the search itself, e.g. tag:\"big breasts\".");
        }
        text = StringUtils.normalizeSpace(text);
        if (text.isEmpty())
        {
            throw new IllegalArgumentException("Enter what to search nhentai for, e.g. artist:name or "
                    + "tag:\"big breasts\" language:english.");
        }
        if (UPLOADED_TERM.matcher(text).find())
        {
            throw new IllegalArgumentException("Leave out uploaded: - a subscription follows the whole search and "
                    + "looks for new galleries itself.");
        }
        return text;
    }

    private static String queryParameter(String queryString, String name)
    {
        return QueryStrings.parameters(queryString).stream()
                .filter(pair -> pair.getKey().equals(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    /** The search is public; an API key only raises its rate limit. */
    @Override
    public Optional<String> notReady(String site)
    {
        return Optional.empty();
    }

    /**
     * Below a cursor, the search narrowed to what was uploaded before the second it stopped in
     * ({@link NhentaiSearchCursor}), so the galleries after the cursor come in one answer. Its first page holds them
     * unless more than a page of galleries was uploaded in the hour (for an older cursor, the day) the filter keeps
     * above that second; then the next pages of the same narrowed search do. A cursor too recent for the filter is
     * found from the plain search's first page, a page or two down.
     */
    @Override
    public SearchPage search(String site, String query, String after)
    {
        try
        {
            if (after == null)
            {
                ListedPage first = listed(query, 1);
                return new SearchPage(first.ids(), first.blacklisted(), first.page() < first.pageCount(),
                        first.total());
            }
            NhentaiSearchCursor cursor = NhentaiSearchCursor.parse(after);
            Optional<Instant> serverNow = api.serverNow();
            ListedPage plainFirst = null;
            if (serverNow.isEmpty())
            {
                // The filter's edge is on nhentai's clock, unknown before its first answer; without it, no filter.
                plainFirst = listed(query, 1);
                serverNow = api.serverNow();
            }
            Optional<String> filter = serverNow.flatMap(cursor::filter);
            String searched = filter.map(term -> NhentaiSearchCursor.filtered(query, term)).orElse(query);
            int page = pageFor(query, searched, cursor);
            // A remembered page is only where to look first: pages move, so while it holds nothing the walk had, the
            // cursor may lie above it, and the walk goes back until it finds a page that does.
            boolean placed = page == 1;
            while (true)
            {
                ListedPage listed = page == 1 && plainFirst != null && filter.isEmpty()
                        ? plainFirst : listed(searched, page);
                List<String> following = listed.following(cursor);
                if (!placed && following.size() == listed.ids().size())
                {
                    page--;
                    placed = page == 1;
                    continue;
                }
                placed = true;
                if (!following.isEmpty())
                {
                    places.put(query, new Place(searched, page, following));
                    // The narrowed search counts only what is old enough, not the whole search.
                    return new SearchPage(following, listed.blacklisted(), listed.page() < listed.pageCount(),
                            filter.isPresent() ? null : listed.total());
                }
                if (listed.ids().isEmpty() || page >= listed.pageCount())
                {
                    confirmEnd(query, cursor, filter.isEmpty() ? listed : plainFirst);
                    return SearchPage.end();
                }
                page++;
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e.getMessage(), e);
        }
    }

    /**
     * Above the second the page's oldest gallery was uploaded in, which may go on on the next page (see
     * {@link NhentaiSearchCursor}). Upload times come from the galleries' own details, since the search lists none,
     * read from the oldest gallery up until one was uploaded later: usually two requests. A gallery gone since it was
     * listed is passed over; it was uploaded no later than the one above it.
     */
    @Override
    public String cursorAfter(String site, String after, List<String> resourceIds)
    {
        NhentaiSearchCursor previous = after == null ? null : NhentaiSearchCursor.parse(after);
        List<Long> ids = resourceIds.stream().map(Long::parseLong).sorted(Comparator.reverseOrder()).toList();
        Long oldest = null;
        Long second = null;
        Long below = null;
        for (int i = ids.size() - 1; i >= 0; i--)
        {
            Optional<Long> uploaded = uploadedAt(ids.get(i));
            if (uploaded.isEmpty())
            {
                continue;
            }
            if (second == null)
            {
                oldest = ids.get(i);
                second = uploaded.get();
            }
            else if (uploaded.get() < second)
            {
                throw new UncheckedIOException(new IOException("nhentai has gallery " + ids.get(i)
                        + " uploaded before gallery " + oldest + ", against the order of their numbers, so where "
                        + "the search left off cannot be told."));
            }
            else if (uploaded.get() > second)
            {
                below = ids.get(i);
                break;
            }
        }
        if (second == null)
        {
            throw new UncheckedIOException(new IOException("None of the galleries nhentai just listed can be read "
                    + "any more, so the search has nothing to continue from."));
        }
        if (below == null)
        {
            // The whole page is one second: the cursor stays where it was. Above the newest page, only more than a
            // page of galleries uploaded in that one second could have a higher id than the page's newest.
            below = previous == null ? ids.getFirst() + 1 : previous.below();
        }
        var handled = new HashSet<>(ids);
        if (previous != null)
        {
            handled.addAll(previous.handled());
        }
        long cut = below;
        handled.removeIf(id -> id >= cut);
        return new NhentaiSearchCursor(cut, second, handled).token();
    }

    /** Empty for a gallery gone since it was listed, or one without a time. */
    private Optional<Long> uploadedAt(long id)
    {
        try
        {
            long uploaded = api.gallery(Long.toString(id)).path("upload_date").asLong(0);
            return uploaded > 0 ? Optional.of(uploaded) : Optional.empty();
        }
        catch (GalleryNotFoundException e)
        {
            return Optional.empty();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e.getMessage(), e);
        }
    }

    @Override
    public long position(String resourceId)
    {
        return Long.parseLong(resourceId);
    }

    @Override
    public String searchPageUrl(String site, String query)
    {
        return "https://nhentai.net/search/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    /**
     * Where the search last gave a page, so the next step of the same walk starts there rather than at page 1: near
     * the top every step reads the plain search, a catch-up of k pages would otherwise read k^2/2, and a narrowed
     * search keeps up to a day of galleries the walk had above the cursor. Only a hint, used when the cursor continues
     * from that page of the same search text; one per query, since the narrowed text changes as the cursor ages.
     */
    private record Place(String searched, int page, long lowest, long highest)
    {
        Place(String searched, int page, List<String> listed)
        {
            this(searched, page, listed.stream().mapToLong(Long::parseLong).min().orElseThrow(),
                    listed.stream().mapToLong(Long::parseLong).max().orElseThrow());
        }
    }

    private int pageFor(String query, String searched, NhentaiSearchCursor cursor)
    {
        Place place = places.get(query);
        // The next cursor stands above the page's oldest second, at most just above its newest gallery.
        return place != null && place.searched().equals(searched) && cursor.below() > place.lowest()
                && cursor.below() <= place.highest() + 1 ? place.page() : 1;
    }

    /**
     * An empty answer below a cursor is the end only if the plain search agrees: one that lists nothing at all,
     * though the walk had galleries from it, or still lists a gallery below the cursor on its last page, failed to
     * answer. Taken for the end, it would stop the walk for good.
     *
     * @param known a page of the plain search already read, or null
     */
    private void confirmEnd(String query, NhentaiSearchCursor cursor, ListedPage known) throws IOException
    {
        ListedPage first = known != null && known.page() == 1 ? known : listed(query, 1);
        ListedPage last = known != null && !known.ids().isEmpty() && known.page() >= first.pageCount() ? known
                : first.pageCount() > 1 ? listed(query, first.pageCount()) : first;
        if (first.ids().isEmpty() || !last.following(cursor).isEmpty())
        {
            throw new IOException("nhentai's search answered with nothing below where the subscription stopped, "
                    + "though it lists older galleries; it is asked again later.");
        }
    }

    /**
     * One page as nhentai answered it, sorted newest first by id: nhentai lists the galleries of one second in no
     * order of their ids, while galleries of different seconds follow their ids.
     */
    private ListedPage listed(String query, int page) throws IOException
    {
        JsonNode body = api.search(query, page);
        if (!body.path("result").isArray() || !body.path("num_pages").isIntegralNumber())
        {
            throw new IOException("nhentai's answer to a search is not a list of galleries.");
        }
        var ids = new TreeSet<Long>(Comparator.reverseOrder());
        var blacklisted = new HashSet<String>();
        for (JsonNode gallery : body.path("result"))
        {
            long id = gallery.path("id").asLong(0);
            if (id <= 0)
            {
                throw new IOException("nhentai's search listed a gallery without an id.");
            }
            ids.add(id);
            if (gallery.path("blacklisted").asBoolean(false))
            {
                blacklisted.add(Long.toString(id));
            }
        }
        JsonNode total = body.path("total");
        return new ListedPage(page, Math.max(0, body.path("num_pages").asInt(0)),
                total.isIntegralNumber() ? total.asLong() : null, List.copyOf(ids), blacklisted);
    }

    private record ListedPage(int page, int pageCount, Long total, List<Long> numbers, Set<String> blacklisted)
    {
        List<String> ids()
        {
            return numbers.stream().map(String::valueOf).toList();
        }

        List<String> following(NhentaiSearchCursor cursor)
        {
            return numbers.stream().filter(cursor::follows).map(String::valueOf).toList();
        }
    }
}

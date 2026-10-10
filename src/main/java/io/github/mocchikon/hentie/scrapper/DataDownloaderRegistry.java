package io.github.mocchikon.hentie.scrapper;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * <b>Asks every downloader</b> whether it accepts a link, never switches over known sources, so a new
 * {@code DataDownloader} bean is the whole registration.
 */
@Component
public class DataDownloaderRegistry
{
    private final List<DataDownloader> downloaders;

    /** Fixed at startup, so worked out once: the runner and the subscription pages ask on every round. */
    private final List<SearchSource> searchSources;
    private final List<SearchSource.SearchSite> searchSites;
    private final Map<String, SearchSource> searchSourcesBySite;

    public DataDownloaderRegistry(List<DataDownloader> downloaders)
    {
        // Two sources sharing a prefix would collide on chapter.gallery_id; fail at startup, where it is obvious.
        Set<String> prefixes = new HashSet<>();
        for (DataDownloader downloader : downloaders)
        {
            if (!prefixes.add(downloader.sourcePrefix()))
            {
                throw new IllegalStateException("Two data downloaders share the source prefix '"
                        + downloader.sourcePrefix() + "'; gallery ids would collide.");
            }
        }
        this.downloaders = List.copyOf(downloaders);
        this.searchSources = downloaders.stream()
                .filter(SearchSource.class::isInstance)
                .map(SearchSource.class::cast)
                .sorted(Comparator.comparing(DataDownloader::sourcePrefix))
                .toList();
        // Stored on subscriptions, so two sites with one key would make a subscription search the wrong one.
        var sites = new ArrayList<SearchSource.SearchSite>();
        var bySite = new HashMap<String, SearchSource>();
        for (SearchSource source : searchSources)
        {
            for (SearchSource.SearchSite site : source.searchSites())
            {
                if (bySite.putIfAbsent(site.key(), source) != null)
                {
                    throw new IllegalStateException("Two search sites share the key '" + site.key() + "'.");
                }
                sites.add(site);
            }
        }
        this.searchSites = List.copyOf(sites);
        this.searchSourcesBySite = Map.copyOf(bySite);
    }

    public Optional<ResourceLink> parse(String link)
    {
        String trimmed = StringUtils.trimToEmpty(link);
        return accepting(trimmed).map(downloader -> ResourceLink.of(downloader, trimmed));
    }

    /**
     * Only returned when it parses back to the same gallery id: queueing it acts on the chapter holding that
     * id, so a source whose {@link DataDownloader#link} disagreed with its parsing would hit another gallery.
     */
    public Optional<String> linkFor(String galleryId)
    {
        if (StringUtils.isBlank(galleryId))
        {
            return Optional.empty();
        }
        return downloaders.stream()
                .filter(downloader -> galleryId.startsWith(downloader.galleryId("")))
                .findFirst()
                .map(downloader -> downloader.link(galleryId.substring(downloader.galleryId("").length())))
                .filter(link -> parse(link).map(ResourceLink::galleryId).filter(galleryId::equals).isPresent());
    }

    /**
     * Only for a gallery id {@link #linkFor} accepts, so an id edited by hand into something its source would
     * never produce gets no link.
     */
    public Optional<String> pageLinkFor(String galleryId, String site)
    {
        return linkFor(galleryId)
                .flatMap(this::parse)
                .flatMap(resource -> Optional.ofNullable(resource.downloader().pageLinkTemplate(site))
                        .map(template -> template.replace(DataDownloader.RESOURCE_ID, resource.resourceId())));
    }

    public boolean isSupported(String link)
    {
        return accepting(StringUtils.trimToEmpty(link)).isPresent();
    }

    /** Blank input is handled here, so no source has to guard against it. */
    private Optional<DataDownloader> accepting(String trimmedLink)
    {
        if (trimmedLink.isEmpty())
        {
            return Optional.empty();
        }
        return downloaders.stream().filter(downloader -> downloader.accepts(trimmedLink)).findFirst();
    }

    // hidden
    private static final Set<String> HIDDEN_SOURCES = Set.of("mock", "chaika");

    /** Prefix to an example link, sorted by prefix, for the Download page. */
    public Map<String, String> linkExamples()
    {
        var examples = new LinkedHashMap<String, String>();
        downloaders.stream()
                .filter(downloader -> !HIDDEN_SOURCES.contains(downloader.sourcePrefix()))
                .sorted(Comparator.comparing(DataDownloader::sourcePrefix))
                .forEach(downloader -> examples.put(downloader.sourcePrefix(), downloader.linkExample()));
        return examples;
    }

    public List<String> sourcePrefixes()
    {
        return downloaders.stream().map(DataDownloader::sourcePrefix).sorted().toList();
    }

    /** Sorted by prefix, so the choice offered does not depend on bean order. */
    public List<FavouritesSource> favouritesSources()
    {
        return downloaders.stream()
                .filter(FavouritesSource.class::isInstance)
                .map(FavouritesSource.class::cast)
                .sorted(Comparator.comparing(DataDownloader::sourcePrefix))
                .toList();
    }

    public Optional<FavouritesSource> favouritesSource(String sourcePrefix)
    {
        return favouritesSources().stream()
                .filter(source -> source.sourcePrefix().equals(sourcePrefix))
                .findFirst();
    }

    /** Sorted by prefix, like {@link #favouritesSources}. */
    public List<SearchSource> searchSources()
    {
        return searchSources;
    }

    /** Every site a subscription may pick, in the order of {@link #searchSources}. */
    public List<SearchSource.SearchSite> searchSites()
    {
        return searchSites;
    }

    /** The source offering the site with this key. */
    public Optional<SearchSource> searchSource(String siteKey)
    {
        return siteKey == null ? Optional.empty() : Optional.ofNullable(searchSourcesBySite.get(siteKey));
    }

    /** Gallery-id prefixes ({@code "ehentai:"}) of the sources refusing every download now. */
    public Set<String> refusals()
    {
        var refusals = new LinkedHashSet<String>();
        for (DataDownloader downloader : downloaders)
        {
            if (downloader.refusingUntil().isPresent())
            {
                refusals.add(downloader.galleryId(""));
            }
        }
        return refusals;
    }

    /** Until when the source of this gallery refuses every download; empty while it does not, or for no source. */
    public Optional<Instant> refusingUntil(String galleryId)
    {
        if (StringUtils.isBlank(galleryId))
        {
            return Optional.empty();
        }
        return downloaders.stream()
                .filter(downloader -> galleryId.startsWith(downloader.galleryId("")))
                .findFirst()
                .flatMap(DataDownloader::refusingUntil);
    }
}

package io.github.mocchikon.hentie.scrapper;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * <b>Asks every downloader</b> whether it accepts a link, never switches over known sources, so a new
 * {@code DataDownloader} bean is the whole registration.
 */
@Component
public class DataDownloaderRegistry
{
    private final List<DataDownloader> downloaders;

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
    public Optional<String> pageLinkFor(String galleryId)
    {
        return linkFor(galleryId)
                .flatMap(this::parse)
                .flatMap(resource -> Optional.ofNullable(resource.downloader().pageLinkTemplate())
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
}

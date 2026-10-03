package io.github.mocchikon.hentie.scrapper;

import java.util.List;

/**
 * A source whose site keeps a list of the user's favourites, for "Download all favourites". Optional: a source
 * without user accounts implements only {@link DataDownloader}. Like a gallery fetch, listing never touches the
 * database; {@code service.download} decides what to queue.
 */
public interface FavouritesSource extends DataDownloader
{
    /** False while something the listing needs is missing, such as an API key, so the button can say so up front. */
    boolean canListFavourites();

    /**
     * One page of the list, counted from 1, in the site's order. Paced to the site's rate limit, so a caller may
     * simply walk the pages.
     *
     * @throws java.io.UncheckedIOException on any failure, worded for the user
     */
    FavouritesPage favourites(int page);

    /**
     * @param resourceIds ids as {@link #resourceId} gives them
     * @param pageCount   how many pages the list has now; it may change while it is walked
     */
    record FavouritesPage(List<String> resourceIds, int pageCount)
    {
        public FavouritesPage
        {
            resourceIds = List.copyOf(resourceIds);
        }
    }
}

package io.github.mocchikon.hentie.scrapper;

import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A source whose site search a subscription can follow. Optional, like {@link FavouritesSource}. Like a gallery fetch,
 * searching never touches the database; {@code service.subscription} decides what to queue.
 * <p>
 * <b>A walk goes newest to oldest and continues from a cursor, never a page number</b>: galleries are added and
 * removed while it walks, so page 7 holds other galleries tomorrow. A cursor names where the walk stopped, and the
 * source turns it into "the galleries listed after that" whatever changed meanwhile.
 */
public interface SearchSource extends DataDownloader
{
    /**
     * What a subscription may pick: the site, or several when one source has more (e-hentai and exhentai). A key is
     * stored on subscriptions, so it is never renamed.
     */
    List<SearchSite> searchSites();

    /**
     * The query as the site's search takes it, from what the user typed or pasted (a search's whole address too).
     *
     * @throws IllegalArgumentException for a query the site cannot follow, worded for the user
     */
    String normalizedQuery(String site, String query);

    /** What the site needs before it can be searched, worded for the user (exhentai: the account cookies). */
    Optional<String> notReady(String site);

    /** What the queueing choices lack for the site's galleries to download, worded for the user; empty when nothing. */
    default Optional<String> choicesProblem(String site, GalleryDlOptions galleryDl)
    {
        return Optional.empty();
    }

    /**
     * One page of the search, newest first, holding only galleries listed after {@code after}. Paced to the site's
     * limit for searches, so a caller may simply ask page after page.
     *
     * @param after a cursor from {@link #cursorAfter}, or null for the newest galleries
     * @throws java.io.UncheckedIOException on any failure, worded for the user. A page that could not be read is
     *                                      never returned as an empty one: the caller would take it as the end.
     * @throws io.github.mocchikon.hentie.service.download.RetryLaterException while the site must not be asked
     */
    SearchPage search(String site, String query, String after);

    /**
     * Where a walk continues after a page: below its galleries, however much the site changed meanwhile. May go over
     * the network (nhentai reads upload times).
     *
     * @param after       the cursor the page was fetched from, null for the newest page
     * @param resourceIds the page's galleries, newest first, not empty
     * @throws java.io.UncheckedIOException on any failure, worded for the user
     */
    String cursorAfter(String site, String after, List<String> resourceIds);

    /** Higher is newer; compares two galleries of the site without a request. */
    long position(String resourceId);

    /** The search on the site itself, for a person to open. */
    String searchPageUrl(String site, String query);

    /** @param queryHelp what the form says to type, in plain text */
    record SearchSite(String key, String label, String queryHelp)
    {
    }

    /**
     * @param resourceIds newest first, every one listed after the cursor asked for
     * @param blacklisted those the site marks as unwanted for the user: walked past, never queued
     * @param more        whether older galleries follow; false only when the site said this is its last page
     * @param total       how many galleries the whole search finds, as the site counts them; null when unknown
     */
    record SearchPage(List<String> resourceIds, Set<String> blacklisted, boolean more, Long total)
    {
        public SearchPage
        {
            resourceIds = List.copyOf(resourceIds);
            blacklisted = Set.copyOf(blacklisted);
        }

        public static SearchPage end()
        {
            return new SearchPage(List.of(), Set.of(), false, null);
        }
    }
}

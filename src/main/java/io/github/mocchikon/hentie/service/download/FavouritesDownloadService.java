package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.FavouritesSource;
import io.github.mocchikon.hentie.service.download.DownloadQueueService.EnqueueResult;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * "Download all favourites": queues every gallery in the user's favourites on a site that the library does not
 * have yet, as a paste of their links with the same choices would.
 * <p>
 * <b>Runs on the request thread, page by page</b>, like the library sweeps; no background job. Each page is queued
 * (and the worker woken) as soon as it is listed, so downloads start while the rest is still being listed, and a
 * crash part-way loses only what pressing the button again redoes. The time goes into the site's rate limit.
 * <p>
 * <b>A gallery the library had is left out</b>, unless its download never finished: queueing the whole list would
 * fetch every gallery's details again only to find the chapter there. That includes a chapter deleted since: the
 * user removed it, and a bulk action must not bring it back.
 */
@Service
@RequiredArgsConstructor
public class FavouritesDownloadService
{
    private static final Logger log = LoggerFactory.getLogger(FavouritesDownloadService.class);

    private final DataDownloaderRegistry registry;
    private final GalleryImportService importService;
    private final DownloadQueueService queueService;
    private final DownloadWorker worker;
    private final WriteGate writeGate;

    /** One listing at a time: a second would list the same pages again, on the same rate limit. */
    private final ReentrantLock listing = new ReentrantLock();

    /** @param ready whether the source can list now; the page says what is missing otherwise */
    public record Option(String source, boolean ready)
    {
    }

    public List<Option> options()
    {
        return registry.favouritesSources().stream()
                .map(source -> new Option(source.sourcePrefix(), source.canListFavourites()))
                .toList();
    }

    /** @param choices as on the paste form; an invalid compression mode becomes None */
    public Outcome queueAll(String sourcePrefix, DownloadChoices choices)
    {
        FavouritesSource source = registry.favouritesSource(sourcePrefix).orElse(null);
        if (source == null)
        {
            return Outcome.refused(sourcePrefix, "No source here can list favourites from \""
                    + StringUtils.abbreviate(StringUtils.defaultString(sourcePrefix), 100) + "\".");
        }
        String name = source.sourcePrefix();
        if (!source.canListFavourites())
        {
            return Outcome.refused(name, "Listing your " + name + " favourites needs your " + name
                    + " API key. Set it in Settings first.");
        }
        if (!listing.tryLock())
        {
            return Outcome.refused(name, "Favourites are already being listed, in another tab or by another "
                    + "device. Their galleries appear in the download queue as they are found.");
        }
        try
        {
            // Its writes wait for the library as long as they must, like a sweep's: the user watches a spinner.
            return writeGate.background("listing your " + name + " favourites",
                    () -> walk(source, choices));
        }
        finally
        {
            listing.unlock();
        }
    }

    private Outcome walk(FavouritesSource source, DownloadChoices choices)
    {
        String name = source.sourcePrefix();
        var tally = new Tally(name);
        // 0 until the first page says how many there are.
        int pageCount = 0;
        for (int page = 1; page == 1 || page <= pageCount; page++)
        {
            FavouritesSource.FavouritesPage listed;
            try
            {
                listed = source.favourites(page);
            }
            catch (RuntimeException e)
            {
                log.warn("Listing {} favourites stopped at page {}: {}", name, page, e.toString());
                return tally.stopped(page, pageCount, StringUtils.defaultIfBlank(e.getMessage(), e.toString()));
            }
            // Past the end, whatever an earlier page said the count was: the list may shrink while it is walked.
            if (listed.resourceIds().isEmpty())
            {
                break;
            }
            pageCount = listed.pageCount();
            queuePage(source, listed.resourceIds(), choices, tally);
            // Each page, so downloads start while the rest is listed.
            worker.kick();
        }
        Outcome outcome = tally.finished();
        log.info("Listed {} {} favourite(s): {} new in the queue, {} queued again, {} already waiting, {} in the "
                        + "library, {} deleted since", outcome.listed(), name, outcome.enqueued().accepted(),
                outcome.enqueued().requeued(), outcome.enqueued().alreadyQueued(), outcome.inLibrary(),
                outcome.deletedSince());
        return outcome;
    }

    /** One short transaction for the page's queue rows, so a request in another tab waits for one page at most. */
    private void queuePage(FavouritesSource source, List<String> resourceIds, DownloadChoices choices, Tally tally)
    {
        var linksByGalleryId = new LinkedHashMap<String, String>();
        resourceIds.forEach(id -> linksByGalleryId.put(source.galleryId(id), source.link(id)));
        Map<String, GalleryImportService.Holding> holdings = importService.holdings(linksByGalleryId.keySet());
        var links = new ArrayList<String>();
        linksByGalleryId.forEach((galleryId, link) ->
        {
            GalleryImportService.Holding holding = holdings.get(galleryId);
            if (holding == GalleryImportService.Holding.IN_LIBRARY)
            {
                tally.inLibrary++;
            }
            else if (holding == GalleryImportService.Holding.DELETED)
            {
                tally.deletedSince++;
            }
            else
            {
                links.add(link);
            }
        });
        tally.listed += linksByGalleryId.size();
        if (!links.isEmpty())
        {
            tally.enqueued = tally.enqueued.plus(queueService.enqueue(links, choices));
        }
    }

    private static final class Tally
    {
        private final String source;
        private int listed;
        private int inLibrary;
        private int deletedSince;
        private EnqueueResult enqueued = EnqueueResult.NONE;

        private Tally(String source)
        {
            this.source = source;
        }

        private Outcome finished()
        {
            return new Outcome(source, null, null, 0, 0, listed, inLibrary, deletedSince, enqueued);
        }

        private Outcome stopped(int page, int pageCount, String error)
        {
            return new Outcome(source, null, error, page, pageCount, listed, inLibrary, deletedSince, enqueued);
        }
    }

    /**
     * @param refusal      why nothing was listed at all; null once listing started
     * @param error        why listing stopped part-way; what was queued before stays queued
     * @param stoppedAt    the page that failed, with {@code pageCount} as known then
     * @param listed       galleries listed, every one counted in exactly one of the other numbers
     * @param inLibrary    left out: the library has them
     * @param deletedSince left out: downloaded before, their chapter deleted since
     */
    public record Outcome(String source, String refusal, String error, int stoppedAt, int pageCount, int listed,
                          int inLibrary, int deletedSince, EnqueueResult enqueued)
    {
        static Outcome refused(String source, String reason)
        {
            return new Outcome(source, reason, null, 0, 0, 0, 0, 0, EnqueueResult.NONE);
        }

        public boolean isRefused()
        {
            return refusal != null;
        }

        public boolean isStopped()
        {
            return error != null;
        }

        /** What the queue page says. */
        public String summary()
        {
            if (isRefused())
            {
                return refusal;
            }
            String counts = counts();
            if (isStopped())
            {
                String reached = pageCount > 0 ? " at page " + stoppedAt + " of " + pageCount : "";
                return "Listing your " + source + " favourites stopped" + reached + ": " + error
                        + (listed > 0 ? " Of the " + listed + " listed before that: " + counts + "." : "");
            }
            return listed == 0 ? "Your " + source + " favourites list is empty."
                    : "Listed " + listed + " " + source + " favourite(s): " + counts + ".";
        }

        private String counts()
        {
            var parts = new ArrayList<String>();
            if (enqueued.accepted() > 0)
            {
                parts.add("queued " + enqueued.accepted());
            }
            if (enqueued.requeued() > 0)
            {
                parts.add("queued again " + enqueued.requeued() + " that had failed");
            }
            if (enqueued.alreadyQueued() > 0)
            {
                parts.add(enqueued.alreadyQueued() + " already waiting");
            }
            if (inLibrary > 0)
            {
                parts.add(inLibrary + " already in the library");
            }
            if (deletedSince > 0)
            {
                parts.add(deletedSince + " left out because they were downloaded before and deleted since - paste "
                        + "their links to download them again");
            }
            if (enqueued.rejected() > 0)
            {
                parts.add(enqueued.rejected() + " not recognized as links of the source");
            }
            return parts.isEmpty() ? "nothing to queue" : String.join(", ", parts);
        }
    }
}

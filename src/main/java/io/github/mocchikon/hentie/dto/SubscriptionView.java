package io.github.mocchikon.hentie.dto;

/**
 * One subscription as its page shows it. The status is worded by the service, which knows why a subscription waits.
 *
 * @param title          its name, or its query when it has none
 * @param searchUrl      the search on the site, for a person to open
 * @param statusKind     {@code ok}, {@code warning} or {@code error}, for the status line's look
 * @param newestLink     the head on the site, null when there is none (or no page for it)
 * @param waiting        its rows waiting in the download queue
 * @param failed         its rows on the queue's Failed list
 * @param choices        what each gallery it queues gets (compression, duplicate titles, gallery-dl)
 * @param schedule       how often it looks for new galleries and re-checks recent ones
 */
public record SubscriptionView(int id, String title, String siteLabel, String query, String searchUrl,
                               boolean enabled, String status, String statusKind,
                               String newestGalleryId, String newestLink, String oldestGalleryId, String oldestLink,
                               int queued, int inLibrary, int deletedSince, int alreadyQueued, int blacklisted,
                               long waiting, long failed, String choices, String schedule)
{
}

package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;

/**
 * What a paste asks of each link it queues, stored on the queue row because the download runs later. One object,
 * so a new choice cannot be dropped by one of the paths that queue links (paste, favourites, subscriptions).
 *
 * @param compressionMode a mode key, or "By rules"; {@link DownloadQueueService#enqueue} makes it storable
 * @param allowUpgrade    a gallery already in the library is downloaded again when the mode is better than the one
 *                        that compressed its pages; ignored "By rules", where the rules decide it
 * @param galleryDl       read only by gallery-dl sources
 */
public record DownloadChoices(String compressionMode, boolean allowUpgrade, boolean avoidDuplicateTitles,
                              GalleryDlOptions galleryDl)
{
}

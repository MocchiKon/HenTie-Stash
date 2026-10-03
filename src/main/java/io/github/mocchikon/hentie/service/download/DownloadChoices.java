package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;

/**
 * What a paste asks of each link it queues, stored on the queue row because the download runs later. One object,
 * so a new choice cannot be dropped by one of the paths that queue links (paste, favourites).
 *
 * @param compressionMode a mode key; {@link DownloadQueueService#enqueue} makes it storable
 * @param galleryDl       read only by gallery-dl sources
 */
public record DownloadChoices(String compressionMode, boolean avoidDuplicateTitles, GalleryDlOptions galleryDl)
{
}

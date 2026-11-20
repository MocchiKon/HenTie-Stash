package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;

/**
 * What a paste asks of each link it queues, stored on the queue row because the download runs later. One object,
 * so a new choice cannot be dropped by one of the paths that queue links (paste, favourites, subscriptions).
 *
 * @param compressionMode        a mode key, or "By rules"; {@link DownloadQueueService#enqueue} makes it storable
 * @param allowUpgrade           a gallery already in the library is downloaded again when the mode is better than
 *                               the one that compressed its pages; ignored "By rules", where the rules decide it
 * @param mergeDuplicateMetadata a duplicate title gives its metadata to the chapter that has it instead of being
 *                               refused; false without {@code avoidDuplicateTitles}, since the forms show it only
 *                               beside a ticked check but still send it when hidden
 * @param galleryDl              read only by gallery-dl sources
 */
public record DownloadChoices(String compressionMode, boolean allowUpgrade, boolean avoidDuplicateTitles,
                              boolean mergeDuplicateMetadata, GalleryDlOptions galleryDl)
{
    public DownloadChoices
    {
        mergeDuplicateMetadata = avoidDuplicateTitles && mergeDuplicateMetadata;
    }
}

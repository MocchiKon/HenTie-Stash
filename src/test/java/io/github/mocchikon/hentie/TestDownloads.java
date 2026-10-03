package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.download.DownloadChoices;

/** Paste choices for suites that care only about the compression mode and the duplicate-title check. */
public final class TestDownloads
{
    /** No cookies, resampled images, no delay: what a paste for a source other than gallery-dl's carries. */
    public static final GalleryDlOptions PLAIN_GALLERY_DL = new GalleryDlOptions(null, false, "0");

    private TestDownloads()
    {
    }

    public static DownloadChoices choices(String compressionMode, boolean avoidDuplicateTitles)
    {
        return new DownloadChoices(compressionMode, avoidDuplicateTitles, PLAIN_GALLERY_DL);
    }

    /** A queue row built by hand, with the gallery-dl choices every queued row carries ({@link #PLAIN_GALLERY_DL}). */
    public static DownloadQueueItem queueItem(String link, String galleryId)
    {
        var item = new DownloadQueueItem();
        item.setLink(link);
        item.setGalleryId(galleryId);
        item.setDownloadOriginals(PLAIN_GALLERY_DL.originals());
        item.setRequestDelay(PLAIN_GALLERY_DL.delay());
        return item;
    }
}

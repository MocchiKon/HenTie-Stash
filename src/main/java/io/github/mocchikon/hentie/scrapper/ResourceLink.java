package io.github.mocchikon.hentie.scrapper;

public record ResourceLink(DataDownloader downloader, String resourceId, String galleryId)
{
    static ResourceLink of(DataDownloader downloader, String link)
    {
        String resourceId = downloader.resourceId(link);
        return new ResourceLink(downloader, resourceId, downloader.galleryId(resourceId));
    }

    /** Built by {@link DataDownloader#galleryId(String)} itself, so it cannot disagree about the separator. */
    public String galleryIdPrefix()
    {
        return downloader.galleryId("");
    }
}

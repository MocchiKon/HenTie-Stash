package io.github.mocchikon.hentie.scrapper;

import java.io.IOException;
import java.net.URI;

/**
 * A source whose pages are fetched one at a time from the addresses {@link GalleryData#getPageUrls} lists. The
 * pipeline owns the loop, the retries and the staging, so every such source behaves alike.
 */
public interface PageDownloader extends DataDownloader
{
    /**
     * One try: the pipeline fetches a failed page again ({@code app.download.page-retries}), the same for every
     * source. A source waits on its own only when its site asks it to slow down.
     *
     * @throws IOException on any failure, including an answer that is no image
     */
    byte[] downloadPage(URI url) throws IOException;

    /**
     * The file extension a page is stored under. The address's own by default; a source whose addresses name no
     * file says it here, or every page would be stored as {@code .jpg}.
     */
    default String pageExtension(URI url)
    {
        return extensionOf(url.getPath() == null ? "" : url.getPath());
    }

    /** The extension of the last part of {@code path}; one rule, so every source stores a page alike. */
    static String extensionOf(String path)
    {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1) : "";
    }
}

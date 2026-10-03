package io.github.mocchikon.hentie.scrapper;

import java.io.IOException;
import java.net.URI;

/**
 * <b>Adding a source means adding one class implementing this interface</b>; nothing else is registered.
 *
 * <p>Each source parses its own links, because link shapes have nothing in common. {@link #accepts} and
 * {@link #resourceId} must agree: whatever one accepts, the other must be able to name.
 *
 * <p><b>A downloader never touches the database or the filesystem</b>, so a new source cannot invent its
 * own storage rules; {@code service.download} decides what to store.
 */
public interface DataDownloader
{
    /** Where {@link #pageLinkTemplate} takes the resource id. */
    String RESOURCE_ID = "{id}";

    /** Lower case and permanent: it is baked into stored gallery ids, so renaming it orphans them. */
    String sourcePrefix();

    /** Called with a non-blank link, trimmed but otherwise as pasted. */
    boolean accepts(String link);

    /** The bare id, without prefix, host or path. Only called for a link {@link #accepts} accepted. */
    String resourceId(String link);

    /**
     * The reverse of {@link #resourceId}: {@code resourceId(link(id))} must be {@code id}. Needed for a
     * full-quality re-download, because the pasted link left with its queue row.
     */
    String link(String resourceId);

    /**
     * The gallery's page on the source's website, for a person to open, with {@link #RESOURCE_ID} where the id
     * goes; null when the source has no website (a local folder). Not {@link #link}: that is what the pipeline
     * fetches from, which need not be a page a browser can show. The id is inserted as it is, so a source whose
     * ids are not safe in a URL must not offer one.
     */
    default String pageLinkTemplate()
    {
        return null;
    }

    /** Namespaced, so two sources both numbering from 1 never collide on the unique {@code chapter.gallery_id}. Never override. */
    default String galleryId(String resourceId)
    {
        return sourcePrefix() + ":" + resourceId;
    }

    /**
     * Must <b>not</b> download the images: it runs before the caller knows whether the gallery is new.
     *
     * @throws GalleryNotFoundException when the source has no such resource (permanent, not retried)
     * @throws java.io.UncheckedIOException on a transient fetch failure (retried)
     */
    GalleryData downloadGalleryInfo(String id);

    /**
     * One try: the pipeline fetches a failed page again ({@code app.download.page-retries}), the same for every
     * source. A source waits on its own only when its site asks it to slow down.
     *
     * @throws IOException on any failure, including an answer that is no image
     */
    byte[] downloadPage(URI url) throws IOException;
}

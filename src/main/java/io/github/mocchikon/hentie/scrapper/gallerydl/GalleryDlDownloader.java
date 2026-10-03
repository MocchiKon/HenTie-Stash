package io.github.mocchikon.hentie.scrapper.gallerydl;

import io.github.mocchikon.hentie.scrapper.DataDownloader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SortedSet;

/**
 * A source whose pages gallery-dl fetches: many pages in one run, into a folder the pipeline hands over. gallery-dl
 * knows each site's page servers, fallbacks and signatures, which a page-by-page source would have to repeat.
 * {@link io.github.mocchikon.hentie.scrapper.GalleryData#getPageCount} gives the page count.
 */
public interface GalleryDlDownloader extends DataDownloader
{
    /**
     * Fetches {@code pages} (numbered from 1 in the gallery's order) into {@code folder}, handing each page to
     * {@code sink} as soon as it is whole. A page that fails is simply not handed over; the pipeline decides what
     * a missing page means.
     *
     * @param folder the only place written to; the caller creates and removes it
     * @return what arrived and gallery-dl's words for what did not
     * @throws RuntimeException a {@link io.github.mocchikon.hentie.scrapper.GalleryNotFoundException} or
     *                          {@link io.github.mocchikon.hentie.service.download.PermanentDownloadException} when
     *                          retrying cannot help, an {@link java.io.UncheckedIOException} otherwise
     * @throws IOException      when the sink fails, or the thread is interrupted
     */
    GalleryDl.Outcome downloadPages(String resourceId, GalleryDlOptions options, SortedSet<Integer> pages,
                                    Path folder, GalleryDl.PageSink sink) throws IOException;
}

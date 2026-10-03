package io.github.mocchikon.hentie.scrapper;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@RequiredArgsConstructor
@AllArgsConstructor
@Builder
public class GalleryData
{
    private final String id;
    private String prettyTitle;
    private String fullTitle;
    private String japaneseTitle;
    private Set<String> artists;
    private Set<String> groups;
    private String language;
    private Set<String> parodies;
    private Set<String> characters;
    private Set<String> tags;
    /** What the gallery is on its source: doujinshi, manga, artist CG and the like. */
    private Set<String> categories;
    /**
     * In reading order. {@link URI}, never {@code URL}: {@code URL.equals} resolves the host, so a set would
     * do a DNS lookup per page and merge two pages whose hosts resolve to one address.
     */
    private LinkedHashSet<URI> pageUrls;
}
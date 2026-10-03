package io.github.mocchikon.hentie.service;

/** A chapter's page files as they are on disk now (uncached), for {@code chapter.page_num} / {@code disk_size}. */
public record PageStats(int pageCount, long diskSize)
{
    public static final PageStats EMPTY = new PageStats(0, 0L);
}

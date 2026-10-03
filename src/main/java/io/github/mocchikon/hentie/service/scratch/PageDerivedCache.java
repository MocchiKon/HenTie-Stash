package io.github.mocchikon.hentie.service.scratch;

/**
 * A cache of files made from a chapter's pages. Every path that deletes or renames pages evicts from all of
 * them, so a new cache only has to implement this.
 *
 * <p>Evicting frees disk space; it is not needed for correctness, because an entry is named after the exact
 * page version it was made from and a stale one is never served.
 */
public interface PageDerivedCache
{
    /** The chapter's pages changed wholesale (re-encoded under new names) or the chapter is gone. */
    void evict(int chapterId);

    /** One page file was deleted, or replaced by a file of another name. */
    void evict(int chapterId, String filename);
}

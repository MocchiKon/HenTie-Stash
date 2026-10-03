package io.github.mocchikon.hentie.service.match;

/** An id, not the entity: candidates are a projection, and only the few a page shows are loaded. */
public record ScoredChapter(int chapterId, double score, double titleScore, int sharedArtists)
{
}

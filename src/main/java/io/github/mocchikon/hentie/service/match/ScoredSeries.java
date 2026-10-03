package io.github.mocchikon.hentie.service.match;

import io.github.mocchikon.hentie.entity.Series;

/** {@code titleScore} and {@code sharedArtists} are kept for display and for tie-breaking. */
public record ScoredSeries(Series series, double score, double titleScore, int sharedArtists, long chapterCount)
{
}

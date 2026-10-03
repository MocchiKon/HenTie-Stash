package io.github.mocchikon.hentie.service.match;

import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Shared by auto-linking and the Add-to-series page, so the two cannot disagree about a good match.
 *
 * <p>Candidates come from capped, ordered seeks: exact key, extensions, bases (token prefixes), and a fuzzy
 * pass by artist, else by {@code match_block}. The fuzzy pass runs only when the exact seek found nothing: it
 * exists for misspelled titles and it is the expensive one.
 *
 * <p>The excluded series (the chapter's own, on the Add-to-series page) is left out by every seek's own
 * predicate, never dropped from the ranking. As an exact hit it would switch off the fuzzy pass, the only one
 * that finds the family of a misspelled title.
 *
 * <p>Ties break on title score, then chapter count (the bigger family is the likelier home), then lowest id,
 * so the outcome never depends on row order.
 */
@Service
@RequiredArgsConstructor
public class SeriesCandidateFinder
{
    /** Rows any single seek may contribute. */
    static final int CANDIDATE_LIMIT = 200;

    /** Above this many series for one artist, the artist stops being a useful filter. */
    static final int ARTIST_FANOUT_LIMIT = 2000;

    private static final int MAX_PREFIXES = 8;

    /**
     * The chapter count is only the third tie-break, so it is fetched for the head of the ranking only,
     * not aggregated over up to two thousand series.
     */
    private static final int COUNT_LOOKUP_LIMIT = 25;

    private final SeriesRepository seriesRepository;
    private final ChapterRepository chapterRepository;

    /** @param excludedSeriesId {@code null} leaves nothing out */
    @Transactional(readOnly = true)
    public List<ScoredSeries> rank(Chapter chapter, Integer excludedSeriesId)
    {
        var key = TitleKey.of(chapter.getTitleFull());
        var artistIds = new HashSet<Integer>();
        for (Artist artist : chapter.getArtists())
        {
            artistIds.add(artist.getId());
        }
        return rank(key, artistIds, Map.of(), excludedSeriesId);
    }

    @Transactional(readOnly = true)
    public List<ScoredSeries> rank(TitleKey key, Set<Integer> artistIds)
    {
        return rank(key, artistIds, Map.of());
    }

    /**
     * {@code pendingArtists} are artists linked but not yet in {@code series_effective_artists}. A caller
     * that defers {@code recomputeDerived} must pass them, or a series linked moments ago looks artist-less
     * and another artist's work joins it.
     */
    @Transactional(readOnly = true)
    public List<ScoredSeries> rank(TitleKey key, Set<Integer> artistIds, Map<Integer, Set<Integer>> pendingArtists)
    {
        return rank(key, artistIds, pendingArtists, null);
    }

    private List<ScoredSeries> rank(TitleKey key, Set<Integer> artistIds, Map<Integer, Set<Integer>> pendingArtists,
                                    Integer excludedSeriesId)
    {
        Map<Integer, Series> candidates = candidates(key, artistIds, excludedSeriesId);
        if (candidates.isEmpty())
        {
            return List.of();
        }

        Map<Integer, Set<Integer>> seriesArtists = effectiveArtists(candidates.keySet());

        var ranked = new ArrayList<ScoredSeries>(candidates.size());
        for (Series series : candidates.values())
        {
            Set<Integer> theirArtists = merged(seriesArtists.get(series.getId()),
                    pendingArtists.get(series.getId()));
            double titleScore = MatchScore.titleScore(key.getTokens(), tokensOf(series));
            double score = MatchScore.combined(titleScore, MatchScore.artistScore(artistIds, theirArtists));
            ranked.add(new ScoredSeries(series, score, titleScore,
                    MatchScore.sharedArtists(artistIds, theirArtists), 0L));
        }
        // Chapter counts break ties afterwards, for the head only (COUNT_LOOKUP_LIMIT).
        ranked.sort(byScore().reversed().thenComparing(candidate -> candidate.series().getId()));
        return withChapterCounts(ranked);
    }

    private static Set<Integer> merged(Set<Integer> materialized, Set<Integer> pending)
    {
        if (pending == null || pending.isEmpty())
        {
            return materialized == null ? Set.of() : materialized;
        }
        var all = new HashSet<>(pending);
        if (materialized != null)
        {
            all.addAll(materialized);
        }
        return all;
    }

    private static Comparator<ScoredSeries> byScore()
    {
        return Comparator.comparingDouble(ScoredSeries::score).thenComparingDouble(ScoredSeries::titleScore);
    }

    /** The tail keeps a count of zero: no caller reads it, and a count could not lift it into the head. */
    private List<ScoredSeries> withChapterCounts(List<ScoredSeries> ranked)
    {
        List<ScoredSeries> head = ranked.subList(0, Math.min(COUNT_LOOKUP_LIMIT, ranked.size()));
        Map<Integer, Long> counts = chapterRepository.chapterCountsBySeriesIds(
                head.stream().map(candidate -> candidate.series().getId()).toList());

        var counted = new ArrayList<ScoredSeries>(head.size());
        for (ScoredSeries candidate : head)
        {
            counted.add(new ScoredSeries(candidate.series(), candidate.score(), candidate.titleScore(),
                    candidate.sharedArtists(), counts.getOrDefault(candidate.series().getId(), 0L)));
        }
        Comparator<ScoredSeries> best = byScore().thenComparingLong(ScoredSeries::chapterCount);
        counted.sort(best.reversed().thenComparing(candidate -> candidate.series().getId()));

        counted.addAll(ranked.subList(head.size(), ranked.size()));
        return counted;
    }

    private Map<Integer, Series> candidates(TitleKey key, Set<Integer> artistIds, Integer excludedSeriesId)
    {
        var candidates = new LinkedHashMap<Integer, Series>();
        if (key.getMatchKey().isEmpty())
        {
            return candidates;   // nothing usable in the title - never match on emptiness
        }
        var limit = PageRequest.of(0, CANDIDATE_LIMIT);

        // Its own seek: OR-ed into the extension range, the cap could drop it for arbitrary extensions.
        List<Series> exact = seriesRepository.findByMatchKey(key.getMatchKey(), excludedSeriesId, limit);
        collect(candidates, exact);
        collect(candidates, seriesRepository.findByMatchKeyExtending(key.getMatchKey(), excludedSeriesId, limit));

        List<String> prefixes = prefixKeys(key.getTokens());
        if (!prefixes.isEmpty())
        {
            collect(candidates, seriesRepository.findByMatchKeyIn(prefixes, excludedSeriesId, limit));
        }

        // The fuzzy pass is for misspelled titles and loads up to ARTIST_FANOUT_LIMIT series.
        if (!exact.isEmpty())
        {
            return candidates;
        }
        if (!artistIds.isEmpty())
        {
            var fanout = PageRequest.of(0, ARTIST_FANOUT_LIMIT);
            List<Series> byArtist = seriesRepository.findByEffectiveArtistIds(artistIds, excludedSeriesId, fanout);
            collect(candidates, byArtist.size() < ARTIST_FANOUT_LIMIT
                    ? byArtist : seriesRepository.findByMatchBlock(key.getMatchBlock(), excludedSeriesId, limit));
        }
        else
        {
            collect(candidates, seriesRepository.findByMatchBlock(key.getMatchBlock(), excludedSeriesId, limit));
        }
        return candidates;
    }

    /**
     * Over {@value #MAX_PREFIXES}, the shortest prefixes are dropped: the longest ones most likely name the
     * real base, and dropping those would make a long title create a duplicate series.
     */
    static List<String> prefixKeys(List<String> tokens)
    {
        var prefixes = new ArrayList<String>();
        var prefix = new StringBuilder();
        for (int i = 0; i < tokens.size() - 1; i++)
        {
            if (i > 0)
            {
                prefix.append(' ');
            }
            prefix.append(tokens.get(i));
            prefixes.add(prefix.toString());
        }
        return prefixes.size() <= MAX_PREFIXES
                ? prefixes : new ArrayList<>(prefixes.subList(prefixes.size() - MAX_PREFIXES, prefixes.size()));
    }

    private static void collect(Map<Integer, Series> candidates, Collection<Series> found)
    {
        for (Series series : found)
        {
            candidates.putIfAbsent(series.getId(), series);
        }
    }

    /** The stored key, not a fresh parse: cheaper and always what was indexed. */
    private static List<String> tokensOf(Series series)
    {
        String key = series.getMatchKey();
        return (key == null || key.isEmpty()) ? List.of() : List.of(key.split(" "));
    }

    private Map<Integer, Set<Integer>> effectiveArtists(Collection<Integer> seriesIds)
    {
        var byId = new HashMap<Integer, Set<Integer>>();
        for (Object[] row : seriesRepository.findEffectiveArtistIdsBySeriesIds(seriesIds))
        {
            byId.computeIfAbsent(((Number) row[0]).intValue(), id -> new HashSet<>())
                    .add(((Number) row[1]).intValue());
        }
        return byId;
    }
}

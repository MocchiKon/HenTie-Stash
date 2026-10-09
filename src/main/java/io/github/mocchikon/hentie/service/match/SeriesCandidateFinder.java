package io.github.mocchikon.hentie.service.match;

import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Shared by auto-linking and the Add-to-series page, so the two cannot disagree about a good match.
 *
 * <p>Candidates come from capped, ordered seeks: by key (exact, extensions, every base), then - unless a key
 * candidate already scores a perfect 1 - by native title, by artist and by the titles nearest in key order
 * ({@link NearestTitles}). These are the seeks "Link chapters" makes from the series' side
 * ({@link ChapterCandidateFinder}), so a pair one direction finds, the other finds too. The skip is what keeps a
 * sweep cheap: nearly every chapter of a known family meets its series under the very key, with its artist.
 *
 * <p>The excluded series (the chapter's own, on the Add-to-series page) is left out by every seek's own
 * predicate, never dropped from the ranking. As a key hit it would make the other seeks look unnecessary, and they
 * are the only ones that find the family of a misspelled or translated title.
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

    /**
     * The very title and a shared artist, which nothing the other seeks find could beat. A base of the title
     * could be beaten: a misspelled sequel ({@code "... Sasafuu 3"}) meets its base by key at 0.98, and its own
     * series only through the artist, at 0.99.
     */
    private static final double PERFECT_SCORE = 1.0 - 1e-9;

    /**
     * The chapter count is only the third tie-break, so it is fetched for the head of the ranking only,
     * not aggregated over up to two thousand series.
     */
    private static final int COUNT_LOOKUP_LIMIT = 25;

    private final SeriesRepository seriesRepository;
    private final ChapterRepository chapterRepository;
    private final EntityManager entityManager;

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
        return rank(key, chapter.getNativeMatchKey(), artistIds, Map.of(), excludedSeriesId);
    }

    @Transactional(readOnly = true)
    public List<ScoredSeries> rank(TitleKey key, Set<Integer> artistIds)
    {
        return rank(key, "", artistIds, Map.of(), null);
    }

    /**
     * {@code pendingArtists} are artists linked but not yet in {@code series_effective_artists}. A caller
     * that defers {@code recomputeDerived} must pass them, or a series linked moments ago looks artist-less (and
     * cannot be found by artist), and another artist's work joins it.
     */
    @Transactional(readOnly = true)
    public List<ScoredSeries> rank(TitleKey key, String nativeKey, Set<Integer> artistIds,
                                   Map<Integer, Set<Integer>> pendingArtists)
    {
        return rank(key, nativeKey, artistIds, pendingArtists, null);
    }

    private List<ScoredSeries> rank(TitleKey key, String nativeKey, Set<Integer> artistIds,
                                    Map<Integer, Set<Integer>> pendingArtists, Integer excludedSeriesId)
    {
        var found = new Found();
        if (!key.getMatchKey().isEmpty())   // nothing usable in the title - never match on emptiness
        {
            byKey(key, excludedSeriesId, found);
        }
        List<ScoredSeries> ranked = score(found, key, nativeKey, artistIds, pendingArtists);
        if (ranked.stream().noneMatch(candidate -> candidate.score() >= PERFECT_SCORE))
        {
            byNativeKey(nativeKey, excludedSeriesId, found);
            if (!key.getMatchKey().isEmpty())
            {
                byArtist(artistIds, pendingArtists, excludedSeriesId, found);
                nearest(key, excludedSeriesId, found);
            }
            ranked = score(found, key, nativeKey, artistIds, pendingArtists);
        }
        if (ranked.isEmpty())
        {
            return ranked;
        }
        // Chapter counts break ties afterwards, for the head only (COUNT_LOOKUP_LIMIT).
        ranked.sort(byScore().reversed().thenComparing(candidate -> candidate.series().getId()));
        return withChapterCounts(ranked);
    }

    /** What the seeks found: the series, and for those found by native title the native keys that matched. */
    private static final class Found
    {
        private final Map<Integer, Series> series = new LinkedHashMap<>();
        private final Map<Integer, Set<String>> nativeKeys = new HashMap<>();
        private final Map<Integer, Set<Integer>> artists = new HashMap<>();
    }

    /** The better of the title against the series' key and the native title against its chapters' native ones. */
    private List<ScoredSeries> score(Found found, TitleKey key, String nativeKey, Set<Integer> artistIds,
                                     Map<Integer, Set<Integer>> pendingArtists)
    {
        var unloaded = found.series.keySet().stream().filter(id -> !found.artists.containsKey(id)).toList();
        if (!unloaded.isEmpty())
        {
            unloaded.forEach(id -> found.artists.put(id, new HashSet<>()));
            for (Object[] row : seriesRepository.findEffectiveArtistIdsBySeriesIds(unloaded))
            {
                found.artists.get(((Number) row[0]).intValue()).add(((Number) row[1]).intValue());
            }
        }

        List<String> nativeTokens = TitleKey.tokensOf(nativeKey);
        var ranked = new ArrayList<ScoredSeries>(found.series.size());
        for (Series series : found.series.values())
        {
            Set<Integer> theirArtists = merged(found.artists.get(series.getId()), pendingArtists.get(series.getId()));
            double titleScore = MatchScore.titleScore(key.getTokens(), TitleKey.tokensOf(series.getMatchKey()));
            for (String theirs : found.nativeKeys.getOrDefault(series.getId(), Set.of()))
            {
                titleScore = Math.max(titleScore, MatchScore.nativeTitleScore(nativeTokens, TitleKey.tokensOf(theirs)));
            }
            double score = MatchScore.combined(titleScore, MatchScore.artistScore(artistIds, theirArtists));
            ranked.add(new ScoredSeries(series, score, titleScore,
                    MatchScore.sharedArtists(artistIds, theirArtists), 0L));
        }
        return ranked;
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

    private void byKey(TitleKey key, Integer excludedSeriesId, Found found)
    {
        var limit = PageRequest.of(0, CANDIDATE_LIMIT);
        // Its own seek: OR-ed into the extension range, the cap could drop it for arbitrary extensions.
        collect(found, seriesRepository.findByMatchKey(key.getMatchKey(), excludedSeriesId, limit));
        collect(found, seriesRepository.findByMatchKeyExtending(key.getMatchKey(), excludedSeriesId, limit));
        List<String> prefixes = prefixKeys(key.getTokens());
        if (!prefixes.isEmpty())
        {
            collect(found, seriesRepository.findByMatchKeyIn(prefixes, excludedSeriesId, limit));
        }
    }

    /**
     * Native keys are on chapters, not series, so a series is found through any of its chapters: a translation
     * meets the original whichever chapter started the series.
     */
    private void byNativeKey(String nativeKey, Integer excludedSeriesId, Found found)
    {
        if (!MatchScore.isDistinctiveNative(nativeKey))
        {
            return;
        }
        var limit = PageRequest.of(0, CANDIDATE_LIMIT);
        var rows = new ArrayList<>(chapterRepository.findSeriesByNativeKey(nativeKey, excludedSeriesId, limit));
        rows.addAll(chapterRepository.findSeriesByNativeKeyExtending(nativeKey, excludedSeriesId, limit));
        List<String> prefixes = prefixKeys(TitleKey.tokensOf(nativeKey)).stream()
                .filter(MatchScore::isDistinctiveNative)
                .toList();
        if (!prefixes.isEmpty())
        {
            rows.addAll(chapterRepository.findSeriesByNativeKeyIn(prefixes, excludedSeriesId, limit));
        }
        var unloaded = new LinkedHashSet<Integer>();
        for (Object[] row : rows)
        {
            int seriesId = ((Number) row[0]).intValue();
            found.nativeKeys.computeIfAbsent(seriesId, id -> new HashSet<>()).add((String) row[1]);
            if (!found.series.containsKey(seriesId))
            {
                unloaded.add(seriesId);
            }
        }
        collect(found, sortedById(seriesRepository.findAllById(unloaded)));
    }

    /**
     * Needs no key at all, so it finds a sibling however its title is spelled. Series linked in the caller's batch
     * are not in {@code series_effective_artists} yet, so they come from {@code pendingArtists}.
     */
    private void byArtist(Set<Integer> artistIds, Map<Integer, Set<Integer>> pendingArtists, Integer excludedSeriesId,
                          Found found)
    {
        if (artistIds.isEmpty())
        {
            return;
        }
        List<Series> byArtist = seriesRepository.findByEffectiveArtistIds(artistIds, excludedSeriesId,
                PageRequest.of(0, ARTIST_FANOUT_LIMIT));
        if (byArtist.size() < ARTIST_FANOUT_LIMIT)
        {
            collect(found, byArtist);
        }
        var pending = new TreeSet<Integer>();
        pendingArtists.forEach((seriesId, artists) ->
        {
            if (!seriesId.equals(excludedSeriesId) && !found.series.containsKey(seriesId)
                    && !Collections.disjoint(artists, artistIds))
            {
                pending.add(seriesId);
            }
        });
        collect(found, sortedById(seriesRepository.findAllById(pending)));
    }

    /** {@code indexed by}: otherwise the planner may walk the key index and compute the expression per row. */
    private void nearest(TitleKey key, Integer excludedSeriesId, Found found)
    {
        var ids = new LinkedHashSet<Integer>();
        for (NearestTitles.Seek seek : NearestTitles.seeks(key))
        {
            String sql = "select id from series indexed by ix_series__condensed_match_key where " + seek.condition()
                    + (excludedSeriesId == null ? "" : " and id <> ?") + " order by " + seek.order();
            var query = entityManager.createNativeQuery(sql);   // NOSONAR - fixed fragments only, every value is bound
            int position = 1;
            for (Object value : seek.values())
            {
                query.setParameter(position++, value);
            }
            if (excludedSeriesId != null)
            {
                query.setParameter(position, excludedSeriesId);
            }
            for (Object id : query.setMaxResults(CANDIDATE_LIMIT).getResultList())
            {
                ids.add(((Number) id).intValue());
            }
        }
        ids.removeAll(found.series.keySet());
        collect(found, sortedById(seriesRepository.findAllById(ids)));
    }

    /** Every token prefix: the base of an {@code "A | B"} title is its shortest part, not its longest. */
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
        return prefixes;
    }

    private static List<Series> sortedById(List<Series> series)
    {
        return series.stream().sorted(Comparator.comparing(Series::getId)).toList();
    }

    private static void collect(Found found, Collection<Series> series)
    {
        for (Series one : series)
        {
            found.series.putIfAbsent(one.getId(), one);
        }
    }
}

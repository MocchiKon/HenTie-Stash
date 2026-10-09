package io.github.mocchikon.hentie.service.match;

import io.github.mocchikon.hentie.dto.CandidateScope;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.entity.link.ChapterArtistLink;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.repository.spec.ChapterSpecifications;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * {@link SeriesCandidateFinder} turned round, for the Link chapters page. {@link MatchScore} is symmetric, so
 * a chapter is offered here with the same score the series gets on that chapter's Add-to-series page.
 *
 * <p>Seeks by key (exact, extensions, bases), by native title, by artist and by the titles nearest in key order
 * ({@link NearestTitles}), each capped and ordered - the seeks the other direction makes. Unlike it, all of them
 * always run: this is one page view, not a sweep.
 * <p>
 * The seeks select a projection: {@code Chapter.series} is an eager to-one, so loading entities would cost a
 * query per candidate.
 *
 * <p>The {@link CandidateScope} is part of every seek's own predicate, not a filter afterwards: the series'
 * own chapters mostly share its key and would fill the cap, and "in no series" lets the extension range use
 * {@code ix_chapter__series_match_key}.
 */
@Service
@RequiredArgsConstructor
public class ChapterCandidateFinder
{
    static final int CANDIDATE_LIMIT = SeriesCandidateFinder.CANDIDATE_LIMIT;

    /** Above this many chapters, an id-ordered slice of the artists' work is no better than chance. */
    static final int ARTIST_FANOUT_LIMIT = 2000;

    /**
     * Native keys of the series' chapters looked for. Most series have one or two (a work, its compilations);
     * more are variants of those, and each costs three seeks.
     */
    private static final int NATIVE_KEY_LIMIT = 8;

    private final EntityManager entityManager;
    private final ChapterRepository chapterRepository;

    /** Ties break on title score, then lowest id, so the order never depends on which seek found a row. */
    @Transactional(readOnly = true)
    public List<ScoredChapter> rank(Series series, CandidateScope scope)
    {
        var key = TitleKey.of(series.getTitleFull());
        if (key.getMatchKey().isEmpty())
        {
            return List.of();   // nothing usable in the title - never match on emptiness
        }
        var artistIds = new HashSet<Integer>();
        series.getEffectiveArtists().forEach(artist -> artistIds.add(artist.getId()));
        List<List<String>> nativeKeys = chapterRepository
                .findNativeKeysBySeriesId(series.getId(), PageRequest.of(0, NATIVE_KEY_LIMIT)).stream()
                .filter(MatchScore::isDistinctiveNative)
                .map(TitleKey::tokensOf)
                .toList();

        Map<Integer, Candidate> candidates = candidates(key, nativeKeys, artistIds, scope, series.getId());
        Map<Integer, Set<Integer>> chapterArtists = artistsOf(candidates.keySet());

        var ranked = new ArrayList<ScoredChapter>(candidates.size());
        for (Candidate candidate : candidates.values())
        {
            Set<Integer> theirArtists = chapterArtists.getOrDefault(candidate.id(), Set.of());
            double titleScore = MatchScore.titleScore(key.getTokens(), TitleKey.tokensOf(candidate.matchKey()));
            List<String> theirNative = TitleKey.tokensOf(candidate.nativeMatchKey());
            for (List<String> ours : nativeKeys)
            {
                titleScore = Math.max(titleScore, MatchScore.nativeTitleScore(ours, theirNative));
            }
            double score = MatchScore.combined(titleScore, MatchScore.artistScore(artistIds, theirArtists));
            ranked.add(new ScoredChapter(candidate.id(), score, titleScore,
                    MatchScore.sharedArtists(artistIds, theirArtists)));
        }
        ranked.sort(Comparator.comparingDouble(ScoredChapter::score)
                .thenComparingDouble(ScoredChapter::titleScore)
                .reversed()
                .thenComparingInt(ScoredChapter::chapterId));
        return ranked;
    }

    private Map<Integer, Candidate> candidates(TitleKey key, List<List<String>> nativeKeys, Set<Integer> artistIds,
                                               CandidateScope scope, int seriesId)
    {
        Specification<Chapter> inScope = ChapterSpecifications.linkableTo(seriesId, scope);
        var candidates = new LinkedHashMap<Integer, Candidate>();
        collect(candidates, seek(inScope.and(keyEquals("matchKey", key.getMatchKey())), CANDIDATE_LIMIT));
        collect(candidates, seek(inScope.and(keyExtends("matchKey", key.getMatchKey())), CANDIDATE_LIMIT));
        List<String> prefixes = SeriesCandidateFinder.prefixKeys(key.getTokens());
        if (!prefixes.isEmpty())
        {
            collect(candidates, seek(inScope.and(keyIn("matchKey", prefixes)), CANDIDATE_LIMIT));
        }

        for (List<String> nativeKey : nativeKeys)
        {
            String joined = String.join(" ", nativeKey);
            collect(candidates, seek(inScope.and(keyEquals("nativeMatchKey", joined)), CANDIDATE_LIMIT));
            collect(candidates, seek(inScope.and(keyExtends("nativeMatchKey", joined)), CANDIDATE_LIMIT));
            List<String> nativePrefixes = SeriesCandidateFinder.prefixKeys(nativeKey).stream()
                    .filter(MatchScore::isDistinctiveNative)
                    .toList();
            if (!nativePrefixes.isEmpty())
            {
                collect(candidates, seek(inScope.and(keyIn("nativeMatchKey", nativePrefixes)), CANDIDATE_LIMIT));
            }
        }

        for (NearestTitles.Seek nearest : NearestTitles.seeks(key))
        {
            collect(candidates, condensedSeek(nearest, scope, seriesId));
        }
        if (!artistIds.isEmpty())
        {
            List<Candidate> byArtist = seek(inScope.and(carryingAnyOf(artistIds)), ARTIST_FANOUT_LIMIT);
            if (byArtist.size() < ARTIST_FANOUT_LIMIT)
            {
                collect(candidates, byArtist);
            }
        }
        return candidates;
    }

    /** The spec carries the order too, so which rows survive the cap never depends on the query plan. */
    private List<Candidate> seek(Specification<Chapter> spec, int limit)
    {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(Object[].class);
        var chapter = query.from(Chapter.class);
        query.multiselect(chapter.get("id"), chapter.get("matchKey"), chapter.get("nativeMatchKey"))
                .where(spec.toPredicate(chapter, query, cb));
        return candidatesOf(entityManager.createQuery(query).setMaxResults(limit).getResultList());
    }

    /**
     * The scope must match {@link ChapterSpecifications#linkableTo}. {@code indexed by} is needed because
     * otherwise the planner walks every unlinked chapter, nearly the whole table after an import.
     */
    private List<Candidate> condensedSeek(NearestTitles.Seek seek, CandidateScope scope, int seriesId)
    {
        String sql = "select id, match_key, native_match_key from chapter indexed by ix_chapter__condensed_match_key"
                + " where " + seek.condition()
                + (scope == CandidateScope.UNLINKED ? " and series_id is null" : " and (series_id is null or series_id <> ?)")
                + " order by " + seek.order();
        var query = entityManager.createNativeQuery(sql);   // NOSONAR - fixed fragments only, every value is bound
        int position = 1;
        for (Object value : seek.values())
        {
            query.setParameter(position++, value);
        }
        if (scope == CandidateScope.ALL)
        {
            query.setParameter(position, seriesId);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.setMaxResults(CANDIDATE_LIMIT).getResultList();
        return candidatesOf(rows);
    }

    private static List<Candidate> candidatesOf(List<Object[]> rows)
    {
        var found = new ArrayList<Candidate>(rows.size());
        for (Object[] row : rows)
        {
            found.add(new Candidate(((Number) row[0]).intValue(), (String) row[1], (String) row[2]));
        }
        return found;
    }

    private static Specification<Chapter> keyEquals(String attribute, String key)
    {
        return (chapter, query, cb) ->
        {
            query.orderBy(cb.asc(chapter.get("id")));
            return cb.equal(chapter.get(attribute), key);
        };
    }

    /** The trailing space stops {@code "ohayo"} taking in {@code "ohayoo"}. */
    private static Specification<Chapter> keyExtends(String attribute, String key)
    {
        var lower = key + ' ';
        return (chapter, query, cb) ->
        {
            var matchKey = chapter.<String>get(attribute);
            query.orderBy(cb.asc(matchKey), cb.asc(chapter.get("id")));
            return cb.and(cb.greaterThanOrEqualTo(matchKey, lower),
                    cb.lessThan(matchKey, lower + SeriesRepository.MATCH_KEY_RANGE_END));
        };
    }

    /** Longest key first: a one-token prefix may be shared by thousands of chapters that would fill the cap. */
    private static Specification<Chapter> keyIn(String attribute, Collection<String> keys)
    {
        return (chapter, query, cb) ->
        {
            var matchKey = chapter.<String>get(attribute);
            query.orderBy(cb.desc(cb.length(matchKey)), cb.asc(chapter.get("id")));
            return matchKey.in(keys);
        };
    }

    /** Rooted on the link projection, so the ids come straight off the covering index. */
    private static Specification<Chapter> carryingAnyOf(Collection<Integer> artistIds)
    {
        return (chapter, query, cb) ->
        {
            query.orderBy(cb.asc(chapter.get("id")));
            Subquery<Integer> carriers = query.subquery(Integer.class);
            var link = carriers.from(ChapterArtistLink.class);
            carriers.select(link.get("ownerId")).where(link.get("metaId").in(artistIds));
            return chapter.get("id").in(carriers);
        };
    }

    private static void collect(Map<Integer, Candidate> candidates, List<Candidate> found)
    {
        for (Candidate candidate : found)
        {
            candidates.putIfAbsent(candidate.id(), candidate);
        }
    }

    /** One query for all candidates, not a lazy load each. */
    private Map<Integer, Set<Integer>> artistsOf(Collection<Integer> chapterIds)
    {
        var byChapter = new HashMap<Integer, Set<Integer>>();
        if (chapterIds.isEmpty())
        {
            return byChapter;
        }
        for (Object[] row : chapterRepository.findArtistIdsByChapterIds(chapterIds))
        {
            byChapter.computeIfAbsent(((Number) row[0]).intValue(), id -> new HashSet<>())
                    .add(((Number) row[1]).intValue());
        }
        return byChapter;
    }

    private record Candidate(int id, String matchKey, String nativeMatchKey)
    {
    }
}

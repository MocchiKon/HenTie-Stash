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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * {@link SeriesCandidateFinder} turned round, for the Link chapters page. {@link MatchScore} is symmetric, so
 * a chapter is offered here with the same score the series gets on that chapter's Add-to-series page.
 *
 * <p>Seeks by exact key, extensions, bases, block and artist, each capped and ordered. Unlike the other
 * direction, the block and artist seeks always both run: the chapter is what is being searched for, so the
 * choice between them cannot be made, and this is one page view, not a sweep.
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
     * Spelled exactly as {@code ix_chapter__condensed_match_key} declares it: SQLite uses an expression index
     * only for that exact expression. Native SQL, because Criteria may bind the literals as parameters.
     */
    private static final String CONDENSED = "replace(match_key, ' ', '')";

    private static final String IN_RANGE = CONDENSED + " >= ? and " + CONDENSED + " < ?";

    private static final String ASCENDING = CONDENSED + ", id";

    private static final String DESCENDING = CONDENSED + " desc, id desc";

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

        Map<Integer, Candidate> candidates = candidates(key, artistIds, scope, series.getId());
        Map<Integer, Set<Integer>> chapterArtists = artistsOf(candidates.keySet());

        var ranked = new ArrayList<ScoredChapter>(candidates.size());
        for (Candidate candidate : candidates.values())
        {
            Set<Integer> theirArtists = chapterArtists.getOrDefault(candidate.id(), Set.of());
            double titleScore = MatchScore.titleScore(key.getTokens(), candidate.tokens());
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

    private Map<Integer, Candidate> candidates(TitleKey key, Set<Integer> artistIds, CandidateScope scope,
                                               int seriesId)
    {
        Specification<Chapter> inScope = ChapterSpecifications.linkableTo(seriesId, scope);
        var candidates = new LinkedHashMap<Integer, Candidate>();
        collect(candidates, seek(inScope.and(keyEquals(key.getMatchKey())), CANDIDATE_LIMIT));
        collect(candidates, seek(inScope.and(keyExtends(key.getMatchKey())), CANDIDATE_LIMIT));

        List<String> prefixes = SeriesCandidateFinder.prefixKeys(key.getTokens());
        if (!prefixes.isEmpty())
        {
            collect(candidates, seek(inScope.and(keyIn(prefixes)), CANDIDATE_LIMIT));
        }
        collect(candidates, byBlock(key, scope, seriesId));
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
        query.multiselect(chapter.get("id"), chapter.get("matchKey"))
                .where(spec.toPredicate(chapter, query, cb));
        return candidatesOf(entityManager.createQuery(query).setMaxResults(limit).getResultList());
    }

    /**
     * Walks outward from where the series' spaceless title sorts in the spaceless-key index, so a crowded
     * block (every title starting "Isekai") yields the chapters nearest the title, not the first by id. Four
     * seeks, with the head being the first {@value MatchScore#MIN_SHARED_HEAD} letters:
     * <ol>
     *   <li>chapters starting with the head;</li>
     *   <li>chapters whose whole key is a shorter start of the head, by equality, since in key order they sit
     *       below every chapter extending them;</li>
     *   <li>the nearest ones below the head, and</li>
     *   <li>the nearest ones above it - where a title misspelled past the block sorts.</li>
     * </ol>
     */
    private List<Candidate> byBlock(TitleKey key, CandidateScope scope, int seriesId)
    {
        String block = key.getMatchBlock();
        String title = key.getMatchKey().replace(" ", "");
        if (block.length() < TitleKey.BLOCK_LENGTH)
        {
            return condensedSeek(CONDENSED + " = ?", List.of(title), ASCENDING, scope, seriesId);
        }
        String head = head(title);
        String pastHead = head + SeriesRepository.MATCH_KEY_RANGE_END;
        var starts = new ArrayList<Object>();
        for (int length = block.length(); length < head.length(); length++)
        {
            starts.add(head.substring(0, length));
        }

        var found = new ArrayList<>(condensedSeek(IN_RANGE, List.of(head, pastHead), ASCENDING, scope, seriesId));
        if (!starts.isEmpty())
        {
            String anyOf = CONDENSED + " in (" + String.join(", ", Collections.nCopies(starts.size(), "?")) + ")";
            found.addAll(condensedSeek(anyOf, starts, "length(" + CONDENSED + ") desc, id", scope, seriesId));
        }
        found.addAll(condensedSeek(IN_RANGE, List.of(block, head), DESCENDING, scope, seriesId));
        found.addAll(condensedSeek(IN_RANGE, List.of(pastHead, block + SeriesRepository.MATCH_KEY_RANGE_END),
                ASCENDING, scope, seriesId));
        return found;
    }

    /** Never splits a surrogate pair: half of one would bind as text no index entry holds. */
    private static String head(String title)
    {
        int end = Math.min(title.length(), MatchScore.MIN_SHARED_HEAD);
        return end > 0 && Character.isHighSurrogate(title.charAt(end - 1)) ? title.substring(0, end - 1)
                : title.substring(0, end);
    }

    /**
     * The scope must match {@link ChapterSpecifications#linkableTo}. {@code indexed by} is needed because
     * otherwise the planner walks every unlinked chapter, nearly the whole table after an import.
     */
    private List<Candidate> condensedSeek(String condition, List<Object> values, String order,
                                          CandidateScope scope, int seriesId)
    {
        String sql = "select id, match_key from chapter indexed by ix_chapter__condensed_match_key where "
                + condition
                + (scope == CandidateScope.UNLINKED ? " and series_id is null" : " and (series_id is null or series_id <> ?)")
                + " order by " + order;
        var query = entityManager.createNativeQuery(sql);   // NOSONAR - fixed fragments only, every value is bound
        int position = 1;
        for (Object value : values)
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
            found.add(new Candidate(((Number) row[0]).intValue(), (String) row[1]));
        }
        return found;
    }

    private static Specification<Chapter> keyEquals(String key)
    {
        return (chapter, query, cb) ->
        {
            query.orderBy(cb.asc(chapter.get("id")));
            return cb.equal(chapter.get("matchKey"), key);
        };
    }

    /** The trailing space stops {@code "ohayo"} taking in {@code "ohayoo"}. */
    private static Specification<Chapter> keyExtends(String key)
    {
        var lower = key + ' ';
        return (chapter, query, cb) ->
        {
            var matchKey = chapter.<String>get("matchKey");
            query.orderBy(cb.asc(matchKey), cb.asc(chapter.get("id")));
            return cb.and(cb.greaterThanOrEqualTo(matchKey, lower),
                    cb.lessThan(matchKey, lower + SeriesRepository.MATCH_KEY_RANGE_END));
        };
    }

    /** Longest key first: a one-token prefix may be shared by thousands of chapters that would fill the cap. */
    private static Specification<Chapter> keyIn(Collection<String> keys)
    {
        return (chapter, query, cb) ->
        {
            var matchKey = chapter.<String>get("matchKey");
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

    private record Candidate(int id, String matchKey)
    {
        List<String> tokens()
        {
            return matchKey == null || matchKey.isEmpty() ? List.of() : List.of(matchKey.split(" "));
        }
    }
}

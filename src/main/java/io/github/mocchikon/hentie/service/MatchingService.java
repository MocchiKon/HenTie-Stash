package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.dto.CandidateScope;
import io.github.mocchikon.hentie.dto.ChapterMatchDto;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.dto.SeriesMatchDto;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.repository.spec.ChapterSpecifications;
import io.github.mocchikon.hentie.repository.spec.SeriesSpecifications;
import io.github.mocchikon.hentie.service.match.ChapterCandidateFinder;
import io.github.mocchikon.hentie.service.match.ScoredChapter;
import io.github.mocchikon.hentie.service.match.ScoredSeries;
import io.github.mocchikon.hentie.service.match.SeriesCandidateFinder;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Backs the "Add to series" and "Link chapters" pages. The rankings delegate to the same finders automatic
 * matching uses, so a page offers first what auto-linking would have chosen.
 */
@Service
@RequiredArgsConstructor
public class MatchingService
{
    /** Bounds the work of ranking a broad term. */
    private static final int NAME_SEARCH_CANDIDATES = 50;

    /**
     * Title score a candidate with no shared artist needs. The seeks are generous (they seek by artist too),
     * so below this a candidate resembles nothing.
     */
    private static final double WEAK_MATCH_FLOOR = 0.5;

    private static final JaroWinklerSimilarity SIMILARITY = new JaroWinklerSimilarity();

    private final SeriesRepository seriesRepository;
    private final ChapterRepository chapterRepository;
    private final SeriesCandidateFinder candidateFinder;
    private final ChapterCandidateFinder chapterCandidateFinder;
    private final TitleSearchIndex titleSearchIndex;
    private final ImageService imageService;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<SeriesMatchDto> topMatches(int chapterId, int limit)
    {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found"));

        // The chapter's own series would score ~1.00 with a link that does nothing. The finder leaves it out
        // of its seeks, not the ranking, or it would switch off the fuzzy pass (see SeriesCandidateFinder).
        Integer currentSeriesId = chapter.getSeries() == null ? null : chapter.getSeries().getId();

        List<ScoredSeries> ranked = candidateFinder.rank(chapter, currentSeriesId).stream()
                .filter(candidate -> candidate.sharedArtists() > 0 || candidate.titleScore() >= WEAK_MATCH_FLOOR)
                .toList();
        if (ranked.size() > limit)
        {
            ranked = ranked.subList(0, limit);
        }

        Map<Integer, String> thumbnails = thumbnails(ranked.stream().map(c -> c.series().getId()).toList());
        var matches = new ArrayList<SeriesMatchDto>(ranked.size());
        for (ScoredSeries candidate : ranked)
        {
            Series series = candidate.series();
            matches.add(new SeriesMatchDto(series.getId(), series.getTitleFull(),
                    thumbnails.getOrDefault(series.getId(), ImageService.PLACEHOLDER),
                    (int) candidate.chapterCount(), candidate.sharedArtists(),
                    candidate.titleScore(), candidate.score()));
        }
        return matches;
    }

    /**
     * Uses the series search form's predicate, so the box and the search page never disagree about what
     * exists. A bounded fetch is ranked afterwards, ordered by {@link #shortestTitleFirst} so the bound
     * cannot hide an exact title.
     */
    @Transactional(readOnly = true)
    public List<SeriesMatchDto> searchByName(String query, int limit)
    {
        String term = StringUtils.trimToEmpty(query);
        if (term.isEmpty())
        {
            return List.of();
        }

        var criteria = new SearchCriteria();
        criteria.setTitle(term);
        Specification<Series> spec = SeriesSpecifications.from(criteria, titleSearchIndex.phraseFor(term).orElse(null));
        List<Series> found = seriesRepository.findBy(spec.and(shortestTitleFirst()),
                matches -> matches.limit(NAME_SEARCH_CANDIDATES).all());

        String needle = term.toLowerCase(Locale.ROOT);
        List<Series> best = found.stream()
                .sorted(Comparator.comparingDouble((Series series) -> nameSimilarity(needle, series.getTitleFull(),
                                series.getNativeTitle())).reversed()
                        .thenComparing(Series::getId))
                .limit(limit)
                .toList();

        List<Integer> ids = best.stream().map(Series::getId).toList();
        Map<Integer, String> thumbnails = thumbnails(ids);
        Map<Integer, Long> counts = chapterRepository.chapterCountsBySeriesIds(ids);

        var results = new ArrayList<SeriesMatchDto>(best.size());
        for (Series series : best)
        {
            double similarity = nameSimilarity(needle, series.getTitleFull(), series.getNativeTitle());
            results.add(new SeriesMatchDto(series.getId(), series.getTitleFull(),
                    thumbnails.getOrDefault(series.getId(), ImageService.PLACEHOLDER),
                    counts.getOrDefault(series.getId(), 0L).intValue(), 0, similarity, similarity));
        }
        return results;
    }

    @Transactional(readOnly = true)
    public List<ChapterMatchDto> topChapterMatches(int seriesId, CandidateScope scope, int limit)
    {
        Series series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found"));
        List<ScoredChapter> ranked = chapterCandidateFinder.rank(series, scope).stream()
                .filter(candidate -> candidate.sharedArtists() > 0 || candidate.titleScore() >= WEAK_MATCH_FLOOR)
                .limit(limit)
                .toList();

        var loaded = new HashMap<Integer, Chapter>();
        chapterRepository.findAllById(ranked.stream().map(ScoredChapter::chapterId).toList())
                .forEach(chapter -> loaded.put(chapter.getId(), chapter));
        Map<Integer, Long> seriesSizes = seriesSizes(loaded.values());

        var matches = new ArrayList<ChapterMatchDto>(ranked.size());
        for (ScoredChapter candidate : ranked)
        {
            Chapter chapter = loaded.get(candidate.chapterId());
            if (chapter != null)
            {
                matches.add(chapterMatch(chapter, seriesSizes, candidate.sharedArtists(), candidate.titleScore(),
                        candidate.score()));
            }
        }
        return matches;
    }

    /**
     * {@link #searchByName} for chapters. The page's scope applies too, so the box never offers a chapter
     * the ranking would refuse.
     */
    @Transactional(readOnly = true)
    public List<ChapterMatchDto> searchChaptersByName(String query, int seriesId, CandidateScope scope, int limit)
    {
        String term = StringUtils.trimToEmpty(query);
        if (term.isEmpty())
        {
            return List.of();
        }

        var criteria = new SearchCriteria();
        criteria.setTitle(term);
        Specification<Chapter> spec = ChapterSpecifications.from(criteria, titleSearchIndex.phraseFor(term).orElse(null))
                .and(ChapterSpecifications.linkableTo(seriesId, scope));
        String needle = term.toLowerCase(Locale.ROOT);
        List<NameHit> best = nameHits(spec.and(shortestTitleFirst())).stream()
                .map(row -> new NameHit(((Number) row[0]).intValue(),
                        nameSimilarity(needle, (String) row[1], (String) row[2])))
                .sorted(Comparator.comparingDouble(NameHit::similarity).reversed()
                        .thenComparingInt(NameHit::chapterId))
                .limit(limit)
                .toList();

        var loaded = new HashMap<Integer, Chapter>();
        chapterRepository.findAllById(best.stream().map(NameHit::chapterId).toList())
                .forEach(chapter -> loaded.put(chapter.getId(), chapter));
        Map<Integer, Long> seriesSizes = seriesSizes(loaded.values());
        var results = new ArrayList<ChapterMatchDto>(best.size());
        for (NameHit hit : best)
        {
            Chapter chapter = loaded.get(hit.chapterId());
            if (chapter != null)
            {
                results.add(chapterMatch(chapter, seriesSizes, 0, hit.similarity(), hit.similarity()));
            }
        }
        return results;
    }

    /**
     * {@code [id, titleFull, nativeTitle]}: a projection, because {@code Chapter.series} is an eager to-one
     * and most candidates are ranked away.
     */
    private List<Object[]> nameHits(Specification<Chapter> spec)
    {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(Object[].class);
        var chapter = query.from(Chapter.class);
        query.multiselect(chapter.get("id"), chapter.get("titleFull"), chapter.get("nativeTitle"))
                .where(spec.toPredicate(chapter, query, cb));
        return entityManager.createQuery(query).setMaxResults(NAME_SEARCH_CANDIDATES).getResultList();
    }

    private record NameHit(int chapterId, double similarity)
    {
    }

    private ChapterMatchDto chapterMatch(Chapter chapter, Map<Integer, Long> seriesSizes, int sharedArtists,
                                         double titleSimilarity, double score)
    {
        Series current = chapter.getSeries();
        return new ChapterMatchDto(chapter.getId(), chapter.getTitleFull(), imageService.thumbnailUrl(chapter.getId()),
                chapter.getLanguage(), chapter.getPageNum(),
                current == null ? null : current.getId(),
                current == null ? null : current.getTitleFull(),
                current == null ? 0L : seriesSizes.getOrDefault(current.getId(), 0L),
                sharedArtists, titleSimilarity, score);
    }

    private Map<Integer, Long> seriesSizes(Collection<Chapter> chapters)
    {
        return chapterRepository.chapterCountsBySeriesIds(chapters.stream()
                .map(Chapter::getSeries)
                .filter(Objects::nonNull)
                .map(Series::getId)
                .distinct()
                .toList());
    }

    /**
     * A row whose whole title is the term is the shortest that can contain it, so it always falls inside
     * the bounded fetch. The shorter of the two searchable titles counts, since either can be what matched;
     * a blank {@code nativeTitle} counts as absent, or a length of 0 would sort it first.
     */
    private static <T> Specification<T> shortestTitleFirst()
    {
        return (root, query, cb) ->
        {
            if (query != null)
            {
                var fullLength = cb.length(root.get("titleFull"));
                var nativeLength = cb.length(
                        cb.coalesce(cb.nullif(root.<String>get("nativeTitle"), ""), root.get("titleFull")));
                var shorter = cb.<Integer>selectCase()
                        .when(cb.lessThan(nativeLength, fullLength), nativeLength)
                        .otherwise(fullLength);
                query.orderBy(cb.asc(shorter), cb.asc(root.get("id")));
            }
            return null;
        };
    }

    private static double nameSimilarity(String needle, String titleFull, String nativeTitle)
    {
        return Math.max(similarity(needle, titleFull), similarity(needle, nativeTitle));
    }

    private static double similarity(String needle, String title)
    {
        if (StringUtils.isBlank(title))
        {
            return 0.0;
        }
        Double score = SIMILARITY.apply(needle, title.toLowerCase(Locale.ROOT));
        return score == null ? 0.0 : score;
    }

    /** One query for the whole page. */
    private Map<Integer, String> thumbnails(Collection<Integer> seriesIds)
    {
        var urls = new HashMap<Integer, String>();
        if (seriesIds.isEmpty())
        {
            return urls;
        }
        for (Object[] row : chapterRepository.findCoverChapterIdBySeriesIds(seriesIds))
        {
            urls.put(((Number) row[0]).intValue(), imageService.thumbnailUrl(((Number) row[1]).intValue()));
        }
        return urls;
    }
}

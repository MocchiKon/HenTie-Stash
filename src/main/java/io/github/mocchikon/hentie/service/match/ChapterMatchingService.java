package io.github.mocchikon.hentie.service.match;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.SeriesService;
import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The best series above the threshold gets the chapter, otherwise a new series is created from it. So every
 * chapter ends up in a series, and later siblings find the series the first one created.
 * <p>
 * Separate from {@link ChapterMatchingSweep} so each slice's {@code @Transactional} goes through the proxy
 * and commits on its own.
 */
@Service
@RequiredArgsConstructor
public class ChapterMatchingService
{
    private static final Logger log = LoggerFactory.getLogger(ChapterMatchingService.class);

    private final ChapterRepository chapterRepository;
    private final SeriesRepository seriesRepository;
    private final SeriesCandidateFinder candidateFinder;
    private final SeriesService seriesService;
    private final SettingsService settingsService;
    private final EntityManager entityManager;

    public enum Outcome
    {
        LINKED,
        CREATED,
        /** Already in a series, or auto-linking is switched off. */
        SKIPPED
    }

    /** {@code key} is the caller's parse, passed in so the title is parsed once. */
    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public Outcome autoLink(Chapter chapter, TitleKey key)
    {
        if (!settingsService.isMatchAutoLinkEnabled() || chapter.getSeries() != null)
        {
            return Outcome.SKIPPED;
        }
        Linked linked = link(chapter, key, artistIds(chapter), settingsService.getMatchThresholdScore(), Map.of());
        seriesService.recomputeDerived(linked.seriesId());
        return linked.outcome();
    }

    /**
     * Touched series are recomputed once each at the end: a slice walks a family contiguously, so a
     * recompute per chapter would be quadratic in family size.
     * <p>
     * The persistence context is cleared after every chapter, because candidate lookup can load thousands
     * of {@code Series} and every later auto-flush would dirty-check them all.
     */
    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public Map<Outcome, Integer> matchBatch(List<Integer> chapterIds)
    {
        double threshold = settingsService.getMatchThresholdScore();
        Map<Integer, Set<Integer>> artists = artistIds(chapterIds);

        var counts = new HashMap<Outcome, Integer>();
        // Artists linked in this batch but not yet materialized; without them the artist veto fails.
        var pendingArtists = new HashMap<Integer, Set<Integer>>();
        for (Integer id : chapterIds)
        {
            Chapter chapter = chapterRepository.findById(id).orElse(null);
            if (chapter == null || chapter.getSeries() != null)
            {
                counts.merge(Outcome.SKIPPED, 1, Integer::sum);
                continue;
            }
            Set<Integer> chapterArtists = artists.getOrDefault(id, Set.of());
            Linked linked = link(chapter, TitleKey.of(chapter.getTitleFull()), chapterArtists, threshold,
                    pendingArtists);
            pendingArtists.computeIfAbsent(linked.seriesId(), series -> new HashSet<>()).addAll(chapterArtists);
            counts.merge(linked.outcome(), 1, Integer::sum);
            entityManager.flush();
            entityManager.clear();
        }
        pendingArtists.keySet().forEach(seriesService::recomputeDerived);
        return counts;
    }

    @Transactional
    public int backfillChapterKeys(List<Integer> chapterIds)
    {
        List<Chapter> chapters = chapterRepository.findAllById(chapterIds);
        for (Chapter chapter : chapters)
        {
            chapter.setMatchKey(TitleKey.of(chapter.getTitleFull()).getMatchKey());
        }
        chapterRepository.saveAll(chapters);
        return chapters.size();
    }

    @Transactional
    public int backfillSeriesKeys(List<Integer> seriesIds)
    {
        List<Series> series = seriesRepository.findAllById(seriesIds);
        series.forEach(SeriesService::applyMatchKeys);
        seriesRepository.saveAll(series);
        return series.size();
    }

    /** {@code seriesId} is the series that now needs its derived values recomputed. */
    private record Linked(Outcome outcome, int seriesId)
    {
    }

    /**
     * Does not recompute the series; callers do that once per series. Logs at DEBUG because a sweep calls
     * it for every chapter.
     */
    private Linked link(Chapter chapter, TitleKey key, Set<Integer> chapterArtists, double threshold,
                        Map<Integer, Set<Integer>> pendingArtists)
    {
        var numbering = Map.of(chapter.getId(), Float.valueOf(key.getChapterNum()));

        List<ScoredSeries> ranked = candidateFinder.rank(key, chapterArtists, pendingArtists);
        ScoredSeries best = ranked.isEmpty() ? null : ranked.getFirst();
        if (best != null && best.score() >= threshold)
        {
            seriesService.addChaptersDeferred(best.series().getId(), numbering);
            log.debug("Matched chapter {} '{}' to series {} '{}' (score {})",
                    chapter.getId(), chapter.getTitle(), best.series().getId(), best.series().getTitle(),
                    Math.round(best.score() * 100) / 100.0);
            return new Linked(Outcome.LINKED, best.series().getId());
        }

        int seriesId = seriesService.createForMatchDeferred(key.getBaseTitleFull(),
                key.getBaseTitlePretty(), numbering);
        log.debug("No series scored {} or better for chapter {} '{}'; created series {}",
                threshold, chapter.getId(), chapter.getTitle(), seriesId);
        return new Linked(Outcome.CREATED, seriesId);
    }

    private static Set<Integer> artistIds(Chapter chapter)
    {
        var ids = new HashSet<Integer>();
        chapter.getArtists().forEach(artist -> ids.add(artist.getId()));
        return ids;
    }

    /** One query per slice, not a lazy load per chapter. */
    private Map<Integer, Set<Integer>> artistIds(List<Integer> chapterIds)
    {
        var byChapter = new HashMap<Integer, Set<Integer>>();
        for (Object[] row : chapterRepository.findArtistIdsByChapterIds(chapterIds))
        {
            byChapter.computeIfAbsent(((Number) row[0]).intValue(), id -> new HashSet<>())
                    .add(((Number) row[1]).intValue());
        }
        return byChapter;
    }
}

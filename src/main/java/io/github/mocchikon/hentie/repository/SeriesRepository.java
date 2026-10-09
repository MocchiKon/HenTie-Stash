package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Series;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface SeriesRepository extends JpaRepository<Series, Integer>, JpaSpecificationExecutor<Series>
{
    /**
     * The open end of a prefix range seek. The highest code point, not {@code U+FFFF}: SQLite compares UTF-8
     * bytes, so a key extended by an emoji would sort above {@code U+FFFF}.
     */
    String MATCH_KEY_RANGE_END = "􏿿";

    long countByIdIn(Collection<Integer> ids);

    /**
     * Separate from {@link #findByMatchKeyExtending}: OR-ed together, the cap could drop the exact key.
     * <p>
     * Every candidate seek here takes {@code excludedId} ({@code null} excludes nothing) inside its own
     * predicate: dropped afterwards, the series would still take a place under the cap, and a hit here decides
     * whether the fuzzy pass runs. Every index these seeks walk carries the id, so the plan does not change.
     */
    @Query("""
            select s from Series s
            where s.matchKey = :key and (:excludedId is null or s.id <> :excludedId)
            order by s.id
            """)
    List<Series> findByMatchKey(String key, Integer excludedId, Pageable pageable);

    /**
     * A range, not {@code LIKE 'key %'}: SQLite uses an index for {@code LIKE} only under certain collation
     * conditions. The bounds are built here so the trailing space, which stops {@code "ohayo"} taking in
     * {@code "ohayoo"}, cannot be forgotten.
     */
    default List<Series> findByMatchKeyExtending(String key, Integer excludedId, Pageable pageable)
    {
        var lower = key + ' ';
        return findByMatchKeyRange(lower, lower + MATCH_KEY_RANGE_END, excludedId, pageable);
    }

    /** Call {@link #findByMatchKeyExtending} instead. */
    @Query("""
            select s from Series s
            where s.matchKey >= :lower and s.matchKey < :upper and (:excludedId is null or s.id <> :excludedId)
            order by s.matchKey, s.id
            """)
    List<Series> findByMatchKeyRange(String lower, String upper, Integer excludedId, Pageable pageable);

    /** Longest key first: a one-token prefix may be shared by thousands of series that would fill the cap. */
    @Query("""
            select s from Series s
            where s.matchKey in :keys and (:excludedId is null or s.id <> :excludedId)
            order by length(s.matchKey) desc, s.id
            """)
    List<Series> findByMatchKeyIn(Collection<String> keys, Integer excludedId, Pageable pageable);

    /** Needs no blocking key, so it finds a sibling however badly its title is misspelled. */
    @Query("""
            select distinct s from Series s join s.effectiveArtists a
            where a.id in :artistIds and (:excludedId is null or s.id <> :excludedId)
            order by s.id
            """)
    List<Series> findByEffectiveArtistIds(Collection<Integer> artistIds, Integer excludedId, Pageable pageable);

    /**
     * {@code [series_id, artist_id]} pairs. JPQL, not native SQL: only JPQL auto-flushes the effective
     * artists the sweep has just written.
     */
    @Query("select s.id, a.id from Series s join s.effectiveArtists a where s.id in :seriesIds")
    List<Object[]> findEffectiveArtistIdsBySeriesIds(Collection<Integer> seriesIds);

    @Query("select s.id from Series s where s.matchKey = '' and s.id > :afterId order by s.id")
    List<Integer> findIdsWithoutMatchKeyAfter(int afterId, Pageable pageable);
}

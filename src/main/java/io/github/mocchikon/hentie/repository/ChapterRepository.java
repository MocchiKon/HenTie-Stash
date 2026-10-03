package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.*;

public interface ChapterRepository extends JpaRepository<Chapter, Integer>, JpaSpecificationExecutor<Chapter>
{
    @Query("SELECT c FROM Chapter c WHERE c.series.id = :seriesId ORDER BY c.chapterNum ASC NULLS LAST, c.id ASC")
    List<Chapter> findBySeriesIdOrderByChapterNumAscIdAsc(Integer seriesId);

    /**
     * {@code [series_id, chapter_id]} in one query for a page of cards. The cover is the first chapter in
     * the order of {@link #findBySeriesIdOrderByChapterNumAscIdAsc}; series with no chapters are absent.
     */
    @Query(value = """
            SELECT series_id, id FROM (
                SELECT series_id, id,
                       ROW_NUMBER() OVER (PARTITION BY series_id ORDER BY chapter_num ASC NULLS LAST, id ASC) AS rn
                FROM chapter
                WHERE series_id IN (:seriesIds)
            ) ranked WHERE rn = 1
            """, nativeQuery = true)
    List<Object[]> findCoverChapterIdBySeriesIds(Collection<Integer> seriesIds);

    @Query("SELECT c FROM Chapter c WHERE c.series.id = :seriesId AND c.language = :language ORDER BY c.chapterNum ASC NULLS LAST, c.id ASC")
    List<Chapter> findBySeriesIdAndLanguageOrderByChapterNumAscIdAsc(Integer seriesId, String language);

    List<Chapter> findBySeriesId(Integer seriesId);

    /** A keyset walk, not an offset page, so a sweep never pays a growing OFFSET scan. */
    @Query("select c.id from Chapter c where c.id > :afterId order by c.id")
    List<Integer> findIdsAfter(int afterId, Pageable pageable);

    /**
     * {@code [id, match_key]} rows. The {@code (match_key, id)} order is the matching sweep's algorithm: a
     * family arrives together, shortest member first (see {@code ChapterMatchingSweep}).
     */
    @Query("""
            select c.id, c.matchKey from Chapter c
            where c.series is null and (c.matchKey > :afterKey or (c.matchKey = :afterKey and c.id > :afterId))
            order by c.matchKey, c.id
            """)
    List<Object[]> findUnlinkedAfter(String afterKey, int afterId, Pageable pageable);

    /** Imported rows still on the DB default. */
    @Query("select c.id from Chapter c where c.matchKey = '' and c.id > :afterId order by c.id")
    List<Integer> findIdsWithoutMatchKeyAfter(int afterId, Pageable pageable);

    /**
     * {@code [chapter_id, artist_id]} pairs. JPQL, not native SQL: the sweep writes links as it goes, and
     * only JPQL auto-flushes them before reading.
     */
    @Query("select c.id, a.id from Chapter c join c.artists a where c.id in :chapterIds")
    List<Object[]> findArtistIdsByChapterIds(Collection<Integer> chapterIds);

    Optional<Chapter> findByGalleryId(String galleryId);

    /**
     * Chapters titled {@code titleFull} from another source; one with no gallery id counts as another
     * source. Answered from {@code ix_chapter__title_full_gallery_id}. {@code substring}, not {@code like}:
     * SQLite's {@code LIKE} folds ASCII case and treats {@code _} as a wildcard.
     */
    @Query("""
            select c.id as id, c.galleryId as galleryId from Chapter c
            where c.titleFull = :titleFull
              and (c.galleryId is null or substring(c.galleryId, 1, length(:galleryIdPrefix)) <> :galleryIdPrefix)
            order by c.id
            """)
    List<TitleClash> findTitleFromOtherSources(String titleFull, String galleryIdPrefix, Limit limit);

    interface TitleClash
    {
        int getId();

        String getGalleryId();
    }

    /** A seek per id on the unique {@code gallery_id}; a gallery id no chapter holds is absent. */
    @Query("select c.galleryId as galleryId, c.downloadStatus as downloadStatus from Chapter c "
            + "where c.galleryId in :galleryIds")
    List<GalleryDownload> findDownloadStatusByGalleryIdIn(Collection<String> galleryIds);

    interface GalleryDownload
    {
        String getGalleryId();

        DownloadStatus getDownloadStatus();
    }

    // Pre-save checks for the unique gallery_id; *AndIdNot skips the row being edited.
    boolean existsByGalleryId(String galleryId);

    boolean existsByGalleryIdAndIdNot(String galleryId, Integer id);

    @Query("select distinct c.language from Chapter c " +
            "where c.series.id = :seriesId and c.language is not null order by c.language")
    List<String> findDistinctLanguagesBySeriesId(Integer seriesId);

    @Query("select distinct c.language from Chapter c where c.language is not null order by c.language")
    List<String> findDistinctLanguages();

    long countBySeriesId(Integer seriesId);

    long countBySeriesIdIn(Collection<Integer> seriesIds);

    long countByIdIn(Collection<Integer> ids);

    /** Read it through {@link #chapterCountsBySeriesIds}. */
    @Query("select c.series.id, count(c) from Chapter c where c.series.id in :seriesIds group by c.series.id")
    List<Object[]> countBySeriesIds(Collection<Integer> seriesIds);

    /** Series with no chapters are absent, so read it with a {@code 0L} default. */
    default Map<Integer, Long> chapterCountsBySeriesIds(Collection<Integer> seriesIds)
    {
        var counts = new HashMap<Integer, Long>();
        if (seriesIds.isEmpty())
        {
            return counts;
        }
        for (Object[] row : countBySeriesIds(seriesIds))
        {
            counts.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** Null when no chapter has a score. */
    @Query("select avg(c.score) from Chapter c where c.series.id = :seriesId and c.score is not null")
    Double averageScoreBySeriesId(Integer seriesId);

    @Query("select coalesce(sum(c.pageNum), 0) from Chapter c where c.series.id = :seriesId")
    long sumPageNumBySeriesId(Integer seriesId);

    @Query("select coalesce(sum(c.diskSize), 0) from Chapter c where c.series.id = :seriesId")
    long sumDiskSizeBySeriesId(Integer seriesId);

    // For SeriesService.recomputeDerived, one per facet.
    @Query("select distinct t.id from Chapter c join c.tags t where c.series.id = :seriesId")
    List<Integer> distinctTagIdsBySeriesId(Integer seriesId);

    @Query("select distinct a.id from Chapter c join c.artists a where c.series.id = :seriesId")
    List<Integer> distinctArtistIdsBySeriesId(Integer seriesId);

    @Query("select distinct ch.id from Chapter c join c.characters ch where c.series.id = :seriesId")
    List<Integer> distinctCharacterIdsBySeriesId(Integer seriesId);

    @Query("select distinct p.id from Chapter c join c.parodies p where c.series.id = :seriesId")
    List<Integer> distinctParodyIdsBySeriesId(Integer seriesId);

    @Query("select distinct g.id from Chapter c join c.groups g where c.series.id = :seriesId")
    List<Integer> distinctGroupIdsBySeriesId(Integer seriesId);

    @Query("select distinct ca.id from Chapter c join c.categories ca where c.series.id = :seriesId")
    List<Integer> distinctCategoryIdsBySeriesId(Integer seriesId);
}

package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The download queue. "Pending" is {@code error is null} and "failed" is {@code error is not null} - see
 * {@link DownloadQueueItem} for why there is no status column.
 */
public interface DownloadQueueRepository extends JpaRepository<DownloadQueueItem, Integer>
{
    Optional<DownloadQueueItem> findByLink(String link);

    /**
     * What {@code enqueue} de-duplicates on, not the link: one gallery has many link spellings
     * ({@code mock:12}, {@code MOCK:12}), and {@code link} is UNIQUE under BINARY collation. {@code findFirst},
     * because an existing database may hold several rows for one gallery.
     */
    Optional<DownloadQueueItem> findFirstByGalleryIdOrderByIdAsc(String galleryId);

    /** A failed row does not count: nothing runs it until a retry, which works out its pages afresh. */
    boolean existsByGalleryIdAndErrorIsNull(String galleryId);

    /** Pending and failed rows alike. */
    @Modifying
    @Query("delete from DownloadQueueItem i where i.galleryId in :galleryIds")
    int deleteByGalleryIdIn(@Param("galleryIds") Collection<String> galleryIds);

    Optional<DownloadQueueItem> findFirstByErrorIsNullOrderByIdAsc();

    List<DownloadQueueItem> findByErrorIsNullOrderByIdAsc(Limit limit);

    List<DownloadQueueItem> findByErrorIsNotNullOrderByIdAsc(Limit limit);

    long countByErrorIsNull();

    long countByErrorIsNotNull();

    /**
     * {@code ignoreImageErrors} is assigned, not OR-ed, so a plain retry puts a lenient item back into
     * strict mode.
     */
    @Modifying
    @Query("update DownloadQueueItem i set i.error = null, i.attempts = 0, "
            + "i.ignoreImageErrors = :ignoreImageErrors where i.error is not null")
    int retryFailed(@Param("ignoreImageErrors") boolean ignoreImageErrors);

    /** Read before "Delete all failed" removes the rows, so their staging can be discarded. */
    @Query("select i.chapterId from DownloadQueueItem i where i.error is not null and i.chapterId is not null")
    List<Integer> failedChapterIds();

    @Modifying
    @Query("delete from DownloadQueueItem i where i.error is not null")
    int deleteFailed();
}

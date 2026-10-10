package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The download queue. "Pending" is {@code error is null} and "failed" is {@code error is not null} - see
 * {@link DownloadQueueItem} for why there is no status column. Both run in {@code (priority, id)} order, so what the
 * user asked for comes before what a subscription found.
 */
public interface DownloadQueueRepository extends JpaRepository<DownloadQueueItem, Integer>,
        JpaSpecificationExecutor<DownloadQueueItem>
{
    Optional<DownloadQueueItem> findByLink(String link);

    /**
     * What {@code enqueue} de-duplicates on, not the link: one gallery has many link spellings
     * ({@code mock:12}, {@code MOCK:12}), and {@code link} is UNIQUE under BINARY collation. {@code findFirst},
     * because an existing database may hold several rows for one gallery.
     */
    Optional<DownloadQueueItem> findFirstByGalleryIdOrderByIdAsc(String galleryId);

    /** {@code link} is UNIQUE on its own, apart from the gallery id, so an insert is checked against both. */
    boolean existsByGalleryIdOrLink(String galleryId, String link);

    /** A failed row does not count: nothing runs it until a retry, which works out its pages afresh. */
    boolean existsByGalleryIdAndErrorIsNull(String galleryId);

    /** Pending and failed rows alike. */
    @Modifying
    @Query("delete from DownloadQueueItem i where i.galleryId in :galleryIds")
    int deleteByGalleryIdIn(@Param("galleryIds") Collection<String> galleryIds);

    List<DownloadQueueItem> findByErrorIsNullOrderByPriorityAscIdAsc(Limit limit);

    List<DownloadQueueItem> findByErrorIsNotNullOrderByPriorityAscIdAsc(Limit limit);

    long countByErrorIsNull();

    long countByErrorIsNotNull();

    long countBySubscriptionIdAndErrorIsNull(int subscriptionId);

    long countBySubscriptionIdAndErrorIsNotNull(int subscriptionId);

    /** Waiting rows per subscription, for the subscriptions page; one query for every subscription. */
    @Query("select i.subscriptionId as subscriptionId, count(i) as rowCount from DownloadQueueItem i "
            + "where i.subscriptionId is not null and i.error is null group by i.subscriptionId")
    List<SubscriptionRows> waitingBySubscription();

    /** Failed rows per subscription, as {@link #waitingBySubscription}. */
    @Query("select i.subscriptionId as subscriptionId, count(i) as rowCount from DownloadQueueItem i "
            + "where i.subscriptionId is not null and i.error is not null group by i.subscriptionId")
    List<SubscriptionRows> failedBySubscription();

    interface SubscriptionRows
    {
        Integer getSubscriptionId();

        long getRowCount();
    }

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

    /**
     * Read before "Clear all" removes the waiting rows; one knows its chapter only once an attempt failed. The
     * running row is left out: its staging belongs to the worker.
     */
    @Query("select i.chapterId from DownloadQueueItem i "
            + "where i.error is null and i.chapterId is not null and i.id <> :runningId")
    List<Integer> waitingChapterIdsExcept(@Param("runningId") int runningId);

    @Modifying
    @Query("delete from DownloadQueueItem i where i.error is null")
    int deleteWaiting();
}

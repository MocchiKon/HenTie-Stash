package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.SubscriptionCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SubscriptionCheckpointRepository extends JpaRepository<SubscriptionCheckpoint, Integer>
{
    Optional<SubscriptionCheckpoint> findFirstBySubscriptionIdOrderByRecordedAtDesc(int subscriptionId);

    Optional<SubscriptionCheckpoint> findFirstBySubscriptionIdAndRecordedAtLessThanEqualOrderByRecordedAtDesc(
            int subscriptionId, LocalDateTime at);

    Optional<SubscriptionCheckpoint> findFirstBySubscriptionIdOrderByRecordedAtAsc(int subscriptionId);

    @Modifying
    @Query("delete from SubscriptionCheckpoint c where c.subscriptionId = :subscriptionId and c.recordedAt < :before")
    int deleteRecordedBefore(@Param("subscriptionId") int subscriptionId, @Param("before") LocalDateTime before);

    @Modifying
    @Query("delete from SubscriptionCheckpoint c where c.subscriptionId = :subscriptionId")
    int deleteAllOf(@Param("subscriptionId") int subscriptionId);
}

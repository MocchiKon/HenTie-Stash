package io.github.mocchikon.hentie.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Where a subscription's head was at a time. A site's search lists no upload times, so this is how a re-check finds
 * where "the last N hours" begin in the search.
 */
@Entity
@Table(name = "subscription_checkpoint", indexes = {
        @Index(name = "ix_subscription_checkpoint__subscription_recorded", columnList = "subscription_id, recorded_at")
})
@Getter
@Setter
public class SubscriptionCheckpoint
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** A plain column: the foreign key deletes checkpoints with their subscription. */
    @Column(name = "subscription_id", nullable = false)
    private Integer subscriptionId;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "gallery_id", nullable = false)
    private String galleryId;
}

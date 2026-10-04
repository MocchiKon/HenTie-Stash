package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SubscriptionRepository extends JpaRepository<Subscription, Integer>
{
    /** By id, so the page keeps its order while subscriptions are edited. */
    List<Subscription> findAllByOrderByIdAsc();

    /** One source's subscriptions, for its runner thread; a handful of rows, so paused ones are filtered by the caller. */
    List<Subscription> findBySourceInOrderByIdAsc(Collection<String> sources);
}

package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SubscriptionRepository extends JpaRepository<Subscription, Integer>
{
    /** By priority (0 first), then id, so both the page and the runner take the most important one first. */
    List<Subscription> findAllByOrderByPriorityAscIdAsc();

    /** One source's subscriptions in the order the runner takes them; a handful of rows, so paused ones are filtered by the caller. */
    List<Subscription> findBySourceInOrderByPriorityAscIdAsc(Collection<String> sources);
}

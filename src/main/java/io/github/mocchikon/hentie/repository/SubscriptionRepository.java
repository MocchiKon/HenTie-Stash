package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionRepository extends JpaRepository<Subscription, Integer>
{
    /**
     * By priority (0 first), then id. Every row, also for the runner: a library has a handful, and a higher priority
     * holds back the lower ones of every site.
     */
    List<Subscription> findAllByOrderByPriorityAscIdAsc();
}

package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.ImageCompressionRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageCompressionRuleRepository extends JpaRepository<ImageCompressionRule, Integer>
{
    /** Walks {@code (priority, id)}: the order a decision ranks rules in. */
    Page<ImageCompressionRule> findAllByOrderByPriorityAscIdAsc(Pageable pageable);
}

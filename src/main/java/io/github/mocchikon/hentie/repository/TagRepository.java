package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Tag;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Integer>
{
    Optional<Tag> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Tag> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Limit limit);

    List<Tag> findAllByOrderByNameAsc();
}

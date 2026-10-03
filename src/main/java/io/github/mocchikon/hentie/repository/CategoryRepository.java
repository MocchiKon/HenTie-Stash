package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Category;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Integer>
{
    Optional<Category> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Category> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Limit limit);

    List<Category> findAllByOrderByNameAsc();
}

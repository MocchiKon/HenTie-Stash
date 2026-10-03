package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Parody;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ParodyRepository extends JpaRepository<Parody, Integer>
{
    Optional<Parody> findByTitleIgnoreCase(String title);

    boolean existsByTitleIgnoreCase(String title);

    List<Parody> findByTitleContainingIgnoreCaseOrderByTitleAsc(String title, Limit limit);

    List<Parody> findAllByOrderByTitleAsc();
}

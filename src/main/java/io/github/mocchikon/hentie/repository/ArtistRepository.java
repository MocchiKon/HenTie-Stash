package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Artist;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArtistRepository extends JpaRepository<Artist, Integer>
{
    Optional<Artist> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Artist> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Limit limit);

    List<Artist> findAllByOrderByNameAsc();
}

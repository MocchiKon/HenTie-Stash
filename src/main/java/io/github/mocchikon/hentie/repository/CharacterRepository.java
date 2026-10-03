package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Character;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CharacterRepository extends JpaRepository<Character, Integer>
{
    Optional<Character> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Character> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Limit limit);

    List<Character> findAllByOrderByNameAsc();
}

package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.Group;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroupRepository extends JpaRepository<Group, Integer>
{
    Optional<Group> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Group> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Limit limit);

    List<Group> findAllByOrderByNameAsc();
}

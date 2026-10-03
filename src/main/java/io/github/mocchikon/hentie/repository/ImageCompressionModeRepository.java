package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.ImageCompressionMode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImageCompressionModeRepository extends JpaRepository<ImageCompressionMode, Integer>
{
    /** By id, so a rename never moves a mode in the dropdown. */
    List<ImageCompressionMode> findAllByOrderByIdAsc();

    Optional<ImageCompressionMode> findFirstByNameIgnoreCase(String name);
}

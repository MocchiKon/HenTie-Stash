package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.DownloadedGallery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DownloadedGalleryRepository extends JpaRepository<DownloadedGallery, String>
{
}

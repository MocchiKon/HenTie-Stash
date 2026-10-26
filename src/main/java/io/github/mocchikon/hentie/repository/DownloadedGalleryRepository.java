package io.github.mocchikon.hentie.repository;

import io.github.mocchikon.hentie.entity.DownloadedGallery;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DownloadedGalleryRepository extends JpaRepository<DownloadedGallery, String>
{
    /**
     * Past downloads titled {@code titleFull} from another source. The prefix is compared with {@code substring},
     * not {@code like}, for the same reason as on chapters. Answered from
     * {@code ix_downloaded_gallery__title_full_gallery_id}.
     */
    @Query("""
            select g from DownloadedGallery g
            where g.titleFull = :titleFull
              and substring(g.galleryId, 1, length(:galleryIdPrefix)) <> :galleryIdPrefix
            order by g.galleryId
            """)
    List<DownloadedGallery> findTitleFromOtherSources(String titleFull, String galleryIdPrefix, Limit limit);
}

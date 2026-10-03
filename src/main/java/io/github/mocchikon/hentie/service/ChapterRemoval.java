package io.github.mocchikon.hentie.service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;

/**
 * The last step of every chapter delete, shared by {@link ChapterService} and {@link SeriesService} so both
 * agree on what "gone" means. Runs in the caller's transaction, after the rows are removed.
 */
@Service
@RequiredArgsConstructor
public class ChapterRemoval
{
    private static final int QUEUE_DELETE_CHUNK = 500;

    private final DownloadQueueRepository downloadQueueRepository;
    private final ImageService imageService;

    @PersistenceContext
    private EntityManager em;

    /**
     * <ul>
     *   <li><b>The queue rows go</b>, so a download in flight aborts instead of publishing. The queue row is
     *       the only thing the worker checks before publishing, so the pages would land in the folder of
     *       a deleted row, where nothing ever cleans them up (on a RAM disk they would also hold memory).
     *       It also stops a retry re-importing a gallery the user just deleted.</li>
     *   <li><b>The folders go last</b>, after the flush, because a file delete cannot be rolled back. After
     *       the flush SQLite holds the write lock, so only an I/O error at commit can still fail.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(Collection<Chapter> chapters)
    {
        List<String> galleryIds = chapters.stream().map(Chapter::getGalleryId).filter(Objects::nonNull).toList();
        // In chunks: a series deleted with its chapters can name more galleries than SQLite binds at once.
        for (int from = 0; from < galleryIds.size(); from += QUEUE_DELETE_CHUNK)
        {
            downloadQueueRepository.deleteByGalleryIdIn(
                    galleryIds.subList(from, Math.min(from + QUEUE_DELETE_CHUNK, galleryIds.size())));
        }
        em.flush();
        chapters.forEach(chapter -> imageService.deleteAll(chapter.getId()));
    }
}

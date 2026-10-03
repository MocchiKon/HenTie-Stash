package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.service.download.DownloadChoices;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Only parsing and hand-off live here; what a link means belongs to {@code scrapper}, what happens next to {@code service.download}. */
@Service
@RequiredArgsConstructor
public class DownloadService
{
    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private final DownloadQueueService queueService;
    private final DownloadWorker worker;
    private final DataDownloaderRegistry registry;
    private final SettingsService settingsService;

    public List<String> parseLinks(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return List.of();
        }
        return Arrays.stream(raw.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /** Unrecognized links are reported back rather than queued to fail later. */
    public DownloadQueueService.EnqueueResult queue(List<String> links, DownloadChoices choices)
    {
        if (links.isEmpty())
        {
            return new DownloadQueueService.EnqueueResult(0, 0, 0, 0);
        }
        DownloadQueueService.EnqueueResult result = queueService.enqueue(links, choices);
        log.info("Queued {} link(s) for download with Image Compression mode {}{}, gallery-dl {} ({} new, {} retried, "
                        + "{} already waiting, {} unsupported)", result.queued(), choices.compressionMode(),
                choices.avoidDuplicateTitles() ? ", avoiding duplicated titles from other sources" : "",
                choices.galleryDl(), result.accepted(), result.requeued(), result.alreadyQueued(), result.rejected());
        worker.kick();
        return result;
    }

    /** Empty when the chapter was never compressed or no source here can fetch its gallery id. */
    public Optional<String> fullQualityLink(Chapter chapter)
    {
        if (chapter.getCompressionMode() == null)
        {
            return Optional.empty();
        }
        return registry.linkFor(chapter.getGalleryId());
    }

    /** Empty for a chapter from no known source, or from one without a website. */
    public Optional<String> sourcePageLink(Chapter chapter)
    {
        return registry.pageLinkFor(chapter.getGalleryId());
    }

    /** @return false when {@link #fullQualityLink} has nothing to offer */
    public boolean queueFullQuality(Chapter chapter)
    {
        Optional<String> link = fullQualityLink(chapter);
        if (link.isEmpty())
        {
            return false;
        }
        queueService.enqueueFullQuality(link.get(), chapter.getGalleryId(), settingsService.getGalleryDlDefaults());
        log.info("Queued a full-quality re-download of chapter {} ({})", chapter.getId(), chapter.getGalleryId());
        worker.kick();
        return true;
    }
}

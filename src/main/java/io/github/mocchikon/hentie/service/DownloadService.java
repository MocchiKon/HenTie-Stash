package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
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
    private final ImageCompressionModeService modeService;

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

    /**
     * Empty unless Lossless is strictly better than the mode that compressed the chapter (a chapter never compressed,
     * or compressed losslessly, has nothing to gain), or when no source here can fetch its gallery id. Lossless, not
     * None: it keeps the source's pixels at a fraction of the size, and None is what an upgrade from a paste is for.
     */
    public Optional<String> losslessRedownloadLink(Chapter chapter)
    {
        return redownloadLink(chapter, BuiltInCompressionMode.LOSSLESS.getKey());
    }

    /** @return false when {@link #losslessRedownloadLink} has nothing to offer */
    public boolean queueLosslessRedownload(Chapter chapter)
    {
        return queueRedownload(chapter, BuiltInCompressionMode.LOSSLESS.getKey());
    }

    /** Empty unless the mode is strictly better than the chapter's recorded one, as for an upgrade. */
    public Optional<String> redownloadLink(Chapter chapter, String modeKey)
    {
        String recorded = chapter.getCompressionMode();
        if (recorded == null || !modeService.isBetter(modeKey, recorded))
        {
            return Optional.empty();
        }
        return registry.linkFor(chapter.getGalleryId());
    }

    /** @return false when {@link #redownloadLink} has nothing to offer */
    public boolean queueRedownload(Chapter chapter, String modeKey)
    {
        Optional<String> link = redownloadLink(chapter, modeKey);
        if (link.isEmpty())
        {
            return false;
        }
        queueService.enqueueRedownload(link.get(), chapter.getGalleryId(), modeKey,
                settingsService.getGalleryDlDefaults());
        log.info("Queued a re-download of chapter {} ({}) in {}", chapter.getId(), chapter.getGalleryId(), modeKey);
        worker.kick();
        return true;
    }

    /** Empty for a chapter from no known source, or from one without a website. */
    public Optional<String> sourcePageLink(Chapter chapter)
    {
        return registry.pageLinkFor(chapter.getGalleryId(), chapter.getSourceSite());
    }
}

package io.github.mocchikon.hentie.service.compress;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manage &rarr; Library maintenance &rarr; <b>"Compress images"</b> with the Settings mode. Walks chapters in
 * id-ordered slices, each with its own short stats transaction, so the write lock is never held during
 * encoding and the app stays usable in another tab. Chapters go one at a time, since one chapter's pages
 * already fill the pool.
 */
@Service
@RequiredArgsConstructor
public class ImageCompressionSweep
{
    private static final Logger log = LoggerFactory.getLogger(ImageCompressionSweep.class);

    /** Chapters per stats-repair transaction. */
    private static final int BATCH_SIZE = 100;

    private final ChapterRepository chapterRepository;
    private final ChapterService chapterService;
    private final ImageCompressionService compressionService;
    private final ImageCompressionModeService modeService;
    private final SettingsService settingsService;
    private final WriteGate writeGate;

    /** @param mode null when the Settings mode is "None" */
    public record Result(String mode, int chapters, ImageCompressionService.Summary summary)
    {
        public String describe()
        {
            if (mode == null)
            {
                return ImageCompressionService.NO_MODE_MESSAGE;
            }
            return chapters + " chapter(s) processed with mode " + mode + " - " + summary.describe();
        }
    }

    /**
     * Holds the run lock for the whole walk, not per chapter, or an upload could slip in between two
     * chapters and share the pool for hours.
     *
     * @throws ImageCompressionService.RunInProgress when another user-started run is already compressing
     */
    public Result compressAll()
    {
        Optional<CompressionProfile> profile =
                compressionService.profileFor(settingsService.getImageCompressionMode());
        if (profile.isEmpty())
        {
            log.info("Compress images: nothing to do - the Settings mode is \"None\"");
            return new Result(null, 0, ImageCompressionService.Summary.NOTHING);
        }
        return compressionService.exclusively(
                () -> writeGate.background("compressing images", () -> walk(profile.get())));
    }

    private Result walk(CompressionProfile profile)
    {
        String modeName = profile.name();
        log.info("Compress images: starting with mode {}", modeName);
        long startedAt = System.nanoTime();

        var slice = PageRequest.of(0, BATCH_SIZE);
        var summary = ImageCompressionService.Summary.NOTHING;
        int chapters = 0;
        int afterId = 0;
        while (true)
        {
            List<Integer> ids = chapterRepository.findIdsAfter(afterId, slice);
            if (ids.isEmpty())
            {
                var took = DurationFormatUtils.formatDurationHMS(
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                // Summary last: it is a sentence ending with a full stop.
                log.info("Compress images: finished in {} - {} chapter(s), {}", took, chapters, summary.describe());
                return new Result(modeName, chapters, summary);
            }
            afterId = ids.get(ids.size() - 1);
            for (int id : ids)
            {
                var chapter = compressionService.compressChapter(id, profile);
                summary = summary.plus(chapter);
                // Per chapter, not per slice: nothing rebuilds the mode from the files, and running the sweep
                // again finds no input left in a finished chapter. A walk stopped mid-slice would otherwise
                // leave every chapter it finished compressed but shown as full quality, for good.
                if (chapter.replaced() > 0)
                {
                    chapterService.setCompressionMode(id, profile.key());
                }
            }
            chapters += ids.size();
            // Search sorts and filters on page_num/disk_size, which just changed.
            chapterService.syncImageStats(ids);
        }
    }

    /** Shown beside the button, so the user knows which mode will run. */
    public String currentModeName()
    {
        return modeService.displayName(settingsService.getImageCompressionMode());
    }
}

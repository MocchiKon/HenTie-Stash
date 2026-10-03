package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A bean of its own so compression runs outside any transaction: encoding takes seconds to minutes and
 * SQLite has one writer. The final stats repair is a call on another bean
 * ({@link ChapterService#rescanImages(int)}), because a self-invocation would skip its {@code @Transactional}.
 * <p>
 * <b>Both entry points claim their turn at the {@link WriteGate} first</b>: they change files before their first
 * write transaction, so a busy library must refuse them before that, not after, when the files would already be
 * saved or re-encoded while the database says otherwise.
 * <p>
 * Uploads use the Settings mode; downloads carry their own on the queue row.
 */
@Service
@RequiredArgsConstructor
public class ChapterImageService
{
    private final ImageService imageService;
    private final ImageCompressionService compressionService;
    private final ChapterService chapterService;
    private final SettingsService settingsService;
    private final WriteGate writeGate;

    public static final String UPLOAD_NOT_COMPRESSED_MESSAGE =
            "Images saved, but not compressed: another Image Compression run is still in progress. Use "
                    + "\"Compress images\" on this chapter once it has finished.";

    /** Not a flag on {@code Summary}: being skipped is a fact about the upload, not about a compression run. */
    public record UploadResult(ImageCompressionService.Summary summary, boolean compressionSkipped)
    {
        /** Empty under "None": saying nothing was compressed would only repeat the user's own setting back. */
        public Optional<String> message()
        {
            if (compressionSkipped)
            {
                return Optional.of(UPLOAD_NOT_COMPRESSED_MESSAGE);
            }
            return summary.files() > 0 ? Optional.of(summary.describe()) : Optional.empty();
        }
    }

    /**
     * Compresses only the files just saved: re-encoding the existing pages would lose quality with every
     * upload.
     * <p>
     * When another run holds the lock the images are still saved, uncompressed: refusing would throw the
     * user's selection away, and waiting could mean waiting for a whole library sweep.
     */
    public UploadResult upload(int chapterId, List<MultipartFile> files) throws IOException
    {
        writeGate.claimTurn();
        List<Path> saved = imageService.saveImages(chapterId, files);   // null / empty selection ignored inside
        CompressionProfile profile = uploadProfile().orElse(null);
        var result = compressSaved(chapterId, saved, profile);
        chapterService.rescanImages(chapterId);
        recordCompression(chapterId, profile, result.summary());
        return result;
    }

    private UploadResult compressSaved(int chapterId, List<Path> saved, CompressionProfile profile)
    {
        try
        {
            return new UploadResult(compressionService.compressFiles(chapterId, saved, profile), false);
        }
        catch (ImageCompressionService.RunInProgress busy)
        {
            return new UploadResult(ImageCompressionService.Summary.NOTHING, true);
        }
    }

    /**
     * @throws ImageCompressionService.RunInProgress when another user-started run is already compressing
     */
    public ImageCompressionService.Summary compressExisting(int chapterId)
    {
        writeGate.claimTurn();
        CompressionProfile profile = uploadProfile().orElse(null);
        var summary = compressionService.compressChapter(chapterId, profile);
        chapterService.rescanImages(chapterId);
        recordCompression(chapterId, profile, summary);
        return summary;
    }

    /** A run that kept every original leaves the chapter full quality, so it records nothing. */
    private void recordCompression(int chapterId, CompressionProfile profile, ImageCompressionService.Summary summary)
    {
        if (profile != null && summary.replaced() > 0)
        {
            chapterService.setCompressionMode(chapterId, profile.key());
        }
    }

    /**
     * The summary is empty whether the mode is None, another run is in progress or the chapter has no
     * images; only this layer knows which, so it builds the message.
     */
    public String compressExistingAndDescribe(int chapterId)
    {
        if (uploadProfile().isEmpty())
        {
            return ImageCompressionService.NO_MODE_MESSAGE;
        }
        try
        {
            return compressExisting(chapterId).describe();
        }
        catch (ImageCompressionService.RunInProgress busy)
        {
            return busy.getMessage();
        }
    }

    public Optional<CompressionProfile> uploadProfile()
    {
        return compressionService.profileFor(settingsService.getImageCompressionMode());
    }
}

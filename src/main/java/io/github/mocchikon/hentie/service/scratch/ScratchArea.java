package io.github.mocchikon.hentie.service.scratch;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Kinds of temporary or derived image file, each with its own folder because they want different things:
 * staging needs a RAM disk only for a compressed download, intermediates always do, and each cache needs a
 * size cap of its own so one cannot prune the other away.
 *
 * <p>Cleanups delete {@code <root>/<chapterId>} recursively, so a root is always a folder the app named
 * itself ({@link #getOwnFolder}) and {@link ScratchSpace} refuses overlapping roots.
 */
@Getter
@RequiredArgsConstructor
public enum ScratchArea
{
    DOWNLOAD_STAGING("Download staging", "storage.download-staging-dir", "downloadStagingDir",
            ".staging", "staging"),
    COMPRESSION_WORK("Image Compression intermediates", "storage.compression-work-dir", "compressionWorkDir",
            ".work", "work"),
    TRANSCODE_CACHE("Decoded JPEG XL cache", "storage.transcode-cache-dir", "transcodeCacheDir",
            ".transcoded", "transcoded"),
    COMFYUI_RESULTS("ComfyUI results cache", "storage.comfyui-results-dir", "comfyuiResultsDir",
            ".comfyui", "comfyui");

    private final String displayName;
    private final String settingKey;
    /** Request parameter name on the Settings form. */
    private final String formField;
    /** Dot-named, so the data folder's listing can never take it for a chapter. */
    private final String dataDirFolder;
    /**
     * Folder inside the library's folder ({@link ScratchSpace#libraryFolderName}) on the RAM disk or in a
     * folder chosen in Settings. Rooting at the chosen folder itself would let cleanups delete the user's own
     * numbered folders.
     */
    private final String ownFolder;
}

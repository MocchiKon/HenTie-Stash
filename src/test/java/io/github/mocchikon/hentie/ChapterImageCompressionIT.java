package io.github.mocchikon.hentie;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.ImageCompressionMode;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.ImageCompressionModeRepository;
import io.github.mocchikon.hentie.service.ChapterImageService;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionSweep;
import io.github.mocchikon.hentie.service.compress.ImageToolLocator;

import static org.assertj.core.api.Assertions.*;

/**
 * Upload, per-chapter button and library sweep: all take the Settings mode and must resync page count and
 * disk size, which search filters on and which would otherwise silently go stale.
 *
 * <p>Not transactional, because encoding runs outside any transaction on purpose; rows are cleaned up by hand.
 */
@SpringBootTest
class ChapterImageCompressionIT
{
    private static final int SIZE = 300;

    @Autowired ChapterImageService chapterImageService;
    @Autowired ChapterService chapterService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired ImageService imageService;
    @Autowired ImageDirectory imageDirectory;
    @Autowired SettingsService settingsService;
    @Autowired ImageCompressionModeService modeService;
    @Autowired ImageCompressionModeRepository modeRepository;
    @Autowired ImageCompressionSweep sweep;
    @Autowired ImageCompressionService compressionService;
    @Autowired ImageToolLocator toolLocator;

    private final List<Integer> created = new ArrayList<>();

    @AfterEach
    void cleanUp()
    {
        created.forEach(chapterService::delete);
        created.clear();
        // The settings cache is shared, so a mode left set would apply to every later suite's uploads.
        settingsService.setImageCompressionMode(BuiltInCompressionMode.NONE.getKey());
        modeRepository.deleteAll();
    }

    // ---- where the intermediates live ----------------------------------------

    /**
     * A run deletes its folders once empty, and {@code stagePage} briefly leaves an empty staging folder
     * before writing page 1. If the work folder were inside staging, a run could delete it under the worker
     * and force a re-fetch of the whole gallery.
     */
    @Test
    void shouldLeaveTheDownloadStagingFolderAloneWhenCompressingTheSameChapter() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a chapter with a page, and an empty staging folder, as stagePage leaves it before page 1.
        int chapterId = newChapter("Compressed while a download stages");
        settingsService.setImageCompressionMode(modeService.save(mode("Sweep JXL")));
        chapterImageService.upload(chapterId, List.of(png("a.png")));
        var staging = imageService.stagingDir(chapterId, false);
        Files.createDirectories(staging);

        // WHEN the per-chapter button runs over that chapter.
        chapterImageService.compressExisting(chapterId);

        // THEN the pipeline's folder is untouched...
        assertThat(staging).isDirectory();
        // ...because the intermediates were never inside it...
        assertThat(compressionService.workDir(chapterId).startsWith(staging)).isFalse();
        // ...and the run still removed its own folder.
        assertThat(compressionService.workDir(chapterId)).doesNotExist();
    }

    /** A sweep may be encoding into the work folder while a download discards staging; downloads take no run lock. */
    @Test
    void shouldLeaveTheWorkFolderAloneWhenDiscardingStagedPages() throws IOException
    {
        // GIVEN a staged page, and an intermediate a running sweep is writing for the same chapter.
        int chapterId = newChapter("Compressed while a download discards");
        var staging = imageService.stagingDir(chapterId, false);
        var work = compressionService.workDir(chapterId);
        Files.createDirectories(staging);
        Files.createDirectories(work);
        Files.writeString(staging.resolve("1.png"), "a staged page");
        Path encoding = Files.writeString(work.resolve("1.0f1e2d3c.jxl"), "an intermediate being written");
        try
        {
            // WHEN
            imageService.discardStagedPages(chapterId);

            // THEN
            assertThat(staging).doesNotExist();
            assertThat(encoding).exists();
        }
        finally
        {
            imageService.deleteAll(chapterId);
        }
    }

    /** Nothing else ever comes back for a deleted chapter's intermediates, which would hold RAM-disk memory. */
    @Test
    void shouldRemoveTheWorkFolderWhenTheChapterIsDeleted() throws IOException
    {
        // GIVEN both folders present, as a process killed mid-encode would leave them.
        int chapterId = newChapter("Killed mid-encode");
        var staging = imageService.stagingDir(chapterId, false);
        var work = compressionService.workDir(chapterId);
        Files.createDirectories(staging);
        Files.createDirectories(work);
        Files.writeString(staging.resolve("1.png"), "a staged page");
        Files.writeString(work.resolve("1.magick.png"), "an abandoned intermediate");

        // WHEN
        imageService.deleteAll(chapterId);

        // THEN neither is left behind.
        assertThat(staging).doesNotExist();
        assertThat(work).doesNotExist();
    }

    // ---- uploads -------------------------------------------------------------

    @Test
    void shouldCompressUploadedImagesWithTheSettingsModeAndResyncTheStats() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a chapter and a lossy JPEG XL mode selected in Settings.
        int chapterId = newChapter("Uploaded and compressed");
        String key = modeService.save(mode("Upload JXL"));
        settingsService.setImageCompressionMode(key);

        // WHEN two pages are uploaded.
        ChapterImageService.UploadResult result =
                chapterImageService.upload(chapterId, List.of(png("a.png"), png("b.png")));

        // THEN both were re-encoded...
        assertThat(result.compressionSkipped()).isFalse();
        ImageCompressionService.Summary summary = result.summary();
        assertThat(summary.files()).isEqualTo(2);
        assertThat(summary.replaced()).isEqualTo(2);
        assertThat(summary.saved()).isPositive();
        // ...they are stored under the encoder's extension, numbered from 1...
        assertThat(imageService.pageUrls(chapterId))
                .containsExactly("/data/" + chapterId + "/1.jxl", "/data/" + chapterId + "/2.jxl");
        // ...and the searchable stats match what is actually on disk now, not what was uploaded.
        Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
        assertThat(chapter.getPageNum()).isEqualTo(2);
        assertThat(chapter.getDiskSize()).isEqualTo(imageService.diskSize(chapterId));
        assertThat(chapter.getDiskSize()).isEqualTo(summary.after());
        // ...and the chapter records the mode, which the detail page labels.
        assertThat(chapter.getCompressionMode()).isEqualTo(key);
    }

    /**
     * Otherwise the detail page would label originals as compressed and offer to re-download them. The
     * format list rules the PNG out, so no encoder is needed.
     */
    @Test
    void shouldRecordNoModeWhenTheRunReplacedNoPage() throws IOException
    {
        // GIVEN a mode that only takes JPEGs.
        int chapterId = newChapter("Out of the mode's scope");
        var jpegOnly = mode("JPEG only");
        jpegOnly.setFormats("JPG");
        settingsService.setImageCompressionMode(modeService.save(jpegOnly));

        // WHEN a PNG is uploaded.
        ChapterImageService.UploadResult result = chapterImageService.upload(chapterId, List.of(png("a.png")));

        // THEN nothing was replaced, and nothing is recorded.
        assertThat(result.compressionSkipped()).isFalse();
        assertThat(result.summary().replaced()).isZero();
        assertThat(imageService.pageUrls(chapterId)).containsExactly("/data/" + chapterId + "/1.png");
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCompressionMode()).isNull();
    }

    /** Nothing else comes back for these folders, so a sweep would leave one per chapter on the RAM disk. */
    @Test
    void shouldLeaveNoStagingFolderBehindWhenImagesWereCompressedOutsideADownload() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a chapter and a JPEG XL mode selected in Settings.
        int chapterId = newChapter("No staging leftovers");
        settingsService.setImageCompressionMode(modeService.save(mode("Leftover check")));

        // WHEN a page is uploaded, THEN it was encoded and staging holds nothing for the chapter.
        ChapterImageService.UploadResult result = chapterImageService.upload(chapterId, List.of(png("a.png")));
        assertThat(result.compressionSkipped()).isFalse();
        ImageCompressionService.Summary upload = result.summary();
        assertThat(upload.files()).isEqualTo(1);
        assertThat(upload.replaced()).isEqualTo(1);
        assertThat(upload.saved()).isPositive();
        assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();

        // WHEN the page is compressed again, THEN staging is still empty, whether or not it saved anything.
        ImageCompressionService.Summary again = chapterImageService.compressExisting(chapterId);
        assertThat(again.files()).isEqualTo(1);
        assertThat(again.replaced()).isBetween(0, 1);
        assertThat(again.after()).isLessThanOrEqualTo(again.before());
        assertThat(Files.exists(imageService.stagingDir(chapterId, false))).isFalse();
    }

    @Test
    void shouldStoreUploadedImagesUntouchedWhenTheSettingsModeIsNone() throws IOException
    {
        // GIVEN
        int chapterId = newChapter("Uploaded unprocessed");
        settingsService.setImageCompressionMode(BuiltInCompressionMode.NONE.getKey());

        // WHEN
        ChapterImageService.UploadResult result = chapterImageService.upload(chapterId, List.of(png("a.png")));

        // THEN nothing was processed, and the page is still a PNG with correct stats.
        assertThat(result.summary()).isEqualTo(ImageCompressionService.Summary.NOTHING);
        assertThat(result.compressionSkipped()).isFalse();
        assertThat(result.message()).isEmpty();
        assertThat(imageService.pageUrls(chapterId)).containsExactly("/data/" + chapterId + "/1.png");
        Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
        assertThat(chapter.getPageNum()).isEqualTo(1);
        assertThat(chapter.getCompressionMode()).isNull();
    }

    /** Re-encoding the existing pages would lose quality every time a page is added. */
    @Test
    void shouldCompressOnlyTheNewlyUploadedImagesWhenAddingToAChapterThatAlreadyHasPages() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a chapter with one page uploaded while compression was off.
        int chapterId = newChapter("Added to later");
        settingsService.setImageCompressionMode(BuiltInCompressionMode.NONE.getKey());
        chapterImageService.upload(chapterId, List.of(png("first.png")));

        // WHEN a mode is chosen and a second page uploaded.
        settingsService.setImageCompressionMode(modeService.save(mode("Later JXL")));
        ChapterImageService.UploadResult result = chapterImageService.upload(chapterId, List.of(png("second.png")));

        // THEN only the new page was considered...
        assertThat(result.compressionSkipped()).isFalse();
        ImageCompressionService.Summary summary = result.summary();
        assertThat(summary.files()).isEqualTo(1);
        assertThat(summary.replaced()).isEqualTo(1);
        // ...and the first page is still the PNG it was.
        assertThat(imageService.pageUrls(chapterId))
                .containsExactly("/data/" + chapterId + "/1.png", "/data/" + chapterId + "/2.jxl");
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isEqualTo(2);
    }

    // ---- the per-chapter button ----------------------------------------------

    @Test
    void shouldReEncodeEveryExistingPageWhenCompressingOneChapter() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a chapter whose three pages were stored unprocessed.
        int chapterId = newChapter("Compressed afterwards");
        settingsService.setImageCompressionMode(BuiltInCompressionMode.NONE.getKey());
        chapterImageService.upload(chapterId, List.of(png("a.png"), png("b.png"), png("c.png")));
        long before = chapterRepository.findById(chapterId).orElseThrow().getDiskSize();
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCompressionMode()).isNull();

        // WHEN a mode is chosen and the chapter is compressed.
        String key = modeService.save(mode("Retrofit JXL"));
        settingsService.setImageCompressionMode(key);
        ImageCompressionService.Summary summary = chapterImageService.compressExisting(chapterId);

        // THEN every page was re-encoded, in place and in order...
        assertThat(summary.files()).isEqualTo(3);
        assertThat(summary.replaced()).isEqualTo(3);
        assertThat(imageService.pageUrls(chapterId)).containsExactly(
                "/data/" + chapterId + "/1.jxl",
                "/data/" + chapterId + "/2.jxl",
                "/data/" + chapterId + "/3.jxl");
        // ...and the stored size came down with it.
        Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
        assertThat(chapter.getPageNum()).isEqualTo(3);
        assertThat(chapter.getDiskSize()).isLessThan(before);
        assertThat(chapter.getDiskSize()).isEqualTo(imageService.diskSize(chapterId));
        assertThat(chapter.getCompressionMode()).isEqualTo(key);
    }

    @Test
    void shouldChangeNothingWhenCompressingAChapterWithTheModeSetToNone() throws IOException
    {
        // GIVEN
        int chapterId = newChapter("Left alone");
        chapterImageService.upload(chapterId, List.of(png("a.png")));
        long before = chapterRepository.findById(chapterId).orElseThrow().getDiskSize();

        // WHEN
        ImageCompressionService.Summary summary = chapterImageService.compressExisting(chapterId);

        // THEN
        assertThat(summary).isEqualTo(ImageCompressionService.Summary.NOTHING);
        assertThat(imageService.pageUrls(chapterId)).containsExactly("/data/" + chapterId + "/1.png");
        Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
        assertThat(chapter.getDiskSize()).isEqualTo(before);
        assertThat(chapter.getCompressionMode()).isNull();
    }

    // ---- the whole-library sweep ----------------------------------------------

    /** Two chapters, so the slice loop and per-slice stats repair are exercised, not a single-row case. */
    @Test
    void shouldCompressEveryChaptersImagesWhenTheLibrarySweepRuns() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN two chapters with unprocessed pages.
        settingsService.setImageCompressionMode(BuiltInCompressionMode.NONE.getKey());
        int first = newChapter("Sweep one");
        int second = newChapter("Sweep two");
        chapterImageService.upload(first, List.of(png("a.png"), png("b.png")));
        chapterImageService.upload(second, List.of(png("c.png")));

        // WHEN the sweep runs with a mode selected.
        String key = modeService.save(mode("Sweep JXL"));
        settingsService.setImageCompressionMode(key);
        ImageCompressionSweep.Result result = sweep.compressAll();

        // THEN it reports the mode it used and the pages it touched...
        assertThat(result.mode()).isEqualTo("Sweep JXL");
        assertThat(result.chapters()).isGreaterThanOrEqualTo(2);
        assertThat(result.summary().replaced()).isGreaterThanOrEqualTo(3);
        assertThat(result.describe()).contains("Sweep JXL");
        // ...both chapters' pages are encoded...
        assertThat(imageService.pageUrls(first)).allMatch(url -> url.endsWith(".jxl"));
        assertThat(imageService.pageUrls(second)).allMatch(url -> url.endsWith(".jxl"));
        // ...and the stats of each were repaired in the same run.
        assertThat(chapterRepository.findById(first).orElseThrow().getDiskSize())
                .isEqualTo(imageService.diskSize(first));
        assertThat(chapterRepository.findById(second).orElseThrow().getDiskSize())
                .isEqualTo(imageService.diskSize(second));
        // ...and each records the mode.
        assertThat(chapterRepository.findById(first).orElseThrow().getCompressionMode()).isEqualTo(key);
        assertThat(chapterRepository.findById(second).orElseThrow().getCompressionMode()).isEqualTo(key);
    }

    @Test
    void shouldDoNothingWhenTheLibrarySweepRunsWithTheModeSetToNone()
    {
        // GIVEN
        settingsService.setImageCompressionMode(BuiltInCompressionMode.NONE.getKey());

        // WHEN
        ImageCompressionSweep.Result result = sweep.compressAll();

        // THEN
        assertThat(result.mode()).isNull();
        assertThat(result.chapters()).isZero();
        assertThat(result.summary()).isEqualTo(ImageCompressionService.Summary.NOTHING);
        assertThat(result.describe()).contains("None");
        assertThat(sweep.currentModeName()).isEqualTo("None");
    }

    // ---- one user-started run at a time --------------------------------------

    /**
     * Refused, not queued: the other run may be a sweep with hours to go. No encoder is needed, since the
     * refusal comes first.
     */
    @Test
    void shouldRefuseToCompressAChapterWhileAnotherRunIsInProgress() throws Exception
    {
        // GIVEN a chapter with an unprocessed page, a mode selected, and another run in progress.
        int chapterId = newChapter("Button pressed during a sweep");
        chapterImageService.upload(chapterId, List.of(png("a.png")));
        settingsService.setImageCompressionMode(modeService.save(mode("Busy JXL")));

        try (var otherRun = RunLockHolder.hold(compressionService))
        {
            // WHEN / THEN the call is refused...
            assertThatThrownBy(() -> chapterImageService.compressExisting(chapterId))
                    .isInstanceOf(ImageCompressionService.RunInProgress.class)
                    .hasMessage(ImageCompressionService.BUSY_MESSAGE);
            // ...the button's own entry point says why instead of failing...
            assertThat(chapterImageService.compressExistingAndDescribe(chapterId))
                    .isEqualTo(ImageCompressionService.BUSY_MESSAGE);
        }
        // ...and the page is exactly as it was.
        assertThat(imageService.pageUrls(chapterId)).containsExactly("/data/" + chapterId + "/1.png");
    }

    /** Refusing would throw the user's files away over something "Compress images" can do later. */
    @Test
    void shouldSaveButNotCompressAnUploadWhileAnotherRunIsInProgress() throws Exception
    {
        // GIVEN a chapter, a mode selected, and another run in progress.
        int chapterId = newChapter("Uploaded during a sweep");
        settingsService.setImageCompressionMode(modeService.save(mode("Busy upload JXL")));
        ChapterImageService.UploadResult result;

        try (var otherRun = RunLockHolder.hold(compressionService))
        {
            // WHEN
            result = chapterImageService.upload(chapterId, List.of(png("a.png")));
        }

        // THEN the page is saved as it arrived, with its stats...
        assertThat(result.compressionSkipped()).isTrue();
        assertThat(result.summary()).isEqualTo(ImageCompressionService.Summary.NOTHING);
        assertThat(result.message()).contains(ChapterImageService.UPLOAD_NOT_COMPRESSED_MESSAGE);
        assertThat(imageService.pageUrls(chapterId)).containsExactly("/data/" + chapterId + "/1.png");
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getPageNum()).isEqualTo(1);
    }

    @Test
    void shouldRefuseTheLibrarySweepWhileAnotherRunIsInProgress() throws Exception
    {
        // GIVEN a mode selected and another run in progress.
        settingsService.setImageCompressionMode(modeService.save(mode("Second sweep JXL")));

        try (var otherRun = RunLockHolder.hold(compressionService))
        {
            // WHEN / THEN
            assertThatThrownBy(() -> sweep.compressAll())
                    .isInstanceOf(ImageCompressionService.RunInProgress.class)
                    .hasMessage(ImageCompressionService.BUSY_MESSAGE);
        }
    }

    // ---- helpers -------------------------------------------------------------

    private void assumeTool(String tool)
    {
        Assumptions.assumeTrue(toolLocator.find(tool).isPresent(),
                () -> "No bundled " + tool + " for this platform - skipping");
    }

    private int newChapter(String titleFull)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        int id = chapterService.create(form);
        created.add(id);
        return id;
    }

    private static MockMultipartFile png(String filename)
    {
        return new MockMultipartFile("files", filename, "image/png", TestImages.png(SIZE, SIZE));
    }

    private static ImageCompressionMode mode(String name)
    {
        var mode = new ImageCompressionMode();
        mode.setName(name);
        mode.setEncoder(ImageEncoder.JXL);
        mode.setEncoderArgs("-q 40 -e 1");
        return mode;
    }
}

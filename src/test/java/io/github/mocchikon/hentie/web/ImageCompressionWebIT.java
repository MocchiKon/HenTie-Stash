package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.RunLockHolder;
import io.github.mocchikon.hentie.TestImages;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.CompressionModeOption;
import io.github.mocchikon.hentie.dto.JxlDelivery;
import io.github.mocchikon.hentie.entity.ImageCompressionMode;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.entity.ViewMode;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.ImageCompressionModeRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Not transactional: settings and mode rows are written through the real request path and the settings cache
 * is a shared singleton, so everything changed is put back in {@code @AfterEach}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImageCompressionWebIT
{
    @Autowired MockMvc mvc;
    @Autowired SettingsService settingsService;
    @Autowired ImageCompressionModeService modeService;
    @Autowired ImageCompressionModeRepository modeRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired ChapterService chapterService;
    @Autowired ImageService imageService;
    @Autowired ImageCompressionService compressionService;

    private final List<Integer> chapters = new ArrayList<>();

    @AfterEach
    void restoreDefaults()
    {
        settingsService.setImageCompressionMode(BuiltInCompressionMode.NONE.getKey());
        settingsService.setSystemImageToolsEnabled(false);
        settingsService.setJxlDelivery(JxlDelivery.AUTO);
        modeRepository.deleteAll();
        queueRepository.deleteAll();
        chapters.forEach(chapterService::delete);
        chapters.clear();
    }

    // ---- the dropdowns -------------------------------------------------------

    @Test
    void shouldOfferTheCompressionModesOnTheSettingsPage() throws Exception
    {
        // GIVEN a user-defined mode alongside the built-in ones.
        modeService.save(mode("Web mode"));

        // WHEN
        mvc.perform(get("/settings").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(view().name("settings"))
                // THEN None + three built-ins + one user mode + Custom.
                .andExpect(model().attribute("compressionModes", hasSize(6)))
                .andExpect(model().attribute("compressionMode", BuiltInCompressionMode.NONE.getKey()))
                .andExpect(model().attribute("jxlDelivery", JxlDelivery.AUTO))
                .andExpect(model().attribute("systemImageTools", false))
                // ...and the markup app.js hooks onto is emitted, including the flag that tells "Custom"
                // (which navigates) from a mode (which is selected).
                .andExpect(content().string(containsString("compression-mode-select")))
                .andExpect(content().string(containsString("data-custom=\"true\"")))
                .andExpect(content().string(containsString("data-custom=\"false\"")));
    }

    /** The previous paste's choice, carried over silently, could re-encode a gallery the user meant to keep. */
    @Test
    void shouldStartAtTheSettingsModeOnTheDownloadPageRatherThanThePreviousPastes() throws Exception
    {
        // GIVEN a Settings mode, and a paste that chose another one.
        settingsService.setImageCompressionMode(BuiltInCompressionMode.HIGH_REDUCTION.getKey());
        mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                        .param("links", "mock:9011")
                        .param("compressionMode", BuiltInCompressionMode.LOSSLESS.getKey()))
                .andExpect(status().is3xxRedirection());

        // WHEN the download page is opened again.
        mvc.perform(get("/chapter/download").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(view().name("chapter-download"))
                // THEN it offers the same list and preselects the Settings mode, not the last paste's...
                .andExpect(model().attribute("compressionMode", BuiltInCompressionMode.HIGH_REDUCTION.getKey()))
                .andExpect(model().attribute("compressionModes", hasSize(5)))
                // ...and the option really renders as the selected one.
                .andExpect(content().string(containsString(
                        "value=\"" + BuiltInCompressionMode.HIGH_REDUCTION.getKey() + "\" data-custom=\"false\" selected")));
    }

    /** The stale key would match no option and leave the browser to select the first one. */
    @Test
    void shouldStartAtNoneOnTheDownloadPageWhenTheSettingsModeWasDeleted() throws Exception
    {
        // GIVEN Settings pointing at a user-defined mode that is then deleted.
        String key = modeService.save(mode("Deleted default"));
        settingsService.setImageCompressionMode(key);
        modeService.delete(ImageCompressionModeService.customId(key));

        // WHEN + THEN
        mvc.perform(get("/chapter/download").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("compressionMode", BuiltInCompressionMode.NONE.getKey()));
    }

    @Test
    void shouldSaveTheCompressionSettingsWhenTheSettingsFormIsSubmitted() throws Exception
    {
        // GIVEN a user-defined mode to point the setting at.
        String key = modeService.save(mode("Chosen"));

        // WHEN
        mvc.perform(post("/settings").with(user("user")).with(csrf())
                        .param("viewMode", ViewMode.FIT.name())
                        .param("titleDisplayMode", "FULL")
                        .param("compressionMode", key)
                        .param("systemImageTools", "true")
                        .param("jxlDelivery", JxlDelivery.ALWAYS.name()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings?saved"));

        // THEN all three land in the settings store.
        assertThat(settingsService.getImageCompressionMode()).isEqualTo(key);
        assertThat(settingsService.isSystemImageToolsEnabled()).isTrue();
        assertThat(settingsService.getJxlDelivery()).isEqualTo(JxlDelivery.ALWAYS);
    }

    /** An unticked checkbox is absent from the body, which must mean "off", not "unchanged". */
    @Test
    void shouldTurnSystemToolsOffWhenTheCheckboxIsNotSubmitted() throws Exception
    {
        // GIVEN it is currently on.
        settingsService.setSystemImageToolsEnabled(true);

        // WHEN the form is submitted without the checkbox.
        mvc.perform(post("/settings").with(user("user")).with(csrf())
                        .param("viewMode", ViewMode.FIT.name())
                        .param("titleDisplayMode", "FULL")
                        .param("compressionMode", BuiltInCompressionMode.NONE.getKey())
                        .param("jxlDelivery", JxlDelivery.AUTO.name()))
                .andExpect(status().is3xxRedirection());

        // THEN
        assertThat(settingsService.isSystemImageToolsEnabled()).isFalse();
    }

    // ---- queueing with a mode -------------------------------------------------

    @Test
    void shouldCarryTheChosenModeOntoTheQueuedRowsWhenLinksArePasted() throws Exception
    {
        // WHEN links are queued from the download form with a mode.
        mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                        .param("links", "mock:9001\nmock:9002")
                        .param("compressionMode", BuiltInCompressionMode.LOSSLESS.getKey()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/queue"));

        // THEN every row carries it.
        assertThat(queueRepository.findAll())
                .hasSize(2)
                .allMatch(item -> BuiltInCompressionMode.LOSSLESS.getKey().equals(item.getCompressionMode()));
    }

    /** A form submitted without the field at all - no JavaScript, an old bookmark - means "None". */
    @Test
    void shouldQueueWithoutCompressionWhenTheFormCarriesNoMode() throws Exception
    {
        // WHEN
        mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                        .param("links", "mock:9003"))
                .andExpect(status().is3xxRedirection());

        // THEN
        assertThat(queueRepository.findAll())
                .hasSize(1)
                .allMatch(item -> BuiltInCompressionMode.NONE.getKey().equals(item.getCompressionMode()));
    }

    // ---- the custom-mode pages ------------------------------------------------

    @Test
    void shouldCreateAModeFromTheCustomModeForm() throws Exception
    {
        // WHEN
        mvc.perform(post("/compression-modes").with(user("user")).with(csrf())
                        .param("name", "From the form")
                        .param("encoder", ImageEncoder.AVIF.name())
                        .param("encoderArgs", "-q 55 -s 2")
                        .param("magickArgs", "-resize 80%")
                        .param("minRelativeReduction", "25")
                        .param("minAbsoluteReduction", "64")
                        .param("formats", "JPG,PNG"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/compression-modes"));

        // THEN every field is stored, and it joins the dropdown.
        ImageCompressionMode saved = modeRepository.findFirstByNameIgnoreCase("From the form").orElseThrow();
        assertThat(saved.getEncoder()).isEqualTo(ImageEncoder.AVIF);
        assertThat(saved.getEncoderArgs()).isEqualTo("-q 55 -s 2");
        assertThat(saved.getMagickArgs()).isEqualTo("-resize 80%");
        assertThat(saved.getMinRelativeReduction()).isEqualTo(25);
        assertThat(saved.getMinAbsoluteReduction()).isEqualTo(64);
        assertThat(saved.getFormats()).isEqualTo("JPG,PNG");
        assertThat(modeService.options()).extracting(CompressionModeOption::label).contains("From the form");
    }

    @Test
    void shouldEditAnExistingModeWithoutCreatingASecondOne() throws Exception
    {
        // GIVEN
        String key = modeService.save(mode("Editable"));
        Integer id = ImageCompressionModeService.customId(key);

        // WHEN the edit form is submitted with the id.
        mvc.perform(post("/compression-modes").with(user("user")).with(csrf())
                        .param("id", String.valueOf(id))
                        .param("name", "Edited")
                        .param("encoder", ImageEncoder.JXL.name())
                        .param("encoderArgs", "-q 90")
                        .param("magickArgs", "")
                        .param("minRelativeReduction", "0")
                        .param("minAbsoluteReduction", "0")
                        .param("formats", ""))
                .andExpect(status().is3xxRedirection());

        // THEN there is still one mode, under its original key.
        assertThat(modeRepository.findAll()).hasSize(1);
        assertThat(modeService.resolve(key).orElseThrow().name()).isEqualTo("Edited");
    }

    /** A duplicate name is a field error the user can fix, not a 500 out of a dead transaction. */
    @Test
    void shouldRejectADuplicateNameOnTheFormRatherThanFailing() throws Exception
    {
        // GIVEN
        modeService.save(mode("Taken"));

        // WHEN
        mvc.perform(post("/compression-modes").with(user("user")).with(csrf())
                        .param("name", "taken")
                        .param("encoder", ImageEncoder.JXL.name())
                        .param("encoderArgs", "-q 90")
                        .param("magickArgs", "")
                        .param("minRelativeReduction", "0")
                        .param("minAbsoluteReduction", "0")
                        .param("formats", ""))
                // THEN the form comes back with the error on the name field, and nothing was created.
                .andExpect(status().isOk())
                .andExpect(view().name("compression-mode-form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"));
        assertThat(modeRepository.findAll()).hasSize(1);
    }

    @Test
    void shouldRejectABlankNameOnTheForm() throws Exception
    {
        // WHEN
        mvc.perform(post("/compression-modes").with(user("user")).with(csrf())
                        .param("name", "  ")
                        .param("encoder", ImageEncoder.JXL.name())
                        .param("encoderArgs", "")
                        .param("magickArgs", "")
                        .param("minRelativeReduction", "0")
                        .param("minAbsoluteReduction", "0")
                        .param("formats", ""))
                // THEN
                .andExpect(status().isOk())
                .andExpect(view().name("compression-mode-form"))
                .andExpect(model().attributeHasFieldErrors("form", "name"));
        assertThat(modeRepository.findAll()).isEmpty();
    }

    @Test
    void shouldListAndDeleteUserDefinedModes() throws Exception
    {
        // GIVEN
        String key = modeService.save(mode("Disposable"));

        // WHEN the list is opened...
        mvc.perform(get("/compression-modes").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(view().name("compression-modes"))
                .andExpect(model().attribute("modes", hasSize(1)));

        // ...and the mode deleted.
        mvc.perform(post("/compression-modes/" + ImageCompressionModeService.customId(key) + "/delete")
                        .with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/compression-modes"));

        // THEN it is gone, and the dropdown is back to the built-ins plus Custom.
        assertThat(modeRepository.findAll()).isEmpty();
        assertThat(modeService.options()).hasSize(5);
    }

    @Test
    void shouldServeTheNewModeFormWithTheEncodersToChooseFrom() throws Exception
    {
        // WHEN + THEN
        mvc.perform(get("/compression-modes/new").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(view().name("compression-mode-form"))
                .andExpect(model().attribute("encoders", ImageEncoder.values()));
    }

    @Test
    void shouldRedirectToTheListWhenEditingAModeThatNoLongerExists() throws Exception
    {
        // WHEN + THEN
        mvc.perform(get("/compression-modes/999999/edit").with(user("user")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/compression-modes"));
    }

    // ---- the maintenance sweep ------------------------------------------------

    /** Says so rather than reporting a walk it did not make. */
    @Test
    void shouldReportNothingToDoWhenTheBulkCompressRunsWithTheModeSetToNone() throws Exception
    {
        // GIVEN the default Settings mode.
        assertThat(settingsService.getImageCompressionMode()).isEqualTo(BuiltInCompressionMode.NONE.getKey());

        // WHEN
        mvc.perform(post("/manage/compress-images").with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#maintenance"))
                .andExpect(result -> assertThat(result.getFlashMap().get("compressResult"))
                        .asString().contains("None"));
    }

    @Test
    void shouldNameTheCurrentModeOnTheManagePage() throws Exception
    {
        // GIVEN
        settingsService.setImageCompressionMode(BuiltInCompressionMode.HIGH_REDUCTION.getKey());

        // WHEN + THEN
        mvc.perform(get("/manage").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("compressionModeName", "High reduction"));
    }

    /** Asserted on the rendered page, because only the template can go wrong here. */
    @Test
    void shouldShowTheCompressionModeBesideAWaitingLinkOnTheQueuePage() throws Exception
    {
        // GIVEN one link queued with a named mode and one with None.
        String key = modeService.save(mode("Queue label"));
        mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                        .param("links", "mock:9101").param("compressionMode", key))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                        .param("links", "mock:9102")
                        .param("compressionMode", BuiltInCompressionMode.NONE.getKey()))
                .andExpect(status().is3xxRedirection());

        // WHEN + THEN the named one is labelled and the None one says nothing at all.
        mvc.perform(get("/chapter/queue").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(view().name("download-queue"))
                .andExpect(content().string(containsString("compressing: Queue label")))
                .andExpect(content().string(not(containsString("compressing: None"))));
    }

    /** Each optional part of the summary line is a Thymeleaf conditional of its own. */
    @Test
    void shouldRenderEveryPartOfAModeSummaryOnTheListPage() throws Exception
    {
        // GIVEN a mode with every optional field set.
        var full = mode("Fully specified");
        full.setEncoder(ImageEncoder.AVIF);
        full.setMagickArgs("-resize 50%");
        full.setMinRelativeReduction(20);
        full.setMinAbsoluteReduction(64);
        full.setFormats("JPG,PNG");
        modeService.save(full);

        // WHEN + THEN
        mvc.perform(get("/compression-modes").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fully specified")))
                .andExpect(content().string(containsString("AVIF")))
                .andExpect(content().string(containsString("-resize 50%")))
                .andExpect(content().string(containsString("only JPG,PNG")));
    }

    /** ...and a mode with none of them set renders the "all image formats" wording instead. */
    @Test
    void shouldSayAllImageFormatsOnTheListPageWhenAModeRestrictsNothing() throws Exception
    {
        // GIVEN
        modeService.save(mode("Unrestricted"));

        // WHEN + THEN
        mvc.perform(get("/compression-modes").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("all image formats")));
    }

    // ---- the per-chapter button ----------------------------------------------

    @Test
    void shouldCompressUploadedImagesAndReportItWhenUploadingThroughTheForm() throws Exception
    {
        // GIVEN a chapter and the default "None" mode, so this test needs no encoder.
        int chapterId = newChapter("Uploaded through the form");

        // WHEN a page is uploaded.
        mvc.perform(multipart("/chapter/" + chapterId + "/images")
                        .file(new MockMultipartFile("files", "a.png", "image/png", TestImages.png(64, 64)))
                        .with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/" + chapterId + "/edit"));

        // THEN it is stored as it arrived, and the page count follows.
        assertThat(imageService.pageUrls(chapterId)).containsExactly("/data/" + chapterId + "/1.png");
        assertThat(imageService.pageCount(chapterId)).isEqualTo(1);
    }

    @Test
    void shouldReportNothingToDoWhenCompressingOneChapterWithTheModeSetToNone() throws Exception
    {
        // GIVEN a chapter with one page.
        int chapterId = newChapter("Compress button");
        mvc.perform(multipart("/chapter/" + chapterId + "/images")
                        .file(new MockMultipartFile("files", "a.png", "image/png", TestImages.png(64, 64)))
                        .with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // WHEN
        mvc.perform(post("/chapter/" + chapterId + "/images/compress").with(user("user")).with(csrf()))
                // THEN it returns to the images section with a message saying nothing was re-encoded.
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/" + chapterId + "/edit#images"))
                .andExpect(result -> assertThat(result.getFlashMap().get("compressed"))
                        .isEqualTo(ImageCompressionService.NO_MODE_MESSAGE));
        // AND the page is exactly as it was.
        assertThat(imageService.pageUrls(chapterId)).containsExactly("/data/" + chapterId + "/1.png");
    }

    /** Another run holding the lock (a sweep in another tab) gives a message, not an error page. */
    @Test
    void shouldReportAnotherRunInProgressWhenTheLibrarySweepIsStartedDuringOne() throws Exception
    {
        // GIVEN a mode selected and another run in progress.
        settingsService.setImageCompressionMode(modeService.save(mode("Busy sweep")));

        try (var otherRun = RunLockHolder.hold(compressionService))
        {
            // WHEN
            mvc.perform(post("/manage/compress-images").with(user("user")).with(csrf()))
                    // THEN
                    .andExpect(status().is3xxRedirection())
                    .andExpect(result -> assertThat(result.getFlashMap().get("compressResult"))
                            .isEqualTo(ImageCompressionService.BUSY_MESSAGE));
        }
    }

    @Test
    void shouldReportAnotherRunInProgressWhenCompressingOneChapterDuringOne() throws Exception
    {
        // GIVEN a chapter, a mode selected, and another run in progress.
        int chapterId = newChapter("Button during a sweep");
        settingsService.setImageCompressionMode(modeService.save(mode("Busy button")));

        try (var otherRun = RunLockHolder.hold(compressionService))
        {
            // WHEN
            mvc.perform(post("/chapter/" + chapterId + "/images/compress").with(user("user")).with(csrf()))
                    // THEN
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/" + chapterId + "/edit#images"))
                    .andExpect(result -> assertThat(result.getFlashMap().get("compressed"))
                            .isEqualTo(ImageCompressionService.BUSY_MESSAGE));
        }
    }

    /** Named because uploading blocks until the encoding is done. */
    @Test
    void shouldNameTheCurrentModeOnTheChapterEditPage() throws Exception
    {
        // GIVEN
        settingsService.setImageCompressionMode(BuiltInCompressionMode.LOSSLESS.getKey());
        int chapterId = newChapter("Named on the edit page");

        // WHEN + THEN
        mvc.perform(get("/chapter/" + chapterId + "/edit").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("compressionModeName", "Lossless"));
    }

    private int newChapter(String titleFull)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        int id = chapterService.create(form);
        chapters.add(id);
        return id;
    }

    private static ImageCompressionMode mode(String name)
    {
        var mode = new ImageCompressionMode();
        mode.setName(name);
        mode.setEncoder(ImageEncoder.JXL);
        mode.setEncoderArgs("-q 90 -e 1");
        return mode;
    }
}

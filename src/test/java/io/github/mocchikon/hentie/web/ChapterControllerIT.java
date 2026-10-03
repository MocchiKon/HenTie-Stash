package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.TestDownloads;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.ChapterViewModel;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.service.*;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Commits to the shared DB, so titles are unique to this suite. Error branches are in {@code ErrorHandlingIT}. */
@SpringBootTest
@AutoConfigureMockMvc
class ChapterControllerIT
{
    @Autowired MockMvc mvc;
    @Autowired ChapterService chapterService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired ImageService imageService;
    @Autowired ImageDirectory imageDirectory;
    @Autowired DownloadQueueRepository downloadQueueRepository;
    @Autowired SettingsService settingsService;
    @Autowired SeriesService seriesService;

    private int newChapter(String titleFull)
    {
        return newChapter(titleFull, null);
    }

    private int newChapter(String titleFull, String galleryId)
    {
        return newChapter(titleFull, galleryId, "English");
    }

    private int newChapter(String titleFull, String galleryId, String language)
    {
        ChapterForm form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage(language);
        form.setGalleryId(galleryId);
        return chapterService.create(form);
    }

    @Test
    void shouldRenderNewChapterForm() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/chapter/new").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("chapter-new"))
                .andExpect(model().attributeExists("form", "statuses", "imageAccept"));
    }

    @Test
    void shouldRedirectToNewChapterWhenCreated() throws Exception
    {
        // WHEN
        mvc.perform(post("/chapter").with(user("user")).with(csrf())
                        .param("titleFull", "chw-create-ok").param("language", "English"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/chapter/*"));

        // THEN
        assertThat(chapterRepository.findAll())
                .extracting("titleFull").contains("chw-create-ok");
    }

    @Test
    void shouldShowValidationErrorsInsteadOfRedirectingWhenTitleFullIsBlank() throws Exception
    {
        // WHEN
        mvc.perform(post("/chapter").with(user("user")).with(csrf())
                        .param("titleFull", "").param("language", "English"))
                .andExpect(status().isOk())
                .andExpect(view().name("chapter-new"))
                .andExpect(model().attributeHasFieldErrors("form", "titleFull"));

        // THEN
        assertThat(chapterRepository.findAll()).extracting("titleFull").doesNotContain("");
    }

    @Test
    void shouldRenderChapterWhenChapterExists() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-view");

        // WHEN
        ResultActions result = mvc.perform(get("/chapter/" + id).with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("chapter-view"))
                .andExpect(model().attributeExists("vm"));
    }

    /**
     * Asserted on the markup because the split happens in the template and the three spans must stay glued
     * together: a newline between them would render as a space inside the title.
     */
    @Test
    void shouldMuteTheUnsharedPartOfTheHeadingWhenPrettyTitleIsContained() throws Exception
    {
        // GIVEN a full title whose pretty form is the bracket-free middle of it.
        ChapterForm form = new ChapterForm();
        form.setTitleFull("[chw-head] Isekai Yuusha (Alpha)");
        form.setLanguage("English");
        int id = chapterService.create(form);

        // WHEN
        ResultActions result = mvc.perform(get("/chapter/" + id).with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<span class=\"title-decoration\">[chw-head] </span>"
                                + "<span>Isekai Yuusha</span>"
                                + "<span class=\"title-decoration\"> (Alpha)</span>")));
    }

    @Test
    void shouldMuteNothingInTheHeadingWhenTitlesAreIdentical() throws Exception
    {
        // GIVEN a title with no decoration to drop, so the pretty title equals it.
        int id = newChapter("chw-head-plain");

        // WHEN
        String html = mvc.perform(get("/chapter/" + id).with(user("user")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // THEN the whole title keeps the normal colour.
        assertThat(html).contains("<span>chw-head-plain</span>").doesNotContain("title-decoration");
    }

    @Test
    void shouldExposeViewModeModelWhenImageViewRequested() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-imageview");

        // WHEN
        ResultActions result = mvc.perform(get("/chapter/" + id + "/view").with(user("user")).param("page", "2"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("image-view"))
                .andExpect(model().attribute("startPage", 2))
                .andExpect(model().attributeExists("defaultViewMode", "viewModes"));
    }

    @Test
    void shouldTellTheImageViewerHowManyPagesToPrepareAhead() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-imageview-ahead");
        settingsService.setPagesAhead(9);
        try
        {
            // WHEN
            var html = mvc.perform(get("/chapter/" + id + "/view").with(user("user")))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("pagesAhead", 9))
                    .andReturn().getResponse().getContentAsString();

            // THEN the page script gets it too
            assertThat(html).contains("pagesAhead: 9,");
        }
        finally
        {
            // A shared singleton: restore the default for later suites.
            settingsService.setPagesAhead(SettingsService.DEFAULT_PAGES_AHEAD);
        }
    }

    @Test
    void shouldRenderFormWhenEditingExistingChapter() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-edit");

        // WHEN
        ResultActions result = mvc.perform(get("/chapter/" + id + "/edit").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("chapter-edit"))
                .andExpect(model().attributeExists("form", "pageUrls"));
    }

    @Test
    void shouldYield404WhenChapterMissing() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/chapter/987654").with(user("user")));

        // THEN
        result.andExpect(status().isNotFound());
    }

    @Test
    void shouldRenderDownloadPage() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/chapter/download").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("chapter-download"));
    }

    /** An absent field (the unticked box, or a hand-made POST) means off. */
    @Test
    void shouldCarryTheDuplicateTitleChoiceFromTheDownloadFormOntoTheQueuedRow() throws Exception
    {
        // GIVEN + WHEN the form is rendered
        mvc.perform(get("/chapter/download").with(user("user")))
                // THEN the box is there, off by default.
                .andExpect(content().string(containsString("name=\"avoidDuplicateTitles\" value=\"true\">")))
                .andExpect(content().string(containsString("Avoid duplicated titles from other sources")));
        try
        {
            // WHEN one link is queued with it ticked and one without.
            mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                            .param("links", "mock:8003").param("avoidDuplicateTitles", "true"))
                    .andExpect(status().is3xxRedirection());
            mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                            .param("links", "mock:8004"))
                    .andExpect(status().is3xxRedirection());

            // THEN each row says what its own paste asked for.
            assertThat(downloadQueueRepository.findByLink("mock:8003").orElseThrow().isAvoidDuplicateTitles())
                    .isTrue();
            assertThat(downloadQueueRepository.findByLink("mock:8004").orElseThrow().isAvoidDuplicateTitles())
                    .isFalse();
        }
        finally
        {
            // This suite commits, and these two links are never going to be downloadable.
            downloadQueueRepository.findByLink("mock:8003").ifPresent(downloadQueueRepository::delete);
            downloadQueueRepository.findByLink("mock:8004").ifPresent(downloadQueueRepository::delete);
        }
    }

    @Test
    void shouldRedirectToTheQueueWhenDownloadSubmitted() throws Exception
    {
        // GIVEN two distinct links the mock source recognizes, one of them pasted twice.
        // WHEN
        ResultActions result = mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                        .param("links", "mock:8001\nmock:8002\nmock:8001"));

        // THEN the user lands on the queue, the only page that can say what became of the links, and the
        // duplicate line was collapsed.
        try
        {
            var flash = result.andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/queue"))
                    .andReturn().getFlashMap();
            var enqueued = (DownloadQueueService.EnqueueResult) flash.get("enqueued");
            assertThat(enqueued.accepted()).isEqualTo(2);
            assertThat(enqueued.requeued()).isZero();
            assertThat(enqueued.rejected()).isZero();
            assertThat(downloadQueueRepository.findByLink("mock:8001")).isPresent();
        }
        finally
        {
            // This suite commits, and these two links are never going to be downloadable.
            downloadQueueRepository.findByLink("mock:8001").ifPresent(downloadQueueRepository::delete);
            downloadQueueRepository.findByLink("mock:8002").ifPresent(downloadQueueRepository::delete);
        }
    }

    @Test
    void shouldRenderTheDownloadQueue() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/chapter/queue").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("download-queue"))
                .andExpect(model().attributeExists("progress", "pending", "failed"));
    }

    /**
     * An interrupted download renders fine, so the badge is all that says pages are missing. Asserted on the
     * markup, since a model flag would not catch a template that never shows it.
     */
    @Test
    void shouldBadgeTheChapterAsIncompleteWhenItsDownloadNeverFinished() throws Exception
    {
        // GIVEN a chapter whose download is still PENDING, and one whose is not.
        int pending = newChapter("chw-download-pending");
        chapterService.setDownloadStatus(pending, DownloadStatus.PENDING);
        int finished = newChapter("chw-download-done");
        chapterService.setDownloadStatus(finished, DownloadStatus.SUCCESSFUL);

        // WHEN each detail page is rendered.
        String pendingHtml = mvc.perform(get("/chapter/" + pending).with(user("user")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String finishedHtml = mvc.perform(get("/chapter/" + finished).with(user("user")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // THEN only the unfinished one carries the badge.
        assertThat(pendingHtml).contains("Incomplete download");
        assertThat(finishedHtml).doesNotContain("Incomplete download");
    }

    // --- Compressed chapters: the label and the full-quality re-download ---------------

    /** Asserted on the markup, since a model flag would not catch a template that never shows them. */
    @Test
    void shouldLabelACompressedChapterAndOfferToDownloadItAgainInFullQuality() throws Exception
    {
        // GIVEN a compressed chapter from the mock source, and an uncompressed one.
        int compressed = newChapter("chw-fq-compressed", "mock:chw-fq-compressed");
        chapterService.setCompressionMode(compressed, BuiltInCompressionMode.LOSSLESS.getKey());
        int plain = newChapter("chw-fq-plain", "mock:chw-fq-plain");

        // WHEN each detail page is rendered.
        String compressedHtml = mvc.perform(get("/chapter/" + compressed).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("compressedWith", "Lossless"))
                .andExpect(model().attribute("canRedownloadFullQuality", true))
                .andReturn().getResponse().getContentAsString();
        String plainHtml = mvc.perform(get("/chapter/" + plain).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("compressedWith", (Object) null))
                .andExpect(model().attribute("canRedownloadFullQuality", false))
                .andReturn().getResponse().getContentAsString();

        // THEN only the compressed one carries both.
        assertThat(compressedHtml).contains("Compressed: Lossless")
                .contains("Re-download in full quality")
                .contains("action=\"/chapter/" + compressed + "/redownload\"");
        assertThat(plainHtml).doesNotContain("Compressed:").doesNotContain("Re-download in full quality");
    }

    @Test
    void shouldLinkTheGalleryIdToItsPageOnTheSourcesWebsite() throws Exception
    {
        // GIVEN a chapter from nhentai, and one from the mock source, which has no website.
        int fromSite = newChapter("chw-source-site", "nhentai:990001");
        int fromMock = newChapter("chw-source-mock", "mock:chw-source-mock");

        // WHEN
        String siteHtml = mvc.perform(get("/chapter/" + fromSite).with(user("user")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String mockHtml = mvc.perform(get("/chapter/" + fromMock).with(user("user")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // THEN
        assertThat(siteHtml).contains("href=\"https://nhentai.net/g/990001/\"").contains("#nhentai:990001");
        assertThat(mockHtml).contains("<span class=\"meta-gallery\">#mock:chw-source-mock</span>")
                .doesNotContain("class=\"meta-gallery\" href=");
    }

    /** Still labelled, since it is compressed, but offered no re-download. */
    @Test
    void shouldLabelButOfferNothingForACompressedChapterAddedByHand() throws Exception
    {
        // GIVEN
        int byHand = newChapter("chw-fq-by-hand");
        chapterService.setCompressionMode(byHand, BuiltInCompressionMode.HIGH_REDUCTION.getKey());

        // WHEN
        String html = mvc.perform(get("/chapter/" + byHand).with(user("user")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // THEN
        assertThat(html).contains("Compressed: High reduction").doesNotContain("Re-download in full quality");
    }

    /** Must not read "None", which would claim the pages are full quality. */
    @Test
    void shouldSayTheModeWasDeletedWhenACompressedChaptersModeNoLongerExists() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-fq-deleted-mode");
        chapterService.setCompressionMode(id, "custom:987654");

        // WHEN + THEN
        mvc.perform(get("/chapter/" + id).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("compressedWith", "a mode since deleted"))
                .andExpect(content().string(containsString("Compressed: a mode since deleted")));
    }

    @Test
    void shouldQueueTheRedownloadAndShowTheQueueWhenTheButtonIsPressed() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-fq-queue", "mock:chw-fq-queue");
        chapterService.setCompressionMode(id, BuiltInCompressionMode.LOSSLESS.getKey());
        try
        {
            // WHEN
            mvc.perform(post("/chapter/" + id + "/redownload").with(user("user")).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/queue"))
                    .andExpect(flash().attribute("redownloadQueued", id));

            // THEN the gallery's row asks for its pages again, uncompressed...
            DownloadQueueItem item = downloadQueueRepository.findByLink("mock:chw-fq-queue").orElseThrow();
            assertThat(item.isReplacePages()).isTrue();
            assertThat(item.getCompressionMode()).isEqualTo(BuiltInCompressionMode.NONE.getKey());
            assertThat(item.getGalleryId()).isEqualTo("mock:chw-fq-queue");
            // ...and the queue page says what it is.
            mvc.perform(get("/chapter/queue").with(user("user")).flashAttr("redownloadQueued", id))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Queued a full-quality re-download")))
                    .andExpect(content().string(containsString("re-downloading in full quality")));
        }
        finally
        {
            // Committed: left pending, a later suite's worker would take it.
            downloadQueueRepository.findByLink("mock:chw-fq-queue").ifPresent(downloadQueueRepository::delete);
        }
    }

    /** The button is hidden then, but a page left open while the chapter changed could still send it. */
    @Test
    void shouldRefuseTheRedownloadOfAChapterThatIsNotCompressed() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-fq-refused", "mock:chw-fq-refused");

        // WHEN
        mvc.perform(post("/chapter/" + id + "/redownload").with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/" + id))
                .andExpect(flash().attribute("redownloadError", containsString("cannot be downloaded again")));

        // THEN
        assertThat(downloadQueueRepository.findByLink("mock:chw-fq-refused")).isEmpty();
    }

    /** The language is already a chip among the metadata, so it is not a badge as well. */
    @Test
    void shouldShowTheLanguageAsAChipOnly() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-language-chip");

        // WHEN
        String html = mvc.perform(get("/chapter/" + id).with(user("user")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // THEN
        assertThat(html).contains("<span>English</span>").doesNotContain("class=\"badge\">English<");
    }

    // --- The image viewer stepping into the next or previous chapter --------------------

    /** Must agree with the detail page's Next: same series, same language, in reading order. */
    @Test
    void shouldNameTheNeighbouringChaptersOfTheSameLanguageInTheSeries() throws Exception
    {
        // GIVEN a series holding two English chapters and, between them, a Japanese one.
        int first = newChapter("chw-step-first");
        int japanese = newChapter("chw-step-japanese", null, "Japanese");
        int second = newChapter("chw-step-second");
        int standalone = newChapter("chw-step-standalone");
        var series = new SeriesForm();
        series.setTitleFull("chw-step-series");
        series.setStatus(Status.REVIEWED);
        int seriesId = seriesService.create(series);
        seriesService.addChapters(seriesId, List.of(first, japanese, second));

        // WHEN + THEN - forward from the first, back from the second, and nothing past either end.
        mvc.perform(get("/chapter/" + first + "/neighbour").param("direction", "next").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(second))
                .andExpect(jsonPath("$.title").value("chw-step-second"));
        mvc.perform(get("/chapter/" + second + "/neighbour").param("direction", "previous").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first));
        mvc.perform(get("/chapter/" + second + "/neighbour").param("direction", "next").with(user("user")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/chapter/" + first + "/neighbour").param("direction", "previous").with(user("user")))
                .andExpect(status().isNoContent());
        // A chapter in no series has no neighbours at all.
        mvc.perform(get("/chapter/" + standalone + "/neighbour").param("direction", "next").with(user("user")))
                .andExpect(status().isNoContent());
    }

    /** Stepping on inside the viewer passes no detail page, so this lookup is where the stats heal. */
    @Test
    void shouldRepairTheStoredStatsOfTheChapterItStepsInto() throws Exception
    {
        // GIVEN a series of two chapters whose second has pages the stored stats do not know about.
        int first = newChapter("chw-step-heal-first");
        int second = newChapter("chw-step-heal-second");
        var series = new SeriesForm();
        series.setTitleFull("chw-step-heal-series");
        series.setStatus(Status.REVIEWED);
        seriesService.addChapters(seriesService.create(series), List.of(first, second));
        try
        {
            Path dir = imageDirectory.chapterDir(second);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("1.jpg"), "abc", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("2.jpg"), "de", StandardCharsets.UTF_8);
            assertThat(chapterRepository.findById(second).orElseThrow().getPageNum()).isZero();

            // WHEN
            mvc.perform(get("/chapter/" + first + "/neighbour").param("direction", "next").with(user("user")))
                    .andExpect(status().isOk());

            // THEN
            Chapter repaired = chapterRepository.findById(second).orElseThrow();
            assertThat(repaired.getPageNum()).isEqualTo(2);
            assertThat(repaired.getDiskSize()).isEqualTo(5);
        }
        finally
        {
            imageService.deleteAll(second);
        }
    }

    /** Stepping back lands on the previous chapter's last page, whose number only the server knows. */
    @Test
    void shouldOpenTheImageViewerAtTheLastPageWhenAskedForIt() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-reader-last");
        try
        {
            Path dir = imageDirectory.chapterDir(id);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("1.jpg"), "a", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("2.jpg"), "b", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("3.jpg"), "c", StandardCharsets.UTF_8);

            // WHEN + THEN
            mvc.perform(get("/chapter/" + id + "/view").param("page", "last").with(user("user")))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("startPage", 3))
                    .andExpect(content().string(containsString("id=\"chapter-notice\"")));
            mvc.perform(get("/chapter/" + id + "/view").param("page", "not-a-page").with(user("user")))
                    .andExpect(model().attribute("startPage", 1));
        }
        finally
        {
            imageService.deleteAll(id);
        }
        // A chapter with no pages still opens, on page 1.
        mvc.perform(get("/chapter/987655/view").param("page", "last").with(user("user")))
                .andExpect(model().attribute("startPage", 1));
    }

    @Test
    void shouldRetryOneFailedQueueItemInTheLenientModeFromTheQueuePage() throws Exception
    {
        // GIVEN a failed queue row.
        var item = TestDownloads.queueItem("mock:8003", "mock:8003");
        item.setError("page 2 could not be downloaded");
        item.setAttempts(3);
        int id = downloadQueueRepository.save(item).getId();
        try
        {
            // WHEN the row's "Retry ignoring image errors" button is used.
            mvc.perform(post("/chapter/queue/" + id + "/retry").with(user("user")).with(csrf())
                            .param("ignoreImageErrors", "true"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/queue"));

            // THEN that item is pending again, in the mode that keeps whatever pages download.
            DownloadQueueItem retried = downloadQueueRepository.findById(id).orElseThrow();
            assertThat(retried.getError()).isNull();
            assertThat(retried.getAttempts()).isZero();
            assertThat(retried.isIgnoreImageErrors()).isTrue();
        }
        finally
        {
            downloadQueueRepository.deleteById(id);
        }
    }

    /** Every other retry of such a row would be refused again. The button clears only the flag. */
    @Test
    void shouldOfferRetryAllowingDuplicateTitleOnlyOnAFailedRowCarryingTheFlag() throws Exception
    {
        // GIVEN two failed rows, one queued avoiding duplicated titles.
        var flagged = TestDownloads.queueItem("mock:8005", "mock:8005");
        flagged.setError("Chapter 1 (from other:1) already has the title");
        flagged.setAttempts(1);
        flagged.setAvoidDuplicateTitles(true);
        int flaggedId = downloadQueueRepository.save(flagged).getId();
        var plain = TestDownloads.queueItem("mock:8006", "mock:8006");
        plain.setError("page 2 could not be downloaded");
        plain.setAttempts(3);
        int plainId = downloadQueueRepository.save(plain).getId();
        try
        {
            // WHEN the queue page is rendered.
            String html = mvc.perform(get("/chapter/queue").with(user("user")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            // THEN only the flagged row carries the button that drops the check, and the flag is shown.
            // (Spring Security inserts the CSRF field as the form's first child.)
            String csrfField = "(\\s*<input type=\"hidden\" name=\"_csrf\"[^>]*>)?\\s*";
            assertThat(html).containsPattern("action=\"/chapter/queue/" + flaggedId + "/retry\"[^>]*>"
                    + csrfField + "<input type=\"hidden\" name=\"allowDuplicateTitle\" value=\"true\">");
            assertThat(html).doesNotContainPattern("action=\"/chapter/queue/" + plainId + "/retry\"[^>]*>"
                    + csrfField + "<input type=\"hidden\" name=\"allowDuplicateTitle\"");
            assertThat(html).contains("avoiding duplicated titles");

            // WHEN that button is used.
            mvc.perform(post("/chapter/queue/" + flaggedId + "/retry").with(user("user")).with(csrf())
                            .param("allowDuplicateTitle", "true"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/queue"));

            // THEN the row is pending again, strict about images, with the check off.
            DownloadQueueItem retried = downloadQueueRepository.findById(flaggedId).orElseThrow();
            assertThat(retried.getError()).isNull();
            assertThat(retried.getAttempts()).isZero();
            assertThat(retried.isIgnoreImageErrors()).isFalse();
            assertThat(retried.isAvoidDuplicateTitles()).isFalse();
        }
        finally
        {
            downloadQueueRepository.deleteById(flaggedId);
            downloadQueueRepository.deleteById(plainId);
        }
    }

    @Test
    void shouldTogglePausingFromTheQueuePage() throws Exception
    {
        try
        {
            // WHEN the pause button is submitted...
            mvc.perform(post("/chapter/queue/pause").with(user("user")).with(csrf()).param("paused", "true"))
                    .andExpect(redirectedUrl("/chapter/queue"));

            // THEN the worker holds off, and the flag is persisted so a restart does not undo it.
            assertThat(settingsService.isDownloadPaused()).isTrue();
        }
        finally
        {
            mvc.perform(post("/chapter/queue/pause").with(user("user")).with(csrf()).param("paused", "false"));
        }
    }

    @Test
    void shouldRedirectToSearchAndRemoveChapterWhenDeleted() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-delete");

        // WHEN
        mvc.perform(post("/chapter/" + id + "/delete").with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search"));

        // THEN
        assertThat(chapterRepository.findById(id)).isEmpty();
    }

    @Test
    void shouldRedirectToChapterWhenEditSaved() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-editsave-before");

        // WHEN
        mvc.perform(post("/chapter/" + id).with(user("user")).with(csrf())
                        .param("titleFull", "chw-editsave-after")
                        .param("title", "Renamed")
                        .param("language", "English")
                        .param("status", "REVIEWED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/" + id));

        // THEN
        assertThat(chapterRepository.findById(id).orElseThrow().getTitleFull()).isEqualTo("chw-editsave-after");
    }

    /**
     * The badge shows the stored columns search filters on, so it cannot disagree with search, and the page
     * repairs them when the disk says otherwise.
     */
    @Test
    void shouldRepairStoredStatsWhenChapterOpened() throws Exception
    {
        // GIVEN a chapter whose pages were placed on disk without going through the upload endpoint.
        int id = newChapter("chw-stats-stored");
        try
        {
            Path dir = imageDirectory.chapterDir(id);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("1.jpg"), "abcd", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("2.jpg"), "ef", StandardCharsets.UTF_8);
            assertThat(chapterRepository.findById(id).orElseThrow().getPageNum()).isZero();

            // WHEN the chapter page is opened.
            ResultActions result = mvc.perform(get("/chapter/" + id).with(user("user")));

            // THEN the badge shows the real numbers, and the columns search reads were written to match.
            result.andExpect(status().isOk()).andExpect(view().name("chapter-view"));
            ChapterViewModel vm = (ChapterViewModel) result.andReturn().getModelAndView().getModel().get("vm");
            assertThat(vm.getPageCount()).isEqualTo(2);
            assertThat(vm.getDiskSizeDisplay()).isEqualTo("6 B");
            assertThat(vm.getPageUrls()).hasSize(2);
            Chapter chapter = chapterRepository.findById(id).orElseThrow();
            assertThat(chapter.getPageNum()).isEqualTo(2);
            assertThat(chapter.getDiskSize()).isEqualTo(6L);
        }
        finally
        {
            imageService.deleteAll(id);
        }
    }

    /**
     * The viewer reads no database row, so it does not self-heal the stats (the still-zero {@code page_num}).
     * Every route into it already passes a heal, so a check here would cost a query per open for nothing.
     */
    @Test
    void shouldServeTheImageViewerWithOnlyItsPagesAndTouchNoDatabaseRow() throws Exception
    {
        // GIVEN pages placed on disk without the upload endpoint, so the stored stats are stale.
        int id = newChapter("chw-reader-pages");
        try
        {
            Path dir = imageDirectory.chapterDir(id);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("1.jpg"), "abc", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("2.jpg"), "de", StandardCharsets.UTF_8);
            assertThat(chapterRepository.findById(id).orElseThrow().getPageNum()).isZero();

            // WHEN the reader is opened at a page.
            ResultActions result = mvc.perform(get("/chapter/" + id + "/view").param("page", "2").with(user("user")));

            // THEN it gets the pages, the id and the start page - and no detail view model at all.
            result.andExpect(status().isOk()).andExpect(view().name("image-view"));
            var modelMap = result.andReturn().getModelAndView().getModel();
            assertThat(modelMap.get("pageUrls")).asInstanceOf(list(String.class))
                    .containsExactly("/data/" + id + "/1.jpg", "/data/" + id + "/2.jpg");
            assertThat(modelMap).containsEntry("chapterId", id);
            assertThat(modelMap).containsEntry("startPage", 2);
            assertThat(modelMap).doesNotContainKey("vm");

            // AND the stored stats are untouched.
            Chapter chapter = chapterRepository.findById(id).orElseThrow();
            assertThat(chapter.getPageNum()).isZero();
            assertThat(chapter.getDiskSize()).isZero();
        }
        finally
        {
            imageService.deleteAll(id);
        }
    }

    /**
     * A deliberate trade: a 404 here would need a query per open. The detail page, where a wrong id surfaces,
     * still 404s ({@link #shouldYield404WhenChapterMissing}).
     */
    @Test
    void shouldRenderAnEmptyImageViewerForAnIdThatDoesNotExist() throws Exception
    {
        mvc.perform(get("/chapter/987654/view").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(view().name("image-view"))
                .andExpect(model().attribute("pageUrls", List.of()));
    }

    /** It must update the stored columns, not just the listing: search filters and sorts on them. */
    @Test
    void shouldRepairStoredStatsWhenImagesRescanned() throws Exception
    {
        // GIVEN a chapter whose page was placed on disk without going through the upload endpoint.
        int id = newChapter("chw-rescan-images");
        try
        {
            Path dir = imageDirectory.chapterDir(id);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("1.jpg"), "abcde", StandardCharsets.UTF_8);
            assertThat(chapterRepository.findById(id).orElseThrow().getPageNum()).isZero();

            // WHEN the button is pressed.
            mvc.perform(post("/chapter/" + id + "/images/rescan").with(user("user")).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/" + id + "/edit#images"))
                    .andExpect(flash().attribute("imagesRescanned", true));

            // THEN the stored stats match the file on disk.
            assertThat(chapterRepository.findById(id).orElseThrow().getPageNum()).isEqualTo(1);
            assertThat(chapterRepository.findById(id).orElseThrow().getDiskSize()).isEqualTo(5L);

            // AND the edit page it redirects to reports what happened (the flash attribute renders).
            mvc.perform(get("/chapter/" + id + "/edit").flashAttr("imagesRescanned", true).with(user("user")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("page count and disk size updated")));
        }
        finally
        {
            imageService.deleteAll(id);
        }
    }

    @Test
    void shouldStorePagesAndRedirectToEditWhenImagesUploaded() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-upload");
        try
        {
            // WHEN
            MockMultipartFile file = new MockMultipartFile(
                    "files", "p.jpg", "image/jpeg", "bytes".getBytes(StandardCharsets.UTF_8));
            mvc.perform(multipart("/chapter/" + id + "/images").file(file).with(user("user")).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/" + id + "/edit"));

            // THEN
            assertThat(imageService.pageCount(id)).isEqualTo(1);
        }
        finally
        {
            imageService.deleteAll(id);
        }
    }

    @Test
    void shouldRedirectBackToTheImageSectionWhenTheDeletedImageIsNoPage() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-delete-image");

        // WHEN + THEN
        // No such page exists, so there is no thumbnail to come back to.
        mvc.perform(post("/chapter/" + id + "/images/delete").with(user("user")).with(csrf())
                        .param("filename", "nope.jpg"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/" + id + "/edit#images"));
    }

    /**
     * A chapter can hold hundreds of pages, so a plain-form delete lands where the page was, not at the top.
     * The edit page numbers the thumbnails by position.
     */
    @Test
    void shouldLandWhereTheDeletedImageStoodWhenItIsDeletedByAPlainPost() throws Exception
    {
        // GIVEN a chapter of three pages.
        int id = newChapter("chw-delete-anchor");
        try
        {
            var dir = imageDirectory.chapterDir(id);
            Files.createDirectories(dir);
            for (String page : List.of("1.jpg", "2.jpg", "3.jpg"))
            {
                Files.writeString(dir.resolve(page), "page", StandardCharsets.UTF_8);
            }

            // WHEN + THEN the middle page goes: page 3 moves up into its place, the second thumbnail.
            mvc.perform(post("/chapter/" + id + "/images/delete").with(user("user")).with(csrf())
                            .param("filename", "2.jpg"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/" + id + "/edit#page-2"));
            // ...the last page goes: nothing follows it, so the page before it, now the last.
            mvc.perform(post("/chapter/" + id + "/images/delete").with(user("user")).with(csrf())
                            .param("filename", "3.jpg"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/" + id + "/edit#page-1"));
            // ...the only page left goes: no thumbnail remains, so the section they were in.
            mvc.perform(post("/chapter/" + id + "/images/delete").with(user("user")).with(csrf())
                            .param("filename", "1.jpg"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/" + id + "/edit#images"));

            // AND every delete really happened, stored stats included.
            assertThat(imageService.pageNames(id)).isEmpty();
            var chapter = chapterRepository.findById(id).orElseThrow();
            assertThat(chapter.getPageNum()).isZero();
            assertThat(chapter.getDiskSize()).isZero();
        }
        finally
        {
            imageService.deleteAll(id);
        }
    }

    /** Only a 204 counts as proof for the script, since a redirect is also what an expired login answers. */
    @Test
    void shouldAnswerNoContentAndDeleteThePageWhenDeletedInPlace() throws Exception
    {
        // GIVEN a chapter of two pages, with its stored stats in step with the disk.
        int id = newChapter("chw-delete-in-place");
        try
        {
            var dir = imageDirectory.chapterDir(id);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("1.jpg"), "first", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("2.jpg"), "second", StandardCharsets.UTF_8);
            chapterService.rescanImages(id);

            // WHEN
            mvc.perform(post("/chapter/" + id + "/images/delete").with(user("user")).with(csrf())
                            .param("filename", "1.jpg").param("inPlace", "true"))
                    // THEN no redirect, no body...
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            // ...and the page is gone, with the stored stats following the disk.
            assertThat(imageService.pageNames(id)).containsExactly("2.jpg");
            var chapter = chapterRepository.findById(id).orElseThrow();
            assertThat(chapter.getPageNum()).isEqualTo(1);
            assertThat(chapter.getDiskSize()).isEqualTo(6L);
        }
        finally
        {
            imageService.deleteAll(id);
        }
    }

    /** The in-place delete and the plain-post fallback depend on this markup. */
    @Test
    void shouldNumberTheThumbnailsAndMarkTheirDeleteFormsOnTheEditPage() throws Exception
    {
        // GIVEN a chapter of two pages.
        int id = newChapter("chw-edit-thumbs");
        try
        {
            var dir = imageDirectory.chapterDir(id);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("1.jpg"), "first", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("2.jpg"), "second", StandardCharsets.UTF_8);

            // WHEN
            mvc.perform(get("/chapter/" + id + "/edit").with(user("user")))
                    .andExpect(status().isOk())
                    // THEN each thumbnail carries its position, which the plain-post redirect lands on...
                    .andExpect(content().string(containsString("id=\"page-1\"")))
                    .andExpect(content().string(containsString("id=\"page-2\"")))
                    // ...each delete form the class app.js deletes in place by...
                    .andExpect(content().string(containsString("class=\"confirm-delete page-delete\"")))
                    // ...and the empty note is there, hidden, for the script to show after the last delete.
                    .andExpect(content().string(containsString("<p class=\"empty\" hidden=\"hidden\">No images yet.</p>")));
        }
        finally
        {
            imageService.deleteAll(id);
        }
    }

    // --- Review mode -----------------------------------------------------------

    @Test
    void shouldRenderTheReviewBarWithMarkAsReviewedWhenReviewingANewChapter() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-review-new");

        // WHEN
        ResultActions result = mvc.perform(get("/chapter/" + id).with(user("user")).param("review", "true"));

        // THEN both actions post to this chapter; the bar starts hidden until app.js finds it in the list
        result.andExpect(status().isOk())
                .andExpect(content().string(containsString("data-review-bar hidden")))
                .andExpect(content().string(containsString("action=\"/chapter/" + id + "/mark-reviewed\"")))
                .andExpect(content().string(containsString("action=\"/chapter/" + id + "/delete\"")))
                .andExpect(content().string(containsString("data-review-skip")))
                .andExpect(content().string(containsString("data-review-exit")));
    }

    @Test
    void shouldOfferNoMarkAsReviewedWhenTheReviewedChapterIsNotNew() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-review-done");
        chapterService.markReviewed(id);

        // WHEN / THEN
        mvc.perform(get("/chapter/" + id).with(user("user")).param("review", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-review-bar")))
                .andExpect(content().string(not(containsString("/mark-reviewed"))));
    }

    @Test
    void shouldRenderNoReviewBarOutsideReviewMode() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-review-off");

        // WHEN
        ResultActions result = mvc.perform(get("/chapter/" + id).with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-review-bar"))));
    }

    @Test
    void shouldPromoteANewChapterToReviewedWhenMarkedReviewed() throws Exception
    {
        // GIVEN
        int id = newChapter("chw-mark-reviewed");

        // WHEN
        mvc.perform(post("/chapter/" + id + "/mark-reviewed").with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/" + id));

        // THEN
        assertThat(chapterRepository.findById(id).orElseThrow().getStatus()).isEqualTo(Status.REVIEWED);
    }

    @Test
    void shouldLeaveAFavouriteAloneWhenMarkedReviewed() throws Exception
    {
        // GIVEN a chapter made a favourite while a review page was still open on it
        ChapterForm form = new ChapterForm();
        form.setTitleFull("chw-mark-favourite");
        form.setLanguage("English");
        form.setStatus(Status.REVIEWED_FAVOURITE);
        int id = chapterService.create(form);

        // WHEN
        mvc.perform(post("/chapter/" + id + "/mark-reviewed").with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // THEN it is not demoted
        assertThat(chapterRepository.findById(id).orElseThrow().getStatus()).isEqualTo(Status.REVIEWED_FAVOURITE);
    }
}

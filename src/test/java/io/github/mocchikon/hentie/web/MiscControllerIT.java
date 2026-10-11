package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.TestLogin;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.*;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.entity.ViewMode;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.SeriesService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.scratch.ScratchArea;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Settings writes hit shared singletons and the password file (not rolled back), so everything they touch is
 * restored afterwards for the other suites.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MiscControllerIT
{
    @Autowired MockMvc mvc;
    @Autowired SettingsService settingsService;
    @Autowired AppProperties appProperties;
    @Autowired ChapterService chapterService;
    @Autowired SeriesService seriesService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired ScratchSpace scratchSpace;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TestLogin testLogin;

    @AfterEach
    void restoreDefaults()
    {
        testLogin.restore();
        settingsService.setDefaultViewMode(ViewMode.FIT);
        settingsService.setPagesAhead(SettingsService.DEFAULT_PAGES_AHEAD);
        settingsService.setSearchPageSize(SettingsService.DEFAULT_SEARCH_PAGE_SIZE);
        settingsService.setTitleDisplayMode(TitleDisplayMode.FULL);
        settingsService.setNhentaiApiKey("");
        // The property, not a literal: left on, auto-linking would stay on for every later suite.
        settingsService.setMatchAutoLinkEnabled(appProperties.isMatchAutoLinkDefault());
        settingsService.setMatchThreshold(SettingsService.DEFAULT_MATCH_THRESHOLD);
        // Shared singleton: a folder left set here would move every later suite's staging.
        assertThat(scratchSpace.update(Map.of(ScratchArea.DOWNLOAD_STAGING, "", ScratchArea.COMPRESSION_WORK, "",
                ScratchArea.TRANSCODE_CACHE, ""))).isEmpty();
    }

    private int newChapter(String titleFull)
    {
        ChapterForm form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        return chapterService.create(form);
    }

    private int newSeries(String titleFull)
    {
        SeriesForm f = new SeriesForm();
        f.setTitleFull(titleFull);
        f.setStatus(Status.REVIEWED);
        return seriesService.create(f);
    }

    // --- Settings --------------------------------------------------------------

    @Test
    void shouldExposeAllModelAttributesWhenRenderingSettingsPage() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/settings").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("settings"))
                .andExpect(model().attributeExists("loginRequired", "viewMode", "viewModes", "pagesAhead",
                        "maxPagesAhead", "searchPageSize", "nhentaiApiKey", "titleDisplayMode", "titleDisplayModes"));
    }

    @Test
    void shouldPersistAndRedirectWhenSavingSettings() throws Exception
    {
        // WHEN
        mvc.perform(post("/settings").with(user("user")).with(csrf())
                        .param("loginRequired", "true")
                        .param("viewMode", "ORIGINAL")
                        .param("pagesAhead", "9")
                        .param("searchPageSize", "50")
                        .param("nhentaiApiKey", "web-key")
                        .param("titleDisplayMode", "NATIVE")
                        .param("matchAutoLink", "true")
                        .param("matchThreshold", "88"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings?saved"));

        // THEN
        assertThat(settingsService.getDefaultViewMode()).isEqualTo(ViewMode.ORIGINAL);
        assertThat(settingsService.getPagesAhead()).isEqualTo(9);
        assertThat(settingsService.getSearchPageSize()).isEqualTo(50);
        assertThat(settingsService.getNhentaiApiKey()).isEqualTo("web-key");
        assertThat(settingsService.getTitleDisplayMode()).isEqualTo(TitleDisplayMode.NATIVE);
        assertThat(settingsService.isMatchAutoLinkEnabled()).isTrue();
        assertThat(settingsService.getMatchThreshold()).isEqualTo(88);
    }

    @Test
    void shouldSaveATemporaryFileFolderAndShowWhereFilesGo() throws Exception
    {
        // GIVEN
        // The app's own folder inside the chosen one, so nothing else there is within reach.
        Path staging = Path.of("./target/test-settings-staging").toAbsolutePath().normalize()
                .resolve(scratchSpace.libraryFolder()).resolve("staging");

        // WHEN
        mvc.perform(post("/settings").with(user("user")).with(csrf())
                        .param("viewMode", "FIT")
                        .param("titleDisplayMode", "FULL")
                        .param("downloadStagingDir", "./target/test-settings-staging")
                        .param("compressionWorkDir", "")
                        .param("transcodeCacheDir", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings?saved"))
                .andExpect(flash().attributeCount(0));

        // THEN it is used for every download, compressed or not, and the others stay automatic...
        assertThat(scratchSpace.downloadStaging(true))
                .isEqualTo(new ScratchSpace.Location(staging, ScratchSpace.Origin.SETTINGS));
        assertThat(scratchSpace.downloadStaging(false)).isEqualTo(scratchSpace.downloadStaging(true));
        assertThat(scratchSpace.compressionWork().origin()).isEqualTo(ScratchSpace.Origin.DATA_DIR);
        assertThat(scratchSpace.transcodeCache().origin()).isEqualTo(ScratchSpace.Origin.DATA_DIR);
        // ...and the page shows both the value and where the files actually go.
        mvc.perform(get("/settings").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"./target/test-settings-staging\"")))
                .andExpect(content().string(containsString(staging + " (set in Settings)")));
    }

    /** Staging inside a chapter folder would let "discard staged pages" delete that chapter's images. */
    @Test
    void shouldRefuseATemporaryFileFolderOnTheDataFolderButSaveTheRest() throws Exception
    {
        // WHEN
        mvc.perform(post("/settings").with(user("user")).with(csrf())
                        .param("viewMode", "FIT")
                        .param("titleDisplayMode", "FULL")
                        .param("searchPageSize", "42")
                        .param("downloadStagingDir", Path.of(appProperties.getDataDir(), "12").toString()))
                .andExpect(status().is3xxRedirection())
                // Not "?saved": a green banner above the refusal would read as if the folder took effect.
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("storageError", allOf(containsString("other settings were saved"),
                        containsString("Download staging"))));

        // THEN
        assertThat(settingsService.getSearchPageSize()).isEqualTo(42);
        assertThat(scratchSpace.settingValue(ScratchArea.DOWNLOAD_STAGING)).isEmpty();
        assertThat(scratchSpace.downloadStaging(false).origin()).isEqualTo(ScratchSpace.Origin.DATA_DIR);
    }

    @Test
    void shouldRedirectAndUpdateHashWhenChangingPassword() throws Exception
    {
        // WHEN
        mvc.perform(post("/settings/password").with(user("user")).with(csrf())
                        .param("password", "web-new-password")
                        .param("passwordRepeat", "web-new-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings?passwordChanged"));

        // THEN
        assertThat(passwordEncoder.matches("web-new-password", settingsService.getPasswordHash())).isTrue();
        assertThat(settingsService.isLoginRequired()).isTrue();
    }

    @Test
    void shouldRefuseABlankPasswordAndKeepTheOldOne() throws Exception
    {
        // GIVEN
        String before = settingsService.getPasswordHash();

        // WHEN
        mvc.perform(post("/settings/password").with(user("user")).with(csrf())
                        .param("password", "   ").param("passwordRepeat", "   "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("passwordError", containsString("was not changed")));

        // THEN
        assertThat(settingsService.getPasswordHash()).isEqualTo(before);
    }

    /**
     * The browser compares a repeat field against the first element with the named id, so a second element
     * sharing that id would make every pair of passwords "not match".
     */
    @Test
    void shouldGiveEachFieldARepeatFieldPointsAtAnIdOfItsOwn() throws Exception
    {
        // WHEN
        String html = mvc.perform(get("/settings").with(user("user")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // THEN
        assertThat(html).contains("data-repeat-of=\"password\"");
        assertThat(html.split("id=\"password\"", -1)).hasSize(2);
        assertThat(html).containsPattern("<input[^>]*id=\"password\"");
    }

    /** A mistyped password that login is then required with would lock the user out. */
    @Test
    void shouldRefuseAPasswordThatDoesNotMatchItsRepeat() throws Exception
    {
        // GIVEN
        String before = settingsService.getPasswordHash();

        // WHEN
        mvc.perform(post("/settings/password").with(user("user")).with(csrf())
                        .param("password", "web-new-password").param("passwordRepeat", "web-new-pasword"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("passwordError", containsString("did not match")));

        // THEN
        assertThat(settingsService.getPasswordHash()).isEqualTo(before);
    }

    /** BCrypt reads only the first 72 bytes, so a longer password would protect no more than its start. */
    @Test
    void shouldRefuseAPasswordLongerThanBcryptReads() throws Exception
    {
        // GIVEN
        String before = settingsService.getPasswordHash();
        String tooLong = "a".repeat(SettingsController.MAX_PASSWORD_BYTES + 1);

        // WHEN
        mvc.perform(post("/settings/password").with(user("user")).with(csrf())
                        .param("password", tooLong).param("passwordRepeat", tooLong))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("passwordError", containsString("too long")));

        // THEN
        assertThat(settingsService.getPasswordHash()).isEqualTo(before);
    }

    // --- Turning login on before any password exists -------------------------------

    /** What a fresh install, or one whose password file was deleted, starts from. */
    private void forgetPassword() throws Exception
    {
        Files.deleteIfExists(Path.of(appProperties.getPasswordFile()));
        settingsService.init();
    }

    @Test
    void shouldAskForAPasswordBesideTheLoginBoxWhileNoneIsSet() throws Exception
    {
        // GIVEN
        forgetPassword();

        // WHEN
        ResultActions result = mvc.perform(get("/settings"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(model().attribute("hasPassword", false))
                .andExpect(content().string(containsString("data-login-password-setup")))
                .andExpect(content().string(containsString("name=\"newPasswordRepeat\"")))
                .andExpect(content().string(containsString("Set a password")));
    }

    @Test
    void shouldNotAskForAPasswordBesideTheLoginBoxOnceOneIsSet() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/settings").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(model().attribute("hasPassword", true))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("data-login-password-setup"))))
                .andExpect(content().string(containsString("Change password")));
    }

    @Test
    void shouldSetThePasswordAndRequireLoginWhenTurnedOnWithOne() throws Exception
    {
        // GIVEN
        forgetPassword();

        // WHEN
        mvc.perform(post("/settings").with(csrf())
                        .param("loginRequired", "true")
                        .param("newPassword", "first-password")
                        .param("newPasswordRepeat", "first-password")
                        .param("viewMode", "FIT")
                        .param("titleDisplayMode", "FULL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings?saved"));

        // THEN
        assertThat(settingsService.hasPassword()).isTrue();
        assertThat(passwordEncoder.matches("first-password", settingsService.getPasswordHash())).isTrue();
        assertThat(settingsService.isLoginRequired()).isTrue();
        mvc.perform(get("/search")).andExpect(redirectedUrl("/login"));
    }

    /** Login stays off rather than locking everybody out - and the rest of the form is saved all the same. */
    @Test
    void shouldKeepLoginOffWhenTurnedOnWithoutAPassword() throws Exception
    {
        // GIVEN
        forgetPassword();

        // WHEN
        mvc.perform(post("/settings").with(csrf())
                        .param("loginRequired", "true")
                        .param("viewMode", "ORIGINAL")
                        .param("titleDisplayMode", "FULL"))
                .andExpect(status().is3xxRedirection())
                // Not "?saved": a green banner above the refusal would read as if login had been turned on.
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("loginError", allOf(containsString("other settings were saved"),
                        containsString("login was not turned on"))));

        // THEN
        assertThat(settingsService.hasPassword()).isFalse();
        assertThat(settingsService.isLoginRequired()).isFalse();
        assertThat(settingsService.getDefaultViewMode()).isEqualTo(ViewMode.ORIGINAL);
    }

    @Test
    void shouldKeepLoginOffWhenTheTwoNewPasswordsDiffer() throws Exception
    {
        // GIVEN
        forgetPassword();

        // WHEN
        mvc.perform(post("/settings").with(csrf())
                        .param("loginRequired", "true")
                        .param("newPassword", "first-password")
                        .param("newPasswordRepeat", "frist-password")
                        .param("viewMode", "FIT")
                        .param("titleDisplayMode", "FULL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("loginError", containsString("did not match")));

        // THEN
        assertThat(settingsService.hasPassword()).isFalse();
        assertThat(settingsService.isLoginRequired()).isFalse();
    }

    @Test
    void shouldShowLogoutButtonWhenLoginIsRequired() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/settings").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(content().string(containsString("logout-form")));
    }

    @Test
    void shouldHideLogoutButtonWhenLoginIsNotRequired() throws Exception
    {
        // GIVEN
        // With login off every request is authenticated, so sec:authorize alone would still show Logout.
        settingsService.setLoginRequired(false);

        // WHEN
        ResultActions result = mvc.perform(get("/settings"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("logout-form"))));
    }

    // --- Login page ------------------------------------------------------------

    @Test
    void shouldExposeConfiguredResetHintsWhenRenderingLoginPage() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/login?error"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("passwordFile", appProperties.getPasswordFile()))
                .andExpect(content().string(containsString(appProperties.getPasswordFile())))
                .andExpect(content().string(containsString("starts with login turned off")));
    }

    // --- Tutorial --------------------------------------------------------------

    @Test
    void shouldRenderTheTutorialWithTheComfyUiGuideAndTheValuesInEffect() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/tutorial").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("tutorial"))
                .andExpect(content().string(containsString("id=\"comfyui\"")))
                .andExpect(content().string(containsString("File → Export (API)")))
                .andExpect(content().string(containsString(appProperties.getPasswordFile())))
                .andExpect(content().string(containsString(
                        "within " + appProperties.getComfyui().getJobTimeoutSeconds() + " s")));
    }

    @Test
    void shouldLinkTheTutorialBeforeSearchInTheNavbarAndFromTheComfyUiSettings() throws Exception
    {
        // WHEN
        String html = mvc.perform(get("/settings").with(user("user")))
                .andReturn().getResponse().getContentAsString();

        // THEN
        assertThat(html.indexOf("href=\"/tutorial\"")).isPositive().isLessThan(html.indexOf("href=\"/search\""));
        assertThat(html).contains("href=\"/tutorial#comfyui\"").doesNotContain("COMFYUI.md");
    }

    // --- Add to series ---------------------------------------------------------

    @Test
    void shouldShowMatchesAndNoSearchWhenAddToSeriesHasNoQuery() throws Exception
    {
        // GIVEN
        int chapterId = newChapter("misc-ats-show");

        // WHEN
        ResultActions result = mvc.perform(get("/chapter/" + chapterId + "/add-to-series").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("add-to-series"))
                .andExpect(model().attributeExists("chapter", "matches"))
                .andExpect(model().attributeDoesNotExist("searchResults"));
    }

    @Test
    void shouldRunByNameSearchWhenAddToSeriesQueryPresent() throws Exception
    {
        // GIVEN
        int chapterId = newChapter("misc-ats-query");

        // WHEN
        ResultActions result = mvc.perform(get("/chapter/" + chapterId + "/add-to-series").with(user("user")).param("q", "anything"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(model().attributeExists("searchResults"));
    }

    @Test
    void shouldAttachChapterToSeriesAndRedirectWhenLinking() throws Exception
    {
        // GIVEN
        int chapterId = newChapter("misc-ats-link-ch");
        int seriesId = newSeries("misc-ats-link-series");

        // WHEN
        mvc.perform(post("/chapter/" + chapterId + "/add-to-series/link").with(user("user")).with(csrf())
                        .param("seriesId", String.valueOf(seriesId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/series/" + seriesId));

        // THEN
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getSeries().getId()).isEqualTo(seriesId);
    }

    /** Numbered from its title, exactly as Link chapters does. */
    @Test
    void shouldNumberTheChapterFromItsTitleWhenAddingItToASeries() throws Exception
    {
        // GIVEN a chapter in no series, so without a number, whose title says it is the third.
        int chapterId = newChapter("misc-ats-number saga 3");
        int seriesId = newSeries("misc-ats-number saga");
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getChapterNum()).isNull();

        // WHEN
        mvc.perform(post("/chapter/" + chapterId + "/add-to-series/link").with(user("user")).with(csrf())
                        .param("seriesId", String.valueOf(seriesId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/series/" + seriesId));

        // THEN it joins as chapter 3, not as a placeholder 1.
        var linked = chapterRepository.findById(chapterId).orElseThrow();
        assertThat(linked.getSeries().getId()).isEqualTo(seriesId);
        assertThat(linked.getChapterNum()).isEqualTo(3.0);
    }

    // --- Link chapters ---------------------------------------------------------

    @Test
    void shouldOfferTheFamilysUnlinkedChaptersWhenLinkChaptersOpens() throws Exception
    {
        // GIVEN a series and a chapter of its family in no series (auto-linking is off in the test profile).
        int seriesId = newSeries("misc-lc-show saga");
        int chapterId = newChapter("misc-lc-show saga 2");

        // WHEN
        MvcResult result = mvc.perform(get("/series/" + seriesId + "/link-chapters").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(view().name("link-chapters"))
                // THEN it looks among chapters in no series unless told otherwise, and runs no name search...
                .andExpect(model().attribute("scope", CandidateScope.UNLINKED))
                .andExpect(model().attributeExists("series", "scopes", "matches"))
                .andExpect(model().attributeDoesNotExist("searchResults"))
                // ...and renders the chapter with a checkbox of the form that links it.
                .andExpect(content().string(containsString("name=\"chapterIds\" value=\"" + chapterId + "\"")))
                .andExpect(content().string(containsString("action=\"/series/" + seriesId + "/link-chapters/link\"")))
                .andReturn();
        assertThat(matches(result, "matches")).extracting(ChapterMatchDto::getChapterId).contains(chapterId);
    }

    @Test
    void shouldSearchChaptersByNameInTheChosenScopeOnLinkChapters() throws Exception
    {
        // GIVEN a chapter filed in another series and one in none, both carrying the term.
        int seriesId = newSeries("misc-lc-search host");
        int otherSeries = newSeries("misc-lc-search elsewhere");
        int elsewhere = newChapter("misc-lc-needle elsewhere");
        seriesService.addChapters(otherSeries, List.of(elsewhere));
        int loose = newChapter("misc-lc-needle loose");

        // WHEN searching among all chapters, and among those in no series.
        MvcResult all = mvc.perform(get("/series/" + seriesId + "/link-chapters").with(user("user"))
                        .param("scope", "ALL").param("q", "misc-lc-needle"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("scope", CandidateScope.ALL))
                .andExpect(model().attribute("query", "misc-lc-needle"))
                // the chapter from another series names it, and its checkbox carries what app.js asks about
                .andExpect(content().string(containsString("data-series-id=\"" + otherSeries + "\"")))
                .andExpect(content().string(containsString("data-series-chapters=\"1\"")))
                .andReturn();
        MvcResult unlinked = mvc.perform(get("/series/" + seriesId + "/link-chapters").with(user("user"))
                        .param("q", "misc-lc-needle"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("scope", CandidateScope.UNLINKED))
                .andReturn();

        // THEN
        assertThat(matches(all, "searchResults")).extracting(ChapterMatchDto::getChapterId)
                .contains(elsewhere, loose);
        assertThat(matches(unlinked, "searchResults")).extracting(ChapterMatchDto::getChapterId)
                .contains(loose).doesNotContain(elsewhere);
    }

    @Test
    void shouldLinkTheTickedChaptersAndComeBackToThePageAsItWas() throws Exception
    {
        // GIVEN a series and two chapters of its family in no series.
        int seriesId = newSeries("misc-lc-link host");
        int second = newChapter("misc-lc-link host 2");
        int third = newChapter("misc-lc-link host 3");

        // WHEN both are ticked and linked from a name search among all chapters.
        mvc.perform(post("/series/" + seriesId + "/link-chapters/link").with(user("user")).with(csrf())
                        .param("chapterIds", String.valueOf(second), String.valueOf(third))
                        .param("scope", "ALL").param("q", "misc-lc-link"))
                // THEN the page comes back with the same scope and search, saying how many joined...
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/series/" + seriesId + "/link-chapters?scope=ALL&q=misc-lc-link"))
                .andExpect(flash().attribute("linked", 2));

        // ...both are in the series, numbered from their titles...
        var secondNow = chapterRepository.findById(second).orElseThrow();
        var thirdNow = chapterRepository.findById(third).orElseThrow();
        assertThat(secondNow.getSeries().getId()).isEqualTo(seriesId);
        assertThat(secondNow.getChapterNum()).isEqualTo(2.0);
        assertThat(thirdNow.getSeries().getId()).isEqualTo(seriesId);
        assertThat(thirdNow.getChapterNum()).isEqualTo(3.0);

        // ...and the page it lands on says so.
        mvc.perform(get("/series/" + seriesId + "/link-chapters").flashAttr("linked", 2).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Linked 2 chapter(s) to this series.")));
    }

    @Test
    void shouldSayNothingWasLinkedWhenNoChapterIsTicked() throws Exception
    {
        // GIVEN
        int seriesId = newSeries("misc-lc-none host");

        // WHEN the form arrives with nothing ticked.
        mvc.perform(post("/series/" + seriesId + "/link-chapters/link").with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/series/" + seriesId + "/link-chapters?scope=UNLINKED"))
                .andExpect(flash().attribute("linked", 0));

        // THEN the page it lands on says nothing was linked, rather than claiming a success.
        mvc.perform(get("/series/" + seriesId + "/link-chapters").flashAttr("linked", 0).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No chapter was linked")))
                .andExpect(content().string(not(containsString("to this series."))));
    }

    @Test
    void shouldAnswerNotFoundForLinkChaptersOfASeriesThatDoesNotExist() throws Exception
    {
        // WHEN + THEN
        mvc.perform(get("/series/" + Integer.MAX_VALUE + "/link-chapters").with(user("user")))
                .andExpect(status().isNotFound());
    }

    @SuppressWarnings("unchecked")
    private static List<ChapterMatchDto> matches(MvcResult result, String attribute)
    {
        return (List<ChapterMatchDto>) result.getModelAndView().getModel().get(attribute);
    }
}

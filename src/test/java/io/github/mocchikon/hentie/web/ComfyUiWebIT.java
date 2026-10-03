package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.FakeComfyUi;
import io.github.mocchikon.hentie.TestImages;
import io.github.mocchikon.hentie.TestWorkflows;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.comfy.ComfyResultCache;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService;
import io.github.mocchikon.hentie.service.comfy.WorkflowCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ComfyUiWebIT
{
    private static final int CHAPTER = 92_002;

    @Autowired MockMvc mvc;
    @Autowired SettingsService settingsService;
    @Autowired WorkflowCatalog catalog;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ImageService imageService;
    @Autowired ComfyResultCache resultCache;
    @Autowired PageProcessingService processing;

    @TempDir Path tmp;

    private FakeComfyUi comfy;
    private Path chapterDir;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException
    {
        comfy = new FakeComfyUi();
        comfy.putUserFile("api_workflows/upscale.json", TestWorkflows.UPSCALE);
        comfy.putUserFile("api_workflows/saved-wrong.json", TestWorkflows.REGULAR_FORMAT);
        settingsService.setComfyUiUrl(comfy.url());
        catalog.invalidate();
        chapterDir = imageDirectory.chapterDir(CHAPTER);
        ImageService.deleteRecursively(chapterDir);
        imageService.evictDerived(CHAPTER);
        TestImages.writePng(chapterDir.resolve("1.png"), 10, 10);
    }

    @AfterEach
    void tearDown() throws Exception
    {
        try
        {
            // Before the fake goes: a page still wanted would otherwise run in the next test, against its fake.
            processing.forgetAll();
        }
        finally
        {
            // Even when a page would not stop: left pointing at a closed fake, every later suite would fail too.
            release.countDown();
            comfy.close();
            // Shared singletons: the next suite must find the defaults again.
            settingsService.setComfyUiUrl("");
            settingsService.setComfyUiWorkflowDir("");
            settingsService.setDefaultWorkflow("");
            settingsService.setComfyUiStartScript("");
            settingsService.setComfyUiAutostart(false);
            catalog.invalidate();
            imageService.evictDerived(CHAPTER);
            ImageService.deleteRecursively(chapterDir);
            // The test profile requires login; a save below may have posted without it.
            settingsService.setLoginRequired(true);
        }
    }

    // ---- the processed page --------------------------------------------------

    /** Still working is a 202 with the progress; ready is the PNG - neither may go into the browser's disk cache. */
    @Test
    void shouldAnswerStillWorkingThenThePng() throws Exception
    {
        // GIVEN a run held by ComfyUI.
        byte[] processed = TestImages.png(24, 24);
        comfy.onPrompt = prompt -> new FakeComfyUi.Hold(release, processed);

        // WHEN + THEN asked at once: still working.
        mvc.perform(get(pageUrl() + "&wait=0").with(user("user")))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.state").exists())
                .andExpect(jsonPath("$.message").isNotEmpty());

        // WHEN + THEN asked again once ComfyUI is done: the image itself.
        release.countDown();
        byte[] body = mvc.perform(get(pageUrl() + "&wait=15").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(processed);
    }

    /** A request that arrives after a later one of the same viewer must not count as a page ahead of it. */
    @Test
    void shouldCountOnlyAViewersLatestRequest() throws Exception
    {
        // GIVEN page 1 held by ComfyUI, and a viewer on page 3.
        var viewer = "&viewer=viewer-web-late-request";
        TestImages.writePng(chapterDir.resolve("2.png"), 11, 11);
        TestImages.writePng(chapterDir.resolve("3.png"), 12, 12);
        comfy.onPrompt = prompt -> new FakeComfyUi.Hold(release, TestImages.png(8, 8));
        mvc.perform(get(pageUrl() + "&wait=0").with(user("user"))).andExpect(status().isAccepted());
        await(() -> comfy.prompts().size() == 1);
        mvc.perform(get(pageUrl("3.png") + "&wait=0&seq=2" + viewer).with(user("user")))
                .andExpect(status().isAccepted());

        // WHEN its earlier request for page 2 arrives only now
        mvc.perform(get(pageUrl("2.png") + "&wait=0&seq=1" + viewer).with(user("user")))
                .andExpect(status().isAccepted());

        // THEN only the page running is ahead of page 3
        mvc.perform(get(pageUrl("3.png") + "&wait=0&seq=2" + viewer).with(user("user")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("queued"))
                .andExpect(jsonPath("$.message").value("Queued - 1 page ahead"));
    }

    /** The server cannot see aborted requests, so the viewer's beacon on the way out tells it. */
    @Test
    void shouldStopThePageRunningForAViewerThatLeaves() throws Exception
    {
        // GIVEN page 1 running for a viewer.
        comfy.onPrompt = prompt -> new FakeComfyUi.Hold(release, TestImages.png(8, 8));
        mvc.perform(get(pageUrl() + "&wait=0&seq=1&viewer=viewer-web-leaving").with(user("user")))
                .andExpect(status().isAccepted());
        await(() -> comfy.holding().size() == 1);
        String running = comfy.holding().getFirst();

        // WHEN
        mvc.perform(post("/comfyui/leave").with(user("user")).with(csrf())
                        .param("viewer", "viewer-web-leaving")
                        .param("seq", "2"))
                .andExpect(status().isNoContent());

        // THEN page 1 is deleted from ComfyUI's queue and interrupted.
        await(() -> comfy.interrupted().contains(running));
        assertThat(comfy.deleted()).containsExactly(running);
    }

    /** A running page still in the viewer's plan must go on while the viewer turns to a page that is ready. */
    @Test
    void shouldKeepThePageRunningThatTheViewersPlanStillHolds() throws Exception
    {
        // GIVEN page 2 processed, and page 3 prefetched for a viewer on page 1.
        TestImages.writePng(chapterDir.resolve("2.png"), 11, 11);
        TestImages.writePng(chapterDir.resolve("3.png"), 12, 12);
        mvc.perform(get(pageUrl("2.png") + "&wait=15").with(user("user"))).andExpect(status().isOk());
        comfy.clearRecords();
        comfy.onPrompt = prompt -> new FakeComfyUi.Hold(release, TestImages.png(8, 8));
        var viewer = "&viewer=viewer-web-plan";
        mvc.perform(get(pageUrl("3.png") + "&wait=0&prefetch=true&seq=1&plan=1.png&plan=2.png&plan=3.png" + viewer)
                        .with(user("user")))
                .andExpect(status().isAccepted());
        await(() -> comfy.holding().size() == 1);

        // WHEN it turns to page 2, and page 3 finishes
        var fromPage2 = "&seq=2&plan=2.png&plan=3.png&plan=1.png" + viewer;
        mvc.perform(get(pageUrl("2.png") + "&wait=0" + fromPage2).with(user("user"))).andExpect(status().isOk());
        release.countDown();

        // THEN page 3 is ready, from its one run.
        mvc.perform(get(pageUrl("3.png") + "&wait=15&prefetch=true" + fromPage2).with(user("user")))
                .andExpect(status().isOk());
        assertThat(comfy.interrupted()).isEmpty();
        assertThat(comfy.prompts()).hasSize(1);
    }

    /** A page prefetched further ahead than the viewer keeps in memory is only made ready, not sent. */
    @Test
    void shouldAnswerNoContentForAWarmRequestOnceReady() throws Exception
    {
        mvc.perform(get(pageUrl() + "&wait=15&prefetch=true&warm=true").with(user("user")))
                .andExpect(status().isNoContent());

        assertThat(comfy.prompts()).hasSize(1);
    }

    @Test
    void shouldAnswerEachProblemWithItsStatusAndTheMessage() throws Exception
    {
        mvc.perform(get("/comfyui/pages/" + CHAPTER + "/7.png?workflow=upscale").with(user("user")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Chapter " + CHAPTER + " has no page 7.png."));
        mvc.perform(get("/comfyui/pages/" + CHAPTER + "/1.png?workflow=saved-wrong&wait=15").with(user("user")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value(containsString("File > Export (API)")));

        settingsService.setComfyUiUrl("http://127.0.0.1:1");
        mvc.perform(get(pageUrl() + "&wait=15").with(user("user")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(containsString("Is ComfyUI running?")));
    }

    /** Every device on the network reaches ComfyUI through this app, so through its login too. */
    @Test
    void shouldKeepProcessedPagesBehindTheLogin() throws Exception
    {
        mvc.perform(get(pageUrl()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        assertThat(comfy.prompts()).isEmpty();
    }

    @Test
    void shouldListTheWorkflowsForTheViewer() throws Exception
    {
        mvc.perform(get("/comfyui/workflows").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.starting").value(false))
                .andExpect(jsonPath("$.workflows[0].name").value("saved-wrong"))
                .andExpect(jsonPath("$.workflows[0].valid").value(false))
                .andExpect(jsonPath("$.workflows[0].problem").value(containsString("File > Export (API)")))
                .andExpect(jsonPath("$.workflows[1].name").value("upscale"))
                .andExpect(jsonPath("$.workflows[1].valid").value(true));
    }

    @Test
    void shouldReportTheStatusForTheSettingsPage() throws Exception
    {
        mvc.perform(get("/comfyui/status").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_LAUNCHED"))
                .andExpect(jsonPath("$.launched").value(false))
                .andExpect(jsonPath("$.summary").value("Running at " + comfy.url() + " - ComfyUI 0.99.0-fake on "
                        + "fake-gpu; results arrive over the websocket, so none are written to ComfyUI's disk."));
    }

    @Test
    void shouldGiveTheViewerTheDefaultWorkflow() throws Exception
    {
        settingsService.setDefaultWorkflow("upscale");

        String page = html(get("/chapter/" + CHAPTER + "/view").with(user("user")));

        assertThat(page).contains("<select id=\"workflow-select\"")
                .contains("<option value=\"upscale\" selected>upscale</option>")
                .contains("defaultWorkflow: \"upscale\"");
    }

    /** Without a workflow there is no processed page to compare the page as stored with. */
    @Test
    void shouldOfferThePageAsStoredOnlyWhileAWorkflowIsSelected() throws Exception
    {
        // WHEN
        String withoutWorkflow = html(get("/chapter/" + CHAPTER + "/view").with(user("user")));
        settingsService.setDefaultWorkflow("upscale");
        String withWorkflow = html(get("/chapter/" + CHAPTER + "/view").with(user("user")));

        // THEN
        assertThat(withoutWorkflow).containsPattern("<button [^>]*id=\"original-btn\"[^>]* disabled");
        assertThat(withWorkflow).containsPattern("<button [^>]*id=\"original-btn\"[^>]*title=\"Show this page as stored \\(s\\)\"")
                .doesNotContainPattern("<button [^>]*id=\"original-btn\"[^>]* disabled");
    }

    @Test
    void shouldClearTheProcessedPages() throws Exception
    {
        // GIVEN a processed page.
        mvc.perform(get(pageUrl() + "&wait=15").with(user("user"))).andExpect(status().isOk());
        assertThat(resultCache.sizeBytes()).isPositive();

        // WHEN
        mvc.perform(post("/comfyui/cache/clear").with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings#comfyui-status"))
                .andExpect(flash().attribute("comfyuiMessage", containsString("of processed pages")));

        // THEN
        assertThat(resultCache.sizeBytes()).isZero();
    }

    // ---- Settings -----------------------------------------------------------

    @Test
    void shouldShowTheWorkflowsAndWhichCannotRunOnSettings() throws Exception
    {
        settingsService.setDefaultWorkflow("upscale");

        String page = html(get("/settings").with(user("user")));

        assertThat(page).contains("<option value=\"upscale\" selected=\"selected\">upscale</option>")
                // An unusable workflow is not offered as the default, but the check list says why.
                .doesNotContain("<option value=\"saved-wrong\"")
                .contains("<span class=\"workflow-name\">saved-wrong</span>")
                .contains("saved in ComfyUI&#39;s regular format")
                // The summary line asks ComfyUI, so the page leaves it to /comfyui/status.
                .contains("<p data-comfyui-summary>Asking ComfyUI...</p>")
                .doesNotContain("Running at " + comfy.url());
    }

    /** Walking the cache is left out of rendering Settings; the page asks for the size once it is up. */
    @Test
    void shouldTellTheSizeOfTheProcessedPages() throws Exception
    {
        // GIVEN a processed page.
        mvc.perform(get(pageUrl() + "&wait=15").with(user("user"))).andExpect(status().isOk());

        // WHEN / THEN
        mvc.perform(get("/comfyui/cache/size").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(ImageService.humanReadableSize(resultCache.sizeBytes())));
        assertThat(resultCache.sizeBytes()).isPositive();
    }

    /** The workflow list and ComfyUI's output are there when wanted, collapsed until then. */
    @Test
    void shouldCollapseTheWorkflowListAndTheOutputOnSettings() throws Exception
    {
        String page = html(get("/settings").with(user("user")));

        assertThat(page).contains("<details class=\"workflow-details has-problems\"> "
                        + "<summary>Workflows: 1 ready, 1 cannot run</summary> <ul class=\"workflow-list\">")
                .contains("<details class=\"comfy-console\"")
                .doesNotContainPattern("<details[^>]* open");
    }

    /** Saving the page for some other reason while ComfyUI is down must not quietly reset the default to None. */
    @Test
    void shouldKeepAStoredDefaultWorkflowSelectableWhenItIsNotListed() throws Exception
    {
        settingsService.setDefaultWorkflow("gone");

        mvc.perform(get("/settings").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gone (not available right now)")));
    }

    @Test
    void shouldSaveTheComfyUiSettings() throws Exception
    {
        // GIVEN a start script that exists.
        Path script = executable(Files.writeString(tmp.resolve("run for hentie.bat"), "@echo off\r\n"));

        // WHEN
        saveSettings("  127.0.0.1:8000/  ", "\\api_workflows\\hentie\\", "upscale", script.toString(), true)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings?saved"));

        // THEN each is stored as the app will use it.
        assertThat(settingsService.getComfyUiUrl()).isEqualTo("http://127.0.0.1:8000");
        assertThat(settingsService.getComfyUiWorkflowDir()).isEqualTo("api_workflows/hentie");
        assertThat(settingsService.getDefaultWorkflow()).isEqualTo("upscale");
        assertThat(settingsService.getComfyUiStartScript()).isEqualTo(script.toString());
        assertThat(settingsService.isComfyUiAutostart()).isTrue();
    }

    /** A refused value keeps what was stored, and the page says so without "Settings saved." above it. */
    @Test
    void shouldRefuseValuesThatCannotWorkAndKeepTheOldOnes() throws Exception
    {
        // GIVEN
        String url = comfy.url();

        // WHEN
        saveSettings("ftp://example.org", "../outside", "../escape", tmp.resolve("missing.bat").toString(), false)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("comfyuiError", containsString("\"ftp://example.org\" is not an http://")))
                .andExpect(flash().attribute("comfyuiError", containsString("\"../outside\" is not a folder")))
                .andExpect(flash().attribute("comfyuiError", containsString("\"../escape\" cannot be a workflow name")))
                .andExpect(flash().attribute("comfyuiError", containsString("missing.bat does not exist")));

        // THEN
        assertThat(settingsService.getComfyUiUrl()).isEqualTo(url);
        assertThat(settingsService.getComfyUiWorkflowDir()).isEqualTo(SettingsService.DEFAULT_COMFYUI_WORKFLOW_DIR);
        assertThat(settingsService.getDefaultWorkflow()).isEmpty();
        assertThat(settingsService.getComfyUiStartScript()).isEmpty();
    }

    /** An upload keeps whatever name it came with, so a "page" could otherwise be a script run on Start. */
    @Test
    void shouldRefuseAStartScriptInsideTheDataFolder() throws Exception
    {
        // GIVEN an uploaded "page" that is really a script.
        Path upload = executable(Files.writeString(chapterDir.resolve("2.bat"), "@echo off\r\n"));

        // WHEN
        saveSettings(comfy.url(), "", "", upload.toString(), false)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("comfyuiError", containsString("inside a folder the app writes into")));

        // THEN
        assertThat(settingsService.getComfyUiStartScript()).isEmpty();
    }

    @Test
    void shouldSayAutostartNeedsAScript() throws Exception
    {
        saveSettings(comfy.url(), "", "", "", true)
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("comfyuiError", containsString("until a start script is set")));

        assertThat(settingsService.isComfyUiAutostart()).isFalse();
    }

    private ResultActions saveSettings(String url, String folder, String workflow,
                                       String script, boolean autostart)
            throws Exception
    {
        var request = post("/settings").with(user("user")).with(csrf())
                .param("loginRequired", "true")
                .param("viewMode", "FIT")
                .param("titleDisplayMode", "FULL")
                .param("comfyUrl", url)
                .param("comfyWorkflowDir", folder)
                .param("defaultWorkflow", workflow)
                .param("comfyStartScript", script);
        if (autostart)
        {
            request.param("comfyAutostart", "on");
        }
        return mvc.perform(request);
    }

    /** On POSIX a start script needs its executable bit; elsewhere there is none to set. */
    private static Path executable(Path script) throws IOException
    {
        if (script.getFileSystem().supportedFileAttributeViews().contains("posix"))
        {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        }
        return script;
    }

    /** The page as rendered, with every run of whitespace made one space - templates break tags across lines. */
    private String html(RequestBuilder request) throws Exception
    {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
                .replaceAll("\\s+", " ");
    }

    private static String pageUrl()
    {
        return pageUrl("1.png");
    }

    private static String pageUrl(String filename)
    {
        return "/comfyui/pages/" + CHAPTER + "/" + filename + "?workflow=upscale";
    }

    private static void await(Check condition) throws Exception
    {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.test())
        {
            if (System.currentTimeMillis() > deadline)
            {
                fail("condition not met within 15 s");
            }
            Thread.sleep(25);
        }
    }

    @FunctionalInterface
    private interface Check
    {
        boolean test() throws Exception;
    }
}

package io.github.mocchikon.hentie;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.comfy.ApiWorkflow;
import io.github.mocchikon.hentie.service.comfy.ComfyResultCache;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.*;
import io.github.mocchikon.hentie.service.comfy.WorkflowCatalog;
import io.github.mocchikon.hentie.service.compress.ImageCompressor;
import io.github.mocchikon.hentie.service.compress.ImageToolLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Against {@link FakeComfyUi}. No database rows: processing reads only the chapter's folder, as the viewer does. */
@SpringBootTest
class ComfyUiProcessingIT
{
    /** Its own chapter id, far from anything another suite uses. */
    private static final int CHAPTER = 92_001;

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired PageProcessingService processing;
    @Autowired WorkflowCatalog catalog;
    @Autowired SettingsService settingsService;
    @Autowired AppProperties appProperties;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ImageService imageService;
    @Autowired ComfyResultCache resultCache;
    @Autowired ImageCompressor compressor;
    @Autowired ImageToolLocator toolLocator;

    private FakeComfyUi comfy;
    private Path chapterDir;
    private byte[] page1;
    private byte[] page2;
    private byte[] page3;
    private byte[] page4;
    private byte[] page5;
    private final CountDownLatch release = new CountDownLatch(1);
    private int originalJobTimeout;

    @BeforeEach
    void setUp() throws IOException
    {
        comfy = new FakeComfyUi();
        comfy.putUserFile("api_workflows/upscale.json", TestWorkflows.UPSCALE);
        settingsService.setComfyUiUrl(comfy.url());
        settingsService.setComfyUiWorkflowDir("");
        catalog.invalidate();
        originalJobTimeout = appProperties.getComfyui().getJobTimeoutSeconds();
        chapterDir = imageDirectory.chapterDir(CHAPTER);
        ImageService.deleteRecursively(chapterDir);
        imageService.evictDerived(CHAPTER);
        // Different sizes, so every page has its own content and so its own upload name.
        page1 = write("1.png", 10);
        page2 = write("2.png", 11);
        page3 = write("3.png", 12);
        page4 = write("4.png", 13);
        page5 = write("5.png", 14);
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
            appProperties.getComfyui().setJobTimeoutSeconds(originalJobTimeout);
            comfy.close();
            // Shared singletons: the next suite must find the defaults again.
            settingsService.setComfyUiUrl("");
            settingsService.setComfyUiWorkflowDir("");
            catalog.invalidate();
            imageService.evictDerived(CHAPTER);
            ImageService.deleteRecursively(chapterDir);
        }
    }

    // ---- the workflow list -------------------------------------------------

    @Test
    void shouldListTheWorkflowsOfTheFolderSayingWhichCannotRun() throws Exception
    {
        // GIVEN a subfolder, a regular-format save, an ambiguous workflow and a file that is no workflow.
        comfy.putUserFile("api_workflows/Sub/2x.json", TestWorkflows.UPSCALE_2X);
        comfy.putUserFile("api_workflows/saved-wrong.json", TestWorkflows.REGULAR_FORMAT);
        comfy.putUserFile("api_workflows/ambiguous.json", TestWorkflows.AMBIGUOUS);
        comfy.putUserFile("api_workflows/notes.txt", "not a workflow");
        comfy.putUserFile("workflows/elsewhere.json", TestWorkflows.UPSCALE);

        // WHEN
        WorkflowCatalog.Listing listing = catalog.listing();

        // THEN
        assertThat(listing.reachable()).isTrue();
        assertThat(listing.problem()).isNull();
        assertThat(listing.workflows()).extracting(WorkflowCatalog.WorkflowInfo::name)
                .containsExactly("ambiguous", "saved-wrong", "Sub/2x", "upscale");
        assertThat(listing.find("upscale").orElseThrow().valid()).isTrue();
        assertThat(listing.find("Sub/2x").orElseThrow().valid()).isTrue();
        assertThat(listing.find("saved-wrong").orElseThrow().problem()).contains("File > Export (API)");
        assertThat(listing.find("ambiguous").orElseThrow().problem()).contains("rename the right one to \"Input\"");
    }

    @Test
    void shouldReadEachWorkflowFileOnlyWhenItChanges() throws Exception
    {
        // GIVEN
        catalog.listing();
        long reads = comfy.count("GET /userdata/api_workflows%2Fupscale.json");

        // WHEN listed again, unchanged, and then after it changed
        catalog.invalidate();
        catalog.listing();
        long unchanged = comfy.count("GET /userdata/api_workflows%2Fupscale.json");
        comfy.putUserFile("api_workflows/upscale.json", TestWorkflows.UPSCALE_2X, System.currentTimeMillis() + 5_000);
        catalog.invalidate();
        catalog.listing();

        // THEN
        assertThat(reads).isEqualTo(1);
        assertThat(unchanged).isEqualTo(1);
        assertThat(comfy.count("GET /userdata/api_workflows%2Fupscale.json")).isEqualTo(2);
    }

    @Test
    void shouldListTheFolderSetInSettings() throws Exception
    {
        comfy.putUserFile("api_workflows/hentie/2x.json", TestWorkflows.UPSCALE_2X);
        settingsService.setComfyUiWorkflowDir("api_workflows/hentie");

        assertThat(catalog.listing().workflows()).extracting(WorkflowCatalog.WorkflowInfo::name).containsExactly("2x");
    }

    @Test
    void shouldSayWhenTheFolderDoesNotExistYet() throws Exception
    {
        settingsService.setComfyUiWorkflowDir("nothing-here");

        WorkflowCatalog.Listing listing = catalog.listing();

        assertThat(listing.reachable()).isTrue();
        assertThat(listing.workflows()).isEmpty();
        assertThat(listing.problem()).contains("no 'nothing-here' folder");
    }

    /** A result made while ComfyUI was up is still correct while it is down. */
    @Test
    void shouldKeepTheLastWorkflowsKnownWhileComfyUiDoesNotAnswer() throws Exception
    {
        // GIVEN a page processed and ComfyUI gone afterwards.
        Path result = ((Ready) processing.request(CHAPTER, "1.png", "upscale", false, WAIT)).file();
        comfy.close();
        catalog.invalidate();

        // WHEN
        WorkflowCatalog.Listing listing = catalog.listing();
        Outcome again = processing.request(CHAPTER, "1.png", "upscale", false, Duration.ZERO);

        // THEN
        assertThat(listing.reachable()).isFalse();
        assertThat(listing.problem()).contains("Is ComfyUI running?");
        assertThat(listing.workflows()).extracting(WorkflowCatalog.WorkflowInfo::name).containsExactly("upscale");
        assertThat(again).isEqualTo(new Ready(result));
    }

    /** Refreshing inside the request would make every page turn wait for a refused connection while ComfyUI is down. */
    @Test
    void shouldAnswerFromTheLastListingWhileTheNextIsFetched() throws Exception
    {
        // GIVEN a listing, ComfyUI gone, and the listing past its time.
        catalog.listing();
        comfy.close();
        Thread.sleep(WorkflowCatalog.LISTING_TTL_MILLIS + 100);

        // WHEN
        WorkflowCatalog.Listing stale = catalog.listing();

        // THEN the last listing answers, and the refresh behind it finds ComfyUI gone.
        assertThat(stale.reachable()).isTrue();
        assertThat(stale.workflows()).extracting(WorkflowCatalog.WorkflowInfo::name).containsExactly("upscale");
        await(() -> !catalog.listing().reachable());
        assertThat(catalog.listing().workflows()).extracting(WorkflowCatalog.WorkflowInfo::name)
                .containsExactly("upscale");
    }

    // ---- processing a page -------------------------------------------------

    @Test
    void shouldProcessAPageAndAnswerFromTheCacheAfterwards() throws Exception
    {
        // GIVEN
        byte[] processed = TestImages.png(40, 40);
        comfy.onPrompt = prompt -> new FakeComfyUi.Succeed(processed);

        // WHEN
        Outcome first = processing.request(CHAPTER, "1.png", "upscale", false, WAIT);
        Outcome second = processing.request(CHAPTER, "1.png", "upscale", false, Duration.ZERO);

        // THEN the result is stored as it came, and the second request never reaches ComfyUI.
        assertThat(first).isInstanceOf(Ready.class);
        assertThat(Files.readAllBytes(((Ready) first).file())).isEqualTo(processed);
        assertThat(((Ready) first).file()).startsWith(resultCache.root());
        assertThat(second).isEqualTo(first);
        assertThat(comfy.prompts()).hasSize(1);
    }

    @Test
    void shouldAskForTheOutputOverTheWebsocketAndOnlyItsBranch() throws Exception
    {
        // WHEN
        assertThat(processing.request(CHAPTER, "1.png", "upscale", false, WAIT)).isInstanceOf(Ready.class);

        // THEN
        JsonNode payload = comfy.prompts().getFirst();
        JsonNode prompt = payload.path("prompt");
        assertThat(prompt.path("4").path("class_type").asText()).isEqualTo(ApiWorkflow.WEBSOCKET_OUTPUT);
        assertThat(prompt.path("4").path("inputs").size()).isEqualTo(1);
        assertThat(payload.path("partial_execution_targets").toString()).isEqualTo("[\"4\"]");
        assertThat(prompt.path("1").path("inputs").path("image").asText()).startsWith("hentie-").endsWith(".png [temp]");
        assertThat(comfy.inputOf(payload)).isEqualTo(page1);
        assertThat(payload.path("client_id").asText()).startsWith("hentie-");
        assertThat(comfy.requests()).doesNotContain("GET /view");
    }

    /** The result may pass through ComfyUI's temp folder, never its output folder. */
    @Test
    void shouldFallBackToPreviewImageWithoutTheWebsocketNode() throws Exception
    {
        // GIVEN
        comfy.websocketNode = false;
        byte[] processed = TestImages.png(30, 30);
        comfy.onPrompt = prompt -> new FakeComfyUi.Succeed(processed);

        // WHEN
        Outcome outcome = processing.request(CHAPTER, "1.png", "upscale", false, WAIT);

        // THEN
        assertThat(Files.readAllBytes(((Ready) outcome).file())).isEqualTo(processed);
        assertThat(comfy.prompts().getFirst().path("prompt").path("4").path("class_type").asText())
                .isEqualTo(ApiWorkflow.PREVIEW_OUTPUT);
        assertThat(comfy.requests()).contains("GET /view");
    }

    /** Uploads are named after their content, so ComfyUI skips writing a page it already has. */
    @Test
    void shouldWriteAPageIntoComfyUiOnlyOnceHoweverManyWorkflowsRunOnIt() throws Exception
    {
        // GIVEN
        comfy.putUserFile("api_workflows/upscale-2x.json", TestWorkflows.UPSCALE_2X);

        // WHEN
        Outcome first = processing.request(CHAPTER, "1.png", "upscale", false, WAIT);
        Outcome second = processing.request(CHAPTER, "1.png", "upscale-2x", false, WAIT);

        // THEN two runs, two results, one upload written.
        assertThat(first).isInstanceOf(Ready.class);
        assertThat(second).isInstanceOf(Ready.class);
        assertThat(((Ready) first).file()).isNotEqualTo(((Ready) second).file());
        assertThat(comfy.count("POST /upload/image")).isEqualTo(2);
        assertThat(comfy.uploadWrites()).isEqualTo(1);
    }

    @Test
    void shouldSendAJpegXlPageAsThePngItDecodesTo() throws Exception
    {
        // GIVEN a real JPEG XL page, which ComfyUI's image loader cannot read.
        Assumptions.assumeTrue(toolLocator.find("cjxl").isPresent() && toolLocator.find("djxl").isPresent(),
                "No bundled cjxl/djxl for this platform - skipping");
        Files.delete(chapterDir.resolve("1.png"));
        Path png = chapterDir.resolve("5.png");
        Files.write(png, TestImages.png(64, 64));
        var profile = new CompressionProfile("test", "Test", ImageEncoder.JXL,
                CompressionProfile.splitArgs("-q 40 -e 1"), List.of(), 0, 0, Set.of());
        assertThat(compressor.compress(png, profile, chapterDir.resolve(".work")).replaced()).isTrue();

        // WHEN
        Outcome outcome = processing.request(CHAPTER, "5.jxl", "upscale", false, WAIT);

        // THEN
        assertThat(outcome).isInstanceOf(Ready.class);
        byte[] sent = comfy.inputOf(comfy.prompts().getFirst());
        assertThat(sent).startsWith(0x89, 'P', 'N', 'G');
    }

    // ---- what goes wrong ---------------------------------------------------

    @Test
    void shouldReportWhatComfyUiRejectsInTheWorkflowsOwnWords() throws Exception
    {
        comfy.onPrompt = prompt -> new FakeComfyUi.Reject("2", "Value not in list", "model_name: '4x.pth' not in []");

        Outcome outcome = processing.request(CHAPTER, "1.png", "upscale", false, WAIT);

        assertThat(outcome).isEqualTo(new Failed(Problem.WORKFLOW_INVALID, "ComfyUI rejected workflow 'upscale': "
                + "Load Upscale Model: Value not in list: model_name: '4x.pth' not in []"));
    }

    @Test
    void shouldReportAWorkflowThatFailsWhileRunning() throws Exception
    {
        comfy.onPrompt = prompt -> new FakeComfyUi.Fail("3", "ImageUpscaleWithModel", "CUDA out of memory");

        Outcome outcome = processing.request(CHAPTER, "1.png", "upscale", false, WAIT);

        assertThat(outcome).isEqualTo(new Failed(Problem.FAILED,
                "ComfyUI failed to run workflow 'upscale': Upscale Image (using Model): CUDA out of memory"));
    }

    /** ComfyUI would only say the same, less clearly. */
    @Test
    void shouldReportAWorkflowThatCannotRunWithoutSendingIt() throws Exception
    {
        comfy.putUserFile("api_workflows/saved-wrong.json", TestWorkflows.REGULAR_FORMAT);

        Outcome invalid = processing.request(CHAPTER, "1.png", "saved-wrong", false, WAIT);
        Outcome missing = processing.request(CHAPTER, "1.png", "not-there", false, WAIT);

        assertThat(invalid).isInstanceOfSatisfying(Failed.class, failed ->
        {
            assertThat(failed.problem()).isEqualTo(Problem.WORKFLOW_INVALID);
            assertThat(failed.message()).contains("File > Export (API)");
        });
        assertThat(missing).isEqualTo(new Failed(Problem.WORKFLOW_NOT_FOUND,
                "Workflow 'not-there' is not in ComfyUI's 'api_workflows' folder."));
        assertThat(comfy.prompts()).isEmpty();
    }

    @Test
    void shouldSayComfyUiIsNotAnswering() throws Exception
    {
        settingsService.setComfyUiUrl("http://127.0.0.1:1");

        Outcome outcome = processing.request(CHAPTER, "1.png", "upscale", false, WAIT);

        assertThat(outcome).isInstanceOfSatisfying(Failed.class, failed ->
        {
            assertThat(failed.problem()).isEqualTo(Problem.UNREACHABLE);
            assertThat(failed.message()).contains("http://127.0.0.1:1").contains("Is ComfyUI running?")
                    .contains("check the address in Settings");
        });
    }

    /** The name comes from a URL, so only a bare image name in the chapter's folder may be read. */
    @Test
    void shouldRefuseAnythingButAnImageOfTheChapter() throws Exception
    {
        Files.writeString(chapterDir.resolve("notes.txt"), "text");

        assertThat(problemOf(processing.request(CHAPTER, "../1.png", "upscale", false, WAIT))).isEqualTo(Problem.PAGE_NOT_FOUND);
        assertThat(problemOf(processing.request(CHAPTER, "9.png", "upscale", false, WAIT))).isEqualTo(Problem.PAGE_NOT_FOUND);
        assertThat(problemOf(processing.request(CHAPTER, "notes.txt", "upscale", false, WAIT))).isEqualTo(Problem.PAGE_NOT_FOUND);
        assertThat(problemOf(processing.request(CHAPTER, "1.png", "../upscale", false, WAIT)))
                .isEqualTo(Problem.WORKFLOW_NOT_FOUND);
        assertThat(comfy.requests()).noneMatch(request -> request.startsWith("POST"));
    }

    /** Cancelled in ComfyUI too, so it does not hold the GPU for a result nobody takes. */
    @Test
    void shouldCancelARunThatTakesTooLong() throws Exception
    {
        appProperties.getComfyui().setJobTimeoutSeconds(1);
        comfy.onPrompt = prompt -> new FakeComfyUi.Hold(release, TestImages.png(8, 8));

        Outcome outcome = processing.request(CHAPTER, "1.png", "upscale", false, WAIT);

        assertThat(outcome).isInstanceOfSatisfying(Failed.class, failed ->
        {
            assertThat(failed.problem()).isEqualTo(Problem.TIMEOUT);
            assertThat(failed.message()).contains("did not finish within 1 s").contains("cancelled");
        });
        assertThat(comfy.requests()).contains("POST /queue", "POST /interrupt");
    }

    // ---- which page runs when ----------------------------------------------

    /**
     * A first run has nothing to be measured against, so the node's count is shown. The workflow is this test's
     * own because run times are kept per version for the whole context.
     */
    @Test
    void shouldTellHowFarARunningPageIs() throws Exception
    {
        // GIVEN a run held after its first nodes reported how far they got.
        comfy.putUserFile("api_workflows/progress.json", TestWorkflows.UPSCALE.replace("4x.pth", "progress.pth"));
        comfy.onPrompt = prompt -> new FakeComfyUi.Hold(release, TestImages.png(8, 8));
        assertThat(processing.request(CHAPTER, "1.png", "progress", false, Duration.ZERO)).isInstanceOf(Pending.class);

        // WHEN
        await(() -> "Upscale Image (using Model)".equals(progressOf("1.png", "progress").node()));
        Progress progress = progressOf("1.png", "progress");

        // THEN the node the user named, and how far it is.
        assertThat(progress.stage()).isEqualTo(Progress.Stage.RUNNING);
        assertThat(progress.estimate()).isNull();
        assertThat(progress.percent()).isEqualTo(50);
        assertThat(progress.message()).isEqualTo("Upscale Image (using Model) 50%");
        release.countDown();
        assertThat(processing.request(CHAPTER, "1.png", "progress", false, WAIT)).isInstanceOf(Ready.class);
    }

    /** Scaled to the page: four times the pixels, four times the time. */
    @Test
    void shouldEstimateHowLongIsLeftFromTheWorkflowsLastRun() throws Exception
    {
        // GIVEN a first run on the 10x10 page 1 that took about 2.5 s, and a second one held, on a 20x20 page.
        write("big.png", 20);
        comfy.putUserFile("api_workflows/timed.json", TestWorkflows.UPSCALE.replace("4x.pth", "timed.pth"));
        var firstDone = new CountDownLatch(1);
        var prompts = new AtomicInteger();
        comfy.onPrompt = prompt ->
        {
            if (prompts.getAndIncrement() > 0)
            {
                return new FakeComfyUi.Hold(release, TestImages.png(8, 8));
            }
            CompletableFuture.delayedExecutor(2_500, TimeUnit.MILLISECONDS).execute(firstDone::countDown);
            return new FakeComfyUi.Hold(firstDone, TestImages.png(8, 8));
        };
        assertThat(processing.request(CHAPTER, "1.png", "timed", false, WAIT)).isInstanceOf(Ready.class);
        assertThat(processing.request(CHAPTER, "big.png", "timed", false, Duration.ZERO)).isInstanceOf(Pending.class);

        // WHEN
        await(() -> "Upscale Image (using Model)".equals(progressOf("big.png", "timed").node()));
        Progress progress = progressOf("big.png", "timed");

        // THEN typical and slow are both four times the first run, and this run has only just begun.
        assertThat(progress.stage()).isEqualTo(Progress.Stage.RUNNING);
        assertThat(progress.estimate()).isNotNull();
        assertThat(progress.estimate().typicalMillis()).isBetween(4 * 2_400L, 4 * 15_000L)
                .isEqualTo(progress.estimate().slowMillis());
        assertThat(progress.estimate().elapsedMillis()).isBetween(0L, progress.estimate().typicalMillis());
        assertThat(progress.percent()).isBetween(0, 99);
        assertThat(progress.message()).matches("Upscale Image \\(using Model\\) \\d{1,2}%, ~\\d+(-\\d+)? s left");
        release.countDown();
        assertThat(processing.request(CHAPTER, "big.png", "timed", false, WAIT)).isInstanceOf(Ready.class);
    }

    /** Exported again, it may use another model, so its old run times say nothing. */
    @Test
    void shouldForgetHowLongAWorkflowTookOnceItIsExportedAgain() throws Exception
    {
        // GIVEN a run of about 1.5 s, then the workflow exported again with another model, and a run held.
        comfy.putUserFile("api_workflows/exported.json", TestWorkflows.UPSCALE.replace("4x.pth", "exported-a.pth"));
        var firstDone = new CountDownLatch(1);
        var prompts = new AtomicInteger();
        comfy.onPrompt = prompt ->
        {
            if (prompts.getAndIncrement() > 0)
            {
                return new FakeComfyUi.Hold(release, TestImages.png(8, 8));
            }
            CompletableFuture.delayedExecutor(1_500, TimeUnit.MILLISECONDS).execute(firstDone::countDown);
            return new FakeComfyUi.Hold(firstDone, TestImages.png(8, 8));
        };
        assertThat(processing.request(CHAPTER, "1.png", "exported", false, WAIT)).isInstanceOf(Ready.class);
        comfy.putUserFile("api_workflows/exported.json", TestWorkflows.UPSCALE.replace("4x.pth", "exported-b.pth"),
                System.currentTimeMillis() + 5_000);
        catalog.invalidate();
        assertThat(processing.request(CHAPTER, "2.png", "exported", false, Duration.ZERO)).isInstanceOf(Pending.class);

        // WHEN
        await(() -> "Upscale Image (using Model)".equals(progressOf("2.png", "exported").node()));
        Progress progress = progressOf("2.png", "exported");

        // THEN the node's own count, as for a first run
        assertThat(progress.estimate()).isNull();
        assertThat(progress.message()).isEqualTo("Upscale Image (using Model) 50%");
        release.countDown();
        assertThat(processing.request(CHAPTER, "2.png", "exported", false, WAIT)).isInstanceOf(Ready.class);
    }

    /** The page the reader jumped to last is the one they are looking at. */
    @Test
    void shouldRunThePageBeingReadFirstAndTheLatestFirst() throws Exception
    {
        // GIVEN page 1 running (held), then a prefetch of 2 and reads of 3 and 4 while it runs.
        var prompts = new AtomicInteger();
        comfy.onPrompt = prompt -> prompts.getAndIncrement() == 0
                ? new FakeComfyUi.Hold(release, TestImages.png(8, 8)) : new FakeComfyUi.Succeed(TestImages.png(8, 8));
        processing.request(CHAPTER, "1.png", "upscale", false, Duration.ZERO);
        await(() -> comfy.prompts().size() == 1);
        processing.request(CHAPTER, "2.png", "upscale", true, Duration.ZERO);
        processing.request(CHAPTER, "3.png", "upscale", false, Duration.ZERO);
        processing.request(CHAPTER, "4.png", "upscale", false, Duration.ZERO);

        // WHEN
        release.countDown();
        await(() -> comfy.prompts().size() == 4);

        // THEN
        assertThat(comfy.prompts()).extracting(comfy::inputOf).containsExactly(page1, page4, page3, page2);
    }

    /** A sleeping tab or locked phone says nothing and still wants its running page when it wakes. */
    @Test
    void shouldDropAPageNobodyAsksAboutAnyMoreButFinishTheOneRunning() throws Exception
    {
        // GIVEN page 1 running for a viewer, page 2 asked for once, and both left for longer than the grace.
        holdFirstPromptFor(new Asker("viewer-silent", 1));
        processing.request(CHAPTER, "2.png", "upscale", true, Duration.ZERO);
        Thread.sleep(appProperties.getComfyui().getAbandonAfterMillis() + 500);

        // WHEN page 3 is asked for, and page 1 finishes.
        processing.request(CHAPTER, "3.png", "upscale", false, Duration.ZERO);
        release.countDown();

        // THEN page 1 ran to its end and page 3 after it, while page 2 never ran.
        assertThat(processing.request(CHAPTER, "3.png", "upscale", false, WAIT)).isInstanceOf(Ready.class);
        assertThat(processing.request(CHAPTER, "1.png", "upscale", false, Duration.ZERO)).isInstanceOf(Ready.class);
        assertThat(comfy.interrupted()).isEmpty();
        assertThat(comfy.prompts()).extracting(comfy::inputOf).containsExactly(page1, page3);
    }

    // Only what runs next can prove a page never runs, so each test below ends with a prefetch of page 5, which
    // a page still wanted would run before. Each test has its own viewer id: the service is shared by the whole
    // context and remembers a viewer's latest request.

    /** The pages a viewer turned past must not run ahead of the one it is on. */
    @Test
    void shouldRunOnlyThePageAViewerTurnedToLast() throws Exception
    {
        // GIVEN page 1 running, and a viewer turning to pages 2, 3 and 4 meanwhile.
        holdFirstPrompt();
        var viewer = "viewer-turned-past";
        processing.request(CHAPTER, "2.png", "upscale", false, Duration.ZERO, new Asker(viewer, 1));
        processing.request(CHAPTER, "3.png", "upscale", false, Duration.ZERO, new Asker(viewer, 2));
        processing.request(CHAPTER, "4.png", "upscale", false, Duration.ZERO, new Asker(viewer, 3));

        // WHEN
        Outcome onScreen = processing.request(CHAPTER, "4.png", "upscale", false, Duration.ZERO, new Asker(viewer, 3));
        release.countDown();

        // THEN only the page running is ahead, and pages 2 and 3 never run.
        assertThat(onScreen).isEqualTo(queued(1));
        assertThat(processing.request(CHAPTER, "4.png", "upscale", false, WAIT, new Asker(viewer, 3)))
                .isInstanceOf(Ready.class);
        assertThat(processing.request(CHAPTER, "5.png", "upscale", true, WAIT, new Asker(viewer, 3)))
                .isInstanceOf(Ready.class);
        assertThat(comfy.prompts()).extracting(comfy::inputOf).containsExactly(page1, page4, page5);
    }

    /** A request delayed in the browser or behind a slow listing must not make its page wanted again. */
    @Test
    void shouldIgnoreARequestTheViewerHasAlreadyMovedOnFrom() throws Exception
    {
        // GIVEN page 1 running, and a viewer on page 3.
        holdFirstPrompt();
        var viewer = "viewer-late-request";
        processing.request(CHAPTER, "3.png", "upscale", false, Duration.ZERO, new Asker(viewer, 2));

        // WHEN its earlier request for page 2 arrives only now
        Outcome late = processing.request(CHAPTER, "2.png", "upscale", false, Duration.ZERO, new Asker(viewer, 1));
        Outcome onScreen = processing.request(CHAPTER, "3.png", "upscale", false, Duration.ZERO, new Asker(viewer, 2));
        release.countDown();

        // THEN
        assertThat(late).isEqualTo(queued(0));
        assertThat(onScreen).isEqualTo(queued(1));
        assertThat(processing.request(CHAPTER, "3.png", "upscale", false, WAIT, new Asker(viewer, 2)))
                .isInstanceOf(Ready.class);
        assertThat(processing.request(CHAPTER, "5.png", "upscale", true, WAIT)).isInstanceOf(Ready.class);
        assertThat(comfy.prompts()).extracting(comfy::inputOf).containsExactly(page1, page3, page5);
    }

    /** The server cannot see a viewer abort a held request, which still ends after its wait. */
    @Test
    void shouldNotWantAPageAgainWhenARequestTheViewerLeftEnds() throws Exception
    {
        // GIVEN page 1 running, and a viewer's request for page 2 held on the server.
        holdFirstPrompt();
        var viewer = "viewer-held-request";
        var held = Thread.ofVirtual().start(() -> uncheckedRequest("2.png", new Asker(viewer, 1)));
        await(() -> held.getState() == Thread.State.TIMED_WAITING);

        // WHEN the viewer turns to page 3, and the held request runs out
        processing.request(CHAPTER, "3.png", "upscale", false, Duration.ZERO, new Asker(viewer, 2));
        held.join();
        release.countDown();

        // THEN
        assertThat(processing.request(CHAPTER, "3.png", "upscale", false, WAIT, new Asker(viewer, 2)))
                .isInstanceOf(Ready.class);
        assertThat(processing.request(CHAPTER, "5.png", "upscale", true, WAIT)).isInstanceOf(Ready.class);
        assertThat(comfy.prompts()).extracting(comfy::inputOf).containsExactly(page1, page3, page5);
    }

    @Test
    void shouldStopWantingAPageWhenTheViewerTurnsBackToAReadyOne() throws Exception
    {
        // GIVEN page 4 processed before, page 1 running, and a viewer waiting for page 3.
        assertThat(processing.request(CHAPTER, "4.png", "upscale", false, WAIT)).isInstanceOf(Ready.class);
        comfy.clearRecords();
        holdFirstPrompt();
        var viewer = "viewer-turned-back";
        processing.request(CHAPTER, "3.png", "upscale", false, Duration.ZERO, new Asker(viewer, 1));

        // WHEN it turns back to page 4
        Outcome back = processing.request(CHAPTER, "4.png", "upscale", false, Duration.ZERO, new Asker(viewer, 2));
        release.countDown();

        // THEN
        assertThat(back).isInstanceOf(Ready.class);
        assertThat(processing.request(CHAPTER, "5.png", "upscale", true, WAIT)).isInstanceOf(Ready.class);
        assertThat(comfy.prompts()).extracting(comfy::inputOf).containsExactly(page1, page5);
    }

    // ---- stopping the page running -----------------------------------------

    /** The reader who turned from page 1 to page 2 is waiting for page 2, not for the rest of page 1. */
    @Test
    void shouldStopThePageRunningWhenItsViewerTurnsToAnother() throws Exception
    {
        // GIVEN page 1 running for a viewer.
        var viewer = "viewer-turned-on";
        String running = holdFirstPromptFor(new Asker(viewer, 1));

        // WHEN it turns to page 2
        Outcome turned = processing.request(CHAPTER, "2.png", "upscale", false, WAIT, new Asker(viewer, 2));

        // THEN page 1 is deleted from ComfyUI's queue and interrupted, and page 2 runs in its place.
        assertThat(turned).isInstanceOf(Ready.class);
        assertThat(comfy.deleted()).containsExactly(running);
        assertThat(comfy.interrupted()).containsExactly(running);
        assertThat(comfy.prompts()).extracting(comfy::inputOf).containsExactly(page1, page2);
    }

    @Test
    void shouldStopThePageRunningWhenItsViewerLeaves() throws Exception
    {
        // GIVEN page 1 running for a viewer.
        var viewer = "viewer-left";
        String running = holdFirstPromptFor(new Asker(viewer, 1));

        // WHEN it leaves
        processing.leave(new Asker(viewer, 2));

        // THEN page 1 is stopped in ComfyUI, and the next page anybody asks for runs at once.
        await(() -> comfy.interrupted().contains(running));
        assertThat(comfy.deleted()).containsExactly(running);
        assertThat(processing.request(CHAPTER, "2.png", "upscale", false, WAIT)).isInstanceOf(Ready.class);
        assertThat(comfy.prompts()).extracting(comfy::inputOf).containsExactly(page1, page2);
    }

    /** An older ComfyUI ignores the prompt id of an interrupt, so it would stop the user's own generation instead. */
    @Test
    void shouldOnlyDeleteAPageStoppedWhileItWaitsInComfyUisQueue() throws Exception
    {
        // GIVEN page 1 waiting in ComfyUI's queue for a viewer; every prompt after it succeeds at once.
        var prompts = new AtomicInteger();
        comfy.onPrompt = prompt -> prompts.getAndIncrement() == 0
                ? new FakeComfyUi.Wait(release, TestImages.png(8, 8)) : new FakeComfyUi.Succeed(TestImages.png(8, 8));
        var viewer = "viewer-left-while-queued";
        processing.request(CHAPTER, "1.png", "upscale", false, Duration.ZERO, new Asker(viewer, 1));
        await(() -> comfy.waiting().size() == 1);
        String queued = comfy.waiting().getFirst();

        // WHEN it leaves, and the next page is asked for - which runs only once page 1 is out of ComfyUI
        processing.leave(new Asker(viewer, 2));
        Outcome next = processing.request(CHAPTER, "2.png", "upscale", false, WAIT);

        // THEN page 1 was deleted from the queue, ComfyUI was asked whether it ran, and nothing was interrupted.
        assertThat(next).isInstanceOf(Ready.class);
        assertThat(comfy.deleted()).containsExactly(queued);
        assertThat(comfy.count("GET /queue")).isEqualTo(1);
        assertThat(comfy.interrupted()).isEmpty();
        assertThat(comfy.prompts()).extracting(comfy::inputOf).containsExactly(page1, page2);
    }

    /** Stopping a page still in the viewer's plan would only restart it from nothing a moment later. */
    @Test
    void shouldKeepThePageRunningWhileItsViewerCollectsReadyPagesOnTheWayToIt() throws Exception
    {
        // GIVEN pages 1 and 2 processed, and page 3 prefetched for a viewer on page 1.
        assertThat(processing.request(CHAPTER, "1.png", "upscale", false, WAIT)).isInstanceOf(Ready.class);
        assertThat(processing.request(CHAPTER, "2.png", "upscale", false, WAIT)).isInstanceOf(Ready.class);
        comfy.clearRecords();
        comfy.onPrompt = prompt -> new FakeComfyUi.Hold(release, TestImages.png(8, 8));
        var viewer = "viewer-collecting";
        processing.request(CHAPTER, "3.png", "upscale", true, Duration.ZERO,
                new Asker(viewer, 1, List.of("1.png", "2.png", "3.png", "4.png")));
        await(() -> comfy.holding().size() == 1);

        // WHEN it turns to page 2, answered from the cache, and asks for page 3 again from there
        var fromPage2 = new Asker(viewer, 2, List.of("2.png", "3.png", "4.png", "1.png"));
        Outcome turned = processing.request(CHAPTER, "2.png", "upscale", false, Duration.ZERO, fromPage2);
        processing.request(CHAPTER, "3.png", "upscale", true, Duration.ZERO, fromPage2);
        release.countDown();

        // THEN page 3 ran once, to its end.
        assertThat(turned).isInstanceOf(Ready.class);
        assertThat(processing.request(CHAPTER, "3.png", "upscale", true, WAIT, fromPage2)).isInstanceOf(Ready.class);
        assertThat(comfy.interrupted()).isEmpty();
        assertThat(comfy.prompts()).extracting(comfy::inputOf).containsExactly(page3);
    }

    @Test
    void shouldStopThePageRunningWhenItsViewerTurnsToAReadyPageAwayFromIt() throws Exception
    {
        // GIVEN page 5 processed, and page 3 prefetched for a viewer on page 1.
        assertThat(processing.request(CHAPTER, "5.png", "upscale", false, WAIT)).isInstanceOf(Ready.class);
        comfy.clearRecords();
        comfy.onPrompt = prompt -> new FakeComfyUi.Hold(release, TestImages.png(8, 8));
        var viewer = "viewer-jumped";
        processing.request(CHAPTER, "3.png", "upscale", true, Duration.ZERO,
                new Asker(viewer, 1, List.of("1.png", "2.png", "3.png")));
        await(() -> comfy.holding().size() == 1);
        String running = comfy.holding().getFirst();

        // WHEN it jumps to page 5, answered from the cache, with a plan that does not reach page 3
        Outcome jumped = processing.request(CHAPTER, "5.png", "upscale", false, Duration.ZERO,
                new Asker(viewer, 2, List.of("5.png", "4.png")));

        // THEN
        assertThat(jumped).isInstanceOf(Ready.class);
        await(() -> comfy.interrupted().contains(running));
        assertThat(comfy.deleted()).containsExactly(running);
    }

    // ---- keeping the cache honest ------------------------------------------

    @Test
    void shouldForgetTheResultsOfADeletedPage() throws Exception
    {
        Path result = ((Ready) processing.request(CHAPTER, "1.png", "upscale", false, WAIT)).file();
        assertThat(result).exists();

        imageService.deletePage(CHAPTER, "1.png");

        assertThat(result).doesNotExist();
    }

    @Test
    void shouldRunAgainWhenTheWorkflowIsExportedAgain() throws Exception
    {
        // GIVEN
        Path old = ((Ready) processing.request(CHAPTER, "1.png", "upscale", false, WAIT)).file();
        comfy.putUserFile("api_workflows/upscale.json", TestWorkflows.UPSCALE_2X, System.currentTimeMillis() + 5_000);
        catalog.invalidate();

        // WHEN
        Path renewed = ((Ready) processing.request(CHAPTER, "1.png", "upscale", false, WAIT)).file();

        // THEN
        assertThat(renewed).isNotEqualTo(old).exists();
        assertThat(old).doesNotExist();
        assertThat(comfy.prompts()).hasSize(2);
        assertThat(comfy.prompts().getLast().path("prompt").path("2").path("inputs").path("model_name").asText())
                .isEqualTo("2x.pth");
    }

    @Test
    void shouldRunAgainWhenThePageIsReplaced() throws Exception
    {
        // GIVEN
        processing.request(CHAPTER, "1.png", "upscale", false, WAIT);

        // WHEN
        Files.write(chapterDir.resolve("1.png"), TestImages.png(20, 20));
        Outcome outcome = processing.request(CHAPTER, "1.png", "upscale", false, WAIT);

        // THEN
        assertThat(outcome).isInstanceOf(Ready.class);
        assertThat(comfy.prompts()).hasSize(2);
        assertThat(comfy.inputOf(comfy.prompts().getLast())).isEqualTo(TestImages.png(20, 20));
    }

    /** Results are not synced, so a power cut can keep a result's name and lose its data. */
    @Test
    void shouldRunAgainRatherThanSendAResultAPowerCutEmptied() throws Exception
    {
        // GIVEN a processed page whose stored result a power cut emptied.
        byte[] processed = TestImages.png(40, 40);
        comfy.onPrompt = prompt -> new FakeComfyUi.Succeed(processed);
        Path result = ((Ready) processing.request(CHAPTER, "1.png", "upscale", false, WAIT)).file();
        Files.write(result, new byte[0]);

        // WHEN
        Outcome again = processing.request(CHAPTER, "1.png", "upscale", false, WAIT);

        // THEN the page ran again, and its result is whole again under the same name.
        assertThat(again).isEqualTo(new Ready(result));
        assertThat(result).hasBinaryContent(processed);
        assertThat(comfy.prompts()).hasSize(2);
    }

    /** The cache takes back only a whole PNG, so a result stored otherwise would run again on every request. */
    @Test
    void shouldFailAResultThatIsNotAWholePngRatherThanStoreIt() throws Exception
    {
        // GIVEN ComfyUI sending a result cut short.
        byte[] processed = TestImages.png(40, 40);
        comfy.onPrompt = prompt -> new FakeComfyUi.Succeed(Arrays.copyOf(processed, processed.length / 2));

        // WHEN
        Outcome outcome = processing.request(CHAPTER, "1.png", "upscale", false, WAIT);

        // THEN
        assertThat(outcome).isInstanceOfSatisfying(Failed.class, failed ->
        {
            assertThat(failed.problem()).isEqualTo(Problem.FAILED);
            assertThat(failed.message()).contains("not a whole PNG");
        });
        assertThat(resultCache.root().resolve(String.valueOf(CHAPTER))).doesNotExist();
    }

    // ---- helpers -----------------------------------------------------------

    private byte[] write(String name, int size) throws IOException
    {
        byte[] png = TestImages.png(size, size);
        Files.createDirectories(chapterDir);
        Files.write(chapterDir.resolve(name), png);
        return png;
    }

    private Progress progressOf(String filename, String workflow) throws InterruptedException
    {
        Outcome outcome = processing.request(CHAPTER, filename, workflow, false, Duration.ZERO);
        assertThat(outcome).isInstanceOf(Pending.class);
        return ((Pending) outcome).progress();
    }

    /** Page 1 held until {@link #release}; every later prompt succeeds at once. */
    private void holdFirstPrompt() throws Exception
    {
        holdFirstPromptFor(Asker.ANONYMOUS);
    }

    /** @return the prompt id ComfyUI runs page 1 under */
    private String holdFirstPromptFor(Asker asker) throws Exception
    {
        var prompts = new AtomicInteger();
        comfy.onPrompt = prompt -> prompts.getAndIncrement() == 0
                ? new FakeComfyUi.Hold(release, TestImages.png(8, 8)) : new FakeComfyUi.Succeed(TestImages.png(8, 8));
        processing.request(CHAPTER, "1.png", "upscale", false, Duration.ZERO, asker);
        await(() -> comfy.holding().size() == 1);
        return comfy.holding().getFirst();
    }

    private void uncheckedRequest(String filename, Asker asker)
    {
        try
        {
            processing.request(CHAPTER, filename, "upscale", false, Duration.ofSeconds(1), asker);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static Pending queued(int ahead)
    {
        return new Pending(new Progress(Progress.Stage.QUEUED, ahead, null, 0, 0, null));
    }

    private static Problem problemOf(Outcome outcome)
    {
        return outcome instanceof Failed failed ? failed.problem() : null;
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

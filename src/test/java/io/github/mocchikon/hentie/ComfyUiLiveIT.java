package io.github.mocchikon.hentie;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.comfy.ComfyUiClient;
import io.github.mocchikon.hentie.service.comfy.ComfyUiLauncher;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Outcome;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Pending;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Ready;
import io.github.mocchikon.hentie.service.comfy.WorkflowCatalog;

import static org.assertj.core.api.Assertions.*;

/**
 * Against a <b>real</b> ComfyUI, opt-in: {@link FakeComfyUi} is only as right as our reading of the protocol,
 * so this checks that reading, e.g. after a ComfyUI update.
 * <pre>
 * ./mvnw test -Dtest=ComfyUiLiveIT -Dcomfyui.live=true
 *     [-Dcomfyui.live.url=http://127.0.0.1:8188]    where ComfyUI answers
 *     [-Dcomfyui.live.workflow=upscale]             an API workflow in its api_workflows folder
 *     [-Dcomfyui.live.script=C:\...\start.bat]     started (and stopped again) if nothing answers yet
 * </pre>
 * The page is tiny, so a CPU-only ComfyUI gets through an upscaler in seconds.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "comfyui.live", matches = "true")
class ComfyUiLiveIT
{
    private static final int CHAPTER = 92_003;
    private static final String URL = System.getProperty("comfyui.live.url", "http://127.0.0.1:8188");
    private static final String WORKFLOW = System.getProperty("comfyui.live.workflow", "upscale");
    private static final String SCRIPT = System.getProperty("comfyui.live.script", "");

    @Autowired SettingsService settingsService;
    @Autowired AppProperties appProperties;
    @Autowired ComfyUiClient client;
    @Autowired ComfyUiLauncher launcher;
    @Autowired WorkflowCatalog catalog;
    @Autowired PageProcessingService processing;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ImageService imageService;

    private Path chapterDir;
    private int jobTimeout;
    private int startupTimeout;

    @BeforeEach
    void setUp() throws Exception
    {
        jobTimeout = appProperties.getComfyui().getJobTimeoutSeconds();
        startupTimeout = appProperties.getComfyui().getStartupTimeoutSeconds();
        appProperties.getComfyui().setJobTimeoutSeconds(600);
        appProperties.getComfyui().setStartupTimeoutSeconds(600);
        settingsService.setComfyUiUrl(URL);
        settingsService.setComfyUiStartScript(SCRIPT);
        catalog.invalidate();
        if (!client.isReachable() && !SCRIPT.isBlank())
        {
            launcher.start();
            launcher.awaitStartup(Duration.ofMinutes(10));
        }
        assertThat(client.isReachable()).as("ComfyUI answers at " + URL).isTrue();
        chapterDir = imageDirectory.chapterDir(CHAPTER);
        ImageService.deleteRecursively(chapterDir);
        imageService.evictDerived(CHAPTER);
    }

    @AfterEach
    void tearDown()
    {
        // Stops only what this suite started - a ComfyUI that was already running is left alone.
        launcher.stop();
        appProperties.getComfyui().setJobTimeoutSeconds(jobTimeout);
        appProperties.getComfyui().setStartupTimeoutSeconds(startupTimeout);
        settingsService.setComfyUiUrl("");
        settingsService.setComfyUiStartScript("");
        catalog.invalidate();
        imageService.evictDerived(CHAPTER);
        ImageService.deleteRecursively(chapterDir);
    }

    @Test
    void shouldRunTheWorkflowOnAPageWithARealComfyUi() throws Exception
    {
        // GIVEN the workflow is there and usable, and a small page.
        WorkflowCatalog.WorkflowInfo info = catalog.listing().find(WORKFLOW).orElseThrow();
        assertThat(info.problem()).isNull();
        TestImages.writePng(chapterDir.resolve("1.png"), 48, 48);

        // WHEN asked the way the viewer asks: again and again until it is ready.
        Outcome outcome = processing.request(CHAPTER, "1.png", WORKFLOW, false, Duration.ofSeconds(20));
        long deadline = System.currentTimeMillis() + 600_000;
        while (outcome instanceof Pending && System.currentTimeMillis() < deadline)
        {
            outcome = processing.request(CHAPTER, "1.png", WORKFLOW, false, Duration.ofSeconds(20));
        }

        // THEN the result is an image ComfyUI made, kept in the results cache.
        assertThat(outcome).isInstanceOf(Ready.class);
        byte[] png = Files.readAllBytes(((Ready) outcome).file());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isPositive();
        // Asked again, it is the cached result - ComfyUI is not bothered twice.
        assertThat(processing.request(CHAPTER, "1.png", WORKFLOW, false, Duration.ZERO)).isEqualTo(outcome);
    }
}

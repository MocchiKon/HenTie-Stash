package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.comfy.*;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Asker;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Failed;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Pending;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Ready;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The viewer never talks to ComfyUI directly, so ComfyUI stays local and every device goes through this app's
 * login.
 *
 * <p><b>A processed page is not a {@code /data} URL</b>: its answer can be progress or an error message, which
 * an {@code <img>} cannot receive. So the viewer fetches it: {@code 202} with progress, the PNG when ready, JSON
 * with a message on failure.
 */
@Controller
@RequestMapping("/comfyui")
@RequiredArgsConstructor
public class ComfyUiController
{
    /** Well inside a browser's request timeout and {@code app.comfyui.abandon-after-millis}. */
    static final int MAX_WAIT_SECONDS = 20;

    /** The results cache already holds every processed page; the browser's disk cache would write it twice. */
    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final PageProcessingService processing;
    private final WorkflowCatalog catalog;
    private final ComfyUiLauncher launcher;
    private final ComfyUiClient client;
    private final ComfyResultCache resultCache;

    public record PendingView(String state, String message, Integer percent) {}

    public record ErrorView(String error) {}

    public record WorkflowView(String name, boolean valid, String problem) {}

    public record WorkflowsView(boolean reachable, boolean starting, String problem, List<WorkflowView> workflows) {}

    public record StatusView(String state, String summary, String message, List<String> console, boolean launched) {}

    public record CacheSizeView(String size) {}

    /**
     * @param wait     seconds to wait before answering 202; 0 answers at once
     * @param prefetch lower priority than the page being read
     * @param warm     answer 204 instead of the image, for a page further ahead than the viewer keeps in memory
     * @param viewer   the viewer's own id; with {@code seq}, only its latest request is wanted ({@link Asker})
     * @param plan     the pages the viewer will ask for next, page on screen first; a running page still in it
     *                 is not stopped
     */
    @GetMapping("/pages/{chapterId:\\d+}/{filename}")
    public ResponseEntity<?> page(@PathVariable int chapterId, @PathVariable String filename,
                                  @RequestParam String workflow,
                                  @RequestParam(defaultValue = "0") int wait,
                                  @RequestParam(defaultValue = "false") boolean prefetch,
                                  @RequestParam(defaultValue = "false") boolean warm,
                                  @RequestParam(required = false) String viewer,
                                  @RequestParam(defaultValue = "0") long seq,
                                  @RequestParam(required = false) List<String> plan,
                                  HttpServletResponse response)
            throws InterruptedException, IOException
    {
        var outcome = processing.request(chapterId, filename, workflow, prefetch,
                Duration.ofSeconds(Math.clamp(wait, 0, MAX_WAIT_SECONDS)), Asker.of(viewer, seq, plan));
        if (outcome instanceof Ready ready)
        {
            if (warm)
            {
                return ResponseEntity.noContent().cacheControl(NO_STORE).build();
            }
            // Written here, not by a message converter: the entry may be deleted at any moment (an open file can
            // still be read), and a stream handed on is never closed if no converter runs. Already gone means
            // not ready; the viewer asks again.
            try (InputStream body = Files.newInputStream(ready.file()))
            {
                response.setStatus(HttpStatus.OK.value());
                response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE.getHeaderValue());
                response.setContentType(MediaType.IMAGE_PNG_VALUE);
                body.transferTo(response.getOutputStream());
            }
            catch (NoSuchFileException e)
            {
                return pending(PageProcessingService.Progress.queued(0));
            }
            return null;   // the response is written
        }
        if (outcome instanceof Pending pending)
        {
            return pending(pending.progress());
        }
        var failed = (Failed) outcome;
        return ResponseEntity.status(failed.problem().getHttpStatus()).cacheControl(NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorView(failed.message()));
    }

    private static ResponseEntity<PendingView> pending(PageProcessingService.Progress progress)
    {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(NO_STORE).contentType(MediaType.APPLICATION_JSON)
                .body(new PendingView(progress.stage().name().toLowerCase(Locale.ROOT), progress.message(),
                        progress.percent()));
    }

    /**
     * A beacon from a viewer that left, because the server cannot see aborted requests.
     *
     * @param seq higher than any earlier request, so one still on its way starts nothing
     */
    @PostMapping("/leave")
    public ResponseEntity<Void> leave(@RequestParam String viewer, @RequestParam long seq)
    {
        processing.leave(Asker.of(viewer, seq));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/workflows")
    @ResponseBody
    public WorkflowsView workflows() throws InterruptedException
    {
        WorkflowCatalog.Listing listing = catalog.listing();
        return new WorkflowsView(listing.reachable(), launcher.isStarting(), listing.problem(),
                listing.workflows().stream()
                        .map(workflow -> new WorkflowView(workflow.name(), workflow.valid(), workflow.problem()))
                        .toList());
    }

    @GetMapping("/status")
    @ResponseBody
    public StatusView status() throws InterruptedException
    {
        ComfyUiLauncher.Status status = launcher.status();
        return new StatusView(status.state().name(), summary(client, status), status.message(), status.console(),
                status.pid() != null);
    }

    /** Includes the output route, since avoiding ComfyUI's disk depends on a node the user may have to install. */
    static String summary(ComfyUiClient client, ComfyUiLauncher.Status status) throws InterruptedException
    {
        String launched = status.pid() == null ? "" : " - started by the app (pid " + status.pid() + ")";
        ComfyUiClient.SystemInfo info;
        try
        {
            info = client.systemStats();
        }
        catch (ComfyUiException e)
        {
            return status.state() == ComfyUiLauncher.State.STARTING
                    ? "Starting" + launched + " - not answering at " + client.baseUrl() + " yet."
                    : "Not answering at " + client.baseUrl() + launched + ".";
        }
        return "Running at " + client.baseUrl() + " - ComfyUI " + info.version()
                + (info.device().isBlank() ? "" : " on " + info.device()) + launched + outputRoute(client) + ".";
    }

    private static String outputRoute(ComfyUiClient client) throws InterruptedException
    {
        try
        {
            return client.supportsWebsocketOutput()
                    ? "; results arrive over the websocket, so none are written to ComfyUI's disk"
                    : "; results pass through ComfyUI's temp folder, because the SaveImageWebsocket node is not installed";
        }
        catch (ComfyUiException e)
        {
            // Only a detail of the line; not worth failing it.
            return "";
        }
    }

    @PostMapping("/start")
    public String start(RedirectAttributes redirect) throws InterruptedException
    {
        redirect.addFlashAttribute("comfyuiMessage", launcher.start());
        return "redirect:/settings#comfyui-status";
    }

    @PostMapping("/stop")
    public String stop(RedirectAttributes redirect)
    {
        redirect.addFlashAttribute("comfyuiMessage", launcher.stop());
        return "redirect:/settings#comfyui-status";
    }

    /** Its own request, after the Settings page loads, because it walks the cache. */
    @GetMapping("/cache/size")
    @ResponseBody
    public CacheSizeView cacheSize()
    {
        return new CacheSizeView(ImageService.humanReadableSize(resultCache.sizeBytes()));
    }

    /** For a stale result its name cannot reveal, such as a model file replaced under the same name. */
    @PostMapping("/cache/clear")
    public String clearCache(RedirectAttributes redirect)
    {
        long before = resultCache.sizeBytes();
        resultCache.clear(resultCache.root());
        redirect.addFlashAttribute("comfyuiMessage", "Cleared " + ImageService.humanReadableSize(before)
                + " of processed pages.");
        return "redirect:/settings#comfyui-status";
    }
}

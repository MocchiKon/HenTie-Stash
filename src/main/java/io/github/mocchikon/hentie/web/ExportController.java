package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.service.comfy.WorkflowCatalog;
import io.github.mocchikon.hentie.service.export.CbzExportService;
import io.github.mocchikon.hentie.service.export.CbzExportService.ChapterExport;
import io.github.mocchikon.hentie.service.export.CbzExportService.SeriesExport;
import io.github.mocchikon.hentie.service.export.ExportPages;
import io.github.mocchikon.hentie.service.export.ExportPages.ExportCancelledException;
import io.github.mocchikon.hentie.service.export.ExportPages.ExportFailedException;
import io.github.mocchikon.hentie.service.export.ExportProgress;
import io.github.mocchikon.hentie.service.export.ExportPages.Processing;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CBZ downloads of a chapter or a series. The export popup asks for an outline first (file names), so it can open
 * the browser's save dialog at once, then follows the download's progress by the id it sends along ({@code export}).
 *
 * <p><b>Refusals come before the first byte</b> (unknown workflow, a page ComfyUI cannot process while nothing was
 * sent yet) as JSON with a message. Once the archive has started, a failure can only cut the download off, which
 * the browser reports without a reason; the progress then says why.
 */
@Controller
@RequestMapping("/export")
@RequiredArgsConstructor
public class ExportController
{
    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    static final String CBZ_TYPE = "application/vnd.comicbook+zip";

    private final CbzExportService exportService;
    private final ExportPages exportPages;
    private final ExportProgress progress;
    private final WorkflowCatalog catalog;

    public enum Layout { CHAPTERS, ONE_FILE }

    /** @param zipName the per-chapter download of a series; null for a chapter */
    public record Outline(String cbzName, String zipName, List<ChapterPages> chapters) {}

    public record ChapterPages(int id, List<String> pages) {}

    public record ErrorView(String error) {}

    public record ProgressView(String state, int done, int total, String message) {}

    @GetMapping("/chapter/{id:\\d+}/outline")
    @ResponseBody
    public Outline chapterOutline(@PathVariable int id)
    {
        ChapterExport chapter = exportService.chapter(id);
        return new Outline(chapter.name() + ".cbz", null,
                List.of(new ChapterPages(id, exportService.pagesOf(id))));
    }

    @GetMapping("/series/{id:\\d+}/outline")
    @ResponseBody
    public Outline seriesOutline(@PathVariable int id, @RequestParam(required = false) String lang)
    {
        SeriesExport series = exportService.series(id, lang);
        return new Outline(series.name() + ".cbz", series.name() + ".zip", series.chapters().stream()
                .map(chapter -> new ChapterPages(chapter.id(), exportService.pagesOf(chapter.id())))
                .toList());
    }

    /**
     * @param workflow a ComfyUI workflow every page is shown through; blank for the pages as stored
     * @param export   an id the popup made up to follow the progress by
     */
    @GetMapping("/chapter/{id:\\d+}")
    public ResponseEntity<?> chapter(@PathVariable int id, @RequestParam(required = false) String workflow,
                                     @RequestParam(required = false) String export,
                                     HttpServletResponse response) throws IOException, InterruptedException
    {
        ChapterExport chapter = exportService.chapter(id);
        return stream(chapter.name() + ".cbz", CBZ_TYPE, workflow, export, response,
                (processing, tracker, out) -> exportService.writeChapter(chapter, processing, tracker, out));
    }

    @GetMapping("/series/{id:\\d+}")
    public ResponseEntity<?> series(@PathVariable int id, @RequestParam(required = false) String workflow,
                                    @RequestParam(required = false) String export,
                                    @RequestParam(required = false) String lang,
                                    @RequestParam(defaultValue = "CHAPTERS") Layout layout,
                                    HttpServletResponse response) throws IOException, InterruptedException
    {
        SeriesExport series = exportService.series(id, lang);
        if (layout == Layout.ONE_FILE)
        {
            return stream(series.name() + ".cbz", CBZ_TYPE, workflow, export, response,
                    (processing, tracker, out) -> exportService.writeSeriesOneFile(series, processing, tracker, out));
        }
        return stream(series.name() + ".zip", "application/zip", workflow, export, response,
                (processing, tracker, out) -> exportService.writeSeriesPerChapter(series, processing, tracker, out));
    }

    /** 404 for an export this app does not know: not started yet, or long finished. */
    @GetMapping("/progress/{export}")
    public ResponseEntity<ProgressView> progress(@PathVariable String export)
    {
        return progress.get(export)
                .map(p -> new ProgressView(p.state().name(), p.done(), p.total(), p.message()))
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The browser cannot be relied on to end the request: a file it is saving is not cancelled with the popup, and
     * a request it does end is noticed only at the next write, which waits for ComfyUI.
     */
    @PostMapping("/progress/{export}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String export)
    {
        return progress.cancel(export) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @FunctionalInterface
    private interface Export
    {
        void write(Processing processing, ExportProgress.Tracker tracker, OutputStream out)
                throws IOException, InterruptedException;
    }

    private ResponseEntity<?> stream(String fileName, String contentType, String workflow, String exportId,
                                     HttpServletResponse response, Export export)
            throws IOException, InterruptedException
    {
        // First, so that a refusal reaches the popup too when the browser saves the file itself and shows no reason.
        ExportProgress.Tracker tracker = progress.start(exportId);
        Processing processing = null;
        if (StringUtils.isNotBlank(workflow))
        {
            var known = catalog.listing().find(workflow);
            if (known.isEmpty() || !known.get().valid())
            {
                String message = known.isEmpty()
                        ? "There is no ComfyUI workflow called '" + workflow + "'."
                        : "The workflow '" + workflow + "' cannot run: " + known.get().problem();
                tracker.failed(message);
                return error(HttpStatus.NOT_FOUND, message);
            }
            processing = new Processing(workflow);
        }
        Processing processed = processing;
        // Ends the export's interest in ComfyUI, which stops the page it waits for.
        tracker.onCancel(() -> exportPages.release(processed));
        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().cachePrivate().getHeaderValue());
        try
        {
            export.write(processing, tracker, response.getOutputStream());
            tracker.finished();
        }
        catch (ExportFailedException e)
        {
            tracker.failed(e.getMessage());
            if (response.isCommitted())
            {
                log.info("Export of {} cut off: {}", fileName, e.getMessage());
                throw e;
            }
            response.reset();
            return error(e instanceof ExportCancelledException ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY,
                    e.getMessage());
        }
        catch (IOException | RuntimeException e)
        {
            // Usually the browser gave up on the download.
            tracker.failed("The download stopped: " + e.getMessage());
            throw e;
        }
        catch (InterruptedException e)
        {
            tracker.failed("The app is shutting down.");
            throw e;
        }
        return null;   // the response is written
    }

    private static ResponseEntity<ErrorView> error(HttpStatus status, String message)
    {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(new ErrorView(message));
    }
}

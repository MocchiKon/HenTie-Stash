package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.ChapterViewModel;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.SelectedFilters;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.entity.ViewMode;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlDownloader;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.*;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.download.DownloadChoices;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import io.github.mocchikon.hentie.service.download.FavouritesDownloadService;
import io.github.mocchikon.hentie.service.subscription.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequestMapping("/chapter")
@RequiredArgsConstructor
public class ChapterController
{
    private static final String REDIRECT_CHAPTER_QUEUE = "redirect:/chapter/queue";
    private static final String REDIRECT_CHAPTER_DOWNLOAD = "redirect:/chapter/download";
    private static final String DUPLICATE = "duplicate";
    private static final String CHAPTER_NEW = "chapter-new";
    private static final String CHAPTER_EDIT = "chapter-edit";
    private static final String REDIRECT_CHAPTER = "redirect:/chapter/";
    /** The {@code page} value that opens the viewer on the chapter's last page. */
    static final String LAST_PAGE = "last";
    /** Added by {@code app.js} to an in-place image delete ({@link #deleteImageInPlace}). */
    static final String IN_PLACE = "inPlace";

    private final ChapterService chapterService;
    private final ChapterImageService chapterImageService;
    private final ImageService imageService;
    private final ImageStatsService imageStatsService;
    private final MetadataService metadataService;
    private final SettingsService settingsService;
    private final DownloadService downloadService;
    private final DownloadQueueService downloadQueueService;
    private final DownloadWorker downloadWorker;
    private final DataDownloaderRegistry downloaderRegistry;
    private final FavouritesDownloadService favouritesDownloadService;
    private final ImageCompressionModeService compressionModeService;
    private final SubscriptionService subscriptionService;

    // --- Add a chapter manually -------------------------------------------------

    @GetMapping("/new")
    public String newChapter(Model model)
    {
        ChapterForm form = new ChapterForm();
        if (form.getStatus() == null)
        {
            form.setStatus(Status.REVIEWED);
        }
        model.addAttribute("form", form);
        addNewModel(form, model);
        return CHAPTER_NEW;
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") ChapterForm form, BindingResult result,
                         @RequestParam(name = "files", required = false) List<MultipartFile> files,
                         Model model, RedirectAttributes redirectAttributes) throws IOException
    {
        if (result.hasErrors())
        {
            addNewModel(form, model);
            return CHAPTER_NEW;
        }
        int id;
        try
        {
            id = chapterService.create(form);
        }
        catch (DuplicateValueException e)
        {
            result.rejectValue(e.getField(), DUPLICATE, e.getMessage());
            addNewModel(form, model);
            return CHAPTER_NEW;
        }
        catch (DataIntegrityViolationException e)
        {
            rejectDuplicate(result, e);
            addNewModel(form, model);
            return CHAPTER_NEW;
        }
        chapterImageService.upload(id, files).message()
                .ifPresent(message -> redirectAttributes.addFlashAttribute("compressed", message));
        return REDIRECT_CHAPTER + id;
    }

    // --- Bulk download from pasted links ---------------------------------------

    @GetMapping("/download")
    public String downloadPage(Model model)
    {
        // Settings' choices, never the last paste's: those were for one paste only. storableKey turns a deleted
        // mode into None, an option the list really has.
        addDownloadModel(model, "", compressionModeService.storableKey(settingsService.getImageCompressionMode()),
                false, settingsService.getGalleryDlDefaults(), null, null);
        return "chapter-download";
    }

    private void addDownloadModel(Model model, String links, String compressionMode, boolean avoidDuplicateTitles,
                                  GalleryDlOptions galleryDl, String requestDelay, String delayError)
    {
        model.addAttribute("links", links);
        model.addAttribute("linkExamples", downloaderRegistry.linkExamples());
        model.addAttribute("compressionMode", compressionMode);
        model.addAttribute("compressionModes", compressionModeService.options());
        model.addAttribute("avoidDuplicateTitles", avoidDuplicateTitles);
        model.addAttribute("browserGroups", GalleryDlOptions.BROWSER_GROUPS);
        model.addAttribute("cookiesBrowser", StringUtils.defaultString(galleryDl.cookiesBrowser()));
        model.addAttribute("downloadOriginals", galleryDl.originals());
        model.addAttribute("requestDelay", requestDelay == null ? galleryDl.delay() : requestDelay);
        model.addAttribute("delayError", delayError);
        var favouritesSources = favouritesDownloadService.options();
        model.addAttribute("favouritesSources", favouritesSources);
        model.addAttribute("favouritesReady",
                favouritesSources.stream().anyMatch(FavouritesDownloadService.Option::ready));
    }

    /**
     * Redirects to the queue, the only page that can say what became of the links. A delay gallery-dl could not
     * read is shown on the form with the links kept, rather than quietly replaced: it decides how fast a site is
     * asked, which the user chose for a reason.
     */
    @PostMapping("/download")
    public String downloadSubmit(@RequestParam(name = "links", required = false) String links,
                                 @RequestParam(name = "compressionMode", required = false) String compressionMode,
                                 @RequestParam(name = "avoidDuplicateTitles", defaultValue = "false")
                                 boolean avoidDuplicateTitles,
                                 @RequestParam(name = "cookiesBrowser", required = false) String cookiesBrowser,
                                 @RequestParam(name = "downloadOriginals", defaultValue = "false")
                                 boolean downloadOriginals,
                                 @RequestParam(name = "requestDelay", required = false) String requestDelay,
                                 Model model, RedirectAttributes redirectAttributes)
    {
        List<String> parsed = downloadService.parseLinks(links);
        boolean usesGalleryDl = parsed.stream().anyMatch(link -> downloaderRegistry.parse(link)
                .filter(resource -> resource.downloader() instanceof GalleryDlDownloader).isPresent());
        Optional<DownloadChoices> choices = downloadChoices(compressionMode, avoidDuplicateTitles, cookiesBrowser,
                downloadOriginals, requestDelay, usesGalleryDl);
        if (choices.isEmpty())
        {
            return downloadFormWithDelayError(model, links, compressionMode, avoidDuplicateTitles, cookiesBrowser,
                    downloadOriginals, requestDelay);
        }
        var result = downloadService.queue(parsed, choices.get());
        redirectAttributes.addFlashAttribute("enqueued", result);
        return REDIRECT_CHAPTER_QUEUE;
    }

    /** Empty when the delay is refused (see {@link #delayOrDefault}). */
    private Optional<DownloadChoices> downloadChoices(String compressionMode, boolean avoidDuplicateTitles,
                                                      String cookiesBrowser, boolean downloadOriginals,
                                                      String requestDelay, boolean usesGalleryDl)
    {
        return delayOrDefault(requestDelay, usesGalleryDl, settingsService.getGalleryDlDefaults().delay())
                .map(d -> new DownloadChoices(compressionMode, avoidDuplicateTitles,
                        new GalleryDlOptions(cookiesBrowser, downloadOriginals, d)));
    }

    /**
     * The delay a paste or a subscription gets, one rule for both forms. Empty when it is not one gallery-dl can read
     * and gallery-dl will read it, which the form reports. A missing or cleared field means Settings' default, and so
     * does an unreadable one that nothing will read (nhentai and chaika ignore it).
     */
    static Optional<String> delayOrDefault(String requestDelay, boolean readByGalleryDl, String defaultDelay)
    {
        boolean blank = StringUtils.isBlank(requestDelay);
        Optional<String> delay = blank ? Optional.empty() : GalleryDlOptions.normalizedDelay(requestDelay);
        return delay.isEmpty() && (blank || !readByGalleryDl) ? Optional.of(defaultDelay) : delay;
    }

    private String downloadFormWithDelayError(Model model, String links, String compressionMode,
                                              boolean avoidDuplicateTitles, String cookiesBrowser,
                                              boolean downloadOriginals, String requestDelay)
    {
        addDownloadModel(model, StringUtils.defaultString(links), compressionModeService.storableKey(compressionMode),
                avoidDuplicateTitles, new GalleryDlOptions(cookiesBrowser, downloadOriginals, "0"),
                StringUtils.abbreviate(StringUtils.defaultString(requestDelay), 100),
                delayProblem(requestDelay));
        return "chapter-download";
    }

    static String delayProblem(String requestDelay)
    {
        return "\"" + StringUtils.abbreviate(StringUtils.strip(StringUtils.defaultString(requestDelay)), 40)
                + "\" is not a delay gallery-dl understands. Give seconds, e.g. 0.5, or a range for a random "
                + "wait, e.g. 0.4-0.65 (at most " + GalleryDlOptions.MAX_DELAY_SECONDS + ").";
    }

    /**
     * Returns only once the whole list is walked (a long-running button). A refusal goes back to the button,
     * since nothing was queued; everything else to the queue, which then holds what was found.
     */
    @PostMapping("/download/favourites")
    public String downloadFavourites(@RequestParam(name = "source", required = false) String source,
                                     @RequestParam(name = "links", required = false) String links,
                                     @RequestParam(name = "compressionMode", required = false) String compressionMode,
                                     @RequestParam(name = "avoidDuplicateTitles", defaultValue = "false")
                                     boolean avoidDuplicateTitles,
                                     @RequestParam(name = "cookiesBrowser", required = false) String cookiesBrowser,
                                     @RequestParam(name = "downloadOriginals", defaultValue = "false")
                                     boolean downloadOriginals,
                                     @RequestParam(name = "requestDelay", required = false) String requestDelay,
                                     Model model, RedirectAttributes redirectAttributes)
    {
        boolean usesGalleryDl = downloaderRegistry.favouritesSource(source)
                .filter(GalleryDlDownloader.class::isInstance).isPresent();
        Optional<DownloadChoices> choices = downloadChoices(compressionMode, avoidDuplicateTitles, cookiesBrowser,
                downloadOriginals, requestDelay, usesGalleryDl);
        if (choices.isEmpty())
        {
            return downloadFormWithDelayError(model, links, compressionMode, avoidDuplicateTitles, cookiesBrowser,
                    downloadOriginals, requestDelay);
        }
        var outcome = favouritesDownloadService.queueAll(source, choices.get());
        if (outcome.isRefused())
        {
            redirectAttributes.addFlashAttribute("favouritesError", outcome.summary());
            return REDIRECT_CHAPTER_DOWNLOAD;
        }
        redirectAttributes.addFlashAttribute("favourites", outcome);
        return REDIRECT_CHAPTER_QUEUE;
    }

    // --- The download queue -----------------------------------------------------

    @GetMapping("/queue")
    public String queue(Model model)
    {
        model.addAttribute("progress", downloadWorker.progress());
        var pending = downloadQueueService.pending();
        model.addAttribute("pending", pending);
        model.addAttribute("failed", downloadQueueService.failed());
        model.addAttribute("subscriptionTitles", subscriptionService.titlesById());
        model.addAttribute("deferredUntil", deferredUntil(pending));
        model.addAttribute("listLimit", DownloadQueueService.LIST_LIMIT);
        // Once per render, not per row: each mode lookup is a repository read.
        model.addAttribute("compressionModeNames", compressionModeService.namesByKey());
        return "download-queue";
    }

    /** A subscription's rows of a source sitting out a ban wait for it, and the page says until when. */
    private Map<Integer, String> deferredUntil(List<DownloadQueueItem> pending)
    {
        var deferred = new HashMap<Integer, String>();
        LocalDateTime now = LocalDateTime.now();
        for (var item : pending)
        {
            downloadQueueService.deferredUntil(item).ifPresent(until -> deferred.put(item.getId(),
                    SubscriptionService.when(LocalDateTime.ofInstant(until, ZoneId.systemDefault()), now)));
        }
        return deferred;
    }

    @PostMapping("/queue/pause")
    public String pauseQueue(@RequestParam boolean paused)
    {
        downloadWorker.setPaused(paused);
        return REDIRECT_CHAPTER_QUEUE;
    }

    /** {@code ignoreImageErrors} retries in lenient mode: keep the pages that download, skip the ones that fail. */
    @PostMapping("/queue/retry-failed")
    public String retryFailed(@RequestParam(defaultValue = "false") boolean ignoreImageErrors,
                              RedirectAttributes redirectAttributes)
    {
        redirectAttributes.addFlashAttribute("retriedCount", downloadQueueService.retryFailed(ignoreImageErrors));
        redirectAttributes.addFlashAttribute("retriedIgnoringImageErrors", ignoreImageErrors);
        downloadWorker.kick();
        return REDIRECT_CHAPTER_QUEUE;
    }

    /**
     * {@code allowDuplicateTitle} is the only way past a duplicate-title refusal: a plain retry keeps the
     * check, so it would only fail again.
     */
    @PostMapping("/queue/{id:\\d+}/retry")
    public String retryQueued(@PathVariable int id,
                              @RequestParam(defaultValue = "false") boolean ignoreImageErrors,
                              @RequestParam(name = "allowDuplicateTitle", defaultValue = "false")
                              boolean allowDuplicateTitle,
                              RedirectAttributes redirectAttributes)
    {
        if (downloadQueueService.retry(id, ignoreImageErrors, allowDuplicateTitle))
        {
            redirectAttributes.addFlashAttribute("retriedCount", 1);
            redirectAttributes.addFlashAttribute("retriedIgnoringImageErrors", ignoreImageErrors);
            downloadWorker.kick();
        }
        return REDIRECT_CHAPTER_QUEUE;
    }

    @PostMapping("/queue/clear-failed")
    public String clearFailed(RedirectAttributes redirectAttributes)
    {
        redirectAttributes.addFlashAttribute("clearedCount", downloadQueueService.deleteFailed());
        return REDIRECT_CHAPTER_QUEUE;
    }

    /** Through the worker, not the queue service: it owns the staging folder of the item it is running. */
    @PostMapping("/queue/{id:\\d+}/remove")
    public String removeQueued(@PathVariable int id)
    {
        downloadWorker.remove(id);
        return REDIRECT_CHAPTER_QUEUE;
    }

    // --- Existing chapters (numeric ids only, so /new, /download and /queue never clash) ---

    /**
     * {@code review} renders review mode's action bar; which item comes next is known only to the browser. The stats
     * are checked against what the view read, so a chapter in sync costs nothing more; one that was repaired is read
     * again, so the page reports the repaired numbers.
     */
    @GetMapping("/{id:\\d+}")
    public String view(@PathVariable int id, @RequestParam(defaultValue = "false") boolean review, Model model)
    {
        ChapterViewModel vm = chapterService.buildView(id);
        if (imageStatsService.healIfDrifted(id, vm.getPageCount(), vm.getPageUrls().size()))
        {
            vm = chapterService.buildView(id);
        }
        model.addAttribute("vm", vm);
        model.addAttribute("review", review);
        String compressionMode = vm.getChapter().getCompressionMode();
        model.addAttribute("compressedWith", compressionMode == null
                ? null : compressionModeService.nameOf(compressionMode).orElse("a mode since deleted"));
        model.addAttribute("canRedownloadFullQuality", downloadService.fullQualityLink(vm.getChapter()).isPresent());
        model.addAttribute("sourcePageLink", downloadService.sourcePageLink(vm.getChapter()).orElse(null));
        return "chapter-view";
    }

    /**
     * Refused for a chapter that is not compressed or has no known source. The button is hidden then, but a
     * page left open while the chapter changed can still post it.
     */
    @PostMapping("/{id:\\d+}/redownload")
    public String redownloadFullQuality(@PathVariable int id, RedirectAttributes redirectAttributes)
    {
        if (!downloadService.queueFullQuality(chapterService.get(id)))
        {
            redirectAttributes.addFlashAttribute("redownloadError", "This chapter cannot be downloaded again in "
                    + "full quality: its pages are not compressed, or it comes from no source this app can fetch from.");
            return REDIRECT_CHAPTER + id;
        }
        redirectAttributes.addFlashAttribute("redownloadQueued", id);
        return REDIRECT_CHAPTER_QUEUE;
    }

    /** Review mode posts this by fetch and moves on itself; the redirect is for a plain form post. */
    @PostMapping("/{id:\\d+}/mark-reviewed")
    public String markReviewed(@PathVariable int id)
    {
        chapterService.markReviewed(id);
        return REDIRECT_CHAPTER + id;
    }

    /**
     * Touches the database <b>not at all</b>: the detail view model would load metadata and every sibling
     * in the series for things this page cannot show, so the next chapter is asked for only at the end
     * ({@link #neighbour}). Hence no stats self-heal (every route here already passed one) and no 404 for a
     * bogus id - both would need the lookup this avoids.
     */
    @GetMapping("/{id:\\d+}/view")
    public String imageView(@PathVariable int id, @RequestParam(defaultValue = "1") String page, Model model)
    {
        List<String> pageUrls = imageService.pageUrls(id);
        model.addAttribute("chapterId", id);
        model.addAttribute("pageUrls", pageUrls);
        model.addAttribute("startPage", startPage(page, pageUrls.size()));
        model.addAttribute("defaultViewMode", settingsService.getDefaultViewMode());
        model.addAttribute("viewModes", ViewMode.values());
        model.addAttribute("pagesAhead", settingsService.getPagesAhead());
        // Only the name: the page fetches the workflow list itself, so a slow or stopped ComfyUI never delays it.
        model.addAttribute("defaultWorkflow", settingsService.getDefaultWorkflow());
        return "image-view";
    }

    /**
     * {@code last} lets the viewer step back into the previous chapter's last page, whose number only the
     * server knows. Anything else that is not a number opens page 1.
     */
    static int startPage(String page, int pageCount)
    {
        if (LAST_PAGE.equalsIgnoreCase(StringUtils.strip(page)))
        {
            return Math.max(1, pageCount);
        }
        return NumberUtils.toInt(StringUtils.strip(page), 1);
    }

    /**
     * Asked only at a chapter's end, so opening the viewer touches no row. 204 when there is no neighbour
     * (no series, or the last/first of its language).
     */
    @GetMapping(value = "/{id:\\d+}/neighbour", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<ChapterService.Neighbour> neighbour(@PathVariable int id,
                                                              @RequestParam(defaultValue = "next") String direction)
    {
        Optional<ChapterService.NeighbourChapter> found =
                chapterService.neighbour(id, !"previous".equalsIgnoreCase(direction));
        found.ifPresent(next -> imageStatsService.healIfDrifted(next.neighbour().id(), next.storedPageNum()));
        return found.map(next -> ResponseEntity.ok(next.neighbour()))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id:\\d+}/edit")
    public String edit(@PathVariable int id, Model model)
    {
        ChapterForm form = chapterService.toForm(id);
        model.addAttribute("form", form);
        addEditModel(id, form, model);
        return CHAPTER_EDIT;
    }

    @PostMapping("/{id:\\d+}")
    public String save(@PathVariable int id, @Valid @ModelAttribute("form") ChapterForm form,
                       BindingResult result, Model model)
    {
        form.setId(id);
        if (result.hasErrors())
        {
            addEditModel(id, form, model);
            return CHAPTER_EDIT;
        }
        try
        {
            chapterService.update(form);
        }
        catch (DuplicateValueException e)
        {
            result.rejectValue(e.getField(), DUPLICATE, e.getMessage());
            addEditModel(id, form, model);
            return CHAPTER_EDIT;
        }
        catch (DataIntegrityViolationException e)
        {
            rejectDuplicate(result, e);
            addEditModel(id, form, model);
            return CHAPTER_EDIT;
        }
        return REDIRECT_CHAPTER + id;
    }

    @PostMapping("/{id:\\d+}/delete")
    public String delete(@PathVariable int id)
    {
        chapterService.delete(id);
        return "redirect:/search";
    }

    @PostMapping("/{id:\\d+}/images")
    public String uploadImages(@PathVariable int id, @RequestParam("files") List<MultipartFile> files,
                               RedirectAttributes redirectAttributes) throws IOException
    {
        chapterImageService.upload(id, files).message()
                .ifPresent(message -> redirectAttributes.addFlashAttribute("compressed", message));
        return REDIRECT_CHAPTER + id + "/edit";
    }

    @PostMapping("/{id:\\d+}/images/compress")
    public String compressImages(@PathVariable int id, RedirectAttributes redirectAttributes)
    {
        redirectAttributes.addFlashAttribute("compressed", chapterImageService.compressExistingAndDescribe(id));
        return REDIRECT_CHAPTER + id + "/edit#images";
    }

    /**
     * The no-JavaScript fallback of {@link #deleteImageInPlace}. Lands on the thumbnail that takes the deleted
     * one's place, so a chapter of hundreds of pages needs no scrolling back.
     */
    @PostMapping("/{id:\\d+}/images/delete")
    public String deleteImage(@PathVariable int id, @RequestParam String filename) throws IOException
    {
        String anchor = anchorAfterDeleting(id, filename);
        chapterService.deletePage(id, filename);
        return REDIRECT_CHAPTER + id + "/edit#" + anchor;
    }

    /**
     * 204, not a redirect: only a 204 proves the delete happened, since an expired login also redirects and
     * a script cannot see where to.
     */
    @PostMapping(value = "/{id:\\d+}/images/delete", params = IN_PLACE)
    @ResponseBody
    public ResponseEntity<Void> deleteImageInPlace(@PathVariable int id, @RequestParam String filename)
            throws IOException
    {
        chapterService.deletePage(id, filename);
        return ResponseEntity.noContent().build();
    }

    /**
     * The edit page numbers thumbnails by position ({@code page-1}, ...), so this names the one that will stand
     * where {@code filename} stands now, or the image section when there is none.
     */
    private String anchorAfterDeleting(int id, String filename)
    {
        List<String> pages = imageService.pageNames(id);
        int position = pages.indexOf(filename) + 1;
        int remaining = pages.size() - 1;
        return position == 0 || remaining == 0 ? "images" : "page-" + Math.min(position, remaining);
    }

    /** For pages that arrived without the app writing them, which leaves the stored stats and cached listing behind. */
    @PostMapping("/{id:\\d+}/images/rescan")
    public String rescanImages(@PathVariable int id, RedirectAttributes redirectAttributes)
    {
        redirectAttributes.addFlashAttribute("imagesRescanned", chapterService.rescanImages(id));
        return REDIRECT_CHAPTER + id + "/edit#images";
    }

    private void addNewModel(ChapterForm form, Model model)
    {
        model.addAttribute("selected", selectedFor(form));
        model.addAttribute("statuses", Status.values());
        model.addAttribute("imageAccept", imageService.acceptAttribute());
        // Shown by the upload input: an upload blocks until compression is done.
        model.addAttribute("compressionModeName",
                compressionModeService.displayName(settingsService.getImageCompressionMode()));
    }

    private void addEditModel(int id, ChapterForm form, Model model)
    {
        addNewModel(form, model);
        model.addAttribute("pageUrls", imageService.pageUrls(id));
    }

    /**
     * Backstop only: duplicates and over-long values are normally caught before saving
     * ({@link DuplicateValueException}, {@code @Size}). This covers the rest, such as a lost unique-key race.
     */
    static void rejectDuplicate(BindingResult result, DataIntegrityViolationException e)
    {
        if (SqlStates.isValueTooLong(e))
        {
            result.reject("toolong", "One of the text fields is too long (maximum 255 characters).");
        }
        else if (SqlStates.isUniqueViolation(e))
        {
            result.reject(DUPLICATE, "A value that must be unique (e.g. gallery ID) is already in use.");
        }
        else
        {
            result.reject("integrity", "Could not save, please check the values and try again.");
        }
    }

    private SelectedFilters selectedFor(ChapterForm form)
    {
        return SelectedFilters.builder()
                .tags(metadataService.resolve(MetadataType.TAG, form.getTagIds()))
                .artists(metadataService.resolve(MetadataType.ARTIST, form.getArtistIds()))
                .characters(metadataService.resolve(MetadataType.CHARACTER, form.getCharacterIds()))
                .parodies(metadataService.resolve(MetadataType.PARODY, form.getParodyIds()))
                .groups(metadataService.resolve(MetadataType.GROUP, form.getGroupIds()))
                .categories(metadataService.resolve(MetadataType.CATEGORY, form.getCategoryIds()))
                .build();
    }
}

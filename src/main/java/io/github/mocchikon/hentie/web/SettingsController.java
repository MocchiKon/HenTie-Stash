package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.JxlDelivery;
import io.github.mocchikon.hentie.dto.ScratchFolderView;
import io.github.mocchikon.hentie.dto.TitleDisplayMode;
import io.github.mocchikon.hentie.entity.ViewMode;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlTool;
import io.github.mocchikon.hentie.service.AppShutdown;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.comfy.ComfyResultCache;
import io.github.mocchikon.hentie.service.comfy.ComfyUiClient;
import io.github.mocchikon.hentie.service.comfy.ComfyUiLauncher;
import io.github.mocchikon.hentie.service.comfy.WorkflowCatalog;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.compress.JxlTranscoder;
import io.github.mocchikon.hentie.service.scratch.ScratchArea;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

@Controller
@RequiredArgsConstructor
public class SettingsController
{
    /** What BCrypt reads of a password; see {@link #passwordProblem}. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final SettingsService settingsService;
    private final ImageCompressionModeService compressionModeService;
    private final ScratchSpace scratchSpace;
    private final JxlTranscoder jxlTranscoder;
    private final WorkflowCatalog workflowCatalog;
    private final ComfyUiLauncher comfyUiLauncher;
    private final ComfyResultCache comfyResultCache;
    private final AppShutdown appShutdown;
    private final WriteGate writeGate;
    private final GalleryDlTool galleryDlTool;

    @GetMapping("/settings")
    public String index(Model model) throws InterruptedException
    {
        model.addAttribute("loginRequired", settingsService.isLoginRequired());
        model.addAttribute("hasPassword", settingsService.hasPassword());
        model.addAttribute("viewMode", settingsService.getDefaultViewMode());
        model.addAttribute("viewModes", ViewMode.values());
        model.addAttribute("pagesAhead", settingsService.getPagesAhead());
        model.addAttribute("maxPagesAhead", SettingsService.MAX_PAGES_AHEAD);
        model.addAttribute("searchPageSize", settingsService.getSearchPageSize());
        model.addAttribute("nhentaiApiKey", settingsService.getNhentaiApiKey());
        model.addAttribute("titleDisplayMode", settingsService.getTitleDisplayMode());
        model.addAttribute("titleDisplayModes", TitleDisplayMode.values());
        model.addAttribute("matchAutoLink", settingsService.isMatchAutoLinkEnabled());
        model.addAttribute("matchThreshold", settingsService.getMatchThreshold());
        model.addAttribute("compressionMode", settingsService.getImageCompressionMode());
        model.addAttribute("compressionModes", compressionModeService.options());
        model.addAttribute("systemImageTools", settingsService.isSystemImageToolsEnabled());
        model.addAttribute("systemGalleryDl", settingsService.isSystemGalleryDlEnabled());
        model.addAttribute("galleryDlDefaults", settingsService.getGalleryDlDefaults());
        model.addAttribute("browserGroups", GalleryDlOptions.BROWSER_GROUPS);
        model.addAttribute("galleryDlStatus", galleryDlTool.status());
        model.addAttribute("jxlDelivery", settingsService.getJxlDelivery());
        model.addAttribute("jxlDeliveries", JxlDelivery.values());
        model.addAttribute("scratchFolders", scratchFolders());
        model.addAttribute("ramDisk", scratchSpace.ramDisk()
                .map(disk -> disk.root() + " - " + ImageService.humanReadableSize(disk.usableBytes()) + " free of "
                        + ImageService.humanReadableSize(disk.totalBytes()))
                .orElse(null));
        model.addAttribute("maxPathLength", ScratchSpace.MAX_PATH_LENGTH);
        model.addAttribute("libraryFolder", scratchSpace.libraryFolder());
        addComfyUiModel(model);
        return "settings";
    }

    private void addComfyUiModel(Model model) throws InterruptedException
    {
        String defaultWorkflow = settingsService.getDefaultWorkflow();
        WorkflowCatalog.Listing listing = workflowCatalog.listing();
        ComfyUiLauncher.Status status = comfyUiLauncher.status();
        model.addAttribute("comfyUrl", settingsService.getComfyUiUrl());
        model.addAttribute("comfyWorkflowDir", settingsService.getComfyUiWorkflowDir());
        model.addAttribute("defaultWorkflow", defaultWorkflow);
        // Keeps a missing stored choice selectable, so saving the page does not quietly reset it to None.
        model.addAttribute("defaultWorkflowMissing", !defaultWorkflow.isEmpty()
                && listing.find(defaultWorkflow).filter(WorkflowCatalog.WorkflowInfo::valid).isEmpty());
        model.addAttribute("comfyListing", listing);
        model.addAttribute("comfyAutostart", settingsService.isComfyUiAutostart());
        model.addAttribute("comfyStartScript", settingsService.getComfyUiStartScript());
        model.addAttribute("comfyStatus", status);
        // No summary line or cache size here: one asks ComfyUI, the other walks the cache. app.js fetches both later.
        model.addAttribute("comfyConsole", String.join("\n", status.console()));
        model.addAttribute("comfyCacheDir", comfyResultCache.root());
    }

    private List<ScratchFolderView> scratchFolders()
    {
        var folders = new ArrayList<ScratchFolderView>();
        for (ScratchArea area : ScratchArea.values())
        {
            List<String> inUse = switch (area)
            {
                case DOWNLOAD_STAGING ->
                {
                    ScratchSpace.Location compressed = scratchSpace.downloadStaging(true);
                    ScratchSpace.Location plain = scratchSpace.downloadStaging(false);
                    yield compressed.equals(plain)
                            ? List.of(describe(plain))
                            : List.of("Compressed downloads: " + describe(compressed),
                                    "Other downloads: " + describe(plain));
                }
                case COMPRESSION_WORK -> List.of(describe(scratchSpace.compressionWork()));
                case TRANSCODE_CACHE -> List.of(describe(scratchSpace.transcodeCache()));
                case COMFYUI_RESULTS -> List.of(describe(scratchSpace.comfyResults()));
            };
            folders.add(new ScratchFolderView(area, scratchSpace.settingValue(area), inUse));
        }
        return folders;
    }

    private static String describe(ScratchSpace.Location location)
    {
        return location.root() + " (" + location.origin().getLabel() + ")";
    }

    @PostMapping("/settings")
    public String save(@RequestParam(name = "loginRequired", defaultValue = "false") boolean loginRequired,
                       @RequestParam ViewMode viewMode,
                       @RequestParam(name = "pagesAhead",
                               defaultValue = "" + SettingsService.DEFAULT_PAGES_AHEAD) int pagesAhead,
                       @RequestParam(name = "searchPageSize",
                               defaultValue = "" + SettingsService.DEFAULT_SEARCH_PAGE_SIZE) int searchPageSize,
                       @RequestParam(required = false) String nhentaiApiKey,
                       @RequestParam TitleDisplayMode titleDisplayMode,
                       @RequestParam(name = "matchAutoLink", defaultValue = "false") boolean matchAutoLink,
                       @RequestParam(name = "matchThreshold",
                               defaultValue = "" + SettingsService.DEFAULT_MATCH_THRESHOLD) int matchThreshold,
                       @RequestParam(name = "compressionMode", required = false) String compressionMode,
                       @RequestParam(name = "systemImageTools", defaultValue = "false") boolean systemImageTools,
                       @RequestParam(name = "systemGalleryDl", defaultValue = "false") boolean systemGalleryDl,
                       @RequestParam(name = "jxlDelivery", defaultValue = "AUTO") JxlDelivery jxlDelivery,
                       @RequestParam(name = "comfyAutostart", defaultValue = "false") boolean comfyAutostart,
                       @RequestParam Map<String, String> params,
                       RedirectAttributes redirect)
    {
        // Each setting is its own write, and the password file is written before any of them: a busy library must
        // refuse the form before the first change, not leave it half saved.
        writeGate.claimTurn();
        Optional<String> loginRefusal = saveLoginRequired(loginRequired, params.get("newPassword"),
                params.get("newPasswordRepeat"));
        settingsService.setDefaultViewMode(viewMode);
        settingsService.setPagesAhead(pagesAhead);
        settingsService.setSearchPageSize(searchPageSize);
        settingsService.setNhentaiApiKey(nhentaiApiKey);
        settingsService.setTitleDisplayMode(titleDisplayMode);
        settingsService.setMatchAutoLinkEnabled(matchAutoLink);
        settingsService.setMatchThreshold(matchThreshold);
        // Never raw: "Custom" and other non-modes must not be stored.
        settingsService.setImageCompressionMode(compressionModeService.storableKey(compressionMode));
        settingsService.setSystemImageToolsEnabled(systemImageTools);
        settingsService.setJxlDelivery(jxlDelivery);
        Optional<String> galleryDlRefusal = saveGalleryDl(params, systemGalleryDl);
        List<String> comfyRefusals = saveComfyUi(params, comfyAutostart);
        Optional<String> refusal = saveScratchFolders(params);
        if (loginRefusal.isPresent() || refusal.isPresent() || !comfyRefusals.isEmpty() || galleryDlRefusal.isPresent())
        {
            galleryDlRefusal.ifPresent(reason -> redirect.addFlashAttribute("galleryDlError", reason));
            // Not "?saved": a green "Settings saved." above a refusal reads as if the refused value took effect.
            loginRefusal.ifPresent(reason -> redirect.addFlashAttribute("loginError", reason));
            refusal.ifPresent(reason -> redirect.addFlashAttribute("storageError", reason));
            if (!comfyRefusals.isEmpty())
            {
                redirect.addFlashAttribute("comfyuiError", "The other settings were saved, but "
                        + String.join(" Also, ", comfyRefusals));
            }
            return "redirect:/settings";
        }
        return "redirect:/settings?saved";
    }

    /**
     * A delay gallery-dl cannot read keeps the old one, with a reason; the rest is saved. Missing fields (a POST
     * without this section) keep their values.
     *
     * @return why the delay was not changed
     */
    private Optional<String> saveGalleryDl(Map<String, String> params, boolean system)
    {
        // The delay field is always posted with the section; an unticked checkbox is not posted at all.
        if (!params.containsKey("galleryDlDelay"))
        {
            return Optional.empty();
        }
        if (system != settingsService.isSystemGalleryDlEnabled())
        {
            settingsService.setSystemGalleryDlEnabled(system);
            galleryDlTool.refreshVersionAsync();
        }
        GalleryDlOptions before = settingsService.getGalleryDlDefaults();
        Optional<String> delay = GalleryDlOptions.normalizedDelay(params.get("galleryDlDelay"));
        settingsService.setGalleryDlDefaults(new GalleryDlOptions(params.get("galleryDlCookiesBrowser"),
                Boolean.parseBoolean(params.get("galleryDlOriginals")), delay.orElse(before.delay())));
        return delay.isPresent() ? Optional.empty() : Optional.of("The other settings were saved, but "
                + ChapterController.delayProblem(params.get("galleryDlDelay")) + " The default delay was not changed.");
    }

    @PostMapping("/settings/gallery-dl/update")
    public String updateGalleryDl(RedirectAttributes redirect)
    {
        redirect.addFlashAttribute("galleryDlUpdate", galleryDlTool.update());
        return "redirect:/settings#gallery-dl-status";
    }

    /**
     * Without a password, turning login on takes one from the fields beside the box; without a usable one
     * login stays off, since otherwise everybody would be locked out.
     *
     * @return why login was not turned on; empty when the choice was saved
     */
    private Optional<String> saveLoginRequired(boolean required, String password, String repeat)
    {
        if (required && !settingsService.hasPassword())
        {
            Optional<String> problem = passwordProblem(password, repeat);
            if (problem.isPresent())
            {
                return Optional.of("The other settings were saved, but login was not turned on: " + problem.get());
            }
            settingsService.changePassword(password);
        }
        settingsService.setLoginRequired(required);
        return Optional.empty();
    }

    /** BCrypt reads only the first 72 bytes, so a longer password is refused rather than silently cut. */
    static Optional<String> passwordProblem(String password, String repeat)
    {
        if (StringUtils.isBlank(password))
        {
            return Optional.of("no password has been set yet, so one has to be entered first.");
        }
        if (!password.equals(repeat))
        {
            return Optional.of("the two passwords did not match.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES)
        {
            return Optional.of("the password is too long - at most " + MAX_PASSWORD_BYTES
                    + " characters, fewer with accented letters.");
        }
        return Optional.empty();
    }

    /**
     * Refused one by one, since (unlike the folders) no value's validity depends on another. A missing field
     * keeps its value.
     *
     * @return why values were refused, one sentence each
     */
    private List<String> saveComfyUi(Map<String, String> params, boolean autostart)
    {
        var refusals = new ArrayList<String>();
        String urlBefore = settingsService.getComfyUiUrl();
        String folderBefore = settingsService.getComfyUiWorkflowDir();
        if (params.containsKey("comfyUrl"))
        {
            ComfyUiClient.normalizeAddress(params.get("comfyUrl")).ifPresentOrElse(settingsService::setComfyUiUrl,
                    () -> refusals.add("the ComfyUI address \"" + shown(params.get("comfyUrl")) + "\" is not an http:// "
                            + "or https:// address, so it was not changed."));
        }
        if (params.containsKey("comfyWorkflowDir"))
        {
            WorkflowCatalog.normalizeFolder(params.get("comfyWorkflowDir")).ifPresentOrElse(
                    settingsService::setComfyUiWorkflowDir,
                    () -> refusals.add("the workflow folder \"" + shown(params.get("comfyWorkflowDir")) + "\" is not a "
                            + "folder inside ComfyUI's user folder, so it was not changed."));
        }
        if (params.containsKey("defaultWorkflow"))
        {
            String workflow = StringUtils.strip(params.get("defaultWorkflow"));
            if (StringUtils.isEmpty(workflow) || WorkflowCatalog.isValidName(workflow))
            {
                settingsService.setDefaultWorkflow(StringUtils.defaultString(workflow));
            }
            else
            {
                refusals.add("\"" + shown(workflow) + "\" cannot be a workflow name, so the default workflow was not "
                        + "changed.");
            }
        }
        if (params.containsKey("comfyStartScript"))
        {
            String script = StringUtils.strip(params.get("comfyStartScript"));
            Optional<Path> path = script.length() > ScratchSpace.MAX_PATH_LENGTH
                    ? Optional.empty() : ComfyUiLauncher.scriptPath(script);
            Optional<String> problem = path.isEmpty()
                    ? Optional.of("the start script " + StringUtils.abbreviate(script, 200) + " is not a path")
                    : comfyUiLauncher.problemWith(path.get());
            if (StringUtils.isEmpty(script))
            {
                settingsService.setComfyUiStartScript("");
            }
            else if (problem.isEmpty())
            {
                // The resolved path: pasted quotes removed, relative made absolute.
                settingsService.setComfyUiStartScript(path.get().toString());
            }
            else
            {
                refusals.add(problem.get() + ", so it was not changed.");
            }
        }
        // An unticked checkbox sends nothing, so the script field beside it is what says the form carried it.
        if (params.containsKey("comfyStartScript"))
        {
            if (autostart && StringUtils.isBlank(settingsService.getComfyUiStartScript()))
            {
                refusals.add("ComfyUI cannot be started automatically until a start script is set, so autostart "
                        + "was not turned on.");
            }
            else
            {
                settingsService.setComfyUiAutostart(autostart);
            }
        }
        // Only on a change: invalidating makes the next Settings render wait for a fresh listing, which costs
        // a refused connection while ComfyUI is down.
        if (!urlBefore.equals(settingsService.getComfyUiUrl())
                || !folderBefore.equals(settingsService.getComfyUiWorkflowDir()))
        {
            workflowCatalog.invalidate();
        }
        return refusals;
    }

    private static String shown(String value)
    {
        return StringUtils.abbreviate(StringUtils.strip(value), 200);
    }

    /** A missing field keeps its value, so a POST without these fields cannot clear them; a blank one means "automatic". */
    private Optional<String> saveScratchFolders(Map<String, String> params)
    {
        var requested = new EnumMap<ScratchArea, String>(ScratchArea.class);
        for (ScratchArea area : ScratchArea.values())
        {
            if (params.containsKey(area.getFormField()))
            {
                requested.put(area, params.get(area.getFormField()));
            }
        }
        if (requested.isEmpty())
        {
            return Optional.empty();
        }
        Path cacheBefore = scratchSpace.transcodeCache().root();
        Path resultsBefore = scratchSpace.comfyResults().root();
        Optional<String> refusal = scratchSpace.update(requested);
        if (refusal.isEmpty() && !cacheBefore.equals(scratchSpace.transcodeCache().root()))
        {
            jxlTranscoder.clear(cacheBefore);
        }
        if (refusal.isEmpty() && !resultsBefore.equals(scratchSpace.comfyResults().root()))
        {
            comfyResultCache.clear(resultsBefore);
        }
        return refusal.map(reason -> "The other settings were saved, but the temporary file folders were not "
                + "changed: " + reason);
    }

    @PostMapping("/settings/password")
    public String changePassword(@RequestParam(required = false) String password,
                                 @RequestParam(required = false) String passwordRepeat,
                                 RedirectAttributes redirect)
    {
        Optional<String> problem = passwordProblem(password, passwordRepeat);
        if (problem.isPresent())
        {
            redirect.addFlashAttribute("passwordError", "The password was not changed: " + problem.get());
            return "redirect:/settings";
        }
        settingsService.changePassword(password);
        return "redirect:/settings?passwordChanged";
    }

    /** A page, not a redirect: the server takes no new requests once shutdown starts, so a redirect would not load. */
    @PostMapping("/settings/shutdown")
    public String shutDown()
    {
        appShutdown.shutDownSoon();
        return "shutdown";
    }
}

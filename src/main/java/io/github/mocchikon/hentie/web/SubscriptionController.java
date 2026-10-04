package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.SubscriptionForm;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.SearchSource;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.subscription.SubscriptionRunner;
import io.github.mocchikon.hentie.service.subscription.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Every change wakes the runner, so a new or edited subscription starts listing at once rather than on the runner's
 * next idle round.
 */
@Controller
@RequestMapping("/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController
{
    private static final String FORM_VIEW = "subscription-form";
    private static final String REDIRECT_LIST = "redirect:/subscriptions";
    private static final String GONE = "That subscription no longer exists.";

    private final SubscriptionService subscriptionService;
    private final SubscriptionRunner runner;
    private final DataDownloaderRegistry registry;
    private final ImageCompressionModeService compressionModeService;
    private final SettingsService settingsService;

    @GetMapping
    public String list(Model model)
    {
        model.addAttribute("subscriptions", subscriptionService.overview());
        return "subscriptions";
    }

    @GetMapping("/new")
    public String create(Model model)
    {
        model.addAttribute("form", subscriptionService.newForm());
        addFormModel(model);
        return FORM_VIEW;
    }

    @GetMapping("/{id:\\d+}/edit")
    public String edit(@PathVariable int id, Model model, RedirectAttributes redirect)
    {
        Optional<SubscriptionForm> form = subscriptionService.form(id);
        if (form.isEmpty())
        {
            redirect.addFlashAttribute("problem", GONE);
            return REDIRECT_LIST;
        }
        model.addAttribute("form", form.get());
        addFormModel(model);
        return FORM_VIEW;
    }

    /**
     * The delay follows the Download page's rule ({@link ChapterController#delayOrDefault}). What only the site can
     * judge comes back from the service.
     */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") SubscriptionForm form, BindingResult result, Model model,
                       RedirectAttributes redirect)
    {
        GalleryDlOptions galleryDl = galleryDlOptions(form, result);
        if (result.hasErrors())
        {
            addFormModel(model);
            return FORM_VIEW;
        }
        try
        {
            subscriptionService.save(form, galleryDl);
        }
        catch (SubscriptionService.Refused e)
        {
            result.rejectValue(e.getField(), "refused", e.getMessage());
            addFormModel(model);
            return FORM_VIEW;
        }
        catch (NoSuchElementException e)
        {
            redirect.addFlashAttribute("problem", GONE);
            return REDIRECT_LIST;
        }
        runner.kick();
        redirect.addFlashAttribute("saved", StringUtils.defaultIfBlank(form.getName(), form.getQuery()));
        return REDIRECT_LIST;
    }

    private GalleryDlOptions galleryDlOptions(SubscriptionForm form, BindingResult result)
    {
        String defaultDelay = settingsService.getGalleryDlDefaults().delay();
        Optional<String> delay = ChapterController.delayOrDefault(form.getRequestDelay(),
                subscriptionService.usesGalleryDl(form.getSource()), defaultDelay);
        if (delay.isEmpty())
        {
            result.rejectValue("requestDelay", "invalid", ChapterController.delayProblem(form.getRequestDelay()));
        }
        return new GalleryDlOptions(form.getCookiesBrowser(), form.isDownloadOriginals(), delay.orElse(defaultDelay));
    }

    @PostMapping("/{id:\\d+}/delete")
    public String delete(@PathVariable int id, RedirectAttributes redirect)
    {
        subscriptionService.delete(id).ifPresentOrElse(title -> redirect.addFlashAttribute("deleted", title),
                () -> redirect.addFlashAttribute("problem", GONE));
        return REDIRECT_LIST;
    }

    @PostMapping("/{id:\\d+}/pause")
    public String pause(@PathVariable int id, RedirectAttributes redirect)
    {
        return changed(subscriptionService.setEnabled(id, false), redirect);
    }

    @PostMapping("/{id:\\d+}/resume")
    public String resume(@PathVariable int id, RedirectAttributes redirect)
    {
        return changed(subscriptionService.setEnabled(id, true), redirect);
    }

    @PostMapping("/{id:\\d+}/check")
    public String checkNow(@PathVariable int id, RedirectAttributes redirect)
    {
        return changed(subscriptionService.checkNow(id), redirect);
    }

    @PostMapping("/{id:\\d+}/restart")
    public String startOver(@PathVariable int id, RedirectAttributes redirect)
    {
        return changed(subscriptionService.startOver(id), redirect);
    }

    private String changed(boolean found, RedirectAttributes redirect)
    {
        if (found)
        {
            runner.kick();
        }
        else
        {
            redirect.addFlashAttribute("problem", GONE);
        }
        return REDIRECT_LIST;
    }

    private void addFormModel(Model model)
    {
        model.addAttribute("sites", registry.searchSites());
        // Space-separated, as the form's show-and-hide reads it.
        model.addAttribute("galleryDlSites", String.join(" ", subscriptionService.galleryDlSites()));
        model.addAttribute("compressionModes", compressionModeService.options());
        model.addAttribute("browserGroups", GalleryDlOptions.BROWSER_GROUPS);
        model.addAttribute("minPollMinutes", SubscriptionForm.MIN_POLL_MINUTES);
        var notReady = new LinkedHashMap<String, String>();
        for (SearchSource.SearchSite site : registry.searchSites())
        {
            registry.searchSource(site.key()).flatMap(source -> source.notReady(site.key()))
                    .ifPresent(reason -> notReady.put(site.key(), reason));
        }
        model.addAttribute("notReady", notReady);
    }
}

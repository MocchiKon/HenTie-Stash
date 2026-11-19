package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.CompressionModeOption;
import io.github.mocchikon.hentie.dto.CompressionRuleForm;
import io.github.mocchikon.hentie.dto.CompressionRuleView;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.beans.propertyeditors.CustomCollectionEditor;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The image compression rules, reachable from Manage and from the Subscriptions page, whose downloads they decide.
 * The list is paginated and filtered by a value: a rule per favourite artist runs into thousands.
 */
@Controller
@RequestMapping("/compression-rules")
@RequiredArgsConstructor
public class CompressionRuleController
{
    private static final String FORM_VIEW = "compression-rule-form";
    private static final String REDIRECT_LIST = "redirect:/compression-rules";

    private final ImageCompressionRuleService ruleService;
    private final ImageCompressionModeService modeService;

    /**
     * A form with one condition row sends each list as a single value, which Spring would split at its commas: an
     * artist called "smith, john" would become two conditions, and the kinds would no longer line up with the names.
     */
    @InitBinder("form")
    void keepCommas(WebDataBinder binder)
    {
        binder.registerCustomEditor(List.class, "conditionTypes", new CustomCollectionEditor(List.class));
        binder.registerCustomEditor(List.class, "conditionValues", new CustomCollectionEditor(List.class));
    }

    @GetMapping
    public String list(@RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "type", required = false) String type,
                       @RequestParam(name = "value", required = false) String value,
                       Model model)
    {
        MetadataType filterType = typeOrNull(type);
        String filterValue = filterType == null ? null : StringUtils.trimToNull(value);
        Page<CompressionRuleView> rules = ruleService.page(page, filterType, filterValue);
        // A page past the end (the last rule on it was deleted) shows the last one instead of nothing.
        if (rules.getContent().isEmpty() && page > 0 && rules.getTotalPages() > 0)
        {
            return redirectToPage(rules.getTotalPages() - 1, filterType, filterValue);
        }
        model.addAttribute("rules", rules);
        model.addAttribute("types", MetadataType.values());
        model.addAttribute("filterType", filterType == null ? MetadataType.ARTIST.getKey() : filterType.getKey());
        model.addAttribute("filterValue", filterValue);
        return "compression-rules";
    }

    @GetMapping("/new")
    public String create(Model model)
    {
        model.addAttribute("form", ruleService.newForm());
        addFormModel(model);
        return FORM_VIEW;
    }

    @GetMapping("/{id:\\d+}/edit")
    public String edit(@PathVariable int id, Model model, RedirectAttributes redirect)
    {
        var editing = ruleService.editing(id);
        if (editing.isEmpty())
        {
            redirect.addFlashAttribute("problem", "That rule no longer exists.");
            return REDIRECT_LIST;
        }
        model.addAttribute("form", editing.get().form());
        model.addAttribute("broken", editing.get().broken());
        addFormModel(model);
        return FORM_VIEW;
    }

    /** A condition the service refuses is shown on the form with every row as typed, so nothing has to be redone. */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") CompressionRuleForm form, BindingResult result, Model model,
                       RedirectAttributes redirect)
    {
        if (result.hasErrors())
        {
            addFormModel(model);
            return FORM_VIEW;
        }
        try
        {
            ruleService.save(form);
        }
        catch (ImageCompressionRuleService.Refused refused)
        {
            model.addAttribute("conditionsError", refused.getMessage());
            addFormModel(model);
            return FORM_VIEW;
        }
        catch (NoSuchElementException gone)
        {
            redirect.addFlashAttribute("problem", gone.getMessage());
            return REDIRECT_LIST;
        }
        redirect.addFlashAttribute("saved", true);
        return REDIRECT_LIST;
    }

    @PostMapping("/{id:\\d+}/delete")
    public String delete(@PathVariable int id, @RequestParam(name = "page", defaultValue = "0") int page,
                         @RequestParam(name = "type", required = false) String type,
                         @RequestParam(name = "value", required = false) String value,
                         RedirectAttributes redirect)
    {
        ruleService.delete(id);
        redirect.addFlashAttribute("deleted", true);
        MetadataType filterType = typeOrNull(type);
        return redirectToPage(page, filterType, filterType == null ? null : StringUtils.trimToNull(value));
    }

    private void addFormModel(Model model)
    {
        model.addAttribute("types", MetadataType.values());
        model.addAttribute("compressionModes", modesWithQuality());
    }

    /** Each mode names its quality, which decides between the rules of one priority. */
    private List<CompressionModeOption> modesWithQuality()
    {
        Map<String, Integer> qualities = modeService.qualities();
        return modeService.options().stream()
                .map(option -> option.custom() ? option : CompressionModeOption.of(option.key(),
                        option.label() + " (quality " + ImageCompressionModeService.qualityOf(qualities, option.key())
                                + ")"))
                .toList();
    }

    private static MetadataType typeOrNull(String key)
    {
        if (StringUtils.isBlank(key))
        {
            return null;
        }
        try
        {
            return MetadataType.fromKey(key);
        }
        catch (IllegalArgumentException unknown)
        {
            return null;
        }
    }

    private static String redirectToPage(int page, MetadataType type, String value)
    {
        var url = UriComponentsBuilder.fromPath("/compression-rules");
        if (page > 0)
        {
            url.queryParam("page", page);
        }
        if (type != null && value != null)
        {
            url.queryParam("type", type.getKey()).queryParam("value", value);
        }
        return "redirect:" + url.encode().build().toUriString();
    }
}

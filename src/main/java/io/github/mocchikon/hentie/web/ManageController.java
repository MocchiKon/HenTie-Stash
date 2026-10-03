package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.OptionDto;
import io.github.mocchikon.hentie.service.*;
import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionSweep;
import io.github.mocchikon.hentie.service.match.ChapterMatchingSweep;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
public class ManageController
{
    /** A type can hold thousands of items, so the page shows only the most recent and searches the rest. */
    private static final int RECENT_LIMIT = 3;

    /** As many as the search form's autocomplete offers. */
    private static final int OPTIONS_LIMIT = 10;

    /** A refused action redirects here, so the message saying why is in view. */
    private static final String REFUSAL_ANCHOR = "section-title";
    public static final String REDIRECT_MANAGE_MAINTENANCE = "redirect:/manage#maintenance";

    private final MetadataService metadataService;
    private final MetadataRuleService metadataRuleService;
    private final MetadataNameFolder metadataNameFolder;
    private final ImageStatsService imageStatsService;
    private final TitleSearchIndex titleSearchIndex;
    private final ChapterMatchingSweep chapterMatchingSweep;
    private final ImageCompressionSweep imageCompressionSweep;
    private final WriteGate writeGate;

    public ManageController(MetadataService metadataService, MetadataRuleService metadataRuleService,
                            MetadataNameFolder metadataNameFolder, ImageStatsService imageStatsService,
                            TitleSearchIndex titleSearchIndex, ChapterMatchingSweep chapterMatchingSweep,
                            ImageCompressionSweep imageCompressionSweep, WriteGate writeGate)
    {
        this.metadataService = metadataService;
        this.metadataRuleService = metadataRuleService;
        this.metadataNameFolder = metadataNameFolder;
        this.imageStatsService = imageStatsService;
        this.titleSearchIndex = titleSearchIndex;
        this.chapterMatchingSweep = chapterMatchingSweep;
        this.imageCompressionSweep = imageCompressionSweep;
        this.writeGate = writeGate;
    }

    @GetMapping("/manage")
    public String index(Model model)
    {
        Map<MetadataType, List<OptionDto>> items = new LinkedHashMap<>();
        for (MetadataType type : MetadataType.values())
        {
            items.put(type, metadataService.recent(type, null, RECENT_LIMIT));
        }
        model.addAttribute("types", MetadataType.values());
        model.addAttribute("items", items);
        model.addAttribute("recentLimit", RECENT_LIMIT);
        model.addAttribute("compressionModeName", imageCompressionSweep.currentModeName());
        return "manage";
    }

    /** Paged: every delete/merge/rename records a rule, so a library collects thousands. */
    @GetMapping("/manage/rules")
    public String rules(@RequestParam String type, @RequestParam(defaultValue = "0") int page, Model model)
    {
        MetadataType t = MetadataType.fromKey(type);
        model.addAttribute("type", t);
        model.addAttribute("types", MetadataType.values());
        model.addAttribute("rules", metadataRuleService.page(t, page));
        return "metadata-rules";
    }

    /** How the user takes a name back: imports and the Manage page may create it again. */
    @PostMapping("/manage/rules/remove")
    public String removeRule(@RequestParam String type, @RequestParam Integer id,
                             @RequestParam(defaultValue = "0") int page)
    {
        MetadataType t = MetadataType.fromKey(type);
        metadataRuleService.delete(id);
        return "redirect:/manage/rules?type=" + t.getKey() + "&page=" + Math.max(page, 0);
    }

    /** The {@value #RECENT_LIMIT} most recent items matching {@code q} (drives the Manage filter box). */
    @GetMapping("/manage/items")
    @ResponseBody
    public List<OptionDto> items(@RequestParam String type, @RequestParam(required = false) String q)
    {
        return metadataService.recent(MetadataType.fromKey(type), q, RECENT_LIMIT);
    }

    /** The Manage page's pickers: what its actions take, so a tag's {@code ♀}/{@code ♂} versions are not offered. */
    @GetMapping("/manage/options")
    @ResponseBody
    public List<OptionDto> options(@RequestParam String type, @RequestParam(required = false) String q)
    {
        return metadataService.autocompleteManaged(MetadataType.fromKey(type), q, OPTIONS_LIMIT);
    }

    /**
     * Needed because images can appear without the app writing them, and imported rows sit on 0, which
     * makes a page-count or size sort tie on every row.
     */
    @PostMapping("/manage/resync-stats")
    public String resyncStats(RedirectAttributes redirectAttributes)
    {
        redirectAttributes.addFlashAttribute("statsRepaired", imageStatsService.resyncAll());
        return REDIRECT_MANAGE_MAINTENANCE;
    }

    /** Triggers keep the index in sync; this repairs what they cannot see, such as rows loaded past them. */
    @PostMapping("/manage/rebuild-title-index")
    public String rebuildTitleIndex(RedirectAttributes redirectAttributes)
    {
        titleSearchIndex.rebuildAll();
        redirectAttributes.addFlashAttribute("titleIndexRebuilt", true);
        return REDIRECT_MANAGE_MAINTENANCE;
    }

    /** Backfill for names stored in mixed case, such as those from the H2 import. See {@link MetadataNameFolder}. */
    @PostMapping("/manage/fold-metadata-names")
    public String foldMetadataNames(RedirectAttributes redirectAttributes)
    {
        redirectAttributes.addFlashAttribute("foldResult", metadataNameFolder.foldAll());
        return REDIRECT_MANAGE_MAINTENANCE;
    }

    @PostMapping("/manage/match-chapters")
    public String matchChapters(RedirectAttributes redirectAttributes)
    {
        redirectAttributes.addFlashAttribute("matchResult", chapterMatchingSweep.matchUnlinked());
        return REDIRECT_MANAGE_MAINTENANCE;
    }

    /** Uses the Settings mode, not a choice of its own, so there is one answer to which mode applies to existing images. */
    @PostMapping("/manage/compress-images")
    public String compressImages(RedirectAttributes redirectAttributes)
    {
        String result;
        try
        {
            result = imageCompressionSweep.compressAll().describe();
        }
        catch (ImageCompressionService.RunInProgress busy)
        {
            // Another run holding the lock is an expected state, not an error.
            result = busy.getMessage();
        }
        redirectAttributes.addFlashAttribute("compressResult", result);
        return REDIRECT_MANAGE_MAINTENANCE;
    }

    @PostMapping("/manage/add")
    public String add(@RequestParam String type, @RequestParam String name, RedirectAttributes redirectAttributes)
    {
        MetadataType t = MetadataType.fromKey(type);
        var refusal = metadataService.add(t, name);
        refusal.ifPresent(r -> flashRefusal(redirectAttributes, r));
        return redirect(t, refusal.isPresent());
    }

    /**
     * {@code createRule} is a hidden field kept in step with the section's one "Add rule" checkbox (one
     * checkbox serves several forms). A request without it means false.
     */
    @PostMapping("/manage/rename")
    public String rename(@RequestParam String type, @RequestParam Integer id, @RequestParam String name,
                         @RequestParam(defaultValue = "false") boolean createRule,
                         RedirectAttributes redirectAttributes)
    {
        MetadataType t = MetadataType.fromKey(type);
        var refusal = metadataService.rename(t, id, name, createRule);
        refusal.ifPresent(r -> flashRefusal(redirectAttributes, r));
        return redirect(t, refusal.isPresent());
    }

    /**
     * Remove and merge are one transaction each, however many links the item has (a tag on half or all of 1.5M
     * chapters: 13-30 s), so they wait for their turn as long as it takes rather than fail after a request's budget.
     */
    @PostMapping("/manage/remove")
    public String remove(@RequestParam String type, @RequestParam Integer id,
                         @RequestParam(defaultValue = "false") boolean createRule)
    {
        MetadataType t = MetadataType.fromKey(type);
        writeGate.background("removing " + t.getDisplayName().toLowerCase(Locale.ROOT),
                () -> metadataService.remove(t, id, createRule));
        return redirect(t);
    }

    @PostMapping("/manage/merge")
    public String merge(@RequestParam String type, @RequestParam Integer sourceId, @RequestParam Integer targetId,
                        @RequestParam(defaultValue = "false") boolean createRule)
    {
        MetadataType t = MetadataType.fromKey(type);
        writeGate.background("merging " + t.getDisplayName().toLowerCase(Locale.ROOT),
                () -> metadataService.merge(t, sourceId, targetId, createRule));
        return redirect(t);
    }

    /** One transaction moving every link of both versions, so it waits like a merge. */
    @PostMapping("/manage/remove-gender")
    public String removeGender(@RequestParam Integer id, @RequestParam(defaultValue = "false") boolean createRule)
    {
        writeGate.background("removing the gender from a tag", () -> metadataService.removeGender(id, createRule));
        return redirect(MetadataType.TAG);
    }

    /**
     * Otherwise the item would silently not appear, or the unique index would throw a 500. The message names
     * the metadata kind because it is shown at the top of the page, not inside the section.
     */
    private void flashRefusal(RedirectAttributes redirectAttributes, MetadataService.Refusal refusal)
    {
        String kind = refusal.type().getDisplayName();
        String message = switch (refusal)
        {
            case MetadataService.RuleConflict rule ->
            {
                String explanation = (rule.targetName() == null) ?
                        "a rule removes it from anything that brings it in"
                        : "a rule replaces it with \"%s\"".formatted(rule.targetName());
                yield "%s: \"%s\" cannot be used: %s. Remove that rule under \"Manage current rules\" for %s first."
                        .formatted(kind, rule.name(), explanation, kind);
            }
            case MetadataService.NameTaken taken ->
                    "%s: \"%s\" cannot be used: another item already has that name. Use Merge if you meant to combine the two."
                            .formatted(kind, taken.name());
            case MetadataService.GenderedName gendered ->
                    "%s: \"%s\" cannot be used: a tag is renamed without \u2640 or \u2642, and its \u2640 and \u2642 versions take the new name with their own symbol."
                            .formatted(kind, gendered.name());
        };
        redirectAttributes.addFlashAttribute("refusal", message);
    }

    /** A refusal lands on the message: the section would scroll past an error shown at the top. */
    private String redirect(MetadataType type, boolean failed)
    {
        return failed ? "redirect:/manage#" + REFUSAL_ANCHOR : redirect(type);
    }

    private String redirect(MetadataType type)
    {
        return "redirect:/manage#" + type.getKey();
    }
}

package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.SelectedFilters;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.SeriesService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/series")
public class SeriesController
{
    public static final String SERIES_EDIT = "series-edit";
    public static final String REDIRECT_SERIES = "redirect:/series/";
    private final SeriesService seriesService;
    private final MetadataService metadataService;
    private final WriteGate writeGate;

    public SeriesController(SeriesService seriesService, MetadataService metadataService, WriteGate writeGate)
    {
        this.seriesService = seriesService;
        this.metadataService = metadataService;
        this.writeGate = writeGate;
    }

    @GetMapping("/{id:\\d+}")
    public String view(@PathVariable int id, @RequestParam(required = false) String lang,
                       @RequestParam(defaultValue = "false") boolean review, Model model)
    {
        model.addAttribute("vm", seriesService.buildView(id, lang));
        model.addAttribute("review", review);
        return "series-view";
    }

    /** See {@link ChapterController#markReviewed}. */
    @PostMapping("/{id:\\d+}/mark-reviewed")
    public String markReviewed(@PathVariable int id)
    {
        seriesService.markReviewed(id);
        return REDIRECT_SERIES + id;
    }

    @GetMapping("/new")
    public String create(@RequestParam(required = false) Integer fromChapter, Model model)
    {
        SeriesForm form = fromChapter != null ? seriesService.prefillFromChapter(fromChapter) : new SeriesForm();
        if (form.getStatus() == null)
        {
            form.setStatus(Status.REVIEWED);
        }
        model.addAttribute("form", form);
        model.addAttribute("selected", selectedFor(form));
        model.addAttribute("creating", true);
        model.addAttribute("chapterCards", List.of());
        model.addAttribute("statuses", Status.values());
        return SERIES_EDIT;
    }

    @GetMapping("/{id:\\d+}/edit")
    public String edit(@PathVariable int id, Model model)
    {
        SeriesForm form = seriesService.toForm(id);
        model.addAttribute("form", form);
        model.addAttribute("selected", selectedFor(form));
        model.addAttribute("creating", false);
        model.addAttribute("chapterCards", seriesService.editChapterCards(id));
        model.addAttribute("statuses", Status.values());
        return SERIES_EDIT;
    }

    @PostMapping
    public String createSubmit(@Valid @ModelAttribute("form") SeriesForm form, BindingResult result, Model model)
    {
        if (result.hasErrors())
        {
            return reshowForm(form, model, true);
        }
        try
        {
            int id = seriesService.create(form);
            return REDIRECT_SERIES + id;
        }
        catch (DataIntegrityViolationException e)   // engine-independent safety net (e.g. value too long)
        {
            ChapterController.rejectDuplicate(result, e);
            return reshowForm(form, model, true);
        }
    }

    @PostMapping("/{id:\\d+}")
    public String updateSubmit(@PathVariable int id, @Valid @ModelAttribute("form") SeriesForm form,
                               BindingResult result, Model model)
    {
        form.setId(id);
        if (result.hasErrors())
        {
            return reshowForm(form, model, false);
        }
        try
        {
            seriesService.update(form);
            return REDIRECT_SERIES + id;
        }
        catch (DataIntegrityViolationException e)   // engine-independent safety net (e.g. value too long)
        {
            ChapterController.rejectDuplicate(result, e);
            return reshowForm(form, model, false);
        }
    }

    private String reshowForm(SeriesForm form, Model model, boolean creating)
    {
        model.addAttribute("selected", selectedFor(form));
        model.addAttribute("creating", creating);
        model.addAttribute("statuses", Status.values());
        model.addAttribute("chapterCards",
                creating || form.getId() == null ? List.of() : seriesService.editChapterCards(form.getId()));
        return SERIES_EDIT;
    }

    @PostMapping("/{id:\\d+}/delete")
    public String delete(@PathVariable int id)
    {
        seriesService.delete(id);
        return "redirect:/search?type=SERIES";
    }

    /**
     * Deletes the chapters and their images too; plain {@link #delete} only unlinks them. One transaction however
     * many chapters the series has, so it waits for its turn as long as it takes.
     */
    @PostMapping("/{id:\\d+}/delete-with-chapters")
    public String deleteWithChapters(@PathVariable int id, RedirectAttributes redirectAttributes)
    {
        redirectAttributes.addFlashAttribute("chaptersDeleted", writeGate.background(
                "deleting a series with its chapters", () -> seriesService.deleteWithChapters(id)));
        return "redirect:/search?type=SERIES";
    }

    @PostMapping("/{id:\\d+}/chapters/add")
    public String addChapters(@PathVariable int id, @RequestParam(name = "chapterIds", required = false) List<Integer> chapterIds)
    {
        seriesService.addChapters(id, chapterIds);
        return REDIRECT_SERIES + id + "/edit";
    }

    @PostMapping("/{id:\\d+}/chapters/{chapterId:\\d+}/num")
    @ResponseBody
    public ResponseEntity<Void> updateChapterNum(@PathVariable int id, @PathVariable int chapterId,
                                                  @RequestParam float chapterNum)
    {
        seriesService.updateChapterNum(id, chapterId, chapterNum);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id:\\d+}/chapters/remove")
    public String removeChapter(@PathVariable int id, @RequestParam int chapterId)
    {
        seriesService.removeChapter(id, chapterId);
        return REDIRECT_SERIES + id + "/edit";
    }

    private SelectedFilters selectedFor(SeriesForm form)
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

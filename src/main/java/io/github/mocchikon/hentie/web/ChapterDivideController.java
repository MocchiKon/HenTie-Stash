package io.github.mocchikon.hentie.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.github.mocchikon.hentie.dto.ChapterDivideForm;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.service.ChapterDivisionService;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * A refused or part-way division comes back to this page with its marks and titles, so nothing has to be
 * marked again.
 */
@Controller
@RequestMapping("/chapter/{id:\\d+}/divide")
@RequiredArgsConstructor
public class ChapterDivideController
{
    private static final String CHAPTER_DIVIDE = "chapter-divide";

    private final ChapterService chapterService;
    private final ChapterDivisionService divisionService;
    private final ImageService imageService;
    private final ImageCompressionService compressionService;

    @GetMapping
    public String show(@PathVariable int id, Model model)
    {
        Chapter chapter = chapterService.get(id);
        // Handed on by a division that stopped part-way. Marks on pages that moved to a made part match nothing here.
        var form = (ChapterDivideForm) model.getAttribute("divide");
        if (form == null)
        {
            form = new ChapterDivideForm();
            form.setKeptTitle(chapter.getTitleFull());
            form.setPartStatus(chapter.getStatus());
        }
        addModel(chapter, form, model);
        return CHAPTER_DIVIDE;
    }

    @PostMapping
    public String divide(@PathVariable int id, @Valid @ModelAttribute("divide") ChapterDivideForm form,
                         BindingResult result, Model model, RedirectAttributes redirectAttributes)
    {
        if (result.hasErrors())
        {
            model.addAttribute("error", result.getAllErrors().getFirst().getDefaultMessage());
            addModel(chapterService.get(id), form, model);
            return CHAPTER_DIVIDE;
        }
        List<ChapterDivisionService.NewPart> parts = form.getStarts().stream()
                .map(start -> new ChapterDivisionService.NewPart(start, form.getTitles().get(start)))
                .toList();
        ChapterDivisionService.Outcome outcome;
        try
        {
            outcome = divisionService.divide(id, form.getKeptTitle(), form.getPartStatus(), parts);
        }
        catch (ChapterDivisionService.Refused refused)
        {
            model.addAttribute("error", refused.getMessage());
            addModel(chapterService.get(id), form, model);
            return CHAPTER_DIVIDE;
        }
        redirectAttributes.addFlashAttribute("divided", outcome.created());
        if (!outcome.complete())
        {
            redirectAttributes.addFlashAttribute("divideError", outcome.stoppedBecause());
            redirectAttributes.addFlashAttribute("divide", form);
            return "redirect:/chapter/" + id + "/divide";
        }
        return "redirect:/chapter/" + id;
    }

    private void addModel(Chapter chapter, ChapterDivideForm form, Model model)
    {
        List<String> pageUrls = imageService.pageUrls(chapter.getId());
        model.addAttribute("chapter", chapter);
        model.addAttribute("divide", form);
        model.addAttribute("statuses", Status.values());
        model.addAttribute("pageUrls", pageUrls);
        model.addAttribute("blocked", pageUrls.size() < 2
                ? ChapterDivisionService.TOO_FEW_PAGES : divisionService.refusal(chapter).orElse(null));
        // Only a warning: the run may be over before the pages are marked, and the post is refused if not.
        model.addAttribute("compressionRunning", compressionService.isRunInProgress());
    }
}

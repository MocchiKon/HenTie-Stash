package io.github.mocchikon.hentie.web;

import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.github.mocchikon.hentie.dto.CandidateScope;
import io.github.mocchikon.hentie.service.MatchingService;
import io.github.mocchikon.hentie.service.SeriesService;
import lombok.RequiredArgsConstructor;

/** Linking returns to the page as it was (same scope and search), since a series usually gathers several chapters. */
@Controller
@RequestMapping("/series/{id:\\d+}/link-chapters")
@RequiredArgsConstructor
public class LinkChaptersController
{
    /** More than Add-to-series' five, since several are linked at once. */
    private static final int MAX_MATCHES = 30;

    private final MatchingService matchingService;
    private final SeriesService seriesService;

    @GetMapping
    public String show(@PathVariable int id, @RequestParam(defaultValue = "UNLINKED") CandidateScope scope,
                       @RequestParam(required = false) String q, Model model)
    {
        model.addAttribute("series", seriesService.get(id));
        model.addAttribute("scope", scope);
        model.addAttribute("scopes", CandidateScope.values());
        model.addAttribute("matches", matchingService.topChapterMatches(id, scope, MAX_MATCHES));
        model.addAttribute("query", q);
        if (StringUtils.isNotBlank(q))
        {
            model.addAttribute("searchResults", matchingService.searchChaptersByName(q, id, scope, MAX_MATCHES));
        }
        return "link-chapters";
    }

    @PostMapping("/link")
    public String link(@PathVariable int id,
                       @RequestParam(name = "chapterIds", required = false) List<Integer> chapterIds,
                       @RequestParam(defaultValue = "UNLINKED") CandidateScope scope,
                       @RequestParam(required = false) String q, RedirectAttributes redirectAttributes)
    {
        // An empty id box binds as null; only a hand-made post sends one, since the input is required.
        List<Integer> picked = chapterIds == null ? List.of() : chapterIds.stream().filter(Objects::nonNull).toList();
        redirectAttributes.addFlashAttribute("linked", seriesService.addChapters(id, picked));
        redirectAttributes.addAttribute("scope", scope.name());
        if (StringUtils.isNotBlank(q))
        {
            redirectAttributes.addAttribute("q", q);
        }
        return "redirect:/series/" + id + "/link-chapters";
    }
}

package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.MatchingService;
import io.github.mocchikon.hentie.service.SeriesService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/chapter/{id}/add-to-series")
public class AddToSeriesController
{
    private static final int MAX_MATCHES = 5;

    private final MatchingService matchingService;
    private final SeriesService seriesService;
    private final ChapterService chapterService;

    public AddToSeriesController(MatchingService matchingService, SeriesService seriesService, ChapterService chapterService)
    {
        this.matchingService = matchingService;
        this.seriesService = seriesService;
        this.chapterService = chapterService;
    }

    @GetMapping
    public String show(@PathVariable int id, @RequestParam(required = false) String q, Model model)
    {
        model.addAttribute("chapter", chapterService.get(id));
        model.addAttribute("matches", matchingService.topMatches(id, MAX_MATCHES));
        model.addAttribute("query", q);
        if (StringUtils.isNotBlank(q))
        {
            model.addAttribute("searchResults", matchingService.searchByName(q, MAX_MATCHES));
        }
        return "add-to-series";
    }

    @PostMapping("/link")
    public String link(@PathVariable int id, @RequestParam int seriesId)
    {
        seriesService.addChapters(seriesId, List.of(id));
        return "redirect:/series/" + seriesId;
    }
}

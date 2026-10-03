package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.CardDto;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.dto.SearchType;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.service.BulkDeleteService;
import io.github.mocchikon.hentie.service.DeletedCount;
import io.github.mocchikon.hentie.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

@Controller
@RequiredArgsConstructor
public class SearchController
{
    private static final String BULK_DELETED = "bulkDeleted";
    private static final String REDIRECT_RESULTS = "redirect:/search/results?";

    private final SearchService searchService;
    private final BulkDeleteService bulkDeleteService;

    /** Pre-fills from query params, which is how "Refine" works. */
    @GetMapping({"/", "/search"})
    public String form(@ModelAttribute("criteria") SearchCriteria criteria, Model model)
    {
        criteria.normalize();
        addFormModel(criteria, model);
        return "search";
    }

    /** No form model: the page shows no tokens, and resolving them costs a catalog walk per selected id. */
    @GetMapping("/search/results")
    public String results(@ModelAttribute("criteria") SearchCriteria criteria, Model model,
                          RedirectAttributes redirectAttributes)
    {
        criteria.normalize();
        Page<CardDto> results = searchService.search(criteria);
        if (results.isEmpty() && criteria.getPage() > 0 && results.getTotalPages() > 0)
        {
            // Past the end, as "Delete this page" leaves it after emptying the last page. The flash is
            // passed on, since it survives only one redirect.
            Object deleted = model.getAttribute(BULK_DELETED);
            if (deleted != null)
            {
                redirectAttributes.addFlashAttribute(BULK_DELETED, deleted);
            }
            return REDIRECT_RESULTS + buildBaseQuery(criteria) + "&page=" + (results.getTotalPages() - 1);
        }
        model.addAttribute("results", results);
        model.addAttribute("baseQuery", buildBaseQuery(criteria));
        return "search-results";
    }

    /** Asked when the button is pressed, not on every results page: on a series search it is a query of its own. */
    @GetMapping("/search/results/delete-count")
    @ResponseBody
    public BulkDeleteService.Preview deleteCount(@ModelAttribute("criteria") SearchCriteria criteria)
    {
        criteria.normalize();
        return bulkDeleteService.preview(criteria);
    }

    /**
     * A separate endpoint with the ids required, so a request without them cannot fall through to the
     * whole-search count, which for a bare type is the entire library.
     */
    @GetMapping("/search/results/delete-count/page")
    @ResponseBody
    public BulkDeleteService.Preview deletePageCount(@RequestParam SearchType type, @RequestParam List<Integer> ids)
    {
        return bulkDeleteService.preview(type, ids);
    }

    /**
     * Refused without a filter: that is the whole library, and the page offers no button for it, so such a
     * request is stale or hand-made.
     */
    @PostMapping("/search/results/delete-all")
    public String deleteAll(@ModelAttribute("criteria") SearchCriteria criteria, RedirectAttributes redirectAttributes)
    {
        criteria.normalize();
        redirectAttributes.addFlashAttribute(BULK_DELETED, criteria.hasFilter()
                ? describe(bulkDeleteService.deleteMatching(criteria), criteria.isSeries())
                : "Nothing was deleted: \"Delete all matching\" needs at least one filter.");
        return REDIRECT_RESULTS + buildBaseQuery(criteria);
    }

    /** Lands on the <b>same</b> page, which now holds the next results, so a search is worked through by pressing again. */
    @PostMapping("/search/results/delete-page")
    public String deletePage(@ModelAttribute("criteria") SearchCriteria criteria,
                             @RequestParam(name = "ids", required = false) List<Integer> ids,
                             RedirectAttributes redirectAttributes)
    {
        criteria.normalize();
        DeletedCount deleted = bulkDeleteService.delete(criteria.getType(), ids == null ? List.of() : ids);
        redirectAttributes.addFlashAttribute(BULK_DELETED, describe(deleted, criteria.isSeries()));
        return REDIRECT_RESULTS + buildBaseQuery(criteria) + "&page=" + Math.max(0, criteria.getPage());
    }

    private static String describe(DeletedCount deleted, boolean series)
    {
        return series
                ? "Deleted " + deleted.series() + " series and their " + deleted.chapters() + " chapter(s)."
                : "Deleted " + deleted.chapters() + " chapter(s).";
    }

    private void addFormModel(SearchCriteria criteria, Model model)
    {
        model.addAttribute("selected", searchService.resolveSelected(criteria));
        model.addAttribute("selectedLanguages", criteria.getLanguages().stream()
                .map(l -> new io.github.mocchikon.hentie.dto.OptionDto(null, l)).toList());
        model.addAttribute("statuses", Status.values());
        model.addAttribute("baseQuery", buildBaseQuery(criteria));
    }

    /** Without the page number, so pagination links keep every filter. */
    private String buildBaseQuery(SearchCriteria c)
    {
        StringJoiner sj = new StringJoiner("&");
        sj.add("type=" + c.getType().name());
        if (StringUtils.isNotBlank(c.getTitle()))
        {
            sj.add("title=" + enc(c.getTitle()));
        }
        appendIds(sj, "tagIds", c.getTagIds());
        appendIds(sj, "artistIds", c.getArtistIds());
        appendIds(sj, "characterIds", c.getCharacterIds());
        appendIds(sj, "parodyIds", c.getParodyIds());
        appendIds(sj, "groupIds", c.getGroupIds());
        appendIds(sj, "categoryIds", c.getCategoryIds());
        appendIds(sj, "excludedTagIds", c.getExcludedTagIds());
        appendIds(sj, "excludedArtistIds", c.getExcludedArtistIds());
        appendIds(sj, "excludedCharacterIds", c.getExcludedCharacterIds());
        appendIds(sj, "excludedParodyIds", c.getExcludedParodyIds());
        appendIds(sj, "excludedGroupIds", c.getExcludedGroupIds());
        appendIds(sj, "excludedCategoryIds", c.getExcludedCategoryIds());
        if (c.getLanguages() != null)
        {
            c.getLanguages().forEach(l -> sj.add("languages=" + enc(l)));
        }
        if (c.getStatuses() != null)
        {
            c.getStatuses().forEach(s -> sj.add("statuses=" + s.name()));
        }
        if (c.getMinScore() != null)
        {
            sj.add("minScore=" + c.getMinScore());
        }
        if (c.getMinPages() != null)
        {
            sj.add("minPages=" + c.getMinPages());
        }
        if (c.getMaxPages() != null)
        {
            sj.add("maxPages=" + c.getMaxPages());
        }
        if (c.getUploadFrom() != null)
        {
            sj.add("uploadFrom=" + c.getUploadFrom());
        }
        if (c.getUploadTo() != null)
        {
            sj.add("uploadTo=" + c.getUploadTo());
        }
        if (StringUtils.isNotBlank(c.getGalleryId()))
        {
            sj.add("galleryId=" + enc(c.getGalleryId()));
        }
        if (c.getSortBy() != null)
        {
            sj.add("sortBy=" + c.getSortBy().name());
        }
        if (c.getSortDir() != null)
        {
            sj.add("sortDir=" + c.getSortDir().name());
        }
        // Carried, or a page number computed at one size would be read at the default one.
        if (c.getSize() > 0)
        {
            sj.add("size=" + c.getSize());
        }
        return sj.toString();
    }

    private void appendIds(StringJoiner sj, String name, List<Integer> ids)
    {
        if (ids != null)
        {
            ids.forEach(id -> sj.add(name + "=" + id));
        }
    }

    private String enc(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

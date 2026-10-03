package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.SeriesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Commits to the shared DB, so every title carries a token unique to this suite and each test filters on it. */
@SpringBootTest
@AutoConfigureMockMvc
class SearchBulkDeleteWebIT
{
    @Autowired MockMvc mvc;
    @Autowired ChapterService chapterService;
    @Autowired SeriesService seriesService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;

    @Test
    void shouldRenderTheBulkActionsAndCardIdsWhenThereAreResults() throws Exception
    {
        // GIVEN
        int id = chapter("Sbdwrender Card");

        // WHEN
        ResultActions result = mvc.perform(get("/search/results").with(user("user")).param("title", "Sbdwrender"));

        // THEN each card carries its id, and both deletes post (CSRF included) with the search in the URL
        result.andExpect(status().isOk())
                .andExpect(content().string(containsString("data-id=\"" + id + "\"")))
                .andExpect(content().string(containsString("data-review-start")))
                .andExpect(content().string(containsString("/search/results/delete-page?type=CHAPTER&amp;title=Sbdwrender&amp;")))
                .andExpect(content().string(containsString("/search/results/delete-all?type=CHAPTER&amp;title=Sbdwrender&amp;")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void shouldRenderNoBulkActionsWhenNothingMatches() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/search/results").with(user("user")).param("title", "Sbdwnothingmatchesthis"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(content().string(not(containsString("results-bulk"))));
    }

    @Test
    void shouldOfferNoDeleteAllWhenTheSearchHasNoFilter() throws Exception
    {
        // GIVEN at least one chapter, so the unfiltered search has results
        chapter("Sbdwunfiltered Card");

        // WHEN / THEN the page still offers review and "Delete this page", but not the whole library
        mvc.perform(get("/search/results").with(user("user")).param("type", "CHAPTER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/search/results/delete-page?")))
                .andExpect(content().string(not(containsString("/search/results/delete-all?"))));
    }

    @Test
    void shouldRefuseDeleteAllWhenTheSearchHasNoFilter() throws Exception
    {
        // GIVEN
        int id = chapter("Sbdwrefuse Card");

        // WHEN a delete-all arrives anyway, for the whole library
        ResultActions result = mvc.perform(post("/search/results/delete-all").with(user("user")).with(csrf())
                .queryParam("type", "CHAPTER"));

        // THEN nothing is deleted, and the page says why
        result.andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("bulkDeleted", "Nothing was deleted: \"Delete all matching\" needs at least one filter."));
        assertThat(chapterRepository.findById(id)).isPresent();
    }

    @Test
    void shouldRefuseDeleteAllWhenEveryValueSentMatchesTheWholeLibrary() throws Exception
    {
        // GIVEN
        int id = chapter("Sbdwnoise Card");

        // WHEN a delete-all arrives carrying only values that look like filters but match every row
        ResultActions result = mvc.perform(post("/search/results/delete-all").with(user("user")).with(csrf())
                .queryParam("type", "CHAPTER").queryParam("minScore", "0").queryParam("minPages", "0")
                .queryParam("tagIds", "").queryParam("statuses", "NEW", "REVIEWED", "REVIEWED_FAVOURITE"));

        // THEN it is refused as the unfiltered search it is, and the search it lands on carries none of them
        result.andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search/results?type=CHAPTER&sortBy=DATE&sortDir=DESC"))
                .andExpect(flash().attribute("bulkDeleted", "Nothing was deleted: \"Delete all matching\" needs at least one filter."));
        assertThat(chapterRepository.findById(id)).isPresent();
    }

    @Test
    void shouldAnswerTheCountsForTheWholeSearchOrForTheGivenIds() throws Exception
    {
        // GIVEN a matching series with two chapters and another with one
        int first = seriesWith("Sbdwcount One", "sbdw-count-1", "sbdw-count-2");
        seriesWith("Sbdwcount Two", "sbdw-count-3");

        // WHEN / THEN the whole series search
        mvc.perform(get("/search/results/delete-count").with(user("user"))
                        .param("type", "SERIES").param("title", "Sbdwcount"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value(2))
                .andExpect(jsonPath("$.chapters").value(3));

        // ...and one page's ids
        mvc.perform(get("/search/results/delete-count/page").with(user("user"))
                        .param("type", "SERIES").param("ids", String.valueOf(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value(1))
                .andExpect(jsonPath("$.chapters").value(2));
    }

    @Test
    void shouldRejectAPageCountThatNamesNoIds() throws Exception
    {
        // WHEN a page sends no ids at all
        ResultActions result = mvc.perform(get("/search/results/delete-count/page").with(user("user")).param("type", "CHAPTER"));

        // THEN it is refused rather than answered with the whole library's count
        result.andExpect(status().isBadRequest());
    }

    @Test
    void shouldDeleteThePostedIdsAndReturnToTheSamePage() throws Exception
    {
        // GIVEN three matching chapters, of which the page posts two
        int doomed1 = chapter("Sbdwpage One");
        int doomed2 = chapter("Sbdwpage Two");
        int kept = chapter("Sbdwpage Three");

        // WHEN
        ResultActions result = mvc.perform(post("/search/results/delete-page").with(user("user")).with(csrf())
                .queryParam("type", "CHAPTER").queryParam("title", "Sbdwpage").queryParam("page", "0")
                .param("ids", String.valueOf(doomed1), String.valueOf(doomed2)));

        // THEN
        result.andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search/results?type=CHAPTER&title=Sbdwpage&sortBy=DATE&sortDir=DESC&page=0"))
                .andExpect(flash().attribute("bulkDeleted", "Deleted 2 chapter(s)."));
        assertThat(chapterRepository.findAllById(List.of(doomed1, doomed2))).isEmpty();
        assertThat(chapterRepository.findById(kept)).isPresent();
    }

    @Test
    void shouldDeleteEverySeriesTheSearchMatchesWithItsChapters() throws Exception
    {
        // GIVEN
        int doomed = seriesWith("Sbdwall Doomed", "sbdw-all-1");
        int doomedChapter = chapterRepository.findBySeriesId(doomed).getFirst().getId();
        int kept = seriesWith("Sbdwkeep Kept", "sbdw-all-2");

        // WHEN
        ResultActions result = mvc.perform(post("/search/results/delete-all").with(user("user")).with(csrf())
                .queryParam("type", "SERIES").queryParam("title", "Sbdwall"));

        // THEN it lands on the first page of the same search, saying what went
        result.andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search/results?type=SERIES&title=Sbdwall&sortBy=DATE&sortDir=DESC"))
                .andExpect(flash().attribute("bulkDeleted", "Deleted 1 series and their 1 chapter(s)."));
        assertThat(seriesRepository.findById(doomed)).isEmpty();
        assertThat(chapterRepository.findById(doomedChapter)).isEmpty();
        assertThat(seriesRepository.findById(kept)).isPresent();
    }

    @Test
    void shouldMoveToTheLastPageKeepingTheFlashWhenThePageIsPastTheEnd() throws Exception
    {
        // GIVEN one page of results, asked for at page 3 - what deleting the last page leaves behind
        chapter("Sbdwpast Only");

        // WHEN
        ResultActions result = mvc.perform(get("/search/results").with(user("user"))
                .param("title", "Sbdwpast").param("page", "3")
                .flashAttr("bulkDeleted", "Deleted 5 chapter(s)."));

        // THEN
        result.andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search/results?type=CHAPTER&title=Sbdwpast&sortBy=DATE&sortDir=DESC&page=0"))
                .andExpect(flash().attribute("bulkDeleted", "Deleted 5 chapter(s)."));
    }

    @Test
    void shouldKeepTheRequestedPageSizeWhenMovingToTheLastPage() throws Exception
    {
        // GIVEN three results, asked for two per page at page 5 - past the end, whose last page is 1
        chapter("Sbdwsize One");
        chapter("Sbdwsize Two");
        chapter("Sbdwsize Three");

        // WHEN
        ResultActions result = mvc.perform(get("/search/results").with(user("user"))
                .param("title", "Sbdwsize").param("size", "2").param("page", "5"));

        // THEN the redirect keeps the size its page number was computed at
        result.andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search/results?type=CHAPTER&title=Sbdwsize&sortBy=DATE&sortDir=DESC&size=2&page=1"));
    }

    private int chapter(String titleFull)
    {
        ChapterForm form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        return chapterService.create(form);
    }

    private int seriesWith(String titleFull, String... chapterTitles)
    {
        SeriesForm form = new SeriesForm();
        form.setTitleFull(titleFull);
        form.setStatus(Status.REVIEWED);
        for (String chapterTitle : chapterTitles)
        {
            form.getChapterIds().add(chapter(chapterTitle));
        }
        return seriesService.create(form);
    }
}

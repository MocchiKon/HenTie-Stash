package io.github.mocchikon.hentie.web;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.SeriesService;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Commits to the shared DB, so titles are unique to this suite. */
@SpringBootTest
@AutoConfigureMockMvc
class SeriesControllerIT
{
    @Autowired MockMvc mvc;
    @Autowired SeriesService seriesService;
    @Autowired ChapterService chapterService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;

    private int newSeries(String titleFull)
    {
        SeriesForm f = new SeriesForm();
        f.setTitleFull(titleFull);
        f.setStatus(Status.REVIEWED);
        return seriesService.create(f);
    }

    private int newChapter(String titleFull)
    {
        ChapterForm form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        return chapterService.create(form);
    }

    @Test
    void shouldRenderNewSeriesForm() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/series/new").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("series-edit"))
                .andExpect(model().attribute("creating", true))
                .andExpect(model().attributeExists("form", "statuses", "chapterCards"));
    }

    @Test
    void shouldPrefillNewSeriesFormWhenFromChapterProvided() throws Exception
    {
        // GIVEN
        int chapterId = newChapter("srw-prefill-source");

        // WHEN
        ResultActions result = mvc.perform(get("/series/new").with(user("user")).param("fromChapter", String.valueOf(chapterId)));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("series-edit"));
    }

    @Test
    void shouldRedirectToNewSeriesWhenCreated() throws Exception
    {
        // WHEN
        mvc.perform(post("/series").with(user("user")).with(csrf())
                        .param("titleFull", "srw-create-ok").param("status", "REVIEWED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/series/*"));

        // THEN
        assertThat(seriesRepository.findAll()).extracting("titleFull").contains("srw-create-ok");
    }

    @Test
    void shouldRenderSeriesWhenSeriesExists() throws Exception
    {
        // GIVEN
        int id = newSeries("srw-view");

        // WHEN
        ResultActions result = mvc.perform(get("/series/" + id).with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("series-view"))
                .andExpect(model().attributeExists("vm"));
    }

    @Test
    void shouldOfferToLinkChaptersFromTheSeriesPage() throws Exception
    {
        // GIVEN
        int id = newSeries("srw-link-chapters");

        // WHEN + THEN the series page leads to the page that finds the chapters it is missing.
        mvc.perform(get("/series/" + id).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/series/" + id + "/link-chapters\"")))
                .andExpect(content().string(containsString(">Link chapters</a>")));
    }

    /**
     * Asserted on the markup because the spans must stay glued together: a source newline would show as a
     * space inside the title.
     */
    @Test
    void shouldMuteTheUnsharedPartOfTheHeadingWhenPrettyTitleIsContained() throws Exception
    {
        // GIVEN
        SeriesForm f = new SeriesForm();
        f.setTitleFull("[srw-head] Isekai Yuusha (Alpha)");
        f.setTitle("Isekai Yuusha");
        f.setStatus(Status.REVIEWED);
        int split = seriesService.create(f);
        int plain = newSeries("srw-head-plain");

        // WHEN
        String splitHtml = mvc.perform(get("/series/" + split).with(user("user")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String plainHtml = mvc.perform(get("/series/" + plain).with(user("user")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // THEN
        assertThat(splitHtml).contains(
                "<span class=\"title-decoration\">[srw-head] </span>"
                        + "<span>Isekai Yuusha</span>"
                        + "<span class=\"title-decoration\"> (Alpha)</span>");
        assertThat(plainHtml).contains("<span>srw-head-plain</span>").doesNotContain("title-decoration");
    }

    @Test
    void shouldRenderFormWhenEditingExistingSeries() throws Exception
    {
        // GIVEN
        int id = newSeries("srw-edit");

        // WHEN
        ResultActions result = mvc.perform(get("/series/" + id + "/edit").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("series-edit"))
                .andExpect(model().attribute("creating", false))
                .andExpect(model().attributeExists("chapterCards"));
    }

    @Test
    void shouldRedirectToSeriesWhenUpdated() throws Exception
    {
        // GIVEN
        int id = newSeries("srw-update-before");

        // WHEN
        mvc.perform(post("/series/" + id).with(user("user")).with(csrf())
                        .param("titleFull", "srw-update-after").param("status", "REVIEWED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/series/" + id));

        // THEN
        assertThat(seriesRepository.findById(id).orElseThrow().getTitleFull()).isEqualTo("srw-update-after");
    }

    @Test
    void shouldRedirectToEditAndLinkChapterWhenChaptersAdded() throws Exception
    {
        // GIVEN
        int seriesId = newSeries("srw-addchapters");
        int chapterId = newChapter("srw-addchapters-ch");

        // WHEN
        mvc.perform(post("/series/" + seriesId + "/chapters/add").with(user("user")).with(csrf())
                        .param("chapterIds", String.valueOf(chapterId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/series/" + seriesId + "/edit"));

        // THEN
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getSeries().getId()).isEqualTo(seriesId);
    }

    @Test
    void shouldReturn200AndPersistWhenChapterNumUpdated() throws Exception
    {
        // GIVEN
        int seriesId = newSeries("srw-num");
        int chapterId = newChapter("srw-num-ch");
        seriesService.addChapters(seriesId, List.of(chapterId));

        // WHEN
        mvc.perform(post("/series/" + seriesId + "/chapters/" + chapterId + "/num")
                        .with(user("user")).with(csrf()).param("chapterNum", "4.5"))
                .andExpect(status().isOk());

        // THEN
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getChapterNum()).isEqualTo(4.5f);
    }

    @Test
    void shouldRedirectToEditAndUnlinkWhenChapterRemoved() throws Exception
    {
        // GIVEN
        int seriesId = newSeries("srw-remove");
        int chapterId = newChapter("srw-remove-ch");
        seriesService.addChapters(seriesId, List.of(chapterId));

        // WHEN
        mvc.perform(post("/series/" + seriesId + "/chapters/remove").with(user("user")).with(csrf())
                        .param("chapterId", String.valueOf(chapterId)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/series/" + seriesId + "/edit"));

        // THEN
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getSeries()).isNull();
    }

    @Test
    void shouldRedirectToSeriesSearchWhenDeleted() throws Exception
    {
        // GIVEN
        int id = newSeries("srw-delete");

        // WHEN
        mvc.perform(post("/series/" + id + "/delete").with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search?type=SERIES"));

        // THEN
        assertThat(seriesRepository.findById(id)).isEmpty();
    }

    /** Unlike /delete, this takes the chapters too, and the search page it lands on prints how many. */
    @Test
    void shouldDeleteLinkedChaptersTooWhenDeletingWithChapters() throws Exception
    {
        // GIVEN
        int id = newSeries("srw-delete-all");
        int chapterId = newChapter("srw-delete-all-ch");
        seriesService.addChapters(id, List.of(chapterId));

        // WHEN
        mvc.perform(post("/series/" + id + "/delete-with-chapters").with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search?type=SERIES"))
                .andExpect(flash().attribute("chaptersDeleted", 1));

        // THEN
        assertThat(seriesRepository.findById(id)).isEmpty();
        assertThat(chapterRepository.findById(chapterId)).isEmpty();
    }

    // --- Review mode -----------------------------------------------------------

    @Test
    void shouldRenderTheReviewBarDeletingWithChaptersWhenReviewingANewSeries() throws Exception
    {
        // GIVEN a NEW series holding one chapter
        SeriesForm f = new SeriesForm();
        f.setTitleFull("srw-review-new");
        f.setStatus(Status.NEW);
        f.getChapterIds().add(newChapter("srw-review-new-c1"));
        int id = seriesService.create(f);

        // WHEN
        ResultActions result = mvc.perform(get("/series/" + id).with(user("user")).param("review", "true"));

        // THEN Delete is the one that takes the chapters, and its confirmation says how many
        result.andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/series/" + id + "/mark-reviewed\"")))
                .andExpect(content().string(containsString("action=\"/series/" + id + "/delete-with-chapters\"")))
                .andExpect(content().string(containsString("AND its 1 chapter(s)")))
                // ...and picking a language keeps the review going
                .andExpect(content().string(containsString("<input type=\"hidden\" name=\"review\" value=\"true\">")));
    }

    @Test
    void shouldOfferNoMarkAsReviewedWhenTheReviewedSeriesIsNotNew() throws Exception
    {
        // GIVEN
        int id = newSeries("srw-review-done");

        // WHEN
        ResultActions result = mvc.perform(get("/series/" + id).with(user("user")).param("review", "true"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(content().string(containsString("data-review-bar")))
                .andExpect(content().string(not(containsString("/mark-reviewed"))));
    }

    @Test
    void shouldPromoteANewSeriesToReviewedWhenMarkedReviewed() throws Exception
    {
        // GIVEN
        SeriesForm f = new SeriesForm();
        f.setTitleFull("srw-mark-reviewed");
        f.setStatus(Status.NEW);
        int id = seriesService.create(f);

        // WHEN
        mvc.perform(post("/series/" + id + "/mark-reviewed").with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/series/" + id));

        // THEN
        assertThat(seriesRepository.findById(id).orElseThrow().getStatus()).isEqualTo(Status.REVIEWED);
    }
}

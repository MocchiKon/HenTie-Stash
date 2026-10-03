package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.Tag;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.MetadataCatalog;
import io.github.mocchikon.hentie.service.MetadataNameFolder;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.match.ChapterMatchingSweep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Commits to the shared DB (no rollback), so every item name is unique to this suite. */
@SpringBootTest
@AutoConfigureMockMvc
class MetadataWebIT
{
    @Autowired MockMvc mvc;
    @Autowired MetadataService metadataService;
    @Autowired MetadataCatalog catalog;
    @Autowired ChapterService chapterService;
    @Autowired TagRepository tagRepository;

    // --- Manage page -----------------------------------------------------------

    @Test
    void shouldRenderTypesAndRecentItemsWhenManagePageRequested() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/manage").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("manage"))
                .andExpect(model().attributeExists("types", "items", "recentLimit"));
    }

    @Test
    void shouldReportRepairedCountWhenImageStatsResyncRequested() throws Exception
    {
        // WHEN the Library maintenance action runs (nothing in this suite has drifted stats).
        ResultActions result = mvc.perform(post("/manage/resync-stats").with(user("user")).with(csrf()));

        // THEN it redirects back to the maintenance section carrying the repaired count.
        result.andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#maintenance"))
                .andExpect(flash().attributeExists("statsRepaired"));
    }

    @Test
    void shouldReportOutcomeWhenTitleIndexRebuildRequested() throws Exception
    {
        // WHEN the other Library maintenance action runs
        ResultActions result = mvc.perform(post("/manage/rebuild-title-index").with(user("user")).with(csrf()));

        // THEN it redirects back to the maintenance section, reporting that the index was rebuilt
        result.andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#maintenance"))
                .andExpect(flash().attribute("titleIndexRebuilt", true));

        // AND the redirect target renders it; the expression runs only with the flash attribute present.
        mvc.perform(get("/manage").with(user("user")).flashAttr("titleIndexRebuilt", true))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Title search index rebuilt.")));
    }

    @Test
    void shouldReportOutcomeWhenMetadataNameFoldRequested() throws Exception
    {
        // GIVEN an unfolded name, written through the repository because the service would fold it.
        Tag legacy = new Tag();
        legacy.setName("MWEB-Fold-Me");
        Integer id = tagRepository.save(legacy).getId();

        // WHEN the Library maintenance action runs.
        ResultActions result = mvc.perform(post("/manage/fold-metadata-names").with(user("user")).with(csrf()));

        // THEN it redirects back to the maintenance section carrying what it did, and the name is folded.
        result.andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#maintenance"))
                .andExpect(flash().attributeExists("foldResult"));
        assertThat(tagRepository.findById(id).orElseThrow().getName()).isEqualTo("mweb-fold-me");

        // AND the redirect target renders the counts; the expression runs only with the attribute present.
        mvc.perform(get("/manage").with(user("user"))
                        .flashAttr("foldResult", new MetadataNameFolder.FoldResult(1, 2, 3)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("merged into an existing item")));
    }

    @Test
    void shouldReportOutcomeWhenChapterMatchingRequested() throws Exception
    {
        // WHEN the third Library maintenance action runs (this suite has no unlinked chapters)
        ResultActions result = mvc.perform(post("/manage/match-chapters").with(user("user")).with(csrf()));

        // THEN it redirects back to the maintenance section carrying what the sweep did
        result.andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#maintenance"))
                .andExpect(flash().attributeExists("matchResult"));

        // AND the redirect target renders it; the expressions run only with the attribute present.
        mvc.perform(get("/manage").with(user("user"))
                        .flashAttr("matchResult", new ChapterMatchingSweep.Result()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("chapter(s) matched")));
    }

    @Test
    void shouldRedirectToTypeAnchorWhenItemAdded() throws Exception
    {
        // WHEN
        mvc.perform(post("/manage/add").with(user("user")).with(csrf())
                        .param("type", "tag").param("name", "mweb-add-tag"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#tag"));

        // THEN
        // Persisted, not just redirected.
        assertHasLabel("mweb-add-tag");
    }

    @Test
    void shouldChangeStoredNameWhenItemRenamed() throws Exception
    {
        // GIVEN
        metadataService.add(MetadataType.ARTIST, "mweb-rename-before");
        Integer id = idOf(MetadataType.ARTIST, "mweb-rename-before");

        // WHEN
        mvc.perform(post("/manage/rename").with(user("user")).with(csrf())
                        .param("type", "artist").param("id", String.valueOf(id)).param("name", "mweb-rename-after"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#artist"));

        // THEN
        assertHasLabelIn(MetadataType.ARTIST, "mweb-rename-after");
    }

    /**
     * Unchecked, the rename would trip the UNIQUE index at commit: a 500 after the controller returned, with
     * nothing said about what went wrong.
     */
    @Test
    void shouldExplainTheClashWhenRenamingOntoANameAnotherItemHas() throws Exception
    {
        // GIVEN two items of one kind.
        metadataService.add(MetadataType.CHARACTER, "mweb-taken-keeper");
        metadataService.add(MetadataType.CHARACTER, "mweb-taken-renamed");
        Integer id = idOf(MetadataType.CHARACTER, "mweb-taken-renamed");

        // WHEN one is renamed onto the other's name.
        mvc.perform(post("/manage/rename").with(user("user")).with(csrf())
                        .param("type", "character").param("id", String.valueOf(id))
                        .param("name", "mweb-taken-keeper").param("createRule", "true"))
                .andExpect(status().is3xxRedirection())
                // THEN it lands on the message, not the section (which would scroll past it), and the message
                // names the kind, the name in the way and what to do instead.
                .andExpect(redirectedUrl("/manage#section-title"))
                .andExpect(flash().attribute("refusal", containsString("Characters")))
                .andExpect(flash().attribute("refusal", containsString("mweb-taken-keeper")))
                .andExpect(flash().attribute("refusal", containsString("Merge")));

        // AND both items are exactly as they were.
        assertHasLabelIn(MetadataType.CHARACTER, "mweb-taken-keeper");
        assertHasLabelIn(MetadataType.CHARACTER, "mweb-taken-renamed");

        // AND the redirect target renders the message; the block runs only with the attribute present.
        mvc.perform(get("/manage").with(user("user")).flashAttr("refusal", "Characters: cannot be used"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Characters: cannot be used")));
    }

    @Test
    void shouldDeleteItemWhenRemoved() throws Exception
    {
        // GIVEN
        metadataService.add(MetadataType.CHARACTER, "mweb-remove-me");
        Integer id = idOf(MetadataType.CHARACTER, "mweb-remove-me");

        // WHEN
        mvc.perform(post("/manage/remove").with(user("user")).with(csrf())
                        .param("type", "character").param("id", String.valueOf(id)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#character"));

        // THEN
        assertThatNoLabel(MetadataType.CHARACTER, "mweb-remove-me");
    }

    @Test
    void shouldRemoveSourceWhenItemsMerged() throws Exception
    {
        // GIVEN
        metadataService.add(MetadataType.PARODY, "mweb-merge-src");
        metadataService.add(MetadataType.PARODY, "mweb-merge-dst");
        Integer src = idOf(MetadataType.PARODY, "mweb-merge-src");
        Integer dst = idOf(MetadataType.PARODY, "mweb-merge-dst");

        // WHEN
        mvc.perform(post("/manage/merge").with(user("user")).with(csrf())
                        .param("type", "parody")
                        .param("sourceId", String.valueOf(src)).param("targetId", String.valueOf(dst)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#parody"));

        // THEN
        assertThatNoLabel(MetadataType.PARODY, "mweb-merge-src");
        assertHasLabelIn(MetadataType.PARODY, "mweb-merge-dst");
    }

    @Test
    void shouldReturnMatchesWhenManageItemsJsonQueried() throws Exception
    {
        // GIVEN
        metadataService.add(MetadataType.TAG, "mweb-items-json");

        // WHEN
        ResultActions result = mvc.perform(get("/manage/items").with(user("user"))
                        .param("type", "tag").param("q", "mweb-items-json"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$[*].label", hasItem("mweb-items-json")));
    }

    // --- Tags and their versions ------------------------------------------------

    @Test
    void shouldOfferNoTagVersionsWhenTheManageListAndPickersAreQueried() throws Exception
    {
        // GIVEN a tag typed with a gender, which brings its plain tag along
        metadataService.add(MetadataType.TAG, "female:mweb-version");

        // WHEN the filter box and a merge picker ask for it
        ResultActions list = mvc.perform(get("/manage/items").with(user("user"))
                .param("type", "tag").param("q", "mweb-version"));
        ResultActions picker = mvc.perform(get("/manage/options").with(user("user"))
                .param("type", "tag").param("q", "mweb-version"));

        // THEN both offer the plain tag only: the version follows whatever is done to it.
        for (ResultActions result : List.of(list, picker))
        {
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].label", hasItem("mweb-version")))
                    .andExpect(jsonPath("$[*].label", not(hasItem("mweb-version \u2640"))));
        }
        // AND the search form's autocomplete still offers the version.
        mvc.perform(get("/api/autocomplete/tag").with(user("user")).param("q", "mweb-version"))
                .andExpect(jsonPath("$[*].label", hasItem("mweb-version \u2640")));
    }

    @Test
    void shouldPointTheManagePickersAtTheManageOptionsAndOfferRemoveGenderForTagsOnly() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/manage").with(user("user")));

        // THEN the merge pickers ask the Manage page's own endpoint, and only the Tags section removes genders.
        result.andExpect(status().isOk())
                .andExpect(content().string(containsString("data-source=\"/manage/options?type=tag\"")))
                .andExpect(content().string(containsString("data-source=\"/manage/options?type=artist\"")))
                .andExpect(content().string(containsString("action=\"/manage/remove-gender\"")));
        String page = result.andReturn().getResponse().getContentAsString();
        assertThat(page.split("action=\"/manage/remove-gender\"", -1)).hasSize(2);
    }

    @Test
    void shouldExplainTheRefusalWhenAPlainTagIsRenamedToAGenderedName() throws Exception
    {
        // GIVEN a tag
        metadataService.add(MetadataType.TAG, "mweb-ungendered-name");
        Integer id = idOf(MetadataType.TAG, "mweb-ungendered-name");

        // WHEN it is renamed to a gendered name
        mvc.perform(post("/manage/rename").with(user("user")).with(csrf())
                        .param("type", "tag").param("id", String.valueOf(id))
                        .param("name", "female:mweb-gendered-name"))
                .andExpect(status().is3xxRedirection())
                // THEN the message names the kind, the canonical name and why.
                .andExpect(redirectedUrl("/manage#section-title"))
                .andExpect(flash().attribute("refusal", containsString("Tags")))
                .andExpect(flash().attribute("refusal", containsString("mweb-gendered-name \u2640")))
                .andExpect(flash().attribute("refusal", containsString("renamed without")));

        // AND nothing was renamed.
        assertHasLabel("mweb-ungendered-name");
    }

    // --- Autocomplete ----------------------------------------------------------

    /**
     * Offers the languages chapters are written in, so the suite supplies its own chapter; relying on another
     * suite's would pass only in a full run. Welsh because nothing else uses it.
     */
    @Test
    void shouldOfferALanguageWhenAChapterIsWrittenInIt() throws Exception
    {
        // GIVEN a chapter in a language nothing else uses.
        int chapterId = chapterInLanguage("mweb-language-welsh", "Welsh");
        try
        {
            // WHEN
            ResultActions result = mvc.perform(get("/api/autocomplete/language").with(user("user")).param("q", "wel"));

            // THEN
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].label", hasItem("Welsh")));
        }
        finally
        {
            chapterService.delete(chapterId);
        }
    }

    /** Offering a language no chapter uses would let the user filter by something that can only return nothing. */
    @Test
    void shouldNotOfferALanguageNoChapterIsWrittenIn() throws Exception
    {
        // GIVEN a chapter in one language, and a query for a different, unused one.
        int chapterId = chapterInLanguage("mweb-language-unused", "Welsh");
        try
        {
            // WHEN
            ResultActions result = mvc.perform(get("/api/autocomplete/language").with(user("user")).param("q", "zul"));

            // THEN Zulu is a real language (the built-in list has it) but no chapter is written in it.
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].label", not(hasItem("Zulu"))));
        }
        finally
        {
            chapterService.delete(chapterId);
        }
    }

    @Test
    void shouldReturnMatchingItemsWhenMetadataAutocompleteQueried() throws Exception
    {
        // GIVEN
        metadataService.add(MetadataType.TAG, "mweb-autocomplete-tag");

        // WHEN
        ResultActions result = mvc.perform(get("/api/autocomplete/tag").with(user("user")).param("q", "mweb-autocomplete"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$[*].label", hasItem("mweb-autocomplete-tag")));
    }

    @Test
    void shouldReturnJsonListWhenLanguagesEndpointQueried() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/api/languages").with(user("user")).param("q", "japan"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(content().string(containsString("Japanese")));
    }

    // --- helpers ---------------------------------------------------------------

    /** Committed, so every caller deletes it again, which also evicts the {@code languages} cache. */
    private int chapterInLanguage(String titleFull, String language)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage(language);
        return chapterService.create(form);
    }

    private Integer idOf(MetadataType type, String label)
    {
        return catalog.allFresh(type).stream()
                .filter(o -> o.getLabel().equals(label)).findFirst().orElseThrow().getId();
    }

    private void assertHasLabel(String label)
    {
        assertHasLabelIn(MetadataType.TAG, label);
    }

    private void assertHasLabelIn(MetadataType type, String label)
    {
        org.assertj.core.api.Assertions.assertThat(catalog.allFresh(type))
                .extracting("label").contains(label);
    }

    private void assertThatNoLabel(MetadataType type, String label)
    {
        org.assertj.core.api.Assertions.assertThat(catalog.allFresh(type))
                .extracting("label").doesNotContain(label);
    }
}

package io.github.mocchikon.hentie.web;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.entity.Tag;
import io.github.mocchikon.hentie.repository.TagRepository;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** The pagination links' {@code baseQuery} must carry every active filter, or paging drops a filter. */
@SpringBootTest
@AutoConfigureMockMvc
class SearchControllerIT
{
    @Autowired MockMvc mvc;
    @Autowired TagRepository tagRepository;
    @Autowired CacheManager cacheManager;

    @Test
    void shouldRenderSearchFormWithStatuses() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/search").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("search"))
                .andExpect(model().attributeExists("statuses", "selected", "baseQuery"));
    }

    @Test
    void shouldRenderSearchFormWhenRootPathRequested() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/").with(user("user")));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("search"));
    }

    @Test
    void shouldRenderResultsAndBuildBaseQueryWhenFiltersProvided() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/search/results").with(user("user"))
                        .param("type", "CHAPTER")
                        .param("title", "hello world")
                        .param("tagIds", "1")
                        .param("statuses", "NEW")
                        .param("minScore", "3")
                        .param("sortBy", "SCORE")
                        .param("sortDir", "ASC"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(view().name("search-results"))
                .andExpect(model().attributeExists("results"))
                // The page renders no tokens, so nothing resolves them.
                .andExpect(model().attributeDoesNotExist("selected"))
                .andExpect(model().attribute("baseQuery", containsString("type=CHAPTER")))
                .andExpect(model().attribute("baseQuery", containsString("tagIds=1")))
                .andExpect(model().attribute("baseQuery", containsString("statuses=NEW")))
                .andExpect(model().attribute("baseQuery", containsString("minScore=3")))
                .andExpect(model().attribute("baseQuery", containsString("sortBy=SCORE")))
                .andExpect(model().attribute("baseQuery", containsString("sortDir=ASC")))
                .andExpect(model().attribute("baseQuery", containsString("title=hello+world")));
    }

    @Test
    void shouldSerializeSeriesTypeWhenSeriesResultsRequested() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/search/results").with(user("user")).param("type", "SERIES"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(model().attribute("baseQuery", containsString("type=SERIES")));
    }

    @Test
    void shouldCarryExcludedIdsIntoBaseQueryWhenExcludedFiltersProvided() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/search/results").with(user("user"))
                        .param("tagIds", "1")
                        .param("excludedTagIds", "2")
                        .param("excludedArtistIds", "3")
                        .param("excludedCharacterIds", "4")
                        .param("excludedParodyIds", "5")
                        .param("excludedGroupIds", "6"));

        // THEN included and excluded ids both survive, each under its own name.
        result.andExpect(status().isOk())
                .andExpect(model().attribute("baseQuery", containsString("tagIds=1")))
                .andExpect(model().attribute("baseQuery", containsString("excludedTagIds=2")))
                .andExpect(model().attribute("baseQuery", containsString("excludedArtistIds=3")))
                .andExpect(model().attribute("baseQuery", containsString("excludedCharacterIds=4")))
                .andExpect(model().attribute("baseQuery", containsString("excludedParodyIds=5")))
                .andExpect(model().attribute("baseQuery", containsString("excludedGroupIds=6")));
    }

    @Test
    void shouldDropExcludedIdWhenSameIdIsAlsoIncluded() throws Exception
    {
        // WHEN tag 7 is both included and excluded, beside a plain exclusion of 8
        ResultActions result = mvc.perform(get("/search/results").with(user("user"))
                        .param("tagIds", "7")
                        .param("excludedTagIds", "7")
                        .param("excludedTagIds", "8"));

        // THEN the include wins and the unrelated exclusion survives.
        result.andExpect(status().isOk())
                .andExpect(model().attribute("baseQuery", containsString("tagIds=7")))
                .andExpect(model().attribute("baseQuery", not(containsString("excludedTagIds=7"))))
                .andExpect(model().attribute("baseQuery", containsString("excludedTagIds=8")));
    }

    @Test
    @Transactional
    void shouldRenderIncludedAndExcludedTokensUnderTheirOwnInputNamesWhenSearchFormPrefilled() throws Exception
    {
        // GIVEN tags that exist only inside this rolled-back transaction.
        Tag included = tag("ctrl-included");
        Tag excluded = tag("ctrl-excluded");
        clearMetadataCache();

        try
        {
            // WHEN
            ResultActions result = mvc.perform(get("/search").with(user("user"))
                    .param("tagIds", String.valueOf(included.getId()))
                    .param("excludedTagIds", String.valueOf(excluded.getId())));

            // THEN each token submits under its own list name, and only the excluded one is marked.
            result.andExpect(status().isOk())
                    .andExpect(content().string(containsString("data-excluded-name=\"excludedTagIds\"")))
                    .andExpect(content().string(containsString(
                            "name=\"tagIds\" value=\"" + included.getId() + "\"")))
                    .andExpect(content().string(containsString(
                            "name=\"excludedTagIds\" value=\"" + excluded.getId() + "\"")))
                    .andExpect(content().string(containsString("token token-toggleable is-excluded")));
        }
        finally
        {
            clearMetadataCache();
        }
    }

    private Tag tag(String name)
    {
        Tag t = new Tag();
        t.setName(name);
        return tagRepository.saveAndFlush(t);
    }

    private void clearMetadataCache()
    {
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.METADATA)).clear();
    }
}

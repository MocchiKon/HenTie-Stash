package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.repository.MetadataRuleRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.MetadataRuleService;
import io.github.mocchikon.hentie.service.MetadataService;
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

/** Commits to the shared database (no rollback), so every name here is unique to this suite. */
@SpringBootTest
@AutoConfigureMockMvc
class MetadataRuleWebIT
{
    @Autowired MockMvc mvc;
    @Autowired MetadataService metadataService;
    @Autowired MetadataRuleService ruleService;
    @Autowired MetadataRuleRepository ruleRepository;
    @Autowired TagRepository tagRepository;

    @Test
    void shouldRecordARuleWhenAnItemIsDeletedWithTheCheckboxTicked() throws Exception
    {
        // GIVEN
        metadataService.add(MetadataType.TAG, "mrweb-delete-with-rule");
        Integer id = tagRepository.findByNameIgnoreCase("mrweb-delete-with-rule").orElseThrow().getId();

        // WHEN the Delete form is submitted with "Add rule" checked (the checkbox's own value).
        mvc.perform(post("/manage/remove").with(user("user")).with(csrf())
                        .param("type", "tag").param("id", String.valueOf(id))
                        .param("createRule", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#tag"));

        // THEN the item is gone and the decision is kept.
        assertThat(tagRepository.findByNameIgnoreCase("mrweb-delete-with-rule")).isEmpty();
        var rule = ruleRepository
                .findByTypeAndSourceNameLower(MetadataType.TAG, "mrweb-delete-with-rule").orElseThrow();
        assertThat(rule.isBlocking()).isTrue();
        assertThat(rule.getSourceNameLower()).isEqualTo("mrweb-delete-with-rule");
    }

    @Test
    void shouldMergeTheVersionsIntoTheTagAndRecordTheirRulesWhenItsGenderIsRemoved() throws Exception
    {
        // GIVEN a tag with both versions
        metadataService.resolveOrCreate(MetadataType.TAG, List.of("female:mrweb-ungender", "male:mrweb-ungender"));
        Integer id = tagRepository.findByNameIgnoreCase("mrweb-ungender").orElseThrow().getId();

        // WHEN "Remove \u2640/\u2642" is submitted with "Add rule" checked
        mvc.perform(post("/manage/remove-gender").with(user("user")).with(csrf())
                        .param("id", String.valueOf(id)).param("createRule", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#tag"));

        // THEN the versions are gone, and both names now lead to the tag.
        assertThat(tagRepository.findByNameIgnoreCase("mrweb-ungender \u2640")).isEmpty();
        assertThat(tagRepository.findByNameIgnoreCase("mrweb-ungender \u2642")).isEmpty();
        for (String version : List.of("mrweb-ungender \u2640", "mrweb-ungender \u2642"))
        {
            assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, version).orElseThrow()
                    .getTargetId()).isEqualTo(id);
        }
    }

    @Test
    void shouldRecordNoRuleWhenAGenderIsRemovedWithTheCheckboxCleared() throws Exception
    {
        // GIVEN a tag with a version
        metadataService.resolveOrCreate(MetadataType.TAG, List.of("female:mrweb-ungender-once"));
        Integer id = tagRepository.findByNameIgnoreCase("mrweb-ungender-once").orElseThrow().getId();

        // WHEN "Remove \u2640/\u2642" is submitted with "Add rule" cleared
        mvc.perform(post("/manage/remove-gender").with(user("user")).with(csrf())
                        .param("id", String.valueOf(id)).param("createRule", "false"))
                .andExpect(status().is3xxRedirection());

        // THEN the version is merged away, as a one-off.
        assertThat(tagRepository.findByNameIgnoreCase("mrweb-ungender-once \u2640")).isEmpty();
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "mrweb-ungender-once \u2640"))
                .isEmpty();
    }

    @Test
    void shouldRecordNoRuleWhenARequestCarriesNoCreateRuleFieldAtAll() throws Exception
    {
        // GIVEN
        metadataService.add(MetadataType.TAG, "mrweb-delete-no-field");
        Integer id = tagRepository.findByNameIgnoreCase("mrweb-delete-no-field").orElseThrow().getId();

        // WHEN nothing says either way (a client that does not send the field).
        mvc.perform(post("/manage/remove").with(user("user")).with(csrf())
                        .param("type", "tag").param("id", String.valueOf(id)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#tag"));

        // THEN the safe reading wins: the delete happens, no standing decision is invented.
        assertThat(tagRepository.findByNameIgnoreCase("mrweb-delete-no-field")).isEmpty();
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "mrweb-delete-no-field")).isEmpty();
    }

    @Test
    void shouldRecordNoRuleWhenTheCheckboxWasCleared() throws Exception
    {
        // GIVEN
        metadataService.add(MetadataType.TAG, "mrweb-delete-no-rule");
        Integer id = tagRepository.findByNameIgnoreCase("mrweb-delete-no-rule").orElseThrow().getId();

        // WHEN the section's checkbox was cleared, so app.js wrote "false" into the form's hidden field.
        mvc.perform(post("/manage/remove").with(user("user")).with(csrf())
                        .param("type", "tag").param("id", String.valueOf(id))
                        .param("createRule", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#tag"));

        // THEN the delete happened, but nothing was remembered.
        assertThat(tagRepository.findByNameIgnoreCase("mrweb-delete-no-rule")).isEmpty();
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "mrweb-delete-no-rule")).isEmpty();
    }

    @Test
    void shouldExplainTheRuleWhenAddingARuledOutNameIsRefused() throws Exception
    {
        // GIVEN a merged-away name, so the rule rewrites rather than blocks.
        metadataService.add(MetadataType.TAG, "mrweb-refuse-src");
        metadataService.add(MetadataType.TAG, "mrweb-refuse-dst");
        Integer src = tagRepository.findByNameIgnoreCase("mrweb-refuse-src").orElseThrow().getId();
        Integer dst = tagRepository.findByNameIgnoreCase("mrweb-refuse-dst").orElseThrow().getId();
        mvc.perform(post("/manage/merge").with(user("user")).with(csrf())
                        .param("type", "tag").param("sourceId", String.valueOf(src))
                        .param("targetId", String.valueOf(dst)).param("createRule", "true"))
                .andExpect(status().is3xxRedirection());

        // WHEN the user tries to add the source name back.
        mvc.perform(post("/manage/add").with(user("user")).with(csrf())
                        .param("type", "tag").param("name", "mrweb-refuse-src"))
                .andExpect(status().is3xxRedirection())
                // THEN it lands on the message rather than on the section, which would scroll past it.
                .andExpect(redirectedUrl("/manage#section-title"))
                // AND the message names the kind, the name and what it maps to instead.
                .andExpect(flash().attribute("refusal", containsString("Tags")))
                .andExpect(flash().attribute("refusal", containsString("mrweb-refuse-src")))
                .andExpect(flash().attribute("refusal", containsString("mrweb-refuse-dst")));

        assertThat(tagRepository.findByNameIgnoreCase("mrweb-refuse-src")).isEmpty();
    }

    @Test
    void shouldLandOnTheSectionWhenTheActionSucceeds() throws Exception
    {
        // WHEN an add that nothing rules out is submitted.
        mvc.perform(post("/manage/add").with(user("user")).with(csrf())
                        .param("type", "artist").param("name", "mrweb-ok-artist"))
                // THEN the page returns to the section that was being edited.
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manage#artist"))
                .andExpect(flash().attributeCount(0));
    }

    @Test
    void shouldRenderTheRulesOfOneKindWhenTheRulesPageRequested() throws Exception
    {
        // GIVEN a rule of each of two kinds.
        ruleService.recordBlock(MetadataType.PARODY, "mrweb-page-parody");
        ruleService.recordBlock(MetadataType.GROUP, "mrweb-page-group");

        // WHEN
        ResultActions result = mvc.perform(get("/manage/rules").with(user("user")).param("type", "parody"));

        // THEN only that kind's rules are listed.
        result.andExpect(status().isOk())
                .andExpect(view().name("metadata-rules"))
                .andExpect(model().attributeExists("type", "types", "rules"))
                .andExpect(model().attribute("type", MetadataType.PARODY))
                .andExpect(content().string(containsString("mrweb-page-parody")))
                .andExpect(content().string(not(containsString("mrweb-page-group"))));
    }

    @Test
    void shouldLinkToTheRulesPageOfEveryKindWhenManagePageRequested() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(get("/manage").with(user("user")));

        // THEN every section offers its rules page and one "Add rule" checkbox, and no rule counts are
        // rendered (a query per metadata kind on the busiest page in Manage).
        result.andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("ruleCounts"))
                .andExpect(content().string(containsString("/manage/rules?type=tag")))
                .andExpect(content().string(containsString("/manage/rules?type=group")))
                .andExpect(content().string(containsString("Manage current rules")))
                .andExpect(content().string(containsString("data-rule-toggle=\"tag\"")));
    }

    @Test
    void shouldForgetTheRuleAndStayOnThePageWhenARuleIsRemoved() throws Exception
    {
        // GIVEN a standing rule.
        ruleService.recordBlock(MetadataType.CHARACTER, "mrweb-remove-rule");
        Integer ruleId = ruleRepository
                .findByTypeAndSourceNameLower(MetadataType.CHARACTER, "mrweb-remove-rule").orElseThrow().getId();

        // WHEN the Remove button on page 2 is used.
        mvc.perform(post("/manage/rules/remove").with(user("user")).with(csrf())
                        .param("type", "character").param("id", String.valueOf(ruleId)).param("page", "1"))
                .andExpect(status().is3xxRedirection())
                // THEN it returns to the same kind and page rather than dumping the user back at page one.
                .andExpect(redirectedUrl("/manage/rules?type=character&page=1"));

        assertThat(ruleRepository.findById(ruleId)).isEmpty();
    }
}

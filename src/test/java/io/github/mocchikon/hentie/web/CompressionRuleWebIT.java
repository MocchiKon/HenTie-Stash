package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.ImageCompressionRule;
import io.github.mocchikon.hentie.repository.ImageCompressionRuleRepository;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionRuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Commits to the shared database (no rollback), so every name here is unique to this suite and its rules go. */
@SpringBootTest
@AutoConfigureMockMvc
class CompressionRuleWebIT
{
    @Autowired MockMvc mvc;
    @Autowired ImageCompressionRuleRepository ruleRepository;
    @Autowired ImageCompressionRuleService ruleService;
    @Autowired MetadataService metadataService;

    @AfterEach
    void removeRules()
    {
        ruleRepository.deleteAll();
    }

    @Test
    void shouldBeReachableFromManageAndFromSubscriptions() throws Exception
    {
        // WHEN + THEN
        mvc.perform(get("/manage").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/compression-rules\"")));
        mvc.perform(get("/subscriptions").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/compression-rules\"")));
        mvc.perform(get("/compression-rules").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(view().name("compression-rules"))
                .andExpect(content().string(containsString("No rules yet.")));
    }

    @Test
    void shouldSaveARuleWithItsConditionsAndListIt() throws Exception
    {
        // WHEN the form posts two rows and an empty one
        mvc.perform(post("/compression-rules").with(user("user")).with(csrf())
                        .param("priority", "0")
                        .param("compressionMode", BuiltInCompressionMode.LOSSLESS.getKey())
                        .param("allowUpgrade", "true")
                        .param("conditionTypes", "artist", "tag", "artist")
                        .param("conditionValues", "crweb-artist", "crweb-tag", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/compression-rules"));

        // THEN one rule naming both values...
        List<ImageCompressionRule> rules = ruleRepository.findAll();
        assertThat(rules).singleElement().satisfies(rule ->
        {
            assertThat(rule.getPriority()).isZero();
            assertThat(rule.getCompressionMode()).isEqualTo(BuiltInCompressionMode.LOSSLESS.getKey());
            assertThat(rule.isAllowUpgrade()).isTrue();
            assertThat(rule.getConditionCount()).isEqualTo(2);
        });
        // ...listed with them, and found by either
        mvc.perform(get("/compression-rules").with(user("user")).param("type", "tag").param("value", "CRWEB-TAG"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("crweb-artist")))
                .andExpect(content().string(containsString("Allows upgrading")));
        // ...and its edit page shows each condition in its row
        int id = rules.getFirst().getId();
        mvc.perform(get("/compression-rules/{id}/edit", id).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(view().name("compression-rule-form"))
                .andExpect(content().string(containsString("value=\"crweb-artist\"")))
                .andExpect(content().string(containsString("value=\"crweb-tag\"")));
    }

    /** Every row comes back as typed with the reason, so nothing has to be typed again. */
    @Test
    void shouldShowTheFormAgainWhenAConditionIsRefused() throws Exception
    {
        // WHEN
        mvc.perform(post("/compression-rules").with(user("user")).with(csrf())
                        .param("priority", "1")
                        .param("compressionMode", BuiltInCompressionMode.NONE.getKey())
                        .param("conditionTypes", "artist", "character")
                        .param("conditionValues", "crweb-kept", "crweb a | crweb b"))
                .andExpect(status().isOk())
                .andExpect(view().name("compression-rule-form"))
                .andExpect(model().attribute("conditionsError", containsString("can't contain")))
                .andExpect(content().string(containsString("value=\"crweb-kept\"")));

        // THEN nothing was saved, not even the row that was fine
        assertThat(ruleRepository.count()).isZero();
        assertThat(metadataService.findId(MetadataType.ARTIST, "crweb-kept")).isEmpty();
    }

    /** One row sends each list as a single value, which must not be split at its comma into two conditions. */
    @Test
    void shouldKeepACommaInTheNameOfTheOnlyCondition() throws Exception
    {
        // WHEN
        mvc.perform(post("/compression-rules").with(user("user")).with(csrf())
                        .param("priority", "1")
                        .param("compressionMode", BuiltInCompressionMode.NONE.getKey())
                        .param("conditionTypes", "artist")
                        .param("conditionValues", "crweb smith, john"))
                .andExpect(status().is3xxRedirection());

        // THEN
        assertThat(ruleRepository.findAll()).singleElement()
                .extracting(ImageCompressionRule::getConditionCount).isEqualTo(1);
        assertThat(metadataService.findId(MetadataType.ARTIST, "crweb smith, john")).isPresent();
    }

    @Test
    void shouldRefuseARuleWithoutAPriority() throws Exception
    {
        // WHEN + THEN
        mvc.perform(post("/compression-rules").with(user("user")).with(csrf())
                        .param("priority", "")
                        .param("compressionMode", BuiltInCompressionMode.NONE.getKey()))
                .andExpect(status().isOk())
                .andExpect(view().name("compression-rule-form"))
                .andExpect(model().attributeHasFieldErrors("form", "priority"));
        assertThat(ruleRepository.count()).isZero();
    }

    @Test
    void shouldDeleteARule() throws Exception
    {
        // GIVEN
        var form = ruleService.newForm();
        form.getConditionTypes().add("group");
        form.getConditionValues().add("crweb-delete-group");
        int id = ruleService.save(form);

        // WHEN + THEN
        mvc.perform(post("/compression-rules/{id}/delete", id).with(user("user")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/compression-rules"));
        assertThat(ruleRepository.existsById(id)).isFalse();
    }

    /** "Allow upgrading" belongs to a mode picked by hand: the rules decide it for By rules. */
    @Test
    void shouldOfferByRulesAndTheUpgradeChoiceOnTheDownloadPage() throws Exception
    {
        // WHEN + THEN
        mvc.perform(get("/chapter/download").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"" + BuiltInCompressionMode.RULES_KEY + "\"")))
                .andExpect(content().string(containsString("name=\"allowUpgrade\"")))
                .andExpect(content().string(containsString("data-shown-for=\"NONE LOSSLESS")))
                .andExpect(content().string(not(containsString("data-shown-for=\"RULES"))));
    }
}

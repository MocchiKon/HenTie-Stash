package io.github.mocchikon.hentie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validation errors must re-render the form (200, not a redirect) with the message visible. The messages
 * render only through per-field {@code th:errors} spans, so finding the text proves those spans are wired up.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorHandlingIT
{
    @Autowired MockMvc mvc;

    private static final String LONG = "a".repeat(300);

    @Test
    void shouldRenderForms() throws Exception
    {
        // WHEN + THEN
        mvc.perform(get("/chapter/new").with(user("user"))).andExpect(status().isOk());
        mvc.perform(get("/series/new").with(user("user"))).andExpect(status().isOk());
    }

    @Test
    void shouldAllowDuplicateChapterTitleFull() throws Exception
    {
        // GIVEN - title_full is not unique.
        mvc.perform(post("/chapter").with(user("user")).with(csrf())
                        .param("titleFull", "Dup Title").param("language", "English"))
                .andExpect(status().is3xxRedirection());

        // WHEN + THEN
        mvc.perform(post("/chapter").with(user("user")).with(csrf())
                        .param("titleFull", "Dup Title").param("language", "English"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void shouldShowFieldErrorWhenDuplicateChapterGalleryId() throws Exception
    {
        // GIVEN
        mvc.perform(post("/chapter").with(user("user")).with(csrf())
                        .param("titleFull", "Gallery A").param("language", "English").param("galleryId", "G-123"))
                .andExpect(status().is3xxRedirection());

        // WHEN
        ResultActions result = mvc.perform(post("/chapter").with(user("user")).with(csrf())
                .param("titleFull", "Gallery B").param("language", "English").param("galleryId", "G-123"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("That gallery ID is already in use")));
    }

    @Test
    void shouldShowFieldErrorWhenTitleFullOverLong() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(post("/chapter").with(user("user")).with(csrf())
                .param("titleFull", LONG).param("language", "en"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Must be 255 characters or fewer.")));
    }

    @Test
    void shouldShowFieldErrorWhenPrettyTitleOverLong() throws Exception
    {
        // WHEN
        ResultActions result = mvc.perform(post("/chapter").with(user("user")).with(csrf())
                .param("titleFull", "Unique Valid Title").param("title", LONG).param("language", "en"));

        // THEN
        result.andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Must be 255 characters or fewer.")));
    }

    @Test
    void shouldAllowDuplicateSeriesTitleFull() throws Exception
    {
        // GIVEN - title_full is not unique.
        mvc.perform(post("/series").with(user("user")).with(csrf())
                        .param("titleFull", "Dup Series").param("status", "REVIEWED"))
                .andExpect(status().is3xxRedirection());

        // WHEN + THEN
        mvc.perform(post("/series").with(user("user")).with(csrf())
                        .param("titleFull", "Dup Series").param("status", "REVIEWED"))
                .andExpect(status().is3xxRedirection());
    }
}

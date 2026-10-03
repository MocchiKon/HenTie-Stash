package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.service.AppShutdown;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** A context of its own, with the shutdown replaced: the real one would end the JVM running every suite. */
@SpringBootTest
@AutoConfigureMockMvc
class ShutdownWebIT
{
    @Autowired MockMvc mvc;
    @MockitoBean AppShutdown appShutdown;

    @Test
    void shouldOfferToShutDownOnTheSettingsPage() throws Exception
    {
        // WHEN + THEN
        mvc.perform(get("/settings").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/settings/shutdown\"")))
                .andExpect(content().string(containsString("Shut down HenTie")));
        verifyNoInteractions(appShutdown);
    }

    /** A page rather than a redirect: once shutdown starts, the server takes no new request to follow one. */
    @Test
    void shouldAnswerWithAPageOfItsOwnAndShutDownWhenAsked() throws Exception
    {
        // WHEN
        mvc.perform(post("/settings/shutdown").with(user("user")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("shutdown"))
                .andExpect(content().string(containsString("HenTie is shutting down")));

        // THEN
        verify(appShutdown).shutDownSoon();
    }

    /** Without the form's token, any web page open in the user's browser could stop the app. */
    @Test
    void shouldNotShutDownWithoutTheFormsToken() throws Exception
    {
        // WHEN
        mvc.perform(post("/settings/shutdown").with(user("user")))
                .andExpect(status().isForbidden());

        // THEN
        verifyNoInteractions(appShutdown);
    }
}

package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.TestLogin;
import io.github.mocchikon.hentie.security.FailedLoginGuard;
import io.github.mocchikon.hentie.service.AppShutdown;
import io.github.mocchikon.hentie.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A context of its own, with the shutdown replaced (the real one would end the JVM running every suite) and the limit
 * the test profile turns off set again.
 */
@SpringBootTest(properties = "app.security.max-failed-logins=3")
@AutoConfigureMockMvc
class FailedLoginShutdownIT
{
    @Autowired MockMvc mvc;
    @Autowired SettingsService settingsService;
    @Autowired TestLogin testLogin;
    @MockitoBean AppShutdown appShutdown;

    @BeforeEach
    void enable() throws Exception
    {
        settingsService.setShutDownOnFailedLogins(true);
        // The count lives in the shared guard; a login starts it over.
        login();
    }

    private void login() throws Exception
    {
        mvc.perform(formLogin("/login").user("username", "user").password("password", TestLogin.PASSWORD))
                .andExpect(authenticated()).andExpect(redirectedUrl("/"));
    }

    @AfterEach
    void restore()
    {
        settingsService.setShutDownOnFailedLogins(true);
        testLogin.restore();
    }

    private ResultActions wrongPassword() throws Exception
    {
        return mvc.perform(formLogin("/login").user("username", "user").password("password", "wrong"));
    }

    @Test
    void shouldShutDownAfterTheThirdWrongPasswordInARow() throws Exception
    {
        // GIVEN the page explaining the shutdown is shown only once there is one
        mvc.perform(get(FailedLoginGuard.SHUT_DOWN_PAGE)).andExpect(redirectedUrl("/login"));

        // WHEN two wrong passwords
        wrongPassword().andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error"));
        wrongPassword().andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error"));

        // THEN the app still runs, and the login page says how many are left
        verifyNoInteractions(appShutdown);
        mvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1 more wrong password(s) in a row")));

        // WHEN the third
        wrongPassword().andExpect(unauthenticated())
                .andExpect(forwardedUrl(FailedLoginGuard.SHUT_DOWN_PAGE));

        // THEN the app shuts down, and the page says why
        verify(appShutdown).shutDownSoon(anyString());
        mvc.perform(get(FailedLoginGuard.SHUT_DOWN_PAGE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Too many wrong passwords")));
    }

    @Test
    void shouldStartTheCountOverAfterALogin() throws Exception
    {
        // GIVEN
        wrongPassword();
        wrongPassword();

        // WHEN
        login();
        wrongPassword();
        wrongPassword();

        // THEN
        verifyNoInteractions(appShutdown);
    }

    @Test
    void shouldNotShutDownWhenTurnedOffInSettings() throws Exception
    {
        // GIVEN Settings saved with the box unticked
        mvc.perform(post("/settings").with(user("user")).with(csrf())
                        .param("loginRequired", "true")
                        .param("viewMode", "FIT")
                        .param("titleDisplayMode", "FULL"))
                .andExpect(status().is3xxRedirection());

        // WHEN
        for (int i = 0; i < 5; i++)
        {
            wrongPassword().andExpect(redirectedUrl("/login?error"));
        }

        // THEN
        verifyNoInteractions(appShutdown);
        mvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(not(containsString("more wrong password(s)"))));
    }
}

package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIT
{
    @Autowired MockMvc mvc;
    @Autowired SettingsService settingsService;
    @Autowired AppProperties appProperties;
    @Autowired TestLogin testLogin;

    @AfterEach
    void restoreLogin()
    {
        // The password file and the settings cache are shared by every suite in the run.
        testLogin.restore();
    }

    @Test
    void shouldRedirectToLoginWhenAccessingProtectedPage() throws Exception
    {
        // WHEN
        var result = mvc.perform(get("/search"));

        // THEN
        result.andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void shouldServeLoginPageWhenRequestedUnauthenticated() throws Exception
    {
        // WHEN
        var result = mvc.perform(get("/login"));

        // THEN
        result.andExpect(status().isOk());
    }

    @Test
    void shouldAuthenticateWhenPasswordIsCorrect() throws Exception
    {
        // WHEN
        var result = mvc.perform(formLogin("/login").user("username", "user").password("password", TestLogin.PASSWORD));

        // THEN
        result.andExpect(authenticated());
    }

    @Test
    void shouldRejectLoginWhenPasswordIsWrong() throws Exception
    {
        // WHEN
        var result = mvc.perform(formLogin("/login").user("username", "user").password("password", "wrong"));

        // THEN
        result.andExpect(unauthenticated());
    }

    /** Deleting the password file is the reset: login turns off instead of locking everyone out, and no default password exists. */
    @Test
    void shouldTurnLoginOffWhenPasswordFileIsDeletedOnStartup() throws Exception
    {
        // WHEN
        Files.deleteIfExists(Paths.get(appProperties.getPasswordFile()));
        settingsService.init();

        // THEN
        assertThat(settingsService.hasPassword()).isFalse();
        assertThat(settingsService.isLoginRequired()).isFalse();
        assertThat(settingsService.get(SettingsService.LOGIN_REQUIRED, "")).isEqualTo("false");
        assertThat(Files.exists(Paths.get(appProperties.getPasswordFile()))).isFalse();
        mvc.perform(get("/search")).andExpect(status().isOk());
        mvc.perform(formLogin("/login").user("username", "user").password("password", TestLogin.PASSWORD))
                .andExpect(unauthenticated());
    }

    @Test
    void shouldServePagesWhenLoginDisabled() throws Exception
    {
        // GIVEN
        settingsService.setLoginRequired(false);
        try
        {
            // WHEN
            var result = mvc.perform(get("/search"));

            // THEN
            result.andExpect(status().isOk());
        }
        finally
        {
            settingsService.setLoginRequired(true);
        }
    }

    @Test
    void shouldEnforceAuthAgainWithoutRestartWhenLoginReEnabled() throws Exception
    {
        // GIVEN
        // LoginToggleFilter reads the toggle per request, so both directions apply without a filter-chain rebuild.
        settingsService.setLoginRequired(false);
        try
        {
            // WHEN + THEN
            mvc.perform(get("/search")).andExpect(status().isOk());

            settingsService.setLoginRequired(true);
            mvc.perform(get("/search"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        }
        finally
        {
            settingsService.setLoginRequired(true);
        }
    }

    @Test
    void shouldTurnLoginOffWhenPasswordFileIsEmptyOnStartup() throws Exception
    {
        // WHEN
        // Blank content (distinct from deleting the file) is no password either.
        Files.writeString(Paths.get(appProperties.getPasswordFile()), "   ");
        settingsService.init();

        // THEN
        assertThat(settingsService.hasPassword()).isFalse();
        assertThat(settingsService.isLoginRequired()).isFalse();
        mvc.perform(get("/search")).andExpect(status().isOk());
    }

    /** A password that survives a restart keeps login as it was - only a missing one turns it off. */
    @Test
    void shouldKeepLoginRequiredWhenPasswordFileSurvivesARestart() throws Exception
    {
        // WHEN
        settingsService.init();

        // THEN
        assertThat(settingsService.isLoginRequired()).isTrue();
        mvc.perform(get("/search")).andExpect(redirectedUrl("/login"));
    }
}

package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.TitleDisplayMode;
import io.github.mocchikon.hentie.entity.Setting;
import io.github.mocchikon.hentie.entity.ViewMode;
import io.github.mocchikon.hentie.repository.SettingRepository;
import io.github.mocchikon.hentie.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@SpringBootTest
@Transactional
class SettingsServiceIT
{
    @Autowired SettingsService settingsService;
    @Autowired SettingRepository settingRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AppProperties appProperties;
    @Autowired TestLogin testLogin;

    @AfterEach
    void restoreDefaults()
    {
        // The settings cache is a shared singleton the rollback does not reach.
        settingsService.setSearchPageSize(SettingsService.DEFAULT_SEARCH_PAGE_SIZE);
        settingsService.setPagesAhead(SettingsService.DEFAULT_PAGES_AHEAD);
        settingsService.setDefaultViewMode(ViewMode.FIT);
        settingsService.setTitleDisplayMode(TitleDisplayMode.FULL);
        settingsService.setNhentaiApiKey("");
        // The password file is not rolled back either.
        testLogin.restore();
    }

    @Test
    void shouldRoundTripWhenSettingSearchPageSize()
    {
        // WHEN
        settingsService.setSearchPageSize(20);

        // THEN
        assertThat(settingsService.getSearchPageSize()).isEqualTo(20);
    }

    /** 0 is valid (only the page on screen); the maximum bounds the plan the viewer sends with every request. */
    @Test
    void shouldClampPagesAheadToWhatTheViewerCanPrepare()
    {
        // WHEN + THEN
        settingsService.setPagesAhead(-1);
        assertThat(settingsService.getPagesAhead()).isZero();

        settingsService.setPagesAhead(SettingsService.MAX_PAGES_AHEAD + 1);
        assertThat(settingsService.getPagesAhead()).isEqualTo(SettingsService.MAX_PAGES_AHEAD);
    }

    @Test
    void shouldClampToAtLeastOneWhenSearchPageSizeIsNonPositive()
    {
        // WHEN + THEN
        settingsService.setSearchPageSize(0);
        assertThat(settingsService.getSearchPageSize()).isEqualTo(1);

        settingsService.setSearchPageSize(-10);
        assertThat(settingsService.getSearchPageSize()).isEqualTo(1);
    }

    @Test
    void shouldFallBackToDefaultWhenSearchPageSizeIsNonNumeric()
    {
        // GIVEN
        settingRepository.save(new Setting(SettingsService.SEARCH_PAGE_SIZE, "not-a-number"));

        // WHEN
        settingsService.init();   // reloads the cache from the DB

        // THEN
        assertThat(settingsService.getSearchPageSize()).isEqualTo(SettingsService.DEFAULT_SEARCH_PAGE_SIZE);
    }

    @Test
    void shouldRoundTripWhenSettingViewMode()
    {
        // WHEN
        settingsService.setDefaultViewMode(ViewMode.ORIGINAL);

        // THEN
        assertThat(settingsService.getDefaultViewMode()).isEqualTo(ViewMode.ORIGINAL);
    }

    @Test
    void shouldFallBackToFitWhenViewModeIsUnknown()
    {
        // GIVEN
        settingRepository.save(new Setting(SettingsService.VIEW_MODE, "NOT_A_MODE"));

        // WHEN
        settingsService.init();

        // THEN
        assertThat(settingsService.getDefaultViewMode()).isEqualTo(ViewMode.FIT);
    }

    @Test
    void shouldRoundTripWhenSettingTitleDisplayMode()
    {
        // WHEN
        settingsService.setTitleDisplayMode(TitleDisplayMode.NATIVE);

        // THEN
        assertThat(settingsService.getTitleDisplayMode()).isEqualTo(TitleDisplayMode.NATIVE);
    }

    @Test
    void shouldFallBackToFullWhenTitleDisplayModeIsUnknown()
    {
        // GIVEN
        settingRepository.save(new Setting(SettingsService.TITLE_DISPLAY_MODE, "bogus"));

        // WHEN
        settingsService.init();

        // THEN
        assertThat(settingsService.getTitleDisplayMode()).isEqualTo(TitleDisplayMode.FULL);
    }

    @Test
    void shouldDefaultToEmptyAndRoundTripWhenSettingNhentaiApiKey()
    {
        // WHEN + THEN - stripped, since a pasted key often brings a line break the site would refuse.
        settingsService.setNhentaiApiKey(" secret-key\n");
        assertThat(settingsService.getNhentaiApiKey()).isEqualTo("secret-key");

        settingsService.setNhentaiApiKey(null);
        assertThat(settingsService.getNhentaiApiKey()).isEmpty();
    }

    @Test
    void shouldUpdateHashToMatchNewPasswordWhenChangingPassword()
    {
        // WHEN
        settingsService.changePassword("brand-new-password");
        String hash = settingsService.getPasswordHash();

        // THEN
        assertThat(passwordEncoder.matches("brand-new-password", hash)).isTrue();
        assertThat(passwordEncoder.matches(TestLogin.PASSWORD, hash)).isFalse();
    }

    /** Requiring login without a password would lock everybody out. */
    @Test
    void shouldRefuseToRequireLoginWhileNoPasswordIsSet() throws Exception
    {
        // GIVEN
        Files.deleteIfExists(Path.of(appProperties.getPasswordFile()));
        settingsService.init();

        // WHEN + THEN
        assertThat(settingsService.hasPassword()).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> settingsService.setLoginRequired(true));
        assertThat(settingsService.isLoginRequired()).isFalse();
    }
}

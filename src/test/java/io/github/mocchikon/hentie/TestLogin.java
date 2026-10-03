package io.github.mocchikon.hentie;

import org.springframework.stereotype.Component;

import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * Gives every {@code @SpringBootTest} context a password and login required, the state the suites assume.
 * No property can seed a password, because the app deliberately has no default one.
 * <p>
 * Found by component scan, like {@link TestSchemaConfig}, because it sits in the application base package.
 */
@Component
@RequiredArgsConstructor
public class TestLogin
{
    public static final String PASSWORD = "test";

    private final SettingsService settingsService;

    @PostConstruct
    public void restore()
    {
        settingsService.changePassword(PASSWORD);
        settingsService.setLoginRequired(true);
    }
}

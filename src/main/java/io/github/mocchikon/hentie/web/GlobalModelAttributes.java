package io.github.mocchikon.hentie.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import io.github.mocchikon.hentie.service.SettingsService;

/**
 * For the Logout button: with login off, {@code LoginToggleFilter} authenticates everyone, so
 * {@code sec:authorize} alone would always show it.
 */
@ControllerAdvice
public class GlobalModelAttributes
{
    private final SettingsService settingsService;

    public GlobalModelAttributes(SettingsService settingsService)
    {
        this.settingsService = settingsService;
    }

    @ModelAttribute("loginRequired")
    public boolean loginRequired()
    {
        return settingsService.isLoginRequired();
    }
}

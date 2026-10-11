package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.UpdateCheckService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** What the navbar on every page needs. */
@ControllerAdvice
public class GlobalModelAttributes
{
    private final SettingsService settingsService;
    private final UpdateCheckService updateCheckService;

    public GlobalModelAttributes(SettingsService settingsService, UpdateCheckService updateCheckService)
    {
        this.settingsService = settingsService;
        this.updateCheckService = updateCheckService;
    }

    /**
     * For the Logout button: with login off, {@code LoginToggleFilter} authenticates everyone, so
     * {@code sec:authorize} alone would always show it.
     */
    @ModelAttribute("loginRequired")
    public boolean loginRequired()
    {
        return settingsService.isLoginRequired();
    }

    /** Null unless a newer release is out. */
    @ModelAttribute("availableUpdate")
    public UpdateCheckService.Release availableUpdate()
    {
        return updateCheckService.available().orElse(null);
    }
}

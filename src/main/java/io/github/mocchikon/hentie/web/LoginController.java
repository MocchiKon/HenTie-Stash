package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.security.FailedLoginGuard;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class LoginController
{
    private final AppProperties appProperties;
    private final FailedLoginGuard failedLoginGuard;

    public LoginController(AppProperties appProperties, FailedLoginGuard failedLoginGuard)
    {
        this.appProperties = appProperties;
        this.failedLoginGuard = failedLoginGuard;
    }

    @GetMapping("/login")
    public String login(Model model)
    {
        // Configurable, so the reset tip names the file in effect.
        model.addAttribute("passwordFile", appProperties.getPasswordFile());
        // Said up front, so the user's own typos never stop the app by surprise.
        failedLoginGuard.attemptsLeft().ifPresent(left -> model.addAttribute("attemptsLeft", left));
        return "login";
    }

    /**
     * Forwarded to from the failed login that stops the app, so it answers the login's POST too. Reachable without
     * login, so it shows the page only once the app is really stopping.
     */
    @RequestMapping(FailedLoginGuard.SHUT_DOWN_PAGE)
    public String shutDown(Model model)
    {
        if (!failedLoginGuard.isTripped())
        {
            return "redirect:/login";
        }
        model.addAttribute("afterFailedLogins", true);
        return "shutdown";
    }
}

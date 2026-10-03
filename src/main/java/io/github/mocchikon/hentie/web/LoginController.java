package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.config.AppProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController
{
    private final AppProperties appProperties;

    public LoginController(AppProperties appProperties)
    {
        this.appProperties = appProperties;
    }

    @GetMapping("/login")
    public String login(Model model)
    {
        // Configurable, so the reset tip names the file in effect.
        model.addAttribute("passwordFile", appProperties.getPasswordFile());
        return "login";
    }
}

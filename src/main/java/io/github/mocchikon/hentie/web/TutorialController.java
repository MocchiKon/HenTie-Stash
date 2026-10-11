package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The user guide lives in the app, not in Markdown files next to it, so it is where the user already is and
 * can link straight to the settings it talks about. Configurable values come from the configuration in effect,
 * so the page never names a default an install has overridden.
 */
@Controller
@RequiredArgsConstructor
public class TutorialController
{
    private final AppProperties appProperties;

    @GetMapping("/tutorial")
    public String tutorial(HttpServletRequest request, Model model)
    {
        model.addAttribute("port", request.getServerPort());
        model.addAttribute("passwordFile", appProperties.getPasswordFile());
        model.addAttribute("dataDir", appProperties.getDataDir());
        model.addAttribute("pagesAhead", SettingsService.DEFAULT_PAGES_AHEAD);
        model.addAttribute("comfyJobTimeoutSeconds", appProperties.getComfyui().getJobTimeoutSeconds());
        model.addAttribute("comfyResultCacheMaxMb", appProperties.getComfyui().getResultCacheMaxMb());
        return "tutorial";
    }
}

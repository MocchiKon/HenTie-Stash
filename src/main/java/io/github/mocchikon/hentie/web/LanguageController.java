package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.service.LanguageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/languages")
public class LanguageController
{
    @GetMapping
    public List<String> search(@RequestParam(defaultValue = "") String q)
    {
        return LanguageService.search(q, 12);
    }
}

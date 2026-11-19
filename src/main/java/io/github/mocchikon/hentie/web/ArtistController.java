package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.service.MetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.regex.Pattern;

/** The heart in an artist's chip on the chapter and series pages. */
@Controller
@RequestMapping("/artist")
@RequiredArgsConstructor
public class ArtistController
{
    /** Only the pages that show the heart: a free-form address would make this an open redirect. */
    private static final Pattern BACK = Pattern.compile("/(chapter|series)/\\d+");

    private final MetadataService metadataService;

    /** The heart posts the new value; without JavaScript the page it was on reloads. */
    @PostMapping("/{id:\\d+}/favourite")
    public String favourite(@PathVariable int id, @RequestParam boolean favourite,
                            @RequestParam(required = false) String back)
    {
        metadataService.setArtistFavourite(id, favourite);
        return "redirect:" + (back != null && BACK.matcher(back).matches() ? back : "/");
    }

    /** {@code app.js} flips the heart itself, so the page keeps its place. */
    @PostMapping(value = "/{id:\\d+}/favourite", params = ChapterController.IN_PLACE)
    @ResponseBody
    public ResponseEntity<Void> favouriteInPlace(@PathVariable int id, @RequestParam boolean favourite)
    {
        return metadataService.setArtistFavourite(id, favourite)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}

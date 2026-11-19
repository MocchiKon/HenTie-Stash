package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.MetadataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Commits to the shared DB, so names are unique to this suite, and removes what it made. */
@SpringBootTest
@AutoConfigureMockMvc
class ArtistControllerIT
{
    @Autowired MockMvc mvc;
    @Autowired ChapterService chapterService;
    @Autowired MetadataService metadataService;
    @Autowired ArtistRepository artistRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void shouldMarkAnArtistFavouriteFromTheHeartInItsChip() throws Exception
    {
        // GIVEN a chapter by the artist
        Artist artist = new Artist();
        artist.setName("acw-heart-artist");
        int artistId = artistRepository.save(artist).getId();
        ChapterForm form = new ChapterForm();
        form.setTitleFull("acw-heart-chapter");
        form.setLanguage("English");
        form.setArtistIds(new ArrayList<>(List.of(artistId)));
        int chapterId = chapterService.create(form);
        try
        {
            mvc.perform(get("/chapter/" + chapterId).with(user("user")))
                    .andExpect(content().string(containsString("/artist/" + artistId + "/favourite")))
                    .andExpect(content().string(containsString("Add artist to favourites")));

            // WHEN the heart is clicked without JavaScript, then in place to take the mark back
            mvc.perform(post("/artist/" + artistId + "/favourite").param("favourite", "true")
                            .param("back", "/chapter/" + chapterId).with(user("user")).with(csrf()))
                    .andExpect(redirectedUrl("/chapter/" + chapterId));
            boolean flaggedAfterClick = favouriteArtistFlag(chapterId);
            mvc.perform(get("/chapter/" + chapterId).with(user("user")))
                    .andExpect(content().string(containsString("Remove artist from favourites")));
            mvc.perform(post("/artist/" + artistId + "/favourite").param("favourite", "false")
                            .param("inPlace", "true").with(user("user")).with(csrf()))
                    .andExpect(status().isNoContent());

            // THEN
            assertThat(flaggedAfterClick).isTrue();
            assertThat(artistRepository.findById(artistId).orElseThrow().isFavourite()).isFalse();
            assertThat(favouriteArtistFlag(chapterId)).isFalse();
        }
        finally
        {
            chapterService.delete(chapterId);
            metadataService.remove(MetadataType.ARTIST, artistId, false);
        }
    }

    @Test
    void shouldReturnOnlyToAChapterOrSeriesPage() throws Exception
    {
        mvc.perform(post("/artist/" + Integer.MAX_VALUE + "/favourite").param("favourite", "true")
                        .param("back", "//elsewhere.example/chapter/1").with(user("user")).with(csrf()))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void shouldAnswerNotFoundInPlaceForAnUnknownArtist() throws Exception
    {
        mvc.perform(post("/artist/" + Integer.MAX_VALUE + "/favourite").param("favourite", "true")
                        .param("inPlace", "true").with(user("user")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    private boolean favouriteArtistFlag(int chapterId)
    {
        return Boolean.TRUE.equals(jdbc.queryForObject("select favourite_artist from chapter where id = ?",
                Boolean.class, chapterId));
    }
}

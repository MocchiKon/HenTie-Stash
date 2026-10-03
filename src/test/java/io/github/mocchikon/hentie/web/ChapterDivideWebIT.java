package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.service.ChapterDivisionService.CreatedPart;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Posts bind the way a browser sends them: {@code starts} once per mark, {@code titles[<first page>]} once per
 * new chapter. Commits to the shared database, so rows are cleaned up by hand and the titles are unique.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChapterDivideWebIT
{
    @Autowired MockMvc mvc;
    @Autowired ChapterService chapterService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired ImageDirectory imageDirectory;

    private final List<Integer> chapters = new ArrayList<>();

    @AfterEach
    void cleanUp()
    {
        chapters.stream().filter(chapterRepository::existsById).forEach(chapterService::delete);
        chapters.clear();
    }

    @Test
    void shouldOfferDividingOnTheEditPageOnlyForAChapterOfSeveralPages() throws Exception
    {
        // GIVEN
        int several = chapterWithPages("Divide Web Button Vol 1", "1.jpg", "2.jpg");
        int single = chapterWithPages("Divide Web Single", "1.jpg");

        // WHEN / THEN a single page has nothing to divide.
        mvc.perform(get("/chapter/" + several + "/edit").with(user("user")))
                .andExpect(content().string(containsString("href=\"/chapter/" + several + "/divide\"")));
        mvc.perform(get("/chapter/" + single + "/edit").with(user("user")))
                .andExpect(content().string(not(containsString("/divide\""))));
    }

    @Test
    void shouldOfferEveryPageButTheFirstAsTheStartOfANewChapter() throws Exception
    {
        // GIVEN
        int id = chapterWithPages("Divide Web Render Vol 1", "1.jpg", "2.jpg", "3.jpg");

        // WHEN / THEN the first page always starts the part that stays, so it carries no mark.
        mvc.perform(get("/chapter/" + id + "/divide").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(view().name("chapter-divide"))
                .andExpect(model().attribute("blocked", nullValue()))
                .andExpect(content().string(containsString("name=\"keptTitle\" value=\"Divide Web Render Vol 1\"")))
                .andExpect(content().string(containsString("name=\"starts\" value=\"2.jpg\"")))
                .andExpect(content().string(containsString("name=\"starts\" value=\"3.jpg\"")))
                .andExpect(content().string(not(containsString("name=\"starts\" value=\"1.jpg\""))));
    }

    @Test
    void shouldPreselectTheChaptersOwnStatusForTheNewChapters() throws Exception
    {
        // GIVEN a favourite.
        int id = chapterWithPages("Divide Web Status Vol 1", "1.jpg", "2.jpg");
        var form = chapterService.toForm(id);
        form.setStatus(Status.REVIEWED_FAVOURITE);
        chapterService.update(form);

        // WHEN / THEN the new chapters' status starts at the compilation's.
        mvc.perform(get("/chapter/" + id + "/divide").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"partStatus\"")))
                .andExpect(content().string(containsString("value=\"REVIEWED_FAVOURITE\" selected=\"selected\"")))
                .andExpect(content().string(not(containsString("value=\"NEW\" selected=\"selected\""))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldGiveTheNewChaptersTheStatusPickedOnThePage() throws Exception
    {
        // GIVEN a compilation nobody has reviewed yet.
        int id = chapterWithPages("Divide Web Picked Vol 1", "1.jpg", "2.jpg");

        // WHEN it is divided with the new chapter marked as reviewed.
        MvcResult result = mvc.perform(post("/chapter/" + id + "/divide").with(user("user")).with(csrf())
                        .param("keptTitle", "Divide Web Picked Vol 1")
                        .param("partStatus", "REVIEWED")
                        .param("starts", "2.jpg")
                        .param("titles[2.jpg]", "Divide Web Picked 2"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // THEN the new chapter has it, and the compilation keeps its own.
        var divided = (List<CreatedPart>) result.getFlashMap().get("divided");
        int part = divided.getFirst().chapterId();
        chapters.add(part);
        assertThat(chapterService.get(part).getStatus()).isEqualTo(Status.REVIEWED);
        assertThat(chapterService.get(id).getStatus()).isEqualTo(Status.NEW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDivideAndLandOnTheChapterNamingTheNewOne() throws Exception
    {
        // GIVEN
        int id = chapterWithPages("Divide Web Post Vol 1", "1.jpg", "2.jpg", "3.jpg");

        // WHEN a new chapter is marked at page 3, under a title with a comma in it.
        MvcResult result = mvc.perform(post("/chapter/" + id + "/divide").with(user("user")).with(csrf())
                        .param("keptTitle", "Divide Web Post 1")
                        .param("starts", "3.jpg")
                        .param("titles[3.jpg]", "Divide Web Post 2, the second"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/" + id))
                .andReturn();

        // THEN one chapter, titled exactly as typed (a list bound from one value would split at the comma),
        // and the part that stays took its own title.
        var divided = (List<CreatedPart>) result.getFlashMap().get("divided");
        assertThat(divided).extracting(CreatedPart::titleFull, CreatedPart::pageCount)
                .containsExactly(tuple("Divide Web Post 2, the second", 1));
        int part = divided.getFirst().chapterId();
        chapters.add(part);
        assertThat(chapterService.get(id).getTitleFull()).isEqualTo("Divide Web Post 1");

        // AND the chapter's page names the new one and links to it.
        mvc.perform(get("/chapter/" + id).with(user("user")).flashAttr("divided", divided))
                .andExpect(content().string(containsString("Divide Web Post 2, the second")))
                .andExpect(content().string(containsString("href=\"/chapter/" + part + "\"")));
    }

    @Test
    void shouldSendARefusedDivisionBackAsItWasPosted() throws Exception
    {
        // GIVEN
        int id = chapterWithPages("Divide Web Refused Vol 1", "1.jpg", "2.jpg", "3.jpg");

        // WHEN two chapters are marked, the second with its title cleared.
        mvc.perform(post("/chapter/" + id + "/divide").with(user("user")).with(csrf())
                        .param("keptTitle", "Divide Web Refused 1")
                        .param("partStatus", "REVIEWED")
                        .param("starts", "2.jpg", "3.jpg")
                        .param("titles[2.jpg]", "Typed for part two")
                        .param("titles[3.jpg]", ""))
                // THEN the page says why, keeping the posted marks and titles.
                .andExpect(status().isOk())
                .andExpect(view().name("chapter-divide"))
                .andExpect(model().attribute("error", containsString("Give part 3 (from page 3) a title")))
                .andExpect(content().string(containsString("name=\"keptTitle\" value=\"Divide Web Refused 1\"")))
                .andExpect(content().string(containsString("value=\"REVIEWED\" selected=\"selected\"")))
                .andExpect(content().string(containsString("name=\"titles[2.jpg]\"")))
                .andExpect(content().string(containsString("value=\"Typed for part two\"")))
                .andExpect(content().string(containsString("value=\"2.jpg\" checked=\"checked\"")))
                .andExpect(content().string(containsString("value=\"3.jpg\" checked=\"checked\"")));

        // AND nothing moved.
        assertThat(imageDirectory.list(id)).containsExactly("1.jpg", "2.jpg", "3.jpg");
    }

    @Test
    void shouldSayWhyAChapterCannotBeDividedInsteadOfOfferingTheForm() throws Exception
    {
        // GIVEN a chapter whose download never finished.
        int id = chapterWithPages("Divide Web Pending Vol 1", "1.jpg", "2.jpg");
        chapterService.setDownloadStatus(id, DownloadStatus.PENDING);

        // WHEN / THEN the reason, before anything is marked - and no marks to make.
        mvc.perform(get("/chapter/" + id + "/divide").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("blocked", containsString("never finished")))
                .andExpect(content().string(not(containsString("name=\"starts\""))));
    }

    @Test
    void shouldAnswerNotFoundForAChapterThatDoesNotExist() throws Exception
    {
        mvc.perform(get("/chapter/987654321/divide").with(user("user")))
                .andExpect(status().isNotFound());
    }

    private int chapterWithPages(String titleFull, String... pages) throws IOException
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        int id = chapterService.create(form);
        chapters.add(id);
        Path dir = imageDirectory.chapterDir(id);
        Files.createDirectories(dir);
        for (String page : pages)
        {
            Files.writeString(dir.resolve(page), "page " + page, StandardCharsets.UTF_8);
        }
        chapterService.rescanImages(id);
        return id;
    }
}

package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.TestLogin;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.ViewMode;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The gallery-dl choices on the Download page and their defaults in Settings. Settings is a shared singleton and
 * these suites commit, so everything changed is put back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GalleryDlWebIT
{
    @Autowired MockMvc mvc;
    @Autowired SettingsService settingsService;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired TestLogin testLogin;

    private GalleryDlOptions defaultsBefore;

    @BeforeEach
    void remember()
    {
        defaultsBefore = settingsService.getGalleryDlDefaults();
    }

    @AfterEach
    void restore()
    {
        settingsService.setGalleryDlDefaults(defaultsBefore);
        settingsService.setSystemGalleryDlEnabled(false);
        queueRepository.findByLink("hitomi:7201").ifPresent(queueRepository::delete);
        queueRepository.findByLink("nhentai:7202").ifPresent(queueRepository::delete);
        testLogin.restore();
    }

    @Test
    void shouldStartTheDownloadFormAtTheSettingsDefaults() throws Exception
    {
        // GIVEN
        settingsService.setGalleryDlDefaults(new GalleryDlOptions("librewolf", true, "1.5-2"));

        // WHEN + THEN
        mvc.perform(get("/chapter/download").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("cookiesBrowser", "librewolf"))
                .andExpect(model().attribute("downloadOriginals", true))
                .andExpect(model().attribute("requestDelay", "1.5-2"))
                .andExpect(content().string(containsString("Use cookies when downloading")))
                .andExpect(content().string(containsString(
                        "data-shown-for-links=\"e-hentai.org ehentai: exhentai.org hitomi.la hitomi:\"")))
                // hitomi reads neither cookies nor originals, so only e-hentai's links show those two.
                .andExpect(content().string(containsString(
                        "<div class=\"field\" data-shown-for-links=\"e-hentai.org ehentai: exhentai.org\">")))
                .andExpect(content().string(containsString("https://hitomi.la/doujinshi/title-english-123456.html")));
    }

    @Test
    void shouldQueueTheLinksWithThePastesChoices() throws Exception
    {
        // WHEN
        mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                        .param("links", "hitomi:7201")
                        .param("compressionMode", "NONE")
                        .param("cookiesBrowser", "Firefox")
                        .param("downloadOriginals", "true")
                        .param("requestDelay", " 0.5 - 1 "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chapter/queue"));

        // THEN
        DownloadQueueItem item = queueRepository.findByLink("hitomi:7201").orElseThrow();
        assertThat(item.getCookiesBrowser()).isEqualTo("firefox");
        assertThat(item.isDownloadOriginals()).isTrue();
        assertThat(item.getRequestDelay()).isEqualTo("0.5-1");
    }

    @Test
    void shouldKeepTheLinksAndSayWhyWhenTheDelayIsUnreadable() throws Exception
    {
        // WHEN
        mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                        .param("links", "hitomi:7201")
                        .param("compressionMode", "NONE")
                        .param("requestDelay", "fast please"))
                // THEN the form comes back with the links and a reason, and nothing was queued.
                .andExpect(status().isOk())
                .andExpect(view().name("chapter-download"))
                .andExpect(model().attribute("links", "hitomi:7201"))
                .andExpect(model().attribute("requestDelay", "fast please"))
                .andExpect(content().string(containsString("is not a delay gallery-dl understands")));
        assertThat(queueRepository.findByLink("hitomi:7201")).isEmpty();
    }

    @Test
    void shouldQueueLinksThatIgnoreTheDelayWhateverItSays() throws Exception
    {
        // GIVEN Settings' default delay
        settingsService.setGalleryDlDefaults(new GalleryDlOptions(null, false, "1.5-2"));

        // WHEN only an nhentai link is pasted, with a delay gallery-dl could not read
        mvc.perform(post("/chapter/download").with(user("user")).with(csrf())
                        .param("links", "nhentai:7202")
                        .param("compressionMode", "NONE")
                        .param("requestDelay", "fast please"))
                // THEN it is queued with Settings' delay, since nothing reads the one typed
                .andExpect(status().is3xxRedirection());
        assertThat(queueRepository.findByLink("nhentai:7202")).get()
                .extracting(DownloadQueueItem::getRequestDelay).isEqualTo("1.5-2");
    }

    @Test
    void shouldSaveTheDefaultsInSettings() throws Exception
    {
        // WHEN
        mvc.perform(settingsPost().param("galleryDlCookiesBrowser", "chrome")
                        .param("galleryDlOriginals", "true")
                        .param("galleryDlDelay", "3"))
                .andExpect(redirectedUrl("/settings?saved"));

        // THEN
        assertThat(settingsService.getGalleryDlDefaults()).isEqualTo(new GalleryDlOptions("chrome", true, "3"));
        assertThat(settingsService.isSystemGalleryDlEnabled()).isTrue();
    }

    @Test
    void shouldKeepTheDefaultDelayAndSayWhyWhenItIsUnreadable() throws Exception
    {
        // GIVEN
        settingsService.setGalleryDlDefaults(new GalleryDlOptions(null, false, "2"));

        // WHEN
        mvc.perform(settingsPost().param("galleryDlCookiesBrowser", "firefox").param("galleryDlDelay", "-5"))
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("galleryDlError", containsString("is not a delay gallery-dl understands")));

        // THEN the delay stays, the rest is saved.
        assertThat(settingsService.getGalleryDlDefaults()).isEqualTo(new GalleryDlOptions("firefox", false, "2"));
    }

    @Test
    void shouldShowWhichGalleryDlIsUsed() throws Exception
    {
        mvc.perform(get("/settings").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("gallery-dl status")))
                .andExpect(content().string(containsString("configured by app.gallery-dl.command")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsPost()
    {
        return post("/settings").with(user("user")).with(csrf())
                .param("loginRequired", "true")
                .param("viewMode", ViewMode.FIT.name())
                .param("titleDisplayMode", "FULL")
                .param("systemGalleryDl", "true");
    }
}

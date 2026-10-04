package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.TestDownloads;
import io.github.mocchikon.hentie.TestLogin;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.Subscription;
import io.github.mocchikon.hentie.entity.ViewMode;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.SubscriptionRepository;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The Subscriptions page and the Settings fields it depends on. These suites commit, so everything made is removed and
 * Settings put back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubscriptionWebIT
{
    @Autowired MockMvc mvc;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired SettingsService settingsService;
    @Autowired TestLogin testLogin;

    private GalleryDlOptions galleryDlBefore;

    @BeforeEach
    void clean()
    {
        galleryDlBefore = settingsService.getGalleryDlDefaults();
        subscriptionRepository.deleteAll();
    }

    @AfterEach
    void restore()
    {
        subscriptionRepository.deleteAll();
        queueRepository.findByLink("https://nhentai.net/g/424242/").ifPresent(queueRepository::delete);
        settingsService.setEhentaiMemberId("");
        settingsService.setEhentaiPassHash("");
        settingsService.setEhentaiIgneous("");
        settingsService.setGalleryDlDefaults(galleryDlBefore);
        settingsService.setSystemGalleryDlEnabled(false);
        testLogin.restore();
    }

    @Test
    void shouldOfferSubscriptionsOnTheManagePageAfterAddManually() throws Exception
    {
        // WHEN
        String html = mvc.perform(get("/manage").with(user("user")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // THEN
        assertThat(html).containsPattern("Add manually</a>\\s*<a class=\"btn\" href=\"/subscriptions\">Subscriptions</a>");
    }

    @Test
    void shouldStartTheFormAtTheSettingsChoices() throws Exception
    {
        // GIVEN
        settingsService.setGalleryDlDefaults(new GalleryDlOptions("librewolf", true, "1.5"));

        // WHEN + THEN
        mvc.perform(get("/subscriptions/new").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("sites", "compressionModes", "browserGroups"))
                .andExpect(model().attribute("galleryDlSites", "e-hentai exhentai"))
                .andExpect(content().string(containsString("data-shown-for=\"e-hentai exhentai\"")))
                .andExpect(content().string(containsString("value=\"librewolf\" selected")))
                .andExpect(content().string(containsString("value=\"1.5\"")))
                .andExpect(content().string(containsString("needs your e-hentai account&#39;s cookies")));
    }

    @Test
    void shouldCreateASubscriptionAndShowIt() throws Exception
    {
        // WHEN
        mvc.perform(subscriptionPost().param("source", "nhentai").param("query", "  artist:someone   language:english")
                        .param("name", "Someone"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/subscriptions"))
                .andExpect(flash().attribute("saved", "Someone"));

        // THEN
        Subscription saved = subscriptionRepository.findAll().getFirst();
        assertThat(saved.getQuery()).isEqualTo("artist:someone language:english");
        assertThat(saved.getPollMinutes()).isEqualTo(60);
        assertThat(saved.getRecheckEveryHours()).isEqualTo(24);
        assertThat(saved.getRecheckDepthHours()).isEqualTo(48);
        assertThat(saved.isEnabled()).isTrue();
        mvc.perform(get("/subscriptions").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Someone")))
                .andExpect(content().string(containsString("Starting: the newest galleries are listed first.")))
                .andExpect(content().string(containsString("https://nhentai.net/search/?q=artist%3Asomeone")));
        mvc.perform(get("/subscriptions/{id}/edit", saved.getId()).with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("artist:someone language:english")));
    }

    @Test
    void shouldRefuseWhatTheSiteCannotFollow() throws Exception
    {
        // WHEN + THEN
        mvc.perform(subscriptionPost().param("source", "nhentai").param("query", "x").param("pollMinutes", "9"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "pollMinutes"));
        mvc.perform(subscriptionPost().param("source", "nhentai").param("query", "x uploaded:<7d"))
                .andExpect(model().attributeHasFieldErrors("form", "query"));
        mvc.perform(subscriptionPost().param("source", "nowhere").param("query", "x"))
                .andExpect(model().attributeHasFieldErrors("form", "source"));
        mvc.perform(subscriptionPost().param("source", "e-hentai").param("query", "f_search=x")
                        .param("requestDelay", "fast"))
                .andExpect(model().attributeHasFieldErrors("form", "requestDelay"))
                .andExpect(content().string(containsString("is not a delay gallery-dl understands")));
        mvc.perform(subscriptionPost().param("source", "e-hentai").param("query", "evil=1"))
                .andExpect(model().attributeHasFieldErrors("form", "query"));
        assertThat(subscriptionRepository.findAll()).isEmpty();
    }

    /** nhentai ignores gallery-dl's choices, so a delay it cannot read is not worth a refusal there. */
    @Test
    void shouldIgnoreAnUnreadableDelayForNhentai() throws Exception
    {
        // WHEN
        mvc.perform(subscriptionPost().param("source", "nhentai").param("query", "x").param("requestDelay", "fast"))
                .andExpect(status().is3xxRedirection());

        // THEN
        assertThat(subscriptionRepository.findAll().getFirst().getRequestDelay())
                .isEqualTo(settingsService.getGalleryDlDefaults().delay());
    }

    @Test
    void shouldRefuseExhentaiWithoutTheAccountOrABrowsersCookies() throws Exception
    {
        // WHEN + THEN no account in Settings
        mvc.perform(subscriptionPost().param("source", "exhentai").param("query", "f_search=x")
                        .param("cookiesBrowser", "firefox"))
                .andExpect(model().attributeHasFieldErrors("form", "source"));

        // WHEN + THEN no browser to download with
        settingsService.setEhentaiMemberId("123");
        settingsService.setEhentaiPassHash("0123456789abcdef0123456789abcdef");
        mvc.perform(subscriptionPost().param("source", "exhentai").param("query", "f_search=x"))
                .andExpect(model().attributeHasFieldErrors("form", "cookiesBrowser"));
        mvc.perform(subscriptionPost().param("source", "exhentai").param("query", "f_search=x")
                        .param("cookiesBrowser", "firefox"))
                .andExpect(status().is3xxRedirection());
        assertThat(subscriptionRepository.findAll()).singleElement()
                .extracting(Subscription::getCookiesBrowser).isEqualTo("firefox");
    }

    @Test
    void shouldStartOverWhenTheSearchChanges() throws Exception
    {
        // GIVEN a subscription part-way through its walk
        mvc.perform(subscriptionPost().param("source", "nhentai").param("query", "x"));
        Subscription walked = subscriptionRepository.findAll().getFirst();
        walked.setNewestGalleryId("nhentai:9");
        walked.setOldestGalleryId("nhentai:1");
        walked.setQueuedCount(9);
        subscriptionRepository.save(walked);

        // WHEN only the name changes, and then the search
        mvc.perform(subscriptionPost().param("id", walked.getId().toString()).param("source", "nhentai")
                .param("query", "x").param("name", "renamed"));
        Subscription renamed = subscriptionRepository.findById(walked.getId()).orElseThrow();
        mvc.perform(subscriptionPost().param("id", walked.getId().toString()).param("source", "nhentai")
                .param("query", "y"));
        Subscription changed = subscriptionRepository.findById(walked.getId()).orElseThrow();

        // THEN
        assertThat(renamed.getNewestGalleryId()).isEqualTo("nhentai:9");
        assertThat(renamed.getRevision()).isZero();
        assertThat(changed.getNewestGalleryId()).isNull();
        assertThat(changed.getQueuedCount()).isZero();
        assertThat(changed.getRevision()).isOne();
    }

    @Test
    void shouldPauseResumeCheckStartOverAndDelete() throws Exception
    {
        // GIVEN
        mvc.perform(subscriptionPost().param("source", "nhentai").param("query", "x"));
        int id = subscriptionRepository.findAll().getFirst().getId();

        // WHEN + THEN
        mvc.perform(action(id, "pause")).andExpect(redirectedUrl("/subscriptions"));
        assertThat(subscriptionRepository.findById(id).orElseThrow().isEnabled()).isFalse();
        mvc.perform(get("/subscriptions").with(user("user")))
                .andExpect(content().string(containsString("Paused: it lists nothing")));
        mvc.perform(action(id, "resume"));
        assertThat(subscriptionRepository.findById(id).orElseThrow().isEnabled()).isTrue();
        mvc.perform(action(id, "check")).andExpect(redirectedUrl("/subscriptions"));
        mvc.perform(action(id, "restart"));
        assertThat(subscriptionRepository.findById(id).orElseThrow().getRevision()).isOne();
        mvc.perform(action(id, "delete")).andExpect(flash().attribute("deleted", "x"));
        assertThat(subscriptionRepository.findById(id)).isEmpty();
        mvc.perform(action(id, "pause")).andExpect(flash().attribute("problem", "That subscription no longer exists."));
    }

    @Test
    void shouldNameTheSubscriptionOnTheQueuePage() throws Exception
    {
        // GIVEN a row a subscription queued
        mvc.perform(subscriptionPost().param("source", "nhentai").param("query", "x").param("name", "My artist"));
        int id = subscriptionRepository.findAll().getFirst().getId();
        var row = TestDownloads.queueItem("https://nhentai.net/g/424242/", "nhentai:424242");
        row.setPriority(DownloadQueueItem.FROM_SUBSCRIPTION);
        row.setSubscriptionId(id);
        queueRepository.save(row);

        // WHEN + THEN
        mvc.perform(get("/chapter/queue").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("from subscription My artist")))
                .andExpect(content().string(containsString("links you paste come before")));
    }

    @Test
    void shouldSaveTheAccountCookiesOneByOne() throws Exception
    {
        // GIVEN
        settingsService.setEhentaiPassHash("ffffffffffffffffffffffffffffffff");

        // WHEN
        mvc.perform(settingsPost().param("ehentaiMemberId", " 123 ").param("ehentaiPassHash", "not a hash")
                        .param("ehentaiIgneous", "mystery"))
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("ehentaiAccountError", containsString("ipb_pass_hash")))
                .andExpect(flash().attribute("ehentaiAccountError", containsString("igneous=mystery")));

        // THEN
        assertThat(settingsService.getEhentaiMemberId()).isEqualTo("123");
        assertThat(settingsService.getEhentaiPassHash()).isEqualTo("ffffffffffffffffffffffffffffffff");
        assertThat(settingsService.getEhentaiIgneous()).isEmpty();
        mvc.perform(get("/settings").with(user("user")))
                .andExpect(content().string(containsString("id=\"ehentai-account\"")))
                .andExpect(content().string(containsString("value=\"123\"")));
    }

    /** The rest takes the form's defaults: a parameter given twice would bind its first value. */
    private MockHttpServletRequestBuilder subscriptionPost()
    {
        return post("/subscriptions").with(user("user")).with(csrf()).param("compressionMode", "NONE");
    }

    private MockHttpServletRequestBuilder action(int id, String action)
    {
        return post("/subscriptions/{id}/" + action, id).with(user("user")).with(csrf());
    }

    private MockHttpServletRequestBuilder settingsPost()
    {
        return post("/settings").with(user("user")).with(csrf())
                .param("loginRequired", "true")
                .param("viewMode", ViewMode.FIT.name())
                .param("titleDisplayMode", "FULL");
    }
}

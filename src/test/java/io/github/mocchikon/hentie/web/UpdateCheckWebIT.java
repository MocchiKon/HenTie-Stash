package io.github.mocchikon.hentie.web;

import com.sun.net.httpserver.HttpServer;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.UpdateCheckService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The navbar badge against a fake of GitHub's latest-release answer. */
@SpringBootTest
@AutoConfigureMockMvc
class UpdateCheckWebIT
{
    @Autowired
    private MockMvc mvc;
    @Autowired
    private UpdateCheckService updateCheck;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private SettingsService settingsService;

    private HttpServer github;
    private volatile int status;
    private volatile String body;
    private String originalUrl;

    @BeforeEach
    void startFake() throws IOException
    {
        assumeThat(updateCheck.runningVersion()).as("build-info from Maven").isNotNull();
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/", exchange ->
        {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        github.start();
        settingsService.setUpdateCheckEnabled(true);
        originalUrl = appProperties.getUpdateCheck().getUrl();
        appProperties.getUpdateCheck().setUrl("http://127.0.0.1:" + github.getAddress().getPort() + "/latest");
    }

    @AfterEach
    void stopFake()
    {
        if (github == null)
        {
            return;
        }
        answer(200, release("0.0.0", "https://github.com/MocchiKon/HenTie-Stash/releases/tag/0.0.0"));
        updateCheck.checkNow();
        appProperties.getUpdateCheck().setUrl(originalUrl);
        settingsService.setUpdateCheckEnabled(appProperties.getUpdateCheck().isEnabledDefault());
        github.stop(0);
    }

    @Test
    void aNewerReleaseShowsTheBadgeLinkingToIt() throws Exception
    {
        String page = "https://github.com/MocchiKon/HenTie-Stash/releases/tag/v999.0.0";
        answer(200, release("v999.0.0", page));
        updateCheck.checkNow();

        mvc.perform(get("/search").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Update Available")))
                .andExpect(content().string(containsString("href=\"" + page + "\"")));
    }

    @Test
    void theRunningReleaseOrAnOlderOneShowsNoBadge() throws Exception
    {
        answer(200, release(updateCheck.runningVersion(), "https://github.com/x"));
        updateCheck.checkNow();
        assertThat(updateCheck.available()).isEmpty();

        mvc.perform(get("/search").with(user("user")))
                .andExpect(content().string(not(containsString("Update Available"))));
    }

    @Test
    void aFailedCheckKeepsTheLastAnswer()
    {
        answer(200, release("v999.0.0", "https://github.com/x"));
        updateCheck.checkNow();

        answer(403, "{\"message\":\"API rate limit exceeded\"}");
        updateCheck.checkNow();
        answer(200, "not json");
        updateCheck.checkNow();
        answer(200, "{\"tag_name\":\"\"}");
        updateCheck.checkNow();
        String fake = appProperties.getUpdateCheck().getUrl();
        appProperties.getUpdateCheck().setUrl("http://127.0.0.1:1/latest");
        updateCheck.checkNow();
        appProperties.getUpdateCheck().setUrl("not a url at all");
        updateCheck.checkNow();
        appProperties.getUpdateCheck().setUrl(fake);

        assertThat(updateCheck.available()).map(UpdateCheckService.Release::version).contains("v999.0.0");
    }

    @Test
    void aLinkThatIsNoWebAddressIsNotUsed() throws Exception
    {
        answer(200, release("v999.0.0", "javascript:alert(1)"));
        updateCheck.checkNow();

        mvc.perform(get("/search").with(user("user")))
                .andExpect(content().string(containsString("Update Available")))
                .andExpect(content().string(not(containsString("javascript:alert"))))
                .andExpect(content().string(containsString("href=\"/guide#access-updates\"")));
    }

    private void answer(int status, String body)
    {
        this.status = status;
        this.body = body;
    }

    private static String release(String tag, String url)
    {
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"" + url + "\",\"draft\":false}";
    }

    @Test
    void noReleaseOrNoRepositoryClearsTheBadge()
    {
        answer(200, release("v999.0.0", "https://github.com/x"));
        updateCheck.checkNow();

        answer(404, "{\"message\":\"Not Found\"}");
        updateCheck.checkNow();

        assertThat(updateCheck.available()).isEmpty();
    }

    @Test
    void turnedOffInSettingsTheBadgeGoesAtOnce() throws Exception
    {
        answer(200, release("v999.0.0", "https://github.com/x"));
        updateCheck.checkNow();

        mvc.perform(post("/settings").with(user("user")).with(csrf())
                        .param("loginRequired", "true").param("viewMode", "FIT").param("titleDisplayMode", "FULL"))
                .andExpect(status().is3xxRedirection());

        assertThat(settingsService.isUpdateCheckEnabled()).isFalse();
        mvc.perform(get("/search").with(user("user")))
                .andExpect(content().string(not(containsString("Update Available"))));
        mvc.perform(get("/settings").with(user("user")))
                .andExpect(content().string(containsString("name=\"updateCheck\"")));

        mvc.perform(post("/settings").with(user("user")).with(csrf())
                        .param("loginRequired", "true").param("viewMode", "FIT").param("titleDisplayMode", "FULL")
                        .param("updateCheck", "on"))
                .andExpect(status().is3xxRedirection());
        assertThat(settingsService.isUpdateCheckEnabled()).isTrue();
        assertThat(updateCheck.available()).isPresent();
    }
}

package io.github.mocchikon.hentie.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mocchikon.hentie.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells the user when a newer release is out. The answer is kept in memory and read by every page's navbar, so a
 * page never waits for GitHub. It is asked once, at startup, while Settings allows it.
 * <p>
 * <b>Nothing here may fail the app.</b> The check is a convenience: no network, GitHub's rate limit, a removed or
 * renamed repository, an odd answer, even a bug here, all end in "no news" logged at DEBUG (an offline machine
 * must not fill its log), and the next start tries again. A running version that cannot be read (no build info,
 * as in an IDE run without Maven) shows no badge, since nothing can be compared.
 */
@Service
public class UpdateCheckService
{
    private static final Logger log = LoggerFactory.getLogger(UpdateCheckService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** {@code 1.2.3}, {@code v1.2}, {@code 1.2.3-SNAPSHOT}: numbers, then an optional qualifier. */
    private static final Pattern VERSION = Pattern.compile("[vV]?(\\d+(?:\\.\\d+)*)(?:[-+.](.+))?");

    private final AppProperties appProperties;
    private final SettingsService settingsService;
    private final String runningVersion;

    /** Made on first use, so a failure to make one is a failed check, never a failed startup. */
    private HttpClient client;
    private volatile Release available;

    /** {@code url} is the release's page, or null when GitHub named none that can be linked to safely. */
    public record Release(String version, String url)
    {
    }

    public UpdateCheckService(AppProperties appProperties, SettingsService settingsService,
                              ObjectProvider<BuildProperties> buildProperties)
    {
        this.appProperties = appProperties;
        this.settingsService = settingsService;
        this.runningVersion = readRunningVersion(buildProperties);
    }

    private static String readRunningVersion(ObjectProvider<BuildProperties> buildProperties)
    {
        try
        {
            BuildProperties build = buildProperties.getIfAvailable();
            return build == null ? null : build.getVersion();
        }
        catch (RuntimeException e)
        {
            log.debug("No running version for the update check: {}", e.toString());
            return null;
        }
    }

    /** A newer release than the one running, if the last check found one and Settings still allows checking. */
    public Optional<Release> available()
    {
        return settingsService.isUpdateCheckEnabled() ? Optional.ofNullable(available) : Optional.empty();
    }

    public String runningVersion()
    {
        return runningVersion;
    }

    /**
     * Once per start, on a thread of its own so startup never waits for GitHub. A release published while the app
     * runs shows from the next start.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start()
    {
        if (runningVersion == null || !settingsService.isUpdateCheckEnabled())
        {
            return;
        }
        try
        {
            Thread.ofPlatform().name("update-check").daemon().start(this::checkNow);
        }
        catch (RuntimeException e)
        {
            log.warn("Update check not started: {}", e.toString());
        }
    }

    /** One check, on the caller's thread, whatever Settings says; the test seam. Never throws, not even an error. */
    public void checkNow()
    {
        try
        {
            Optional<Release> latest = fetchLatest();
            Release newer = latest.filter(r -> isNewer(r.version(), runningVersion)).orElse(null);
            if (newer != null && !newer.equals(available))
            {
                log.info("HenTie {} is available (running {}): {}", newer.version(), runningVersion, newer.url());
            }
            available = newer;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (Exception e)
        {
            log.debug("Update check failed: {}", e.toString());
        }
        catch (Throwable e)
        {
            log.warn("Update check failed unexpectedly", e);
        }
    }

    /**
     * Empty when GitHub has no release to compare with: it answers 404 before the first release and for a removed
     * repository. A renamed one redirects.
     */
    private Optional<Release> fetchLatest() throws IOException, InterruptedException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(appProperties.getUpdateCheck().getUrl()))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                // GitHub refuses requests without one.
                .header("User-Agent", "HenTie/" + runningVersion)
                .GET()
                .build();
        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404)
        {
            return Optional.empty();
        }
        if (response.statusCode() != 200)
        {
            throw new IOException("HTTP " + response.statusCode());
        }
        JsonNode release = JSON.readTree(response.body());
        String tag = release.path("tag_name").asText("");
        if (tag.isBlank())
        {
            throw new IOException("The answer names no tag");
        }
        return Optional.of(new Release(tag, linkable(release.path("html_url").asText(null))));
    }

    private synchronized HttpClient client()
    {
        if (client == null)
        {
            client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return client;
    }

    /** The navbar links to it, so nothing but a web address may get there (never {@code javascript:}). */
    private static String linkable(String url)
    {
        return url != null && (url.startsWith("https://") || url.startsWith("http://")) ? url : null;
    }

    /**
     * Compares the numbers part by part, a missing one counting as 0; with equal numbers a release without a
     * qualifier is newer than one with ({@code 1.0.0} after {@code 1.0.0-SNAPSHOT}), and two qualifiers are not
     * ordered. A version that cannot be read is never newer, so a tag named oddly shows no badge for good.
     */
    static boolean isNewer(String release, String running)
    {
        ParsedVersion r = ParsedVersion.of(release);
        ParsedVersion c = ParsedVersion.of(running);
        if (r == null || c == null)
        {
            return false;
        }
        for (int i = 0; i < Math.max(r.numbers().size(), c.numbers().size()); i++)
        {
            long a = i < r.numbers().size() ? r.numbers().get(i) : 0;
            long b = i < c.numbers().size() ? c.numbers().get(i) : 0;
            if (a != b)
            {
                return a > b;
            }
        }
        return r.qualifier() == null && c.qualifier() != null;
    }

    private record ParsedVersion(List<Long> numbers, String qualifier)
    {
        static ParsedVersion of(String text)
        {
            if (text == null)
            {
                return null;
            }
            Matcher m = VERSION.matcher(text.strip());
            if (!m.matches())
            {
                return null;
            }
            List<Long> numbers = new ArrayList<>();
            try
            {
                for (String part : m.group(1).split("\\."))
                {
                    numbers.add(Long.parseLong(part));
                }
            }
            catch (NumberFormatException e)
            {
                return null;
            }
            return new ParsedVersion(numbers, m.group(2));
        }
    }
}

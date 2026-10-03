package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.JxlDelivery;
import io.github.mocchikon.hentie.dto.TitleDisplayMode;
import io.github.mocchikon.hentie.entity.Setting;
import io.github.mocchikon.hentie.entity.ViewMode;
import io.github.mocchikon.hentie.repository.SettingRepository;
import io.github.mocchikon.hentie.service.scratch.ScratchArea;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The key-value {@link Setting} store, cached in memory (single user).
 *
 * <p>The password hash is the exception: it lives in a file ({@link AppProperties#getPasswordFile()}), never
 * in the DB, so deleting that file is the password reset.
 */
@Service
public class SettingsService
{
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    public static final String LOGIN_REQUIRED = "login.required";
    public static final String VIEW_MODE = "default.view.mode";
    /** How many next pages the viewer prepares (JPEG XL decode or ComfyUI run) while one is read. */
    public static final String VIEWER_PAGES_AHEAD = "viewer.pages-ahead";
    public static final String SEARCH_PAGE_SIZE = "search.page.size";
    public static final String TITLE_DISPLAY_MODE = "title.display.mode";
    public static final String API_KEY_PREFIX = "apikey.";
    /** Optional for downloads (it raises nhentai's rate limits), required to list the user's favourites. */
    public static final String NHENTAI_API_KEY = API_KEY_PREFIX + "nhentai";
    public static final String MATCH_AUTO_LINK = "matching.auto-link";
    public static final String MATCH_THRESHOLD = "matching.threshold";
    public static final String DOWNLOAD_PAUSED = "download.paused";
    /** Applies to uploads; a download carries its own mode on the queue row and only starts at this one. */
    public static final String IMAGE_COMPRESSION_MODE = "image.compression.mode";
    /** Use ImageMagick/cjxl/avifenc from the PATH instead of the copies bundled beside the jar. */
    public static final String IMAGE_TOOLS_SYSTEM = "image.compression.system-binaries";
    public static final String JXL_DELIVERY = "image.jxl.delivery";
    public static final String COMFYUI_URL = "comfyui.url";
    /** The folder inside ComfyUI's user folder whose API-format workflows are offered. */
    public static final String COMFYUI_WORKFLOW_DIR = "comfyui.workflow-dir";
    public static final String COMFYUI_DEFAULT_WORKFLOW = "comfyui.default-workflow";
    public static final String COMFYUI_AUTOSTART = "comfyui.autostart";
    public static final String COMFYUI_START_SCRIPT = "comfyui.start-script";
    /** {@code <pid>:<start epoch millis>} of the ComfyUI the app launched, blank when none. */
    public static final String COMFYUI_LAUNCHED = "comfyui.launched-process";

    /** The folder {@code COMFYUI.md} tells the user to export workflows into. */
    public static final String DEFAULT_COMFYUI_WORKFLOW_DIR = "api_workflows";

    public static final int DEFAULT_SEARCH_PAGE_SIZE = 36;

    public static final int DEFAULT_PAGES_AHEAD = 6;

    /** Bounded because the viewer sends its whole plan with every processed-page request. */
    public static final int MAX_PAGES_AHEAD = 100;

    /**
     * Percent of {@link io.github.mocchikon.hentie.service.match.MatchScore}. Below ~71 it starts merging different
     * works by the same artist.
     */
    public static final int DEFAULT_MATCH_THRESHOLD = 75;

    private final SettingRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Mirrors the password file; null while no password is set. */
    private volatile String passwordHash;

    public SettingsService(SettingRepository repository, PasswordEncoder passwordEncoder, AppProperties appProperties)
    {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    /**
     * <b>Not {@code @Transactional}, because it cannot be:</b> a lifecycle callback runs on the raw bean,
     * before the proxy exists (a self-injected proxy is unproxied here too). Each seed is an idempotent
     * upsert with its own transaction, so an interrupted boot seeds the rest next time.
     */
    @PostConstruct
    public void init()
    {
        repository.findAll().forEach(s -> cache.put(s.getKey(), s.getValue()));

        // Off on a fresh install: there is no password yet, and requiring one would lock everybody out.
        if (!cache.containsKey(LOGIN_REQUIRED))
        {
            put(LOGIN_REQUIRED, Boolean.toString(false));
        }
        if (!cache.containsKey(VIEW_MODE))
        {
            put(VIEW_MODE, ViewMode.FIT.name());
        }
        if (!cache.containsKey(SEARCH_PAGE_SIZE))
        {
            put(SEARCH_PAGE_SIZE, Integer.toString(DEFAULT_SEARCH_PAGE_SIZE));
        }
        if (!cache.containsKey(TITLE_DISPLAY_MODE))
        {
            put(TITLE_DISPLAY_MODE, TitleDisplayMode.FULL.name());
        }
        if (!cache.containsKey(MATCH_AUTO_LINK))
        {
            put(MATCH_AUTO_LINK, Boolean.toString(appProperties.isMatchAutoLinkDefault()));
        }
        if (!cache.containsKey(MATCH_THRESHOLD))
        {
            put(MATCH_THRESHOLD, Integer.toString(DEFAULT_MATCH_THRESHOLD));
        }
        if (!cache.containsKey(IMAGE_COMPRESSION_MODE))
        {
            put(IMAGE_COMPRESSION_MODE, BuiltInCompressionMode.NONE.getKey());
        }
        if (!cache.containsKey(JXL_DELIVERY))
        {
            put(JXL_DELIVERY, JxlDelivery.AUTO.name());
        }

        loadPasswordFile();
    }

    /**
     * A missing or empty file means no password, and <b>login is turned off</b> (persisted), since requiring
     * it would lock everybody out. That makes deleting the file a self-service reset.
     * <p>
     * There is deliberately no default password: it would have to be shown where a user who forgot theirs
     * can find it, and then it protects nothing.
     */
    private void loadPasswordFile()
    {
        Path file = passwordFile();
        try
        {
            String stored = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : "";
            passwordHash = stored.isEmpty() ? null : stored;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Could not read password file " + file.toAbsolutePath(), e);
        }
        if (passwordHash == null && Boolean.parseBoolean(cache.get(LOGIN_REQUIRED)))
        {
            put(LOGIN_REQUIRED, Boolean.toString(false));
            log.warn("Password file '{}' is missing or empty, so login has been turned off. Set a password "
                    + "in Settings to require it again.", file.toAbsolutePath());
        }
    }

    private Path passwordFile()
    {
        return Paths.get(appProperties.getPasswordFile());
    }

    private void writePasswordHash(String hash) throws IOException
    {
        Path file = passwordFile().toAbsolutePath();
        Path parent = file.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        Files.writeString(file, hash + System.lineSeparator(), StandardCharsets.UTF_8);
        passwordHash = hash;
    }

    // ---- reads -------------------------------------------------------------

    /**
     * Never true without a password, whatever the setting says. {@link #setLoginRequired} and startup
     * already prevent that; this is the last line, because the lockout could not be undone in the app.
     */
    public boolean isLoginRequired()
    {
        return hasPassword() && Boolean.parseBoolean(cache.getOrDefault(LOGIN_REQUIRED, "false"));
    }

    public boolean hasPassword()
    {
        return passwordHash != null;
    }

    public ViewMode getDefaultViewMode()
    {
        String raw = cache.getOrDefault(VIEW_MODE, ViewMode.FIT.name());
        try
        {
            return ViewMode.valueOf(raw);
        }
        catch (IllegalArgumentException e)
        {
            return ViewMode.FIT;
        }
    }

    /** 0 prepares only the page on screen. */
    public int getPagesAhead()
    {
        try
        {
            return Math.clamp(Integer.parseInt(
                    cache.getOrDefault(VIEWER_PAGES_AHEAD, Integer.toString(DEFAULT_PAGES_AHEAD))), 0, MAX_PAGES_AHEAD);
        }
        catch (NumberFormatException e)
        {
            return DEFAULT_PAGES_AHEAD;
        }
    }

    public int getSearchPageSize()
    {
        try
        {
            return Math.max(1, Integer.parseInt(cache.getOrDefault(SEARCH_PAGE_SIZE, Integer.toString(DEFAULT_SEARCH_PAGE_SIZE))));
        }
        catch (NumberFormatException e)
        {
            return DEFAULT_SEARCH_PAGE_SIZE;
        }
    }

    public String getPasswordHash()
    {
        return passwordHash;
    }

    /** Blank when none is set. */
    public String getNhentaiApiKey()
    {
        return cache.getOrDefault(NHENTAI_API_KEY, "");
    }

    public TitleDisplayMode getTitleDisplayMode()
    {
        String raw = cache.getOrDefault(TITLE_DISPLAY_MODE, TitleDisplayMode.FULL.name());
        try
        {
            return TitleDisplayMode.valueOf(raw);
        }
        catch (IllegalArgumentException e)
        {
            return TitleDisplayMode.FULL;
        }
    }

    public boolean isMatchAutoLinkEnabled()
    {
        return Boolean.parseBoolean(
                cache.getOrDefault(MATCH_AUTO_LINK, Boolean.toString(appProperties.isMatchAutoLinkDefault())));
    }

    public boolean isDownloadPaused()
    {
        return Boolean.parseBoolean(cache.getOrDefault(DOWNLOAD_PAUSED, "false"));
    }

    public String getImageCompressionMode()
    {
        return cache.getOrDefault(IMAGE_COMPRESSION_MODE, BuiltInCompressionMode.NONE.getKey());
    }

    /** Off by default: the bundled {@code bin} copies are known-good and need no installation. */
    public boolean isSystemImageToolsEnabled()
    {
        return Boolean.parseBoolean(cache.getOrDefault(IMAGE_TOOLS_SYSTEM, "false"));
    }

    public JxlDelivery getJxlDelivery()
    {
        try
        {
            return JxlDelivery.valueOf(cache.getOrDefault(JXL_DELIVERY, JxlDelivery.AUTO.name()));
        }
        catch (IllegalArgumentException e)
        {
            return JxlDelivery.AUTO;
        }
    }

    /** The stored address, else {@code app.comfyui.default-url}. */
    public String getComfyUiUrl()
    {
        return StringUtils.defaultString(
                StringUtils.firstNonBlank(cache.get(COMFYUI_URL), appProperties.getComfyui().getDefaultUrl()));
    }

    public String getComfyUiWorkflowDir()
    {
        return StringUtils.defaultIfBlank(cache.get(COMFYUI_WORKFLOW_DIR), DEFAULT_COMFYUI_WORKFLOW_DIR);
    }

    /** Blank means "show the pages as stored". */
    public String getDefaultWorkflow()
    {
        return cache.getOrDefault(COMFYUI_DEFAULT_WORKFLOW, "");
    }

    public boolean isComfyUiAutostart()
    {
        return Boolean.parseBoolean(cache.getOrDefault(COMFYUI_AUTOSTART, "false"));
    }

    public String getComfyUiStartScript()
    {
        return cache.getOrDefault(COMFYUI_START_SCRIPT, "");
    }

    public String getLaunchedComfyUi()
    {
        return cache.getOrDefault(COMFYUI_LAUNCHED, "");
    }

    /** A percentage, 0-100. */
    public int getMatchThreshold()
    {
        try
        {
            int threshold = Integer.parseInt(
                    cache.getOrDefault(MATCH_THRESHOLD, Integer.toString(DEFAULT_MATCH_THRESHOLD)));
            return Math.clamp(threshold, 0, 100);
        }
        catch (NumberFormatException e)
        {
            return DEFAULT_MATCH_THRESHOLD;
        }
    }

    /** On the 0-1 scale the scores use. */
    public double getMatchThresholdScore()
    {
        return getMatchThreshold() / 100.0;
    }

    public String get(String key, String defaultValue)
    {
        return cache.getOrDefault(key, defaultValue);
    }

    // ---- writes ------------------------------------------------------------

    /** @throws IllegalStateException when asked to require login while no password is set */
    @Transactional
    public void setLoginRequired(boolean required)
    {
        if (required && !hasPassword())
        {
            throw new IllegalStateException("Login cannot be required before a password is set.");
        }
        put(LOGIN_REQUIRED, Boolean.toString(required));
    }

    @Transactional
    public void setDefaultViewMode(ViewMode mode)
    {
        put(VIEW_MODE, mode.name());
    }

    @Transactional
    public void setPagesAhead(int pages)
    {
        put(VIEWER_PAGES_AHEAD, Integer.toString(Math.clamp(pages, 0, MAX_PAGES_AHEAD)));
    }

    @Transactional
    public void setSearchPageSize(int size)
    {
        put(SEARCH_PAGE_SIZE, Integer.toString(Math.max(1, size)));
    }

    /** Stripped: a pasted key often carries a space or line break that the site would refuse. */
    @Transactional
    public void setNhentaiApiKey(String apiKey)
    {
        put(NHENTAI_API_KEY, StringUtils.strip(StringUtils.defaultString(apiKey)));
    }

    @Transactional
    public void setTitleDisplayMode(TitleDisplayMode mode)
    {
        put(TITLE_DISPLAY_MODE, mode.name());
    }

    @Transactional
    public void setMatchAutoLinkEnabled(boolean enabled)
    {
        put(MATCH_AUTO_LINK, Boolean.toString(enabled));
    }

    /** Persisted rather than a worker field, so a deliberate pause survives a restart. */
    @Transactional
    public void setDownloadPaused(boolean paused)
    {
        put(DOWNLOAD_PAUSED, Boolean.toString(paused));
    }

    @Transactional
    public void setImageCompressionMode(String modeKey)
    {
        put(IMAGE_COMPRESSION_MODE, StringUtils.defaultIfBlank(modeKey, BuiltInCompressionMode.NONE.getKey()));
    }

    @Transactional
    public void setSystemImageToolsEnabled(boolean enabled)
    {
        put(IMAGE_TOOLS_SYSTEM, Boolean.toString(enabled));
    }

    @Transactional
    public void setJxlDelivery(JxlDelivery delivery)
    {
        put(JXL_DELIVERY, delivery.name());
    }

    /** Stored as given (blank = the default); the caller validates, since only it can report a refusal. */
    @Transactional
    public void setComfyUiUrl(String url)
    {
        put(COMFYUI_URL, StringUtils.defaultString(url));
    }

    @Transactional
    public void setComfyUiWorkflowDir(String dir)
    {
        put(COMFYUI_WORKFLOW_DIR, StringUtils.defaultIfBlank(dir, DEFAULT_COMFYUI_WORKFLOW_DIR));
    }

    @Transactional
    public void setDefaultWorkflow(String workflow)
    {
        put(COMFYUI_DEFAULT_WORKFLOW, StringUtils.defaultString(workflow));
    }

    @Transactional
    public void setComfyUiAutostart(boolean autostart)
    {
        put(COMFYUI_AUTOSTART, Boolean.toString(autostart));
    }

    @Transactional
    public void setComfyUiStartScript(String script)
    {
        put(COMFYUI_START_SCRIPT, StringUtils.strip(StringUtils.defaultString(script)));
    }

    @Transactional
    public void setLaunchedComfyUi(String process)
    {
        put(COMFYUI_LAUNCHED, StringUtils.defaultString(process));
    }

    /** Written together because they are validated together - see {@code ScratchSpace.update}. */
    @Transactional
    public void setScratchDirs(Map<ScratchArea, String> paths)
    {
        paths.forEach((area, path) -> put(area.getSettingKey(), StringUtils.defaultString(path)));
    }

    @Transactional
    public void setMatchThreshold(int percent)
    {
        put(MATCH_THRESHOLD, Integer.toString(Math.clamp(percent, 0, 100)));
    }

    public void changePassword(String rawPassword)
    {
        try
        {
            writePasswordHash(passwordEncoder.encode(rawPassword));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(
                    "Could not write password file " + passwordFile().toAbsolutePath(), e);
        }
    }

    private void put(String key, String value)
    {
        repository.save(new Setting(key, value));
        cache.put(key, value);
    }
}

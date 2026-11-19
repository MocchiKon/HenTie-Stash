package io.github.mocchikon.hentie.scrapper.ehentai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mocchikon.hentie.scrapper.*;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDl;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlDownloader;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlException;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.download.LoginRequiredException;
import io.github.mocchikon.hentie.service.download.PermanentDownloadException;
import io.github.mocchikon.hentie.service.download.RetryLaterException;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.mocchikon.hentie.scrapper.JsonFields.text;

/**
 * e-hentai.org and exhentai.org as <b>one source</b>: a gallery has the same gid and token on both, so a link from
 * either names one gallery id, {@code ehentai:<gid>/<token>}. The token is part of it because nothing can be fetched
 * without it.
 * <p>
 * <b>Metadata from the JSON API, pages through gallery-dl.</b> The API is one cheap request for everything a chapter
 * needs; gallery-dl knows the image servers, their fallbacks and the account's image limit.
 * <p>
 * <b>The domain follows the account, not the pasted link</b>: with the e-hentai account from Settings exhentai.org
 * (it also has the galleries e-hentai hides), falling back once to e-hentai.org when exhentai refuses the account;
 * without one e-hentai.org, since exhentai shows nothing to a visitor. A re-download has no pasted link at all, so
 * the domain could not come from one anyway.
 * <p>
 * <b>Searches and downloads use the same account</b>, so an exhentai subscription can only queue galleries while
 * exhentai accepts the cookies its downloads will send. A browser's cookie store would be a second login the app
 * cannot check, and gallery-dl quietly switches to e-hentai.org when it holds none, where a hidden gallery looks
 * missing. Whether exhentai accepts the account is learnt from every search and download, and asked for once
 * ({@link #checkLogin}) when nothing else would tell. Searches and downloads also share the cooldown: a ban stops
 * both.
 */
@Component
public class EhentaiDownloader implements GalleryDlDownloader, SearchSource
{
    private static final Logger log = LoggerFactory.getLogger(EhentaiDownloader.class);

    static final String PREFIX = "ehentai";

    static final String EXHENTAI = "exhentai.org";
    static final String EHENTAI = "e-hentai.org";

    /**
     * A gallery's link on either domain, any subdomain or case, with whatever follows the token. A page link
     * ({@code /s/<page token>/<gid>-<n>}) is not accepted: its gallery token takes an API call to find, and a link
     * must be understood without going over the network.
     */
    private static final Pattern GALLERY_URL = Pattern.compile(
            "(?:https?://)?(?:[a-z0-9-]+\\.)*(?:e-hentai|exhentai)\\.org/(?:g|mpv)/0*(\\d{1,10})/([0-9a-f]{10})(?:[/?#].*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PREFIXED_ID =
            Pattern.compile(PREFIX + ":\\s*0*(\\d{1,10})\\s*/\\s*([0-9a-f]{10})", Pattern.CASE_INSENSITIVE);

    private static final Pattern RESOURCE_ID_SHAPE = Pattern.compile("(\\d{1,10})/([0-9a-f]{10})");

    static final String EHENTAI_SITE = "e-hentai";
    static final String EXHENTAI_SITE = "exhentai";

    private final EhentaiProperties properties;
    private final EhentaiApi api;
    private final GalleryDl galleryDl;
    private final Supplier<EhentaiSearchPages.Account> account;
    private final EhentaiSearchPages searchPages;
    private final EhentaiCooldown cooldown = new EhentaiCooldown();
    private final ApplicationEventPublisher events;

    /**
     * The account exhentai last accepted; null while that is unknown, or since it refused one. Only for publishing
     * {@link LoginAccepted} once per account: a stale value costs at most one event too many or too few, and the
     * next search or download corrects it.
     */
    private volatile EhentaiSearchPages.Account acceptedAccount;

    private final AtomicBoolean checkingLogin = new AtomicBoolean();

    public EhentaiDownloader(EhentaiProperties properties, GalleryDl galleryDl, SettingsService settingsService,
                             ApplicationEventPublisher events)
    {
        this.properties = properties;
        this.api = new EhentaiApi(properties);
        this.galleryDl = galleryDl;
        this.events = events;
        // Read on every search, so cookies saved in Settings apply at once.
        this.account = () -> new EhentaiSearchPages.Account(settingsService.getEhentaiMemberId(),
                settingsService.getEhentaiPassHash(), settingsService.getEhentaiIgneous());
        this.searchPages = new EhentaiSearchPages(properties, account);
    }

    @PreDestroy
    void close()
    {
        api.close();
        searchPages.close();
    }

    @Override
    public String sourcePrefix()
    {
        return PREFIX;
    }

    @Override
    public boolean accepts(String link)
    {
        return idIn(link) != null;
    }

    /** {@code <gid>/<token>}: the gid without leading zeros, the token in lower case, so one gallery has one id. */
    @Override
    public String resourceId(String link)
    {
        return idIn(link);
    }

    /** Always e-hentai.org: it parses back, and which domain is fetched from is decided per run. */
    @Override
    public Set<String> linkMarkers()
    {
        return Set.of(EHENTAI, EXHENTAI, PREFIX + ":");
    }

    /** Its bans for excessive pageloads are what the Settings delay is tuned for. */
    @Override
    public boolean needsSlowDelay()
    {
        return true;
    }

    @Override
    public String link(String resourceId)
    {
        return galleryUrl(EHENTAI, resourceId);
    }

    @Override
    public String pageLinkTemplate()
    {
        return "https://" + EHENTAI + "/g/" + RESOURCE_ID + "/";
    }

    /** exhentai.org for a gallery fetched from there: it may be one e-hentai hides. */
    @Override
    public String pageLinkTemplate(String site)
    {
        return EXHENTAI_SITE.equals(site) ? "https://" + EXHENTAI + "/g/" + RESOURCE_ID + "/" : pageLinkTemplate();
    }

    @Override
    public String linkExample()
    {
        return "https://e-hentai.org/g/123456/0123456789/ (or exhentai.org)";
    }

    private static String idIn(String link)
    {
        return DataDownloader.matchLink(link, PREFIXED_ID, GALLERY_URL).map(m -> Long.parseLong(m.group(1)) + "/" + m.group(2).toLowerCase(Locale.ROOT)).orElse(null);
    }

    private static String galleryUrl(String domain, String resourceId)
    {
        return "https://" + domain + "/g/" + resourceId + "/";
    }

    private static Matcher parsed(String resourceId)
    {
        Matcher matcher = RESOURCE_ID_SHAPE.matcher(StringUtils.defaultString(resourceId));
        if (!matcher.matches())
        {
            throw new GalleryNotFoundException("Not an e-hentai gallery id: '" + resourceId + "'");
        }
        return matcher;
    }

    // ---- metadata ------------------------------------------------------------------------------------------

    @Override
    public GalleryData downloadGalleryInfo(String id)
    {
        Matcher parts = parsed(id);
        cooldown.check();
        // The worker runs the item again every few seconds while gallery-dl is updated; without this, each run
        // would ask the API for metadata the refused page run then throws away, and e-hentai counts every request.
        if (galleryDl.isUpdating())
        {
            throw new RetryLaterException("gallery-dl is being updated; the download is retried once that has "
                    + "finished.", null);
        }
        JsonNode gallery;
        try
        {
            gallery = api.gallery(Long.parseLong(parts.group(1)), parts.group(2));
        }
        catch (EhentaiApi.BannedException e)
        {
            throw new PermanentDownloadException(startCooldown(e.getMessage()), e);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e.getMessage(), e);
        }
        return galleryData(id, gallery);
    }

    static GalleryData galleryData(String id, JsonNode gallery)
    {
        var tags = new ArrayList<String>();
        gallery.path("tags").forEach(tag -> tags.add(tag.asText("")));
        if (gallery.path("expunged").asBoolean(false))
        {
            log.info("e-hentai gallery {} is expunged; downloading it anyway", id);
        }
        return EhTags.galleryData(id, tags, text(gallery, "category"))
                .fullTitle(unescaped(text(gallery, "title")))
                .japaneseTitle(unescaped(text(gallery, "title_jpn")))
                .pageCount(pageCount(gallery))
                .build();
    }

    /** The API sends {@code filecount} as a string. */
    private static int pageCount(JsonNode gallery)
    {
        try
        {
            return Math.max(0, Integer.parseInt(gallery.path("filecount").asText("0").strip()));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    /** Titles come with HTML entities ({@code &amp;}). */
    private static String unescaped(String value)
    {
        return value == null ? null : StringUtils.stripToNull(StringEscapeUtils.unescapeHtml4(value));
    }

    // ---- pages ---------------------------------------------------------------------------------------------

    /**
     * {@code --sleep-request}, because e-hentai needs one request per image (its page, then its API call) and that
     * is what its ban counts; the image itself comes from a Hentai@Home server.
     *
     * @throws LoginRequiredException when e-hentai.org hides the gallery and exhentai accepted no account
     */
    @Override
    public GalleryDl.Outcome downloadPages(String resourceId, GalleryDlOptions options, SortedSet<Integer> pages,
                                           Path folder, GalleryDl.PageSink sink) throws IOException
    {
        parsed(resourceId);
        cooldown.check();
        var args = new ArrayList<>(List.of("--sleep-request", options.delay(),
                "-o", "original=" + options.originals()));
        EhentaiSearchPages.Account credentials = account.get();
        if (!credentials.complete())
        {
            try
            {
                return galleryDl.download(galleryUrl(EHENTAI, resourceId), args, pages, folder, sink);
            }
            catch (GalleryDlException e)
            {
                if (e.kind() == GalleryDlException.Kind.REFUSED)
                {
                    log.info("e-hentai.org refused gallery {} without an account: {}", resourceId, e.getMessage());
                    throw new LoginRequiredException("e-hentai shows this gallery only to logged-in users. Add your "
                            + "e-hentai account in Settings; the download then starts again by itself.", e);
                }
                throw failure(e);
            }
        }
        try (GalleryDl.CookiesFile cookies = galleryDl.cookiesFile(cookiesOf(credentials)))
        {
            args.addAll(cookies.args());
            return downloadWithAccount(resourceId, credentials, args, pages, folder, sink);
        }
    }

    private GalleryDl.Outcome downloadWithAccount(String resourceId, EhentaiSearchPages.Account credentials,
                                                  List<String> args, SortedSet<Integer> pages, Path folder,
                                                  GalleryDl.PageSink sink) throws IOException
    {
        Set<Integer> received = new HashSet<>();
        GalleryDl.PageSink tracking = (page, file) ->
        {
            sink.accept(page, file);
            received.add(page);
        };
        try
        {
            GalleryDl.Outcome outcome = galleryDl.download(galleryUrl(EXHENTAI, resourceId), args, pages, folder,
                    tracking);
            accepted(credentials);
            return outcome.fromSite(EXHENTAI_SITE);
        }
        catch (GalleryDlException e)
        {
            if (e.kind() != GalleryDlException.Kind.REFUSED)
            {
                throw failure(e);
            }
            refused(credentials);
            log.info("exhentai.org refused the e-hentai account for gallery {} ({}); trying e-hentai.org", resourceId,
                    e.getMessage());
        }
        SortedSet<Integer> rest = new TreeSet<>(pages);
        rest.removeAll(received);
        try
        {
            GalleryDl.Outcome second = galleryDl.download(galleryUrl(EHENTAI, resourceId), args, rest, folder,
                    tracking);
            return new GalleryDl.Outcome(received, second.problem(), second.writeFailed());
        }
        catch (GalleryDlException again)
        {
            if (again.kind() == GalleryDlException.Kind.REFUSED || again.kind() == GalleryDlException.Kind.NOT_FOUND)
            {
                log.info("e-hentai.org did not show gallery {} either: {}", resourceId, again.getMessage());
                throw new LoginRequiredException("exhentai did not accept your e-hentai account (the cookies in "
                        + "Settings may have expired), and e-hentai does not show this gallery. Update them in "
                        + "Settings; the download then starts again by itself.", again);
            }
            throw failure(again);
        }
    }

    /**
     * The account's login for both domains; {@code igneous} only for exhentai, where it is set. Without it exhentai
     * sends gallery-dl through the forums for one, which it follows like a browser.
     */
    private static Map<String, Map<String, String>> cookiesOf(EhentaiSearchPages.Account credentials)
    {
        var login = new LinkedHashMap<String, String>();
        login.put("ipb_member_id", credentials.memberId());
        login.put("ipb_pass_hash", credentials.passHash());
        var exhentai = new LinkedHashMap<>(login);
        if (StringUtils.isNotBlank(credentials.igneous()))
        {
            exhentai.put("igneous", credentials.igneous());
        }
        return Map.of("." + EXHENTAI, exhentai, "." + EHENTAI, login);
    }

    private RuntimeException failure(GalleryDlException e)
    {
        return switch (e.kind())
        {
            case BANNED, IMAGE_LIMIT -> new PermanentDownloadException(startCooldown(e.getMessage()), e);
            case NO_GP -> new PermanentDownloadException(e.getMessage()
                    + " - originals cost GP; untick \"Download originals\" and paste the link again.", e);
            default -> e.forWorker();
        };
    }

    // ---- the account ---------------------------------------------------------------------------------------

    private void accepted(EhentaiSearchPages.Account credentials)
    {
        if (!credentials.equals(acceptedAccount))
        {
            acceptedAccount = credentials;
            log.info("exhentai accepts the e-hentai account from Settings");
            events.publishEvent(new LoginAccepted(PREFIX));
        }
    }

    private void refused(EhentaiSearchPages.Account credentials)
    {
        if (credentials.equals(acceptedAccount))
        {
            acceptedAccount = null;
        }
    }

    /**
     * One request for exhentai's front page, the cheapest page that needs the account. Skipped during a cooldown
     * (a request during a ban can extend it) and while a check runs.
     */
    @Override
    public void checkLogin()
    {
        EhentaiSearchPages.Account credentials = account.get();
        if (!credentials.complete() || credentials.equals(acceptedAccount) || cooldown.until().isPresent()
                || !checkingLogin.compareAndSet(false, true))
        {
            return;
        }
        Thread.ofVirtual().name("exhentai-login-check").start(() ->
        {
            try
            {
                searchPages.fetch(true, "", null);
                if (credentials.equals(account.get()))
                {
                    accepted(credentials);
                }
            }
            catch (EhentaiSearchPages.RefusedException e)
            {
                refused(credentials);
                log.info("exhentai did not accept the e-hentai account from Settings: {}", e.getMessage());
            }
            catch (EhentaiApi.BannedException e)
            {
                startCooldown(e.getMessage());
            }
            catch (IOException | RuntimeException e)
            {
                log.info("Could not ask exhentai whether it accepts the e-hentai account: {}", e.toString());
            }
            finally
            {
                checkingLogin.set(false);
            }
        });
    }

    private String startCooldown(String why)
    {
        String message = cooldown.start(why, Duration.ofMinutes(Math.max(0, properties.getCooldownMinutes())));
        log.warn("{}", message);
        return message;
    }

    /** Forgets a cooldown; for the tests, which share this bean. */
    public void clearCooldown()
    {
        cooldown.clear();
    }

    /** A subscription's rows wait for the cooldown's end instead of failing like a paste's. */
    @Override
    public Optional<Instant> refusingUntil()
    {
        return cooldown.until();
    }

    // ---- subscriptions -------------------------------------------------------------------------------------

    @Override
    public List<SearchSite> searchSites()
    {
        String help = "Paste a search's address from the site, or the part after its \"?\" "
                + "(f_search=female%3Ablowjob%24+language%3Aenglish%24, with f_cats and the other f_ filters as you "
                + "like), or just the search text, e.g. female:blowjob$ language:english$. Paging (next, page) is "
                + "dropped: the subscription walks the pages itself.";
        return List.of(new SearchSite(EHENTAI_SITE, "e-hentai", help),
                new SearchSite(EXHENTAI_SITE, "exhentai", help + " exhentai also shows the galleries e-hentai hides; "
                        + "it needs your e-hentai account's cookies in Settings."));
    }

    @Override
    public String normalizedQuery(String site, String query)
    {
        return EhentaiSearchQuery.normalized(query);
    }

    @Override
    public Optional<String> notReady(String site)
    {
        if (EXHENTAI_SITE.equals(site) && !account.get().complete())
        {
            return Optional.of("exhentai shows its search only to a logged-in account: set your e-hentai account's "
                    + "cookies (ipb_member_id and ipb_pass_hash) in Settings.");
        }
        return Optional.empty();
    }

    /**
     * @throws RetryLaterException during a cooldown, without asking the site: requests during a ban can extend it
     */
    @Override
    public SearchPage search(String site, String query, String after)
    {
        Optional<Map.Entry<String, Instant>> refusal = cooldown.searchRefusal();
        if (refusal.isPresent())
        {
            throw new RetryLaterException(refusal.get().getKey(), null, refusal.get().getValue());
        }
        boolean exhentai = EXHENTAI_SITE.equals(site);
        Optional<String> missing = notReady(site);
        if (missing.isPresent())
        {
            throw new UncheckedIOException(new IOException(missing.get()));
        }
        EhentaiSearchPages.Account credentials = account.get();
        try
        {
            EhentaiSearchPages.Page page = searchPages.fetch(exhentai, query,
                    after == null ? null : Long.parseLong(after));
            if (exhentai)
            {
                accepted(credentials);
            }
            return new SearchPage(page.resourceIds(), Set.of(), page.more(), page.total());
        }
        catch (EhentaiSearchPages.RefusedException e)
        {
            refused(credentials);
            throw new UncheckedIOException(e.getMessage(), e);
        }
        catch (EhentaiApi.BannedException e)
        {
            startCooldown(e.getMessage());
            throw cooldown.searchRefusal()
                    .map(refused -> new RetryLaterException(refused.getKey(), e, refused.getValue()))
                    .orElseGet(() -> new RetryLaterException(e.getMessage(), e, null));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e.getMessage(), e);
        }
    }

    /**
     * The page's last gid alone: {@code next=<gid>} lists exactly the galleries below it, whatever changed meanwhile,
     * so where the page came from does not matter.
     */
    @Override
    public String cursorAfter(String site, String after, List<String> resourceIds)
    {
        return Long.toString(position(resourceIds.getLast()));
    }

    @Override
    public long position(String resourceId)
    {
        return Long.parseLong(parsed(resourceId).group(1));
    }

    @Override
    public String searchPageUrl(String site, String query)
    {
        return "https://" + (EXHENTAI_SITE.equals(site) ? EXHENTAI : EHENTAI) + "/?" + query;
    }
}

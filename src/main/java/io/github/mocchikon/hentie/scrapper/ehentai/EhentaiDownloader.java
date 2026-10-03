package io.github.mocchikon.hentie.scrapper.ehentai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mocchikon.hentie.scrapper.DataDownloader;
import io.github.mocchikon.hentie.scrapper.EhTags;
import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.scrapper.GalleryNotFoundException;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDl;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlDownloader;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlException;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.download.PermanentDownloadException;
import io.github.mocchikon.hentie.service.download.RetryLaterException;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
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
 * <b>The domain follows the cookies, not the pasted link</b>: with cookies exhentai.org (it also has the galleries
 * e-hentai hides), falling back once to e-hentai.org when exhentai refuses the account; without them e-hentai.org,
 * since exhentai shows nothing to a visitor. A re-download has no pasted link at all, so the domain could not come
 * from one anyway.
 */
@Component
public class EhentaiDownloader implements GalleryDlDownloader
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

    private final EhentaiProperties properties;
    private final EhentaiApi api;
    private final GalleryDl galleryDl;
    private final EhentaiCooldown cooldown = new EhentaiCooldown();

    public EhentaiDownloader(EhentaiProperties properties, GalleryDl galleryDl)
    {
        this.properties = properties;
        this.api = new EhentaiApi(properties);
        this.galleryDl = galleryDl;
    }

    @PreDestroy
    void close()
    {
        api.close();
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
    public String link(String resourceId)
    {
        return galleryUrl(EHENTAI, resourceId);
    }

    @Override
    public String pageLinkTemplate()
    {
        return "https://" + EHENTAI + "/g/" + RESOURCE_ID + "/";
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
     */
    @Override
    public GalleryDl.Outcome downloadPages(String resourceId, GalleryDlOptions options, SortedSet<Integer> pages,
                                           Path folder, GalleryDl.PageSink sink) throws IOException
    {
        parsed(resourceId);
        cooldown.check();
        var args = new ArrayList<>(List.of("--sleep-request", options.delay(),
                "-o", "original=" + options.originals()));
        if (options.usesCookies())
        {
            args.addAll(List.of("--cookies-from-browser", options.cookiesBrowser()));
        }
        Set<Integer> received = new HashSet<>();
        GalleryDl.PageSink tracking = (page, file) ->
        {
            sink.accept(page, file);
            received.add(page);
        };
        String domain = options.usesCookies() ? EXHENTAI : EHENTAI;
        try
        {
            return galleryDl.download(galleryUrl(domain, resourceId), args, pages, folder, tracking);
        }
        catch (GalleryDlException e)
        {
            if (e.kind() != GalleryDlException.Kind.REFUSED || !domain.equals(EXHENTAI))
            {
                throw failure(e, options);
            }
            log.info("exhentai.org refused the account for gallery {} ({}); trying e-hentai.org", resourceId,
                    e.getMessage());
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
                    throw new PermanentDownloadException("exhentai.org refused the cookies from "
                            + options.cookiesBrowser() + " (no ExHentai access, or not logged in there), and "
                            + "e-hentai.org would not show the gallery either: " + again.getMessage(), again);
                }
                throw failure(again, options);
            }
        }
    }

    /** A refusal is worded by whether cookies were sent: without them there is no account to blame. */
    private RuntimeException failure(GalleryDlException e, GalleryDlOptions options)
    {
        return switch (e.kind())
        {
            case BANNED, IMAGE_LIMIT -> new PermanentDownloadException(startCooldown(e.getMessage()), e);
            case NO_GP -> new PermanentDownloadException(e.getMessage()
                    + " - originals cost GP; untick \"Download originals\" and paste the link again.", e);
            case REFUSED -> new PermanentDownloadException(e.getMessage() + (options.usesCookies()
                    ? " - e-hentai refused the account. Log in to it in the browser the cookies come from."
                    : " - e-hentai shows this gallery only to a logged-in account. Choose the browser you are logged "
                    + "in with under \"Use cookies when downloading\" and paste the link again."), e);
            default -> e.forWorker();
        };
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
}

package io.github.mocchikon.hentie.scrapper.ehentai;

import io.github.mocchikon.hentie.scrapper.QueryStrings;
import io.github.mocchikon.hentie.scrapper.RateLimitedHttp;
import io.github.mocchikon.hentie.scrapper.RequestPacer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything that goes over the wire for e-hentai's and exhentai's search pages, and what they say. The site has no
 * search API, so these are its HTML pages, asked for as a browser asks ({@link EhentaiProperties#getBrowserUserAgent}).
 * <p>
 * <b>One pacer for both domains</b>: e-hentai counts the page loads of both against one IP when it bans for
 * "excessive pageloads", and downloads load its pages too.
 * <p>
 * <b>A page that cannot be read is an error, never an empty page</b>: the walk would take an empty page as the end of
 * the search and stop listing for good. Only the site's own "No hits found" is an empty result.
 * <p>
 * <b>The account's cookies go only to e-hentai's own hosts.</b> Redirects are followed by hand, and only between
 * them: exhentai sends a visitor without its {@code igneous} cookie through the e-hentai forums to get one, and the
 * cookies the hosts set on the way are kept in memory for the next search.
 */
final class EhentaiSearchPages
{
    /** exhentai's detour through the forums takes three; anything longer is no login. */
    private static final int MAX_REDIRECTS = 6;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /** Hosts of e-hentai itself, besides the configured addresses (which the tests point at a fake). */
    private static final Set<String> SITE_HOSTS = Set.of("e-hentai.org", "exhentai.org", "forums.e-hentai.org");

    private static final Pattern GALLERY = Pattern.compile("/g/(\\d{1,10})/([0-9a-f]{10})/");
    private static final Pattern NEXT_URL = Pattern.compile("nexturl\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern FOUND = Pattern.compile("Found (?:about )?([\\d,]{1,15}) results?");
    private static final Pattern TITLE = Pattern.compile("<title>([^<]{0,200})</title>", Pattern.CASE_INSENSITIVE);

    /** Not the account's: those come from Settings on every request, so a changed one applies at once. */
    private static final Set<String> ACCOUNT_COOKIES = Set.of("ipb_member_id", "ipb_pass_hash");

    private final EhentaiProperties properties;
    private final Supplier<Account> account;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final RequestPacer pacer = new RequestPacer();

    /** Cookies the site set, per host, as a browser would keep them; only for searches with the account. */
    private final Map<String, Map<String, String>> learned = new ConcurrentHashMap<>();

    /** The account {@link #learned} belongs to: what the site set for another one would only confuse it. */
    private volatile Account learnedFor;

    /** The e-hentai account's cookies, as the user pasted them into Settings; blank when unset. */
    record Account(String memberId, String passHash, String igneous)
    {
        boolean complete()
        {
            return StringUtils.isNoneBlank(memberId, passHash);
        }
    }

    /**
     * @param resourceIds {@code <gid>/<token>}, newest first
     * @param more        false only when the page said it is the search's last
     * @param total       as the page counts the search's galleries ("about"); null when it does not say
     */
    record Page(List<String> resourceIds, boolean more, Long total)
    {
    }

    /** exhentai would not show the search to the account: wrong or expired cookies, or no ExHentai access. */
    static final class RefusedException extends IOException
    {
        RefusedException(String message)
        {
            super(message);
        }
    }

    EhentaiSearchPages(EhentaiProperties properties, Supplier<Account> account)
    {
        this.properties = properties;
        this.account = account;
    }

    void close()
    {
        client.shutdownNow();
    }

    /**
     * One page of the search, below {@code belowGid} when given.
     *
     * @throws EhentaiApi.BannedException when e-hentai banned this IP
     * @throws RefusedException           when exhentai would not show the search to the account
     */
    Page fetch(boolean exhentai, String query, Long belowGid) throws IOException
    {
        URI search = URI.create(base(exhentai) + "/?" + query + (belowGid == null ? "" : "&next=" + belowGid));
        Account credentials = exhentai ? account.get() : null;
        if (credentials != null && !credentials.equals(learnedFor))
        {
            learned.clear();
            learnedFor = credentials;
        }
        URI uri = search;
        boolean askedAgain = false;
        for (int hop = 0; ; hop++)
        {
            HttpResponse<byte[]> response = RateLimitedHttp.send(client, request(uri, credentials), pacer,
                    Duration.ofMillis(Math.max(0, properties.getSearchRequestIntervalMillis())), "e-hentai");
            if (credentials != null)
            {
                learn(uri, response);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400)
            {
                URI next = redirect(uri, response, exhentai);
                if (hop >= MAX_REDIRECTS)
                {
                    throw refusedOrFailed(exhentai, "it kept redirecting the search (last to " + next.getHost() + ")");
                }
                uri = next;
                continue;
            }
            if (status != 200)
            {
                throw new IOException("e-hentai answered HTTP " + status + " for the search.");
            }
            if (!sameSearch(uri, search))
            {
                // The detour that set the cookies ends on the front page, not on the search: ask for it once more.
                if (askedAgain)
                {
                    throw refusedOrFailed(exhentai, "it would not show the search after logging in");
                }
                askedAgain = true;
                uri = search;
                continue;
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            byte[] body = response.body();
            if (exhentai && (body == null || body.length == 0 || type.startsWith("image/")))
            {
                throw new RefusedException("exhentai.org sent an empty page instead of the search: it did not accept "
                        + "the account" + (isMystery() ? " (igneous=mystery: the account has no ExHentai access)"
                        : " - check the e-hentai account cookies in Settings, they may have expired") + ".");
            }
            return parse(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8));
        }
    }

    /**
     * Whether a redirect ended on the search itself, spelled another way (https, another order of its filters, a
     * trailing "/" or "&"): asking again would only be redirected there again. The front page, where the cookie
     * detour ends, is a gallery list too, so anything else is asked for once more.
     */
    private static boolean sameSearch(URI landed, URI search)
    {
        if (!host(landed).equals(host(search))
                || !StringUtils.defaultIfEmpty(landed.getRawPath(), "/").equals(
                StringUtils.defaultIfEmpty(search.getRawPath(), "/")))
        {
            return false;
        }
        try
        {
            return new HashSet<>(QueryStrings.parameters(landed.getRawQuery()))
                    .equals(new HashSet<>(QueryStrings.parameters(search.getRawQuery())));
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }
    }

    private String base(boolean exhentai)
    {
        return StringUtils.removeEnd(StringUtils.strip(exhentai ? properties.getExhentaiUrl()
                : properties.getEhentaiUrl()), "/");
    }

    private HttpRequest request(URI uri, Account credentials)
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds())))
                .header("User-Agent", properties.getBrowserUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Cookie", cookies(uri, credentials))
                .GET();
        return builder.build();
    }

    /** {@code nw=1} skips the content warning, as gallery-dl sends it; the rest only with the account. */
    private String cookies(URI uri, Account credentials)
    {
        var cookies = new LinkedHashMap<String, String>();
        cookies.put("nw", "1");
        if (credentials != null && credentials.complete())
        {
            cookies.put("ipb_member_id", credentials.memberId());
            cookies.put("ipb_pass_hash", credentials.passHash());
            if (StringUtils.isNotBlank(credentials.igneous()))
            {
                cookies.put("igneous", credentials.igneous());
            }
            cookies.putAll(learned.getOrDefault(host(uri), Map.of()));
        }
        var header = new StringJoiner("; ");
        cookies.forEach((name, value) -> header.add(name + "=" + value));
        return header.toString();
    }

    private void learn(URI uri, HttpResponse<byte[]> response)
    {
        for (String setCookie : response.headers().allValues("Set-Cookie"))
        {
            String pair = StringUtils.substringBefore(setCookie, ";");
            int equals = pair.indexOf('=');
            if (equals <= 0)
            {
                continue;
            }
            String name = pair.substring(0, equals).strip();
            String value = pair.substring(equals + 1).strip();
            if (ACCOUNT_COOKIES.contains(name))
            {
                continue;
            }
            Map<String, String> cookies = learned.computeIfAbsent(host(uri), h -> new ConcurrentHashMap<>());
            if (value.isEmpty() || value.equals("deleted"))
            {
                cookies.remove(name);
            }
            else
            {
                cookies.put(name, value);
            }
        }
    }

    private boolean isMystery()
    {
        return learned.values().stream().anyMatch(cookies -> "mystery".equals(cookies.get("igneous")));
    }

    /** Only to e-hentai's own hosts, which the account belongs to; anywhere else would be given its cookies. */
    private URI redirect(URI from, HttpResponse<byte[]> response, boolean exhentai) throws IOException
    {
        String location = response.headers().firstValue("Location").orElse("");
        URI next;
        try
        {
            next = from.resolve(location.strip());
        }
        catch (IllegalArgumentException e)
        {
            throw refusedOrFailed(exhentai, "it redirected the search to an address that is none");
        }
        String scheme = StringUtils.defaultString(next.getScheme());
        if (!isSiteHost(next))
        {
            throw refusedOrFailed(exhentai, "it sent the search to " + next.getHost() + ", which is not e-hentai");
        }
        if (!scheme.equals("https") && !(scheme.equals("http") && isPlainHttpBase(next)))
        {
            throw refusedOrFailed(exhentai, "it sent the search to " + scheme + "://" + next.getHost()
                    + ", where the account's cookies would go unencrypted");
        }
        return next;
    }

    /** Plain http only where a configured address uses it (the tests' fakes); the account's cookies go with it. */
    private boolean isPlainHttpBase(URI uri)
    {
        for (boolean exhentai : new boolean[]{false, true})
        {
            URI base = URI.create(base(exhentai));
            if ("http".equalsIgnoreCase(base.getScheme()) && host(base).equals(host(uri)))
            {
                return true;
            }
        }
        return false;
    }

    private boolean isSiteHost(URI uri)
    {
        String host = host(uri);
        return SITE_HOSTS.contains(host) || host.equals(host(URI.create(base(false))))
                || host.equals(host(URI.create(base(true))));
    }

    private static String host(URI uri)
    {
        return StringUtils.defaultString(uri.getHost()).toLowerCase(Locale.ROOT);
    }

    private IOException refusedOrFailed(boolean exhentai, String what)
    {
        return exhentai
                ? new RefusedException("exhentai.org did not accept the account: " + what + ". Check the e-hentai "
                + "account cookies in Settings; they may have expired.")
                : new IOException("e-hentai did not answer the search: " + what + ".");
    }

    /**
     * The galleries inside the results list, in its order; any display mode lists each as a {@code /g/} link,
     * several times. Outside the list (a front page's popular row) links are ignored.
     *
     * @throws EhentaiApi.BannedException for the ban page
     * @throws IOException                for anything that is not a results page or "No hits found"
     */
    static Page parse(String html) throws IOException
    {
        int list = html.indexOf("class=\"itg");
        if (list < 0)
        {
            if (html.contains(">No hits found<"))
            {
                return new Page(List.of(), false, 0L);
            }
            // Only without a results list: a title may hold the same words.
            if (StringUtils.containsIgnoreCase(html, "temporarily banned"))
            {
                throw new EhentaiApi.BannedException("e-hentai has temporarily banned this IP address ("
                        + StringUtils.abbreviate(StringUtils.normalizeSpace(html), 300) + ")");
            }
            throw new IOException("e-hentai's answer is no list of search results" + describe(html)
                    + "; the site may have changed or be down.");
        }
        int end = html.indexOf("class=\"searchnav\"", list);
        String results = html.substring(list, end < 0 ? html.length() : end);
        var ids = new LinkedHashSet<String>();
        long previous = Long.MAX_VALUE;
        Matcher gallery = GALLERY.matcher(results);
        while (gallery.find())
        {
            String id = Long.parseLong(gallery.group(1)) + "/" + gallery.group(2);
            if (ids.contains(id))
            {
                continue;
            }
            long gid = Long.parseLong(gallery.group(1));
            if (gid >= previous)
            {
                throw new IOException("e-hentai listed gallery " + gid + " after " + previous + ", out of the order of "
                        + "their ids, so where the search left off cannot be trusted.");
            }
            previous = gid;
            ids.add(id);
        }
        if (ids.isEmpty())
        {
            throw new IOException("e-hentai's results list holds no gallery" + describe(html) + ".");
        }
        Matcher next = NEXT_URL.matcher(html);
        if (!next.find())
        {
            throw new IOException("e-hentai's results page has no link to its next page; the site may have changed.");
        }
        Matcher found = FOUND.matcher(html);
        Long total = found.find() ? Long.parseLong(found.group(1).replace(",", "")) : null;
        return new Page(List.copyOf(ids), !StringEscapeUtils.unescapeHtml4(next.group(1)).isBlank(), total);
    }

    private static String describe(String html)
    {
        Matcher title = TITLE.matcher(html);
        if (title.find() && !title.group(1).isBlank())
        {
            return " (\"" + StringUtils.abbreviate(StringUtils.normalizeSpace(title.group(1)), 100) + "\")";
        }
        return html.isBlank() ? " (an empty page)" : "";
    }
}

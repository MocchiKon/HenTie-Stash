package io.github.mocchikon.hentie.scrapper.ehentai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The page fixtures keep only the shape e-hentai's search pages have (checked against the site): the results list in
 * any display mode, each gallery linked several times, the {@code nexturl} the site's script pages with, and the
 * count above the list.
 */
class EhentaiSearchPagesTest
{
    private HttpServer server;
    private final List<String> cookies = new CopyOnWriteArrayList<>();
    private final List<String> userAgents = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop()
    {
        if (server != null)
        {
            server.stop(0);
        }
    }

    static String resultsPage(String listClass, String nextUrl, long... gids)
    {
        var html = new StringBuilder("<html><head><title>E-Hentai Galleries</title></head><body>"
                + "<div class=\"searchtext\"><p>Found about 375,313 results.</p></div>"
                + "<div class=\"searchnav\"><a id=\"unext\" href=\"#\">Next</a></div>");
        html.append(listClass.startsWith("itg gld") ? "<div class=\"" + listClass + "\">"
                : "<table class=\"" + listClass + "\"><tbody>");
        for (long gid : gids)
        {
            String link = "https://e-hentai.org/g/" + gid + "/" + token(gid) + "/";
            html.append("<tr><td><a href=\"").append(link).append("\"><img></a></td><td><a href=\"").append(link)
                    .append("\"><div class=\"glink\">Gallery ").append(gid).append("</div></a></td></tr>");
        }
        html.append(listClass.startsWith("itg gld") ? "</div>" : "</tbody></table>");
        html.append("<div class=\"searchnav\"></div><script>var nexturl=\"").append(nextUrl)
                .append("\";</script></body></html>");
        return html.toString();
    }

    static String token(long gid)
    {
        return String.format("%010x", gid * 7919);
    }

    @Test
    void shouldListTheGalleriesOfEveryDisplayModeInOrder() throws IOException
    {
        for (String listClass : List.of("itg gltm", "itg gltc", "itg glte", "itg gld"))
        {
            // WHEN
            EhentaiSearchPages.Page page = EhentaiSearchPages.parse(resultsPage(listClass,
                    "https://e-hentai.org/?f_search=x&amp;next=4229794", 4230080, 4230066, 4229794));

            // THEN each once, newest first, and more pages to come
            assertThat(page.resourceIds()).as(listClass).containsExactly("4230080/" + token(4230080),
                    "4230066/" + token(4230066), "4229794/" + token(4229794));
            assertThat(page.more()).isTrue();
            assertThat(page.total()).isEqualTo(375_313L);
        }
    }

    @Test
    void shouldSayWhenAPageIsTheLast() throws IOException
    {
        // WHEN
        EhentaiSearchPages.Page page = EhentaiSearchPages.parse(resultsPage("itg glte", "", 12, 11));

        // THEN
        assertThat(page.more()).isFalse();
        assertThat(page.resourceIds()).hasSize(2);
    }

    @Test
    void shouldReadNoHitsAsAnEmptyLastPage() throws IOException
    {
        // WHEN
        EhentaiSearchPages.Page page = EhentaiSearchPages.parse("<html><body><div><p style=\"text-align:center\">"
                + "No hits found</p></div></body></html>");

        // THEN
        assertThat(page.resourceIds()).isEmpty();
        assertThat(page.more()).isFalse();
    }

    /** Taken for the end of the list, any of these would stop a subscription's walk for good. */
    @Test
    void shouldNeverReadAPageItDoesNotKnowAsTheEnd()
    {
        // WHEN + THEN
        assertThatThrownBy(() -> EhentaiSearchPages.parse("<html><head><title>Maintenance</title></head></html>"))
                .isInstanceOf(IOException.class).hasMessageContaining("Maintenance");
        assertThatThrownBy(() -> EhentaiSearchPages.parse(""))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> EhentaiSearchPages.parse(resultsPage("itg glte", "x", 5, 4)
                .replace("var nexturl=\"x\";", "")))
                .isInstanceOf(IOException.class).hasMessageContaining("next page");
        assertThatThrownBy(() -> EhentaiSearchPages.parse(resultsPage("itg glte", "x")))
                .isInstanceOf(IOException.class).hasMessageContaining("no gallery");
        assertThatThrownBy(() -> EhentaiSearchPages.parse(resultsPage("itg glte", "x", 5, 7)))
                .isInstanceOf(IOException.class).hasMessageContaining("out of the order");
    }

    @Test
    void shouldTellTheBanPage()
    {
        // WHEN + THEN
        assertThatThrownBy(() -> EhentaiSearchPages.parse("Your IP address has been temporarily banned for excessive "
                + "pageloads which indicates that you are using automated mirroring/harvesting software. The ban "
                + "expires in 59 minutes and 36 seconds"))
                .isInstanceOf(EhentaiApi.BannedException.class).hasMessageContaining("temporarily banned");
    }

    /** A title may hold the ban's words; only a page without results is the ban page. */
    @Test
    void shouldNotTakeAResultsPageForTheBanPage() throws IOException
    {
        // WHEN
        EhentaiSearchPages.Page page = EhentaiSearchPages.parse(resultsPage("itg glte", "", 3)
                .replace("Gallery 3", "I got temporarily banned"));

        // THEN
        assertThat(page.resourceIds()).hasSize(1);
    }

    @Test
    void shouldIgnoreGalleriesOutsideTheResultsList() throws IOException
    {
        // GIVEN a popular row above the list, newer than the results
        String html = resultsPage("itg glte", "", 50, 40).replace("<div class=\"searchnav\"><a id=\"unext\"",
                "<div id=\"pp\"><a href=\"https://e-hentai.org/g/99999/" + token(99999) + "/\">popular</a></div>"
                        + "<div class=\"searchnav\"><a id=\"unext\"");

        // WHEN
        EhentaiSearchPages.Page page = EhentaiSearchPages.parse(html);

        // THEN
        assertThat(page.resourceIds()).containsExactly("50/" + token(50), "40/" + token(40));
    }

    // ---- over the wire ------------------------------------------------------------------------------------

    private EhentaiSearchPages pages(EhentaiSearchPages.Account account)
    {
        var properties = new EhentaiProperties();
        properties.setEhentaiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/eh");
        properties.setExhentaiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/ex");
        properties.setSearchRequestIntervalMillis(0);
        return new EhentaiSearchPages(properties, () -> account);
    }

    private void serve(com.sun.net.httpserver.HttpHandler handler) throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange ->
        {
            cookies.add(String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
            userAgents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
            paths.add(exchange.getRequestURI().toString());
            handler.handle(exchange);
        });
        server.start();
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0)
        {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Test
    void shouldAskAsABrowserWithTheCursorAndNoAccountForEhentai() throws IOException
    {
        // GIVEN
        serve(exchange -> answer(exchange, 200, resultsPage("itg glte", "", 9, 8)));

        // WHEN
        EhentaiSearchPages.Page page = pages(new EhentaiSearchPages.Account("1", "a".repeat(32), ""))
                .fetch(false, "f_search=x", 10L);

        // THEN
        assertThat(page.resourceIds()).hasSize(2);
        assertThat(paths).containsExactly("/eh/?f_search=x&next=10");
        assertThat(cookies).containsExactly("nw=1");
        assertThat(userAgents.getFirst()).startsWith("Mozilla/5.0");
    }

    /**
     * exhentai sends a visitor without igneous through the forums to get one, and ends the detour on its front page:
     * the search is asked again, with the cookie the detour set.
     */
    @Test
    void shouldFollowExhentaisLoginDetourAndAskAgain() throws IOException
    {
        // GIVEN
        serve(exchange ->
        {
            String cookie = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"));
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/forums/remoteapi.php"))
            {
                exchange.getResponseHeaders().add("Location", "/ex/?token=1");
                exchange.getResponseHeaders().add("Set-Cookie", "ipb_session_id=s1; path=/");
                answer(exchange, 302, "");
            }
            else if ("/ex/".equals(path) && "token=1".equals(exchange.getRequestURI().getQuery()))
            {
                exchange.getResponseHeaders().add("Set-Cookie", "igneous=ab12cd; path=/; domain=.exhentai.org");
                exchange.getResponseHeaders().add("Location", "/ex/");
                answer(exchange, 302, "");
            }
            else if (!cookie.contains("igneous="))
            {
                exchange.getResponseHeaders().add("Location", "/forums/remoteapi.php?ex=abc");
                answer(exchange, 302, "");
            }
            else if (exchange.getRequestURI().getQuery() == null)
            {
                answer(exchange, 200, "<html>front page</html>");
            }
            else
            {
                answer(exchange, 200, resultsPage("itg glte", "next", 30, 20));
            }
        });

        // WHEN
        EhentaiSearchPages.Page page = pages(new EhentaiSearchPages.Account("123", "f".repeat(32), ""))
                .fetch(true, "f_search=y", null);

        // THEN the account's cookies went along every step, and the search was asked again with igneous
        assertThat(page.resourceIds()).hasSize(2);
        assertThat(paths).containsExactly("/ex/?f_search=y", "/forums/remoteapi.php?ex=abc", "/ex/?token=1", "/ex/",
                "/ex/?f_search=y");
        assertThat(cookies).allMatch(cookie -> cookie.contains("ipb_member_id=123")
                && cookie.contains("ipb_pass_hash=" + "f".repeat(32)) && cookie.contains("nw=1"));
        assertThat(cookies.getLast()).contains("igneous=ab12cd");
    }

    @Test
    void shouldTakeAnEmptyExhentaiPageForARefusedAccount() throws IOException
    {
        // GIVEN
        serve(exchange -> answer(exchange, 200, ""));

        // WHEN + THEN
        assertThatThrownBy(() -> pages(new EhentaiSearchPages.Account("1", "a".repeat(32), "c0ffee"))
                .fetch(true, "f_search=x", null))
                .isInstanceOf(EhentaiSearchPages.RefusedException.class).hasMessageContaining("Settings");
        assertThat(cookies.getFirst()).contains("igneous=c0ffee");
    }

    /** The account's cookies must never follow a redirect off e-hentai's own hosts. */
    @Test
    void shouldNotFollowARedirectToAnotherHost() throws IOException
    {
        // GIVEN
        serve(exchange ->
        {
            exchange.getResponseHeaders().add("Location", "https://example.com/steal");
            answer(exchange, 302, "");
        });

        // WHEN + THEN
        assertThatThrownBy(() -> pages(new EhentaiSearchPages.Account("1", "a".repeat(32), ""))
                .fetch(true, "f_search=x", null))
                .isInstanceOf(EhentaiSearchPages.RefusedException.class).hasMessageContaining("example.com");
        assertThat(paths).hasSize(1);
    }
}

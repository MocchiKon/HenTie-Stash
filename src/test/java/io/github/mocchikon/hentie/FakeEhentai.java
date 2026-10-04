package io.github.mocchikon.hentie;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Just enough of e-hentai's and exhentai's search pages for a subscription to walk: {@code /eh/} and {@code /ex/}
 * list one set of galleries, 25 a page, newest first, and {@code next=<gid>} lists exactly the galleries below that
 * gid, as the site does. A fake rather than a mock, because what goes over the wire (cookies, the cursor, the ban
 * page) is what the suites test.
 */
public final class FakeEhentai implements AutoCloseable
{
    private static final int PER_PAGE = 25;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final NavigableSet<Long> galleries = new ConcurrentSkipListSet<>(Comparator.reverseOrder());
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile boolean banned;
    private volatile String exhentaiCookie;

    /** @param cookie the {@code Cookie} header sent, null without one */
    public record Request(String path, Map<String, String> query, String cookie, String userAgent)
    {
    }

    private FakeEhentai() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public static FakeEhentai start() throws IOException
    {
        return new FakeEhentai();
    }

    public String ehentaiUrl()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/eh";
    }

    public String exhentaiUrl()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/ex";
    }

    /** Every gallery's token is made from its gid, so a test can name its gallery id. */
    public static String token(long gid)
    {
        return String.format("%010x", gid * 7919);
    }

    public static String galleryId(long gid)
    {
        return "ehentai:" + gid + "/" + token(gid);
    }

    public FakeEhentai galleries(long fromGid, int count)
    {
        for (long gid = fromGid; gid < fromGid + count; gid++)
        {
            galleries.add(gid);
        }
        return this;
    }

    /** Every page is the ban page while set. */
    public FakeEhentai banned(boolean banned)
    {
        this.banned = banned;
        return this;
    }

    /** exhentai shows its pages only to a request whose cookies hold this; others get its empty page. */
    public FakeEhentai exhentaiRequires(String cookie)
    {
        exhentaiCookie = cookie;
        return this;
    }

    public List<Request> requests()
    {
        return List.copyOf(requests);
    }

    @Override
    public void close()
    {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException
    {
        try (exchange)
        {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = parameters(exchange.getRequestURI().getRawQuery());
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            requests.add(new Request(path, query, cookie, exchange.getRequestHeaders().getFirst("User-Agent")));
            if (banned)
            {
                send(exchange, "Your IP address has been temporarily banned for excessive pageloads which indicates "
                        + "that you are using automated mirroring/harvesting software. The ban expires in 59 minutes "
                        + "and 36 seconds");
                return;
            }
            if (path.startsWith("/ex") && exhentaiCookie != null
                    && (cookie == null || !cookie.contains(exhentaiCookie)))
            {
                send(exchange, "");
                return;
            }
            String next = query.get("next");
            List<Long> below = galleries.stream()
                    .filter(gid -> next == null || gid < Long.parseLong(next))
                    .limit(PER_PAGE + 1)
                    .toList();
            send(exchange, page(below.stream().limit(PER_PAGE).toList(), below.size() > PER_PAGE, path));
        }
    }

    private String page(List<Long> gids, boolean more, String path)
    {
        if (gids.isEmpty())
        {
            return "<html><body><p style=\"text-align:center\">No hits found</p></body></html>";
        }
        String host = path.startsWith("/ex") ? "https://exhentai.org" : "https://e-hentai.org";
        var html = new StringBuilder("<html><head><title>Galleries</title></head><body><div class=\"searchtext\">"
                + "<p>Found about " + String.format(Locale.ENGLISH, "%,d", galleries.size()) + " results.</p></div>"
                + "<div class=\"searchnav\"></div><table class=\"itg glte\">");
        for (long gid : gids)
        {
            String link = host + "/g/" + gid + "/" + token(gid) + "/";
            html.append("<tr><td><a href=\"").append(link).append("\"><img></a></td><td><a href=\"").append(link)
                    .append("\">Gallery ").append(gid).append("</a></td></tr>");
        }
        html.append("</table><div class=\"searchnav\"></div><script>var nexturl=\"")
                .append(more ? host + "/?next=" + gids.getLast() : "").append("\";</script></body></html>");
        return html.toString();
    }

    private static Map<String, String> parameters(String query)
    {
        var parameters = new LinkedHashMap<String, String>();
        if (query != null)
        {
            for (String pair : query.split("&"))
            {
                int equals = pair.indexOf('=');
                if (equals > 0)
                {
                    parameters.put(pair.substring(0, equals),
                            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return parameters;
    }

    private static void send(HttpExchange exchange, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0)
        {
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(bytes);
            }
        }
    }
}

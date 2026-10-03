package io.github.mocchikon.hentie;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Just enough of nhentai's API and image servers to test the source against. A fake rather than a mock, because
 * what goes over the wire (headers, paths, 429s) is what these suites test.
 * <p>
 * It is its own image server too ({@code /api/v2/cdn} lists it), so a test sees every request the source makes,
 * thumbnails included. Only page images are served; anything else answers 404.
 */
public final class FakeNhentai implements AutoCloseable
{
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, String> galleries = new ConcurrentHashMap<>();
    private final Map<String, Image> images = new ConcurrentHashMap<>();
    private final Map<String, Deque<Integer>> failures = new ConcurrentHashMap<>();
    private final List<List<Integer>> favouritePages = new CopyOnWriteArrayList<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile String acceptedKey;

    /** {@code authorization} is null when the header was not sent. */
    public record Request(String path, String query, String authorization, String userAgent)
    {
    }

    private record Image(byte[] bytes, String contentType)
    {
    }

    private FakeNhentai() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public static FakeNhentai start() throws IOException
    {
        return new FakeNhentai();
    }

    public String baseUrl()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ---- what the site holds -------------------------------------------------------------------------------

    public FakeNhentai gallery(String id, String json)
    {
        galleries.put(id, json);
        return this;
    }

    /**
     * A gallery as nhentai sends one: cover, thumbnail and a thumbnail per page beside the pages, artists joined
     * as nhentai sometimes does, and "translated" filed before the language. Only the page images are served.
     */
    public FakeNhentai simpleGallery(String id, int pages)
    {
        ObjectNode gallery = JSON.createObjectNode();
        gallery.put("id", Integer.parseInt(id));
        gallery.put("media_id", "m" + id);
        ObjectNode title = gallery.putObject("title");
        title.put("english", "[Circle (Alpha | Beta)] Gallery " + id + " [English]");
        title.putNull("japanese");
        title.put("pretty", "Gallery " + id);
        gallery.putObject("cover").put("path", "galleries/m" + id + "/cover.jpg");
        gallery.putObject("thumbnail").put("path", "galleries/m" + id + "/thumb.jpg");
        ArrayNode tags = gallery.putArray("tags");
        tag(tags, "language", "translated");
        tag(tags, "language", "english");
        tag(tags, "artist", "alpha | beta");
        tag(tags, "group", "circle");
        tag(tags, "tag", "full color");
        tag(tags, "category", "doujinshi");
        gallery.put("num_pages", pages);
        ArrayNode pageList = gallery.putArray("pages");
        for (int page = 1; page <= pages; page++)
        {
            String path = "galleries/m" + id + "/" + page + ".jpg";
            pageList.addObject()
                    .put("number", page)
                    .put("path", path)
                    .put("thumbnail", "galleries/m" + id + "/" + page + "t.jpg");
            image(path, ("page " + page + " of " + id).getBytes(StandardCharsets.UTF_8));
        }
        return gallery(id, gallery.toString());
    }

    private static void tag(ArrayNode tags, String type, String name)
    {
        tags.addObject().put("type", type).put("name", name);
    }

    public FakeNhentai image(String path, byte[] bytes)
    {
        return image(path, bytes, "image/jpeg");
    }

    public FakeNhentai image(String path, byte[] bytes, String contentType)
    {
        images.put(path, new Image(bytes, contentType));
        return this;
    }

    /** The favourites of the key's owner, a list of gallery ids per page. */
    @SafeVarargs
    public final FakeNhentai favourites(List<Integer>... pages)
    {
        favouritePages.clear();
        favouritePages.addAll(List.of(pages));
        return this;
    }

    /** The one key the favourites accept; without it any key does. */
    public FakeNhentai acceptKey(String key)
    {
        acceptedKey = key;
        return this;
    }

    /**
     * The next requests for {@code path} answer these statuses first, one each; a 429 says to retry at once.
     * {@code path} may carry a query ({@code /api/v2/favorites?page=2}) to fail only that one.
     */
    public FakeNhentai failNext(String path, Integer... statuses)
    {
        failures.computeIfAbsent(path, p -> new ArrayDeque<>()).addAll(List.of(statuses));
        return this;
    }

    // ---- what the source asked -----------------------------------------------------------------------------

    public List<Request> requests()
    {
        return List.copyOf(requests);
    }

    public long requestsFor(String path)
    {
        return requests.stream().filter(request -> request.path().equals(path)).count();
    }

    @Override
    public void close()
    {
        server.stop(0);
        executor.shutdownNow();
    }

    // ---- serving -------------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException
    {
        try (exchange)
        {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            requests.add(new Request(path, query, exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("User-Agent")));

            Integer failure = query == null ? null : nextFailure(path + "?" + query);
            if (failure == null)
            {
                failure = nextFailure(path);
            }
            if (failure != null)
            {
                if (failure == 429)
                {
                    exchange.getResponseHeaders().add("Retry-After", "0");
                }
                send(exchange, failure, "application/json", "{\"error\":\"fake failure\"}".getBytes());
                return;
            }
            if (path.equals("/api/v2/cdn"))
            {
                ObjectNode body = JSON.createObjectNode();
                body.putArray("image_servers").add(baseUrl() + "/");
                body.putArray("thumb_servers").add(baseUrl() + "/thumbs");
                send(exchange, 200, "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
            }
            else if (path.startsWith("/api/v2/galleries/"))
            {
                String gallery = galleries.get(path.substring("/api/v2/galleries/".length()));
                if (gallery == null)
                {
                    send(exchange, 404, "application/json", "{\"error\":\"Gallery not found\"}".getBytes());
                }
                else
                {
                    send(exchange, 200, "application/json", gallery.getBytes(StandardCharsets.UTF_8));
                }
            }
            else if (path.equals("/api/v2/favorites"))
            {
                favourites(exchange, query);
            }
            else
            {
                Image image = images.get(path.substring(1));
                if (image == null)
                {
                    send(exchange, 404, "text/plain", "Not Found".getBytes());
                }
                else
                {
                    send(exchange, 200, image.contentType(), image.bytes());
                }
            }
        }
    }

    private Integer nextFailure(String key)
    {
        Deque<Integer> queued = failures.get(key);
        return queued == null ? null : queued.pollFirst();
    }

    private void favourites(HttpExchange exchange, String query) throws IOException
    {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        boolean authorized = authorization != null && authorization.startsWith("Key ")
                && (acceptedKey == null || authorization.equals("Key " + acceptedKey));
        if (!authorized)
        {
            send(exchange, 401, "application/json", "{\"error\":\"Invalid API key\"}".getBytes());
            return;
        }
        int page = query != null && query.startsWith("page=") ? Integer.parseInt(query.substring(5)) : 1;
        List<Integer> ids = page >= 1 && page <= favouritePages.size() ? favouritePages.get(page - 1) : List.of();
        ObjectNode body = JSON.createObjectNode();
        ArrayNode result = body.putArray("result");
        for (int id : ids)
        {
            result.addObject().put("id", id).put("media_id", "m" + id).put("english_title", "Gallery " + id);
        }
        body.put("num_pages", favouritePages.size());
        body.put("per_page", 25);
        body.put("total", favouritePages.stream().mapToInt(List::size).sum());
        send(exchange, 200, "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException
    {
        if (contentType != null)
        {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0)
        {
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(body);
            }
        }
    }

    /** Every path asked for, in order, for assertions that read better on one list. */
    public List<String> paths()
    {
        var paths = new ArrayList<String>();
        requests.forEach(request -> paths.add(request.path()));
        return paths;
    }
}

package io.github.mocchikon.hentie;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * panda.chaika.moe as the chaika source sees it: {@code /api?archive=<id>} and the archive at
 * {@code /archive/<id>/download/}, answered in byte ranges like the real server. Every range asked for is recorded,
 * so a suite can check nothing downloaded a whole archive.
 */
public final class FakeChaika implements AutoCloseable
{
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final HttpServer server;
    private final Map<String, String> api = new ConcurrentHashMap<>();
    private final Map<String, byte[]> archives = new ConcurrentHashMap<>();
    private final List<String> ranges = new CopyOnWriteArrayList<>();
    private volatile boolean ignoreRanges;

    private FakeChaika() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api", this::api);
        server.createContext("/archive/", this::archive);
        server.start();
    }

    public static FakeChaika start() throws IOException
    {
        return new FakeChaika();
    }

    public String baseUrl()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close()
    {
        server.stop(0);
    }

    /** An archive whose zip holds {@code entries} (name to bytes, in that order), deflated where asked. */
    public FakeChaika archive(String id, String json, Map<String, byte[]> entries, boolean deflate) throws IOException
    {
        api.put(id, json);
        archives.put(id, zip(entries, deflate));
        return this;
    }

    public FakeChaika rawArchive(String id, String json, byte[] zip)
    {
        api.put(id, json);
        archives.put(id, zip);
        return this;
    }

    /** Answers every range with the whole archive, as a server that does not do ranges would. */
    public void ignoreRanges()
    {
        ignoreRanges = true;
    }

    public List<String> ranges()
    {
        return new ArrayList<>(ranges);
    }

    public static byte[] zip(Map<String, byte[]> entries, boolean deflate) throws IOException
    {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes))
        {
            for (var file : entries.entrySet())
            {
                var entry = new ZipEntry(file.getKey());
                if (!deflate)
                {
                    var crc = new CRC32();
                    crc.update(file.getValue());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(file.getValue().length);
                    entry.setCompressedSize(file.getValue().length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private void api(HttpExchange exchange) throws IOException
    {
        String query = exchange.getRequestURI().getQuery();
        String id = query == null ? "" : query.replaceFirst("^archive=", "");
        String json = api.get(id);
        if (json == null)
        {
            send(exchange, 404, "{\"result\": \"Archive does not exist.\"}".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        send(exchange, 200, json.getBytes(StandardCharsets.UTF_8), null);
    }

    private void archive(HttpExchange exchange) throws IOException
    {
        Matcher path = Pattern.compile("/archive/(\\d+)/download/?").matcher(exchange.getRequestURI().getPath());
        byte[] zip = path.matches() ? archives.get(path.group(1)) : null;
        if (zip == null)
        {
            send(exchange, 404, new byte[0], null);
            return;
        }
        String range = exchange.getRequestHeaders().getFirst("Range");
        ranges.add(range == null ? "(none)" : range);
        Matcher matcher = range == null ? null : RANGE.matcher(range);
        if (ignoreRanges || matcher == null || !matcher.matches())
        {
            send(exchange, 200, zip, null);
            return;
        }
        long from;
        long to;
        if (matcher.group(1).isEmpty())
        {
            from = Math.max(0, zip.length - Long.parseLong(matcher.group(2)));
            to = zip.length - 1;
        }
        else
        {
            from = Long.parseLong(matcher.group(1));
            to = matcher.group(2).isEmpty() ? zip.length - 1 : Math.min(zip.length - 1, Long.parseLong(matcher.group(2)));
        }
        byte[] part = java.util.Arrays.copyOfRange(zip, (int) from, (int) to + 1);
        send(exchange, 206, part, "bytes " + from + "-" + to + "/" + zip.length);
    }

    private static void send(HttpExchange exchange, int status, byte[] body, String contentRange) throws IOException
    {
        if (contentRange != null)
        {
            exchange.getResponseHeaders().set("Content-Range", contentRange);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0)
        {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}

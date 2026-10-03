package io.github.mocchikon.hentie.scrapper.chaika;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mocchikon.hentie.scrapper.*;
import io.github.mocchikon.hentie.service.ImageDirectory;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.mocchikon.hentie.scrapper.JsonFields.text;

/**
 * panda.chaika.moe, an archive of e-hentai galleries as zip files. Its metadata comes from its JSON API and is
 * e-hentai's ({@link EhTags}).
 * <p>
 * <b>Pages are read straight out of the remote zip</b>, one byte range each: the server answers ranges, and its
 * archives store pages uncompressed. So a resumed or partial download fetches only the pages it misses, never the
 * whole archive (often hundreds of MB), and every page goes through the pipeline like any other source's: retries,
 * lenient mode, compression as pages land. Each page is checked against the archive's CRC.
 * <p>
 * <b>Page addresses carry where the page is in the archive</b> (in the fragment, which is never sent), so fetching
 * a page needs no state from reading the index.
 */
@Component
@RequiredArgsConstructor
public class ChaikaDownloader implements PageDownloader
{
    private static final Logger log = LoggerFactory.getLogger(ChaikaDownloader.class);

    static final String PREFIX = "chaika";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * An archive's link, with whatever follows the id. A gallery link ({@code /gallery/<id>/}) is not accepted: which
     * archive it means takes an API call to find, and a link must be understood without going over the network.
     */
    private static final Pattern ARCHIVE_URL = Pattern.compile(
            "(?:https?://)?(?:[a-z0-9-]+\\.)*chaika\\.moe/archive/0*(\\d{1,10})(?:[/?#].*)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern PREFIXED_ID = Pattern.compile(PREFIX + ":\\s*0*(\\d{1,10})", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)");

    /** The start a {@code Range} header asks for; empty for a suffix ({@code bytes=-N}). */
    private static final Pattern REQUESTED_START = Pattern.compile("bytes=(\\d*)-");

    /** How much past an entry's name the local header's extra field is assumed to need, to fetch a page in one go. */
    private static final int LOCAL_EXTRA_SLACK = 256;

    private final ChaikaProperties properties;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final RequestPacer apiPacer = new RequestPacer();
    private final RequestPacer archivePacer = new RequestPacer();

    @PreDestroy
    void close()
    {
        client.shutdownNow();
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

    @Override
    public String resourceId(String link)
    {
        return idIn(link);
    }

    /** Always the site's own address, whatever {@code base-url} says, so it parses back. */
    @Override
    public String link(String resourceId)
    {
        return "https://panda.chaika.moe/archive/" + resourceId + "/";
    }

    @Override
    public String pageLinkTemplate()
    {
        return link(RESOURCE_ID);
    }

    @Override
    public String linkExample()
    {
        return "https://panda.chaika.moe/archive/12345/";
    }

    private static String idIn(String link)
    {
        return DataDownloader.matchLink(link, PREFIXED_ID, ARCHIVE_URL).map(m -> m.group(1)).orElse(null);
    }

    // ---- a gallery -------------------------------------------------------------------------------------------

    @Override
    public GalleryData downloadGalleryInfo(String id)
    {
        if (id == null || !id.matches("\\d{1,10}"))
        {
            throw new GalleryNotFoundException("Not a chaika archive id: '" + id + "'");
        }
        try
        {
            JsonNode archive = archiveInfo(id);
            List<ZipIndex.Entry> pages = pagesOf(id);
            int listed = archive.path("filecount").asInt(-1);
            if (listed >= 0 && listed != pages.size())
            {
                log.info("chaika archive {} lists {} file(s), and its zip holds {} image(s); going by the zip", id,
                        listed, pages.size());
            }
            var urls = new LinkedHashSet<URI>();
            for (ZipIndex.Entry entry : pages)
            {
                urls.add(pageUri(id, entry));
            }
            return galleryData(id, archive, urls);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e.getMessage(), e);
        }
    }

    static GalleryData galleryData(String id, JsonNode archive, LinkedHashSet<URI> pageUrls)
    {
        var tags = new ArrayList<String>();
        // chaika writes e-hentai's tags with underscores for spaces.
        archive.path("tags").forEach(tag -> tags.add(tag.asText("").replace('_', ' ')));
        return EhTags.galleryData(id, tags, text(archive, "category"))
                .fullTitle(text(archive, "title"))
                .japaneseTitle(text(archive, "title_jpn"))
                .pageUrls(pageUrls)
                .build();
    }

    private JsonNode archiveInfo(String id) throws IOException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api?archive=" + id))
                .timeout(requestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response = RateLimitedHttp.send(client, request, apiPacer,
                Duration.ofMillis(Math.max(0, properties.getApiRequestIntervalMillis())), "chaika");
        if (response.statusCode() == 404)
        {
            throw new GalleryNotFoundException("chaika has no archive " + id + ".");
        }
        if (response.statusCode() != 200)
        {
            throw new IOException("chaika answered HTTP " + response.statusCode() + " for archive " + id + ".");
        }
        JsonNode node;
        try
        {
            node = JSON.readTree(response.body());
        }
        catch (IOException e)
        {
            throw new IOException("chaika's answer for archive " + id + " is not JSON.", e);
        }
        if (node == null || !node.isObject())
        {
            throw new IOException("chaika's answer for archive " + id + " is not a JSON object.");
        }
        return node;
    }

    /** The archive's images in reading order, from its index alone: a few small byte ranges. */
    private List<ZipIndex.Entry> pagesOf(String id) throws IOException
    {
        String archive = archiveUrl(id);
        Ranged tail = range(archive, "bytes=-" + ZipIndex.MAX_TAIL, ZipIndex.MAX_TAIL);
        long total = tail.totalSize();
        ZipIndex.Directory directory = ZipIndex.locate(tail.bytes(), total);
        if (directory.needsZip64Record())
        {
            long at = directory.zip64RecordOffset();
            directory = ZipIndex.zip64Directory(range(archive, "bytes=" + at + "-" + (at + ZipIndex.ZIP64_EOCD_SIZE - 1),
                    ZipIndex.ZIP64_EOCD_SIZE).bytes());
        }
        long tailStart = total - tail.bytes().length;
        byte[] central;
        if (directory.offset() >= tailStart && directory.offset() + directory.size() <= total)
        {
            int from = (int) (directory.offset() - tailStart);
            central = java.util.Arrays.copyOfRange(tail.bytes(), from, from + (int) directory.size());
        }
        else
        {
            if (directory.size() > Integer.MAX_VALUE - 16)
            {
                throw new IOException("chaika archive " + id + " has an index too large to read.");
            }
            central = range(archive, "bytes=" + directory.offset() + "-" + (directory.offset() + directory.size() - 1),
                    directory.size()).bytes();
        }
        var pages = new ArrayList<ZipIndex.Entry>();
        for (ZipIndex.Entry entry : ZipIndex.parseCentralDirectory(central))
        {
            if (entry.directory() || !isPage(entry.name()))
            {
                continue;
            }
            if (entry.encrypted())
            {
                throw new GalleryNotFoundException("chaika archive " + id + " is encrypted (" + entry.name() + ").");
            }
            if (entry.method() != ZipIndex.STORED && entry.method() != ZipIndex.DEFLATED)
            {
                throw new GalleryNotFoundException("chaika archive " + id + " uses a compression this app cannot "
                        + "read (method " + entry.method() + " for " + entry.name() + ").");
            }
            pages.add(entry);
        }
        pages.sort((a, b) -> ZipIndex.NATURAL_ORDER.compare(a.name(), b.name()));
        return pages;
    }

    /** An image the app can show, not a folder, a hidden file or a macOS resource fork. */
    static boolean isPage(String name)
    {
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        return !name.startsWith("__MACOSX/") && !name.contains("/__MACOSX/") && !fileName.startsWith(".")
                && ImageDirectory.isImage(fileName);
    }

    // ---- a page ----------------------------------------------------------------------------------------------

    /** The archive's address, and in the fragment where the page is in it. */
    static URI pageUri(String id, ZipIndex.Entry entry)
    {
        String fragment = "n=" + encode(entry.name()) + "&o=" + entry.localHeaderOffset() + "&c=" + entry.compressedSize()
                + "&u=" + entry.size() + "&m=" + entry.method() + "&crc=" + Long.toHexString(entry.crc());
        return URI.create("https://panda.chaika.moe/archive/" + id + "/download/#" + fragment);
    }

    /** The entry a page address names; the archive id comes from its path. */
    static PageRef pageRef(URI uri) throws IOException
    {
        Matcher id = Pattern.compile("/archive/(\\d{1,10})/download/?").matcher(StringUtils.defaultString(uri.getRawPath()));
        String fragment = uri.getRawFragment();
        if (!id.matches() || fragment == null)
        {
            throw new IOException("Not a chaika page address: " + uri);
        }
        Map<String, String> fields = new HashMap<>();
        for (String pair : fragment.split("&"))
        {
            int eq = pair.indexOf('=');
            if (eq > 0)
            {
                fields.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        try
        {
            var entry = new ZipIndex.Entry(fields.get("n"), Integer.parseInt(fields.get("m")),
                    Long.parseLong(fields.get("c")), Long.parseLong(fields.get("u")), Long.parseLong(fields.get("crc"), 16),
                    Long.parseLong(fields.get("o")), false, false);
            if (entry.name() == null)
            {
                throw new IOException("Not a chaika page address: " + uri);
            }
            return new PageRef(id.group(1), entry);
        }
        catch (RuntimeException e)
        {
            throw new IOException("Not a chaika page address: " + uri, e);
        }
    }

    record PageRef(String archiveId, ZipIndex.Entry entry)
    {
    }

    @Override
    public String pageExtension(URI url)
    {
        try
        {
            return PageDownloader.extensionOf(pageRef(url).entry().name());
        }
        catch (IOException e)
        {
            return "";
        }
    }

    /**
     * One range from the entry's local header to the end of its data, guessing the header's extra field; a second
     * one only when the guess was short.
     */
    @Override
    public byte[] downloadPage(URI url) throws IOException
    {
        PageRef ref = pageRef(url);
        ZipIndex.Entry entry = ref.entry();
        if (entry.compressedSize() > properties.getMaxEntryBytes() || entry.size() > properties.getMaxEntryBytes())
        {
            throw new IOException(entry.name() + " is larger than a page may be.");
        }
        String archive = archiveUrl(ref.archiveId());
        long nameBytes = entry.name().getBytes(StandardCharsets.UTF_8).length;
        long guess = ZipIndex.LOCAL_HEADER_SIZE + nameBytes + LOCAL_EXTRA_SLACK + entry.compressedSize();
        long start = entry.localHeaderOffset();
        byte[] fetched = range(archive, "bytes=" + start + "-" + (start + guess - 1), guess).bytes();
        int dataStart = ZipIndex.dataStart(fetched);
        if (dataStart < 0)
        {
            throw new IOException("chaika sent too little of " + entry.name() + ".");
        }
        byte[] data;
        if (dataStart + entry.compressedSize() <= fetched.length)
        {
            data = java.util.Arrays.copyOfRange(fetched, dataStart, (int) (dataStart + entry.compressedSize()));
        }
        else
        {
            long from = start + dataStart;
            data = range(archive, "bytes=" + from + "-" + (from + entry.compressedSize() - 1),
                    entry.compressedSize()).bytes();
        }
        return ZipIndex.content(entry, data, properties.getMaxEntryBytes());
    }

    // ---- plumbing --------------------------------------------------------------------------------------------

    private record Ranged(byte[] bytes, long totalSize)
    {
    }

    /**
     * Part of an archive. <b>A server that ignores the range is refused</b> and its answer dropped unread, or every
     * page would download the whole archive.
     */
    private Ranged range(String url, String range, long maxBytes) throws IOException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout())
                .header("Range", range)
                .GET()
                .build();
        HttpResponse<InputStream> response = RateLimitedHttp.sendStreaming(client, request, archivePacer,
                Duration.ofMillis(Math.max(0, properties.getPageRequestIntervalMillis())), "chaika");
        try (InputStream body = response.body())
        {
            int status = response.statusCode();
            if (status == 404)
            {
                throw new GalleryNotFoundException("chaika has no archive at " + url + ".");
            }
            if (status != 206)
            {
                throw new IOException("chaika answered HTTP " + status + " instead of a part of the archive "
                        + url + (status == 200 ? " (it ignored the byte range)" : "") + ".");
            }
            Matcher sent = CONTENT_RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
            if (!sent.find())
            {
                throw new IOException("chaika sent part of " + url + " without saying the archive's size.");
            }
            long first = Long.parseLong(sent.group(1));
            long last = Long.parseLong(sent.group(2));
            long total = Long.parseLong(sent.group(3));
            // Another part than the one asked for would be parsed as if it were it; the index has no checksum.
            Matcher asked = REQUESTED_START.matcher(range);
            boolean asRequested = asked.find() && (asked.group(1).isEmpty()
                    ? last == total - 1 : first == Long.parseLong(asked.group(1)));
            if (!asRequested || last < first)
            {
                throw new IOException("chaika sent bytes " + first + "-" + last + " of " + url + " when asked for "
                        + range + ".");
            }
            int expected = (int) Math.min(Math.min(maxBytes, last - first + 1), Integer.MAX_VALUE - 16);
            byte[] bytes = body.readNBytes(expected);
            if (bytes.length != expected)
            {
                throw new IOException("chaika sent " + bytes.length + " of the " + expected + " bytes it announced for "
                        + url + ".");
            }
            return new Ranged(bytes, total);
        }
    }

    private String archiveUrl(String id)
    {
        return baseUrl() + "/archive/" + id + "/download/";
    }

    private String baseUrl()
    {
        return StringUtils.removeEnd(StringUtils.strip(properties.getBaseUrl()), "/");
    }

    private Duration requestTimeout()
    {
        return Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds()));
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

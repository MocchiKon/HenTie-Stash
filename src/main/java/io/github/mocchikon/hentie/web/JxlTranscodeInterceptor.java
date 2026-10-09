package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.JxlDelivery;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.compress.JxlTranscoder;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Sends a stored {@code .jxl} page as PNG to a browser that cannot display JPEG XL.
 *
 * <p><b>The URL does not change</b>: rewriting URLs would make every place that builds one know about
 * browser support, and a link shared between two browsers would be wrong for one of them.
 *
 * <p>An interceptor, not a filter, so it runs after Spring Security and no unauthenticated request reaches
 * a decoder.
 *
 * <p>Every {@code .jxl} response carries {@code Vary: Cookie}, the pass-through one too, or a browser that
 * lost JPEG XL support would keep showing its cached {@code .jxl}. The {@code private} lifetime is set in
 * {@code WebConfig}, because the resource handler overwrites {@code Cache-Control}.
 */
@Component
@RequiredArgsConstructor
public class JxlTranscodeInterceptor implements HandlerInterceptor
{
    /** Set by {@code app.js} after it has tried to decode a two-pixel JPEG XL. */
    public static final String SUPPORT_COOKIE = "jxl";

    /**
     * Shared with {@code WebConfig} so both variants expire together. Short because the Settings delivery
     * dropdown changes the body with no request header changing: this is how long that change takes to
     * reach a browser.
     */
    public static final Duration CACHE_TTL = Duration.ofHours(1);

    private final SettingsService settingsService;
    private final JxlTranscoder transcoder;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException
    {
        PageRef page = pageOf(request);
        if (page == null)
        {
            return true;
        }
        // Before deciding: this URL has two possible bodies either way. The resource handler leaves Vary alone.
        response.setHeader(HttpHeaders.VARY, HttpHeaders.COOKIE);
        if (!transcodeFor(request))
        {
            return true;
        }
        // Named before any decode, so a 304 or a HEAD never costs a djxl run.
        Optional<Path> entry = transcoder.cacheEntry(page.chapterId(), page.filename());
        if (entry.isEmpty())
        {
            // The resource handler gives a missing page the usual 404.
            return true;
        }
        if (ifNoneMatch(request, etagOf(entry.get())))
        {
            cacheHeaders(response, entry.get());
            response.setStatus(HttpStatus.NOT_MODIFIED.value());
            return false;
        }
        if (HttpMethod.HEAD.matches(request.getMethod()))
        {
            cacheHeaders(response, entry.get());
            response.setContentType(MediaType.IMAGE_PNG_VALUE);
            // The length is only known once decoded; a HEAD is not worth a decode to learn it.
            if (transcoder.isWhole(entry.get()))
            {
                response.setContentLengthLong(Files.size(entry.get()));
            }
            return false;
        }
        Optional<Path> png = transcoder.pngFor(page.chapterId(), page.filename());
        if (png.isEmpty())
        {
            // Serve the stored JPEG XL; the browser shows a broken image and the transcoder has logged why.
            return true;
        }
        write(png.get(), response);
        return false;
    }

    /**
     * In {@code AUTO} an unknown client is assumed unable: that always renders, while guessing "supported"
     * would show a first-time visitor broken thumbnails.
     */
    private boolean transcodeFor(HttpServletRequest request)
    {
        JxlDelivery delivery = settingsService.getJxlDelivery();
        return switch (delivery)
        {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> !"1".equals(cookie(request));
        };
    }

    private static String cookie(HttpServletRequest request)
    {
        Cookie[] cookies = request.getCookies();
        if (cookies == null)
        {
            return null;
        }
        for (Cookie cookie : cookies)
        {
            if (cookie.getName().equals(SUPPORT_COOKIE))
            {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** {@code /data/{chapterId}/{filename}.jxl}, or null for anything else under {@code /data}. */
    private static PageRef pageOf(HttpServletRequest request)
    {
        if (!HttpMethod.GET.matches(request.getMethod()) && !HttpMethod.HEAD.matches(request.getMethod()))
        {
            return null;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String[] parts = path.split("/");
        // ["", "data", "<chapterId>", "<filename>"]
        if (parts.length != 4 || !parts[1].equals("data") || !JxlTranscoder.isJxl(parts[3]))
        {
            return null;
        }
        try
        {
            return new PageRef(Integer.parseInt(parts[2]), parts[3]);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static void write(Path png, HttpServletResponse response) throws IOException
    {
        cacheHeaders(response, png);
        response.setContentType(MediaType.IMAGE_PNG_VALUE);
        response.setContentLengthLong(Files.size(png));
        try (OutputStream out = response.getOutputStream())
        {
            Files.copy(png, out);
        }
    }

    /**
     * <b>No {@code Last-Modified}</b>: it would be the decode time, later than the page's, so after switching
     * delivery to <i>Never</i> the resource handler would answer the PNG's revalidation with 304 for ever.
     * Each variant carries a validator only its own path honours.
     */
    private static void cacheHeaders(HttpServletResponse response, Path entry)
    {
        // Vary: Cookie was set by preHandle.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=" + CACHE_TTL.toSeconds());
        response.setHeader(HttpHeaders.ETAG, etagOf(entry));
    }

    /**
     * The entry's name records the page version, so no hashing is needed. Weak, because a re-decode after a
     * prune need not be byte-identical.
     */
    private static String etagOf(Path entry)
    {
        return "W/\"" + entry.getFileName() + "\"";
    }

    /** Weak comparison, as {@code If-None-Match} requires. */
    private static boolean ifNoneMatch(HttpServletRequest request, String etag)
    {
        String header = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (header == null)
        {
            return false;
        }
        ETag ours = ETag.create(etag);
        return ETag.parse(header).stream().anyMatch(tag -> tag.isWildcard() || tag.compare(ours, false));
    }

    private record PageRef(int chapterId, String filename) {}
}

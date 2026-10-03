package io.github.mocchikon.hentie.scrapper.hitomi;

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
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static io.github.mocchikon.hentie.scrapper.JsonFields.text;

/**
 * hitomi.la, entirely through gallery-dl: hitomi computes its image addresses from a script that changes every few
 * hours ({@code gg.js}), and gallery-dl follows it.
 * <p>
 * <b>hitomi serves no originals</b>, only webp (or avif) re-encodes, so "Download originals" means nothing here,
 * and there is no login, so no cookies are read: reading a browser's cookie store can fail, for nothing.
 */
@Component
@RequiredArgsConstructor
public class HitomiDownloader implements GalleryDlDownloader
{
    static final String PREFIX = "hitomi";

    /**
     * Any of hitomi's gallery pages, with or without the title slug before the id: {@code /doujinshi/title-123.html},
     * {@code /galleries/123.html}, {@code /reader/123.html#4}. Anime galleries are accepted only to be refused with a
     * reason: they are videos.
     */
    private static final Pattern GALLERY_URL = Pattern.compile(
            "(?:https?://)?(?:[a-z0-9-]+\\.)*hitomi\\.la/(?:manga|doujinshi|cg|gamecg|imageset|galleries|reader|anime)"
                    + "/(?:[^/?#]*-)?0*(\\d{1,12})(?:\\.html)?(?:[?#].*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PREFIXED_ID = Pattern.compile(PREFIX + ":\\s*0*(\\d{1,12})", Pattern.CASE_INSENSITIVE);

    /** hitomi's gallery types as the categories nhentai and e-hentai use, so the three share one category. */
    private static final Map<String, String> CATEGORIES = Map.of(
            "doujinshi", "doujinshi",
            "manga", "manga",
            "artistcg", "artist cg",
            "gamecg", "game cg",
            "imageset", "image set");

    private final GalleryDl galleryDl;

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

    /** The one gallery address both gallery-dl and a browser understand without the title slug. */
    @Override
    public String link(String resourceId)
    {
        return "https://hitomi.la/galleries/" + resourceId + ".html";
    }

    @Override
    public String pageLinkTemplate()
    {
        return link(RESOURCE_ID);
    }

    @Override
    public String linkExample()
    {
        return "https://hitomi.la/doujinshi/title-english-123456.html";
    }

    private static String idIn(String link)
    {
        return DataDownloader.matchLink(link, PREFIXED_ID, GALLERY_URL).map(m -> m.group(1)).orElse(null);
    }

    private static void requireId(String id)
    {
        if (id == null || !id.matches("\\d{1,12}"))
        {
            throw new GalleryNotFoundException("Not a hitomi gallery id: '" + id + "'");
        }
    }

    /** One gallery-dl run, which reads the gallery's record and nothing else. */
    @Override
    public GalleryData downloadGalleryInfo(String id)
    {
        requireId(id);
        try
        {
            return galleryData(id, galleryDl.metadata(link(id), List.of()));
        }
        catch (GalleryDlException e)
        {
            throw e.forWorker();
        }
        catch (IOException e)
        {
            throw new java.io.UncheckedIOException(e.getMessage(), e);
        }
    }

    /**
     * gallery-dl's metadata as it formats it: tags already carry hitomi's ♀/♂ ({@code "Big Breasts ♀"}). A gallery
     * without a language (game CGs and image sets often have none) is Japanese, as on e-hentai, or the import would
     * refuse it for good and it could never be downloaded.
     */
    static GalleryData galleryData(String id, JsonNode gallery)
    {
        String type = StringUtils.defaultString(text(gallery, "type")).toLowerCase(Locale.ROOT);
        if (type.equals("anime"))
        {
            throw new PermanentDownloadException("hitomi gallery " + id + " is an anime gallery: a video, not pages.");
        }
        Set<String> categories = new LinkedHashSet<>();
        if (!type.isEmpty())
        {
            categories.add(CATEGORIES.getOrDefault(type, type));
        }
        return GalleryData.builder()
                .id(id)
                .fullTitle(text(gallery, "title"))
                .japaneseTitle(text(gallery, "title_jpn"))
                .language(Objects.requireNonNullElse(text(gallery, "language"), EhTags.DEFAULT_LANGUAGE))
                .tags(texts(gallery.path("tags")))
                .artists(texts(gallery.path("artist")))
                .groups(texts(gallery.path("group")))
                .parodies(texts(gallery.path("parody")))
                .characters(texts(gallery.path("characters")))
                .categories(categories)
                .pageCount(Math.max(0, gallery.path("count").asInt(0)))
                .build();
    }

    /**
     * {@code --sleep}: gallery-dl reads hitomi's gallery with one request, so its request delay would never come
     * between two images; this one comes before each image download.
     */
    @Override
    public GalleryDl.Outcome downloadPages(String resourceId, GalleryDlOptions options, SortedSet<Integer> pages,
                                           Path folder, GalleryDl.PageSink sink) throws IOException
    {
        requireId(resourceId);
        try
        {
            return galleryDl.download(link(resourceId), List.of("--sleep", options.delay(), "-o", "format=webp"),
                    pages, folder, sink);
        }
        catch (GalleryDlException e)
        {
            throw e.forWorker();
        }
    }

    private static Set<String> texts(JsonNode array)
    {
        var values = new LinkedHashSet<String>();
        array.forEach(value ->
        {
            String text = value.isTextual() ? StringUtils.stripToNull(value.textValue()) : null;
            if (text != null)
            {
                values.add(text);
            }
        });
        return values;
    }
}

package io.github.mocchikon.hentie.service;

import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import io.github.mocchikon.hentie.config.CacheConfig;

/**
 * A memo over {@link ImageDirectory#list(int)}, kept separate so a call that may return a remembered
 * answer is visible in the type it depends on. It exists because a search grid asks for one thumbnail per
 * card, and a {@code readdir} per card costs far more than one {@code stat}.
 * <p>
 * <b>The listing is validated against the directory's modification time, not merely expired</b>, because
 * the scraper writes pages without the app knowing. Overwriting a file in place does not change that time,
 * but the listing is still correct then.
 */
@Component
public class ImageDirectoryCache
{
    /**
     * How long a directory timestamp must predate a listing before an <i>unchanged</i> timestamp proves
     * nothing changed. File times are coarse (FAT stores whole 2 s), so a write in the same tick would
     * leave the timestamp identical.
     */
    static final long CLOCK_GRANULARITY_MS = 2_100;

    private final ImageDirectory imageDirectory;
    private final CacheManager cacheManager;

    public ImageDirectoryCache(ImageDirectory imageDirectory, CacheManager cacheManager)
    {
        this.imageDirectory = imageDirectory;
        this.cacheManager = cacheManager;
    }

    /** Ordered image filenames (page 1 = lowest number). Empty when the directory is missing. */
    public List<String> filenames(int chapterId)
    {
        // Timestamp BEFORE listing: a file added in between then shows up as a newer timestamp on the next
        // read. Read afterwards, the stored timestamp would be newer than the listing it labels.
        FileTime modified = imageDirectory.lastModified(chapterId);
        long readAt = System.currentTimeMillis();
        Cache cache = cache();
        var cached = cache == null ? null : cache.get(chapterId, Listing.class);
        if (cached != null && cached.stillValid(modified))
        {
            return cached.names();
        }
        List<String> names = imageDirectory.list(chapterId);
        if (cache != null)
        {
            cache.put(chapterId, new Listing(modified, readAt, names));
        }
        return names;
    }

    public void evict(int chapterId)
    {
        Cache cache = cache();
        if (cache != null)
        {
            cache.evict(chapterId);
        }
    }

    private Cache cache()
    {
        return cacheManager.getCache(CacheConfig.IMAGE_LIST);
    }

    /** {@code readAtMillis} only decides whether an unchanged timestamp proves anything ({@link #CLOCK_GRANULARITY_MS}). */
    private record Listing(FileTime directoryModified, long readAtMillis, List<String> names)
    {
        boolean stillValid(FileTime current)
        {
            if (!Objects.equals(directoryModified, current))
            {
                return false;   // an entry was added, removed or renamed (or the directory appeared/vanished)
            }
            if (current == null)
            {
                return true;    // still no directory, so no timestamp to be imprecise about
            }
            return readAtMillis - current.toMillis() >= CLOCK_GRANULARITY_MS;
        }
    }
}

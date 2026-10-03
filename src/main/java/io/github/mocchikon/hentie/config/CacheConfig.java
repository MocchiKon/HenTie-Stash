package io.github.mocchikon.hentie.config;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Eviction keeps the caches correct; the TTL is only a safety net. Every cache named here must be filled by
 * its owning path ({@code CacheUsageIT}), so none silently becomes write-only.
 */
@Configuration
@EnableCaching
public class CacheConfig
{
    /** A read cache for the UI, never a source of ids to write: it can hold rows of a rolled-back transaction. */
    public static final String METADATA = "metadata";
    /** Validated against the directory's modification time on every read, since images appear without the app. */
    public static final String IMAGE_LIST = "imageList";
    public static final String LANGUAGES = "languages";
    /** Lets a broad search pay its count once while the user pages. Kept correct by eviction on every write. */
    public static final String SEARCH_COUNT = "searchCount";

    @Bean
    public CacheManager cacheManager()
    {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofMinutes(10)));
        return manager;
    }

    /** Runs an uncached count beside the content query, so a first search waits for the slower one, not both. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService searchExecutor()
    {
        int threads = Math.clamp(Runtime.getRuntime().availableProcessors(), 2, 4);
        return Executors.newFixedThreadPool(threads);
    }
}

package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Programmatic eviction of the search totals, for self-invoked paths where a {@code @CacheEvict} would not fire,
 * and to evict only on a real change.
 */
@Component
@RequiredArgsConstructor
public class SearchCountCache
{
    private final CacheManager cacheManager;

    public void clear()
    {
        Cache cache = cacheManager.getCache(CacheConfig.SEARCH_COUNT);
        if (cache != null)
        {
            cache.clear();
        }
    }
}

package io.github.mocchikon.hentie;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.transaction.annotation.Transactional;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Tag;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.SearchService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import static org.assertj.core.api.Assertions.*;

/**
 * Every cache in {@link CacheConfig} is actually <b>filled</b> by the path that owns it. A cache whose
 * {@code @Cacheable} is self-invoked is silently dead - reads stay correct, just uncached - so only an
 * assertion on the cache itself catches it. Each test names the exact key production writes under, so a
 * key that changes shape fails too.
 */
@SpringBootTest
@Transactional
class CacheUsageIT
{
    @Autowired CacheManager cacheManager;
    @Autowired MetadataService metadataService;
    @Autowired SearchService searchService;
    @Autowired ImageService imageService;
    @Autowired TagRepository tagRepository;
    @Autowired ChapterRepository chapterRepository;
    @PersistenceContext EntityManager em;

    /**
     * Also after: these caches are process-wide and get filled from rows the rollback discards, so left
     * behind they would name rows that do not exist and break a later suite far from the cause.
     */
    @BeforeEach
    @AfterEach
    void clearAllCaches()
    {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    /** Filled by {@code MetadataCatalog.all}, keyed by the {@link MetadataType} itself. */
    @Test
    void shouldFillTheMetadataCacheWhenAutocompleting()
    {
        // GIVEN a tag to find and an empty cache.
        Tag tag = new Tag();
        tag.setName("cache-usage-tag");
        tagRepository.save(tag);
        em.flush();
        assertThat(cacheManager.getCache(CacheConfig.METADATA).get(MetadataType.TAG)).isNull();

        // WHEN the UI reads the list.
        assertThat(metadataService.autocomplete(MetadataType.TAG, "cache-usage", 10))
                .extracting("label").containsExactly("cache-usage-tag");

        // THEN the list was remembered under that type.
        var cached = cacheManager.getCache(CacheConfig.METADATA).get(MetadataType.TAG);
        assertThat(cached).isNotNull();
        assertThat((List<?>) cached.get()).isNotEmpty();
    }

    /** Filled by {@code SearchService.languages()} - a no-argument {@code @Cacheable}, so the key is empty. */
    @Test
    void shouldFillTheLanguagesCacheWhenListingLanguages()
    {
        // GIVEN a chapter in a language, so the list is not empty either way.
        chapter("cache-usage-languages", "English");
        em.flush();
        assertThat(cacheManager.getCache(CacheConfig.LANGUAGES).get(SimpleKey.EMPTY)).isNull();

        // WHEN
        List<String> languages = searchService.languages();

        // THEN
        assertThat(languages).contains("English");
        var cached = cacheManager.getCache(CacheConfig.LANGUAGES).get(SimpleKey.EMPTY);
        assertThat(cached).isNotNull();
        assertThat((List<?>) cached.get()).isEqualTo(languages);
    }

    /** Filled by {@code SearchService}'s count, keyed by {@link SearchCriteria#filterKey()}. */
    @Test
    void shouldFillTheSearchCountCacheWhenSearching()
    {
        // GIVEN two chapters and an empty cache.
        chapter("cache-usage-count-1", "English");
        chapter("cache-usage-count-2", "English");
        em.flush();
        var criteria = new SearchCriteria();
        criteria.setTitle("cache-usage-count");
        assertThat(cacheManager.getCache(CacheConfig.SEARCH_COUNT).get(criteria.filterKey())).isNull();

        // WHEN
        var page = searchService.search(criteria);

        // THEN the exact total was remembered under this filter, so paging it again is free.
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(cacheManager.getCache(CacheConfig.SEARCH_COUNT).get(criteria.filterKey(), Long.class))
                .isEqualTo(2L);
    }

    /** Filled by {@code ImageDirectoryCache.filenames}, keyed by the chapter id. */
    @Test
    void shouldFillTheImageListCacheWhenReadingPages()
    {
        // GIVEN a chapter (its directory need not exist - an empty listing is cached just the same).
        int chapterId = chapter("cache-usage-images", "English");
        em.flush();
        assertThat(cacheManager.getCache(CacheConfig.IMAGE_LIST).get(chapterId)).isNull();

        // WHEN
        assertThat(imageService.pageUrls(chapterId)).isEmpty();

        // THEN
        assertThat(cacheManager.getCache(CacheConfig.IMAGE_LIST).get(chapterId)).isNotNull();
    }

    private int chapter(String titleFull, String language)
    {
        Chapter c = new Chapter();
        c.setTitle(titleFull);
        c.setTitleFull(titleFull);
        c.setUploadDate(LocalDate.of(2020, 1, 1));
        c.setLanguage(language);
        return chapterRepository.save(c).getId();
    }
}

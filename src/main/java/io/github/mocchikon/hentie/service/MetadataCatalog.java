package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.OptionDto;
import io.github.mocchikon.hentie.entity.Metadata;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The full sorted list of one metadata kind, cached so autocomplete and the Manage filter box filter in
 * memory instead of querying per keystroke.
 *
 * <p><b>It is its own bean so the cache works.</b> {@code @Cacheable} only fires on a call through the
 * proxy; on {@code MetadataService}, next to its callers in the same class, it would never fire.
 */
@Service
public class MetadataCatalog
{
    @PersistenceContext
    private EntityManager em;

    private final CacheManager cacheManager;

    public MetadataCatalog(CacheManager cacheManager)
    {
        this.cacheManager = cacheManager;
    }

    @Cacheable(value = CacheConfig.METADATA, key = "#type")
    @Transactional(readOnly = true)
    public List<OptionDto> all(MetadataType type)
    {
        return queryAll(type);
    }

    /**
     * Uncached. <b>Anything that writes based on this list must use it</b>: {@link #all} may be filled by a
     * transaction that later rolled back, so it can name rows that do not exist.
     */
    @Transactional(readOnly = true)
    public List<OptionDto> allFresh(MetadataType type)
    {
        return queryAll(type);
    }

    /** For callers that evict only when they really created something, which {@code @CacheEvict} cannot express. */
    public void evict(MetadataType type)
    {
        Cache cache = cacheManager.getCache(CacheConfig.METADATA);
        if (cache != null)
        {
            cache.evict(type);
        }
    }

    private List<OptionDto> queryAll(MetadataType type)
    {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<?> cq = cb.createQuery(type.getEntityClass());
        Root<?> root = cq.from(type.getEntityClass());
        cq.orderBy(cb.asc(cb.lower(root.get(type.getNameProperty()))));

        List<OptionDto> options = new ArrayList<>();
        for (Object entity : em.createQuery(cq).getResultList())
        {
            Metadata m = (Metadata) entity;
            options.add(new OptionDto(m.getId(), m.getName()));
        }
        return options;
    }
}

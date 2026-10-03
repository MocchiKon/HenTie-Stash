package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.*;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.spec.ChapterSpecifications;
import io.github.mocchikon.hentie.repository.spec.CompoundSearchQuery;
import io.github.mocchikon.hentie.repository.spec.OperandKey;
import io.github.mocchikon.hentie.repository.spec.SeriesSpecifications;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class SearchService
{
    private final ChapterRepository chapterRepository;
    private final ImageService imageService;
    private final MetadataService metadataService;
    private final SettingsService settingsService;
    private final CacheManager cacheManager;
    private final EntityManagerFactory entityManagerFactory;
    private final ExecutorService searchExecutor;
    private final TitleSearchIndex titleSearchIndex;
    private final AppProperties appProperties;

    @PersistenceContext
    private EntityManager em;

    // Deliberately not @Transactional: with no transaction open, the count runs on its own connection
    // in parallel with the content page (see pageOf).
    public Page<CardDto> search(SearchCriteria criteria)
    {
        int page = Math.max(0, criteria.getPage());
        int size = criteria.getSize() <= 0 ? settingsService.getSearchPageSize() : criteria.getSize();
        Pageable pageable = PageRequest.of(page, size, buildSort(criteria));
        TitleDisplayMode titleMode = settingsService.getTitleDisplayMode();

        if (criteria.isSeries())
        {
            Page<Series> result = pageOf(resolveSeries(criteria), pageable);
            Map<Integer, String> thumbnails = seriesThumbnails(result.getContent());
            return result.map(series -> new CardDto(series.getId(), "/series/" + series.getId(),
                    thumbnails.getOrDefault(series.getId(), ImageService.PLACEHOLDER),
                    displayTitle(series.getTitleFull(), series.getTitle(), series.getNativeTitle(), titleMode)));
        }

        Page<Chapter> result = pageOf(resolveChapters(criteria), pageable);
        return result.map(chapter -> new CardDto(chapter.getId(), "/chapter/" + chapter.getId(),
                imageService.thumbnailUrl(chapter.getId()),
                displayTitle(chapter.getTitleFull(), chapter.getTitle(), chapter.getNativeTitle(), titleMode)));
    }

    /** The same cached count the results page shows, so a confirmation cannot disagree with the grid. */
    public long count(SearchCriteria criteria)
    {
        return criteria.isSeries()
                ? cachedCount(resolveSeries(criteria), false)
                : cachedCount(resolveChapters(criteria), false);
    }

    /**
     * An id keyset, so rows a delete skipped can never make the walk revisit them for ever. Selecting
     * {@code Integer} gives the series language facet its {@code LIMIT}ed, early-exiting shape.
     */
    public List<Integer> idsAfter(SearchCriteria criteria, int afterId, int limit)
    {
        String titlePhrase = resolveTitlePhrase(criteria);
        return criteria.isSeries()
                ? idsAfter(Series.class, SeriesSpecifications.from(criteria, titlePhrase), afterId, limit)
                : idsAfter(Chapter.class, ChapterSpecifications.from(criteria, titlePhrase), afterId, limit);
    }

    private <T> List<Integer> idsAfter(Class<T> type, Specification<T> spec, int afterId, int limit)
    {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Integer> cq = cb.createQuery(Integer.class);
        Root<T> root = cq.from(type);
        Predicate after = cb.greaterThan(root.get("id"), afterId);
        Predicate predicate = spec.toPredicate(root, cq, cb);
        cq.select(root.get("id"))
                .where(predicate == null ? after : cb.and(predicate, after))
                .orderBy(cb.asc(root.get("id")));
        return em.createQuery(cq).setMaxResults(limit).getResultList();
    }

    /**
     * Rooted on both tables rather than collecting series ids first, so it is one query at any scale. Not
     * cached: it is asked only when "Delete all matching" is pressed.
     */
    public long chapterCountInMatchingSeries(SearchCriteria criteria)
    {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Series> series = cq.from(Series.class);
        Root<Chapter> chapter = cq.from(Chapter.class);
        Predicate linked = cb.equal(chapter.get("series").get("id"), series.get("id"));
        Predicate predicate = SeriesSpecifications.from(criteria, resolveTitlePhrase(criteria)).toPredicate(series, cq, cb);
        cq.select(cb.count(chapter)).where(predicate == null ? linked : cb.and(predicate, linked));
        return em.createQuery(cq).getSingleResult();
    }

    /** Null when there is no title filter or the term is too short for the index; the spec then uses {@code LIKE}. */
    private String resolveTitlePhrase(SearchCriteria criteria)
    {
        if (StringUtils.isBlank(criteria.getTitle()))
        {
            return null;
        }
        return titleSearchIndex.phraseFor(criteria.getTitle()).orElse(null);
    }

    /** Resolved once per search, so its content page and its count run the same predicate. */
    private Resolved<Chapter> resolveChapters(SearchCriteria criteria)
    {
        String titlePhrase = resolveTitlePhrase(criteria);
        Plan plan = plan(criteria, titlePhrase);
        return new Resolved<>(Chapter.class, Chapter::getId,
                ChapterSpecifications.from(criteria, titlePhrase, plan.driver()), criteria, titlePhrase, plan);
    }

    private Resolved<Series> resolveSeries(SearchCriteria criteria)
    {
        String titlePhrase = resolveTitlePhrase(criteria);
        Plan plan = plan(criteria, titlePhrase);
        return new Resolved<>(Series.class, Series::getId,
                SeriesSpecifications.from(criteria, titlePhrase, plan.driver()), criteria, titlePhrase, plan);
    }

    /**
     * Picks the query shapes from how many rows each included value has, probed with a {@code LIMIT}ed count
     * (well under 1 ms a tag, a few ms a title term). No full count first: it would run before the page instead
     * of beside it.
     * <ul>
     *   <li>A small value exists: Criteria, starting from the smallest, the others checked per candidate. The
     *       native merge would read every broad value in full.</li>
     *   <li>Every value is broad, or one metadata value alone is sorted by date with no owner filter:
     *       {@link CompoundSearchQuery} for page and count.</li>
     *   <li>Exclusions only: the native count; the native page for the date order when no row filter is left
     *       (the Criteria page stops after one page, which is better anywhere else).</li>
     * </ul>
     */
    private Plan plan(SearchCriteria criteria, String titlePhrase)
    {
        if (!CompoundSearchQuery.expressible(criteria, titlePhrase))
        {
            return Plan.CRITERIA;
        }
        List<CompoundSearchQuery.Operand> operands = CompoundSearchQuery.operands(criteria, titlePhrase);
        boolean exclusions = CompoundSearchQuery.hasExclusions(criteria);
        if (operands.isEmpty())
        {
            if (!exclusions)
            {
                return Plan.CRITERIA;
            }
            boolean nativeContent = sortBy(criteria) == SortBy.DATE
                    && !CompoundSearchQuery.leavesRowFilters(criteria, titlePhrase);
            return new Plan(null, nativeContent, true, true);
        }
        if (operands.size() == 1 && !exclusions && sortBy(criteria) == SortBy.DATE
                && !CompoundSearchQuery.hasOwnerFilters(criteria) && !OperandKey.TITLE.equals(operands.getFirst().key()))
        {
            // One value by date: its own index gives the page whatever its size. Not a title term: the title
            // index reads rowids backwards slowly, so a rare term is faster from its list (7 ms against 22 ms).
            return new Plan(null, true, true, true);
        }

        int broad = appProperties.getSearch().getLargeListRows();
        CompoundSearchQuery.Operand smallest = null;
        long smallestRows = Long.MAX_VALUE;
        for (CompoundSearchQuery.Operand operand : operands)
        {
            long rows = number(em, CompoundSearchQuery.probe(operand, broad));
            if (rows < smallestRows)
            {
                smallest = operand;
                smallestRows = rows;
            }
        }
        if (smallestRows < broad)
        {
            // A lone small value counts fastest natively: no owner table, no stream beside it to read in full.
            return new Plan(smallest.key(), false, operands.size() == 1 && !exclusions, false);
        }
        return new Plan(null, true, true, true);
    }

    /**
     * Not {@code findAll(spec, pageable)}, which runs the whole predicate twice per page: the count is cached
     * per filter, so paging pays it once.
     * <p>
     * On a cache miss the count runs on its own connection in parallel with the content, so a first broad
     * search waits for {@code max(count, content)} rather than their sum.
     */
    private <T> Page<T> pageOf(Resolved<T> search, Pageable pageable)
    {
        Cache cache = cacheManager.getCache(CacheConfig.SEARCH_COUNT);
        Long cached = cache == null ? null : cache.get(search.criteria().filterKey(), Long.class);
        if (cached != null)
        {
            List<T> content = pageable.getOffset() >= cached ? List.of() : pageContent(search, pageable);
            return new PageImpl<>(content, pageable, cached);
        }

        if (TransactionSynchronizationManager.isActualTransactionActive())
        {
            // A second connection could not see this transaction's uncommitted rows, so count here.
            long total = cachedCount(search, false);
            List<T> content = pageable.getOffset() >= total ? List.of() : pageContent(search, pageable);
            return new PageImpl<>(content, pageable, total);
        }

        CompletableFuture<Long> countFuture =
                CompletableFuture.supplyAsync(() -> cachedCount(search, true), searchExecutor);
        List<T> content = pageContent(search, pageable);
        return new PageImpl<>(content, pageable, countFuture.join());
    }

    private <T> long cachedCount(Resolved<T> search, boolean ownConnection)
    {
        Cache cache = cacheManager.getCache(CacheConfig.SEARCH_COUNT);
        if (cache == null)
        {
            return count(search, ownConnection);
        }
        Long total = cache.get(search.criteria().filterKey(), () -> count(search, ownConnection));
        return total == null ? 0L : total;
    }

    /**
     * {@code ownConnection} is for the executor thread, since the request-bound {@link #em} is not
     * thread-safe. The native and the Criteria count must agree on the total.
     */
    private <T> long count(Resolved<T> search, boolean ownConnection)
    {
        EntityManager countEm = ownConnection ? entityManagerFactory.createEntityManager() : em;
        try
        {
            if (search.plan().nativeCount())
            {
                return number(countEm, CompoundSearchQuery.count(search.criteria(), search.titlePhrase(),
                        search.plan().streamOwnerFilters()));
            }

            CriteriaBuilder cb = countEm.getCriteriaBuilder();
            CriteriaQuery<Long> cq = cb.createQuery(Long.class);
            Root<T> root = cq.from(search.type());
            Predicate predicate = search.spec().toPredicate(root, cq, cb);
            cq.select(cb.count(root));
            if (predicate != null)
            {
                cq.where(predicate);
            }
            return countEm.createQuery(cq).getSingleResult();
        }
        finally
        {
            if (ownConnection)
            {
                countEm.close();
            }
        }
    }

    private <T> List<T> pageContent(Resolved<T> search, Pageable pageable)
    {
        if (search.plan().nativeContent())
        {
            return nativePageContent(search, pageable);
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> cq = cb.createQuery(search.type());
        Root<T> root = cq.from(search.type());
        Predicate predicate = search.spec().toPredicate(root, cq, cb);
        cq.select(root);
        if (predicate != null)
        {
            cq.where(predicate);
        }
        if (pageable.getSort().isSorted())
        {
            cq.orderBy(QueryUtils.toOrders(pageable.getSort(), root, cb));
        }
        TypedQuery<T> q = em.createQuery(cq);
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        return q.getResultList();
    }

    /** The page's ids come in page order from the native query; the rows are then read by primary key. */
    private <T> List<T> nativePageContent(Resolved<T> search, Pageable pageable)
    {
        SearchCriteria criteria = search.criteria();
        List<Integer> ids = numbers(em, CompoundSearchQuery.pageIds(criteria, search.titlePhrase(),
                sortBy(criteria), direction(criteria), pageable.getOffset(), pageable.getPageSize()));
        if (ids.isEmpty())
        {
            return List.of();
        }
        Map<Integer, T> byId = new HashMap<>();
        String jpql = "select e from " + search.type().getSimpleName() + " e where e.id in :ids";
        for (T row : em.createQuery(jpql, search.type()).setParameter("ids", ids).getResultList())
        {
            byId.put(search.idOf().apply(row), row);
        }
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private static jakarta.persistence.Query nativeQuery(EntityManager em, CompoundSearchQuery.Query query)
    {
        jakarta.persistence.Query nativeQuery = em.createNativeQuery(query.sql());
        List<Object> params = query.params();
        for (int i = 0; i < params.size(); i++)
        {
            nativeQuery.setParameter(i + 1, params.get(i));
        }
        return nativeQuery;
    }

    private static long number(EntityManager em, CompoundSearchQuery.Query query)
    {
        return ((Number) nativeQuery(em, query).getSingleResult()).longValue();
    }

    private static List<Integer> numbers(EntityManager em, CompoundSearchQuery.Query query)
    {
        return ((List<?>) nativeQuery(em, query).getResultList()).stream()
                .map(value -> ((Number) value).intValue())
                .toList();
    }

    private String displayTitle(String titleFull, String pretty, String nativeTitle, TitleDisplayMode mode)
    {
        return switch (mode)
        {
            case PRETTY -> StringUtils.isNotBlank(pretty) ? pretty : titleFull;
            case NATIVE -> StringUtils.isNotBlank(nativeTitle) ? nativeTitle : titleFull;
            default -> titleFull;
        };
    }

    private static SortBy sortBy(SearchCriteria criteria)
    {
        return criteria.getSortBy() == null ? SortBy.DATE : criteria.getSortBy();
    }

    private static Sort.Direction direction(SearchCriteria criteria)
    {
        return criteria.getSortDir() == null ? Sort.Direction.DESC : criteria.getSortDir();
    }

    /** {@code DATE} sorts by id, a fast proxy for the upload/created date. */
    private Sort buildSort(SearchCriteria criteria)
    {
        Sort.Direction dir = direction(criteria);
        return switch (sortBy(criteria))
        {
            case SCORE -> Sort.by(new Sort.Order(dir, "score", Sort.NullHandling.NULLS_LAST), new Sort.Order(dir, "id"));
            case PAGE_NUM -> Sort.by(new Sort.Order(dir, "pageNum"), new Sort.Order(dir, "id"));
            case DISK_SIZE -> Sort.by(new Sort.Order(dir, "diskSize"), new Sort.Order(dir, "id"));
            default -> Sort.by(dir, "id");
        };
    }

    @Transactional(readOnly = true)
    public SelectedFilters resolveSelected(SearchCriteria criteria)
    {
        return SelectedFilters.builder()
                .tags(metadataService.resolve(MetadataType.TAG, criteria.getTagIds()))
                .artists(metadataService.resolve(MetadataType.ARTIST, criteria.getArtistIds()))
                .characters(metadataService.resolve(MetadataType.CHARACTER, criteria.getCharacterIds()))
                .parodies(metadataService.resolve(MetadataType.PARODY, criteria.getParodyIds()))
                .groups(metadataService.resolve(MetadataType.GROUP, criteria.getGroupIds()))
                .categories(metadataService.resolve(MetadataType.CATEGORY, criteria.getCategoryIds()))
                .excludedTags(metadataService.resolve(MetadataType.TAG, criteria.getExcludedTagIds()))
                .excludedArtists(metadataService.resolve(MetadataType.ARTIST, criteria.getExcludedArtistIds()))
                .excludedCharacters(metadataService.resolve(MetadataType.CHARACTER, criteria.getExcludedCharacterIds()))
                .excludedParodies(metadataService.resolve(MetadataType.PARODY, criteria.getExcludedParodyIds()))
                .excludedGroups(metadataService.resolve(MetadataType.GROUP, criteria.getExcludedGroupIds()))
                .excludedCategories(metadataService.resolve(MetadataType.CATEGORY, criteria.getExcludedCategoryIds()))
                .build();
    }

    @Cacheable(CacheConfig.LANGUAGES)
    @Transactional(readOnly = true)
    public List<String> languages()
    {
        return chapterRepository.findDistinctLanguages();
    }

    /** One query for the whole page: never one per card, and never loading a series's chapters. */
    private Map<Integer, String> seriesThumbnails(List<Series> series)
    {
        if (series.isEmpty())
        {
            return Map.of();
        }
        List<Integer> ids = series.stream().map(Series::getId).toList();
        Map<Integer, String> thumbnails = new HashMap<>();
        for (Object[] row : chapterRepository.findCoverChapterIdBySeriesIds(ids))
        {
            int seriesId = ((Number) row[0]).intValue();
            int chapterId = ((Number) row[1]).intValue();
            thumbnails.put(seriesId, imageService.thumbnailUrl(chapterId));
        }
        return thumbnails;
    }

    /**
     * {@code driver}: the included value a Criteria query starts from. {@code nativeContent} /
     * {@code nativeCount}: run the page or the count as a {@link CompoundSearchQuery}.
     * {@code streamOwnerFilters}: let the native count merge a single status or language in as a stream.
     */
    private record Plan(OperandKey driver, boolean nativeContent, boolean nativeCount, boolean streamOwnerFilters)
    {
        static final Plan CRITERIA = new Plan(null, false, false, false);
    }

    private record Resolved<T>(Class<T> type, Function<T, Integer> idOf, Specification<T> spec,
                               SearchCriteria criteria, String titlePhrase, Plan plan)
    {
    }
}

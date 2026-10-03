package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.OptionDto;
import io.github.mocchikon.hentie.entity.Metadata;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Add/rename/remove/merge of the six metadata kinds, generic over {@link MetadataType}.
 *
 * <p>Never add a cached list method here: a {@code @Cacheable} called from its own class is not proxied.
 * The cached list is {@link MetadataCatalog}.
 *
 * <p>Delete, merge and rename can leave a <b>rule</b> ({@link MetadataRuleService}), or the next import
 * would bring the name back. Rules are applied only in {@link #resolveOrCreate}, where names become rows.
 */
@Service
public class MetadataService
{
    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

    @PersistenceContext
    private EntityManager em;

    private final MetadataCatalog catalog;

    private final MetadataRuleService ruleService;

    public MetadataService(MetadataCatalog catalog, MetadataRuleService ruleService)
    {
        this.catalog = catalog;
        this.ruleService = ruleService;
    }

    /** Returned rather than thrown, so the Manage page can say what stood in the way. */
    public sealed interface Refusal
    {
        MetadataType type();

        /** Normalized ({@link #normalize}). */
        String name();
    }

    /** {@code name} is the rule's own name, not what the user typed; {@code targetName} is null for a blocking rule. */
    public record RuleConflict(MetadataType type, String name, String targetName) implements Refusal
    {
    }

    /**
     * Checked up front because the UNIQUE index would only throw at <b>commit</b>, too late to explain.
     * Not turned into a merge: that cannot be undone and has its own form.
     */
    public record NameTaken(MetadataType type, String name) implements Refusal
    {
    }

    /** Substring match, capped; an exact match is listed first. */
    public List<OptionDto> autocomplete(MetadataType type, String query, int limit)
    {
        String needle = query == null ? "" : MetadataService.normalize(query);
        return catalog.all(type).stream()
                .filter(o -> needle.isEmpty() || fold(o.getLabel()).contains(needle))
                // The list is already alphabetical; a stable sort floats an exact match to the top.
                .sorted(Comparator.comparing(o -> !o.getLabel().equalsIgnoreCase(needle)))
                .limit(limit)
                .toList();
    }

    /** The most recently added items when {@code query} is blank, else a substring match with an exact hit first. */
    public List<OptionDto> recent(MetadataType type, String query, int limit)
    {
        String needle = query == null ? "" : MetadataService.normalize(query);
        Comparator<OptionDto> order = needle.isEmpty()
                ? Comparator.comparing(OptionDto::getId, Comparator.reverseOrder())
                : Comparator.<OptionDto, Boolean>comparing(o -> !o.getLabel().equalsIgnoreCase(needle))
                            .thenComparing(o -> fold(o.getLabel()));
        return catalog.all(type).stream()
                .filter(o -> needle.isEmpty() || fold(o.getLabel()).contains(needle))
                .sorted(order)
                .limit(limit)
                .toList();
    }

    /** Preserves the requested order. */
    public List<OptionDto> resolve(MetadataType type, Collection<Integer> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            return List.of();
        }
        Set<Integer> wanted = new HashSet<>(ids);
        List<OptionDto> all = catalog.all(type);
        List<OptionDto> result = new ArrayList<>();
        for (Integer id : ids)
        {
            all.stream().filter(o -> o.getId().equals(id) && wanted.contains(id))
                    .findFirst().ifPresent(result::add);
        }
        return result;
    }

    /**
     * Refused when a rule rules the name out: such a row would be stripped from every import, so it would
     * look present and behave as if it were not.
     *
     * @return the rule that refused the name, or empty when none did (added, blank or already there)
     */
    @Transactional
    @CacheEvict(value = CacheConfig.METADATA, key = "#type")
    public Optional<RuleConflict> add(MetadataType type, String name)
    {
        if (StringUtils.isBlank(name) || existsByName(type, name))
        {
            return Optional.empty();
        }
        Optional<RuleConflict> conflict = conflictFor(type, name);
        if (conflict.isPresent())
        {
            return conflict;
        }
        try
        {
            Metadata entity = (Metadata) type.getEntityClass().getDeclaredConstructor().newInstance();
            entity.setName(normalize(name));
            em.persist(entity);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Cannot create " + type, e);
        }
        return Optional.empty();
    }

    /**
     * The one spelling a name is stored in. Every lookup folds case anyway, so storing the folded name makes
     * the chips and search agree with matching.
     */
    public static String normalize(String name)
    {
        return (name == null) ? null : fold(name.trim());
    }

    /**
     * {@code Locale.ROOT}, not the default: under a Turkish locale {@code "Big Sister"} folds to
     * {@code "bıg sıster"}, which would miss the stored row and fork a duplicate.
     */
    private static String fold(String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * Refused when another item holds the name, or a rule rules it out - except a rule pointing <i>at this
     * row</i>, which the rename fulfils, so it is dropped as redundant.
     *
     * @return what refused the new name, or empty when nothing did (renamed, or blank)
     */
    @Transactional
    @CacheEvict(value = CacheConfig.METADATA, key = "#type")
    public Optional<Refusal> rename(MetadataType type, Integer id, String newName, boolean createRule)
    {
        if (StringUtils.isBlank(newName))
        {
            return Optional.empty();
        }
        Object entity = em.find(type.getEntityClass(), id);
        if (entity == null)
        {
            return Optional.empty();
        }
        String normalized = normalize(newName);
        // Checked before the redundant rule is dropped, so a refused rename leaves nothing behind. Only a
        // DIFFERENT row counts: renaming onto its own name (in any case) is allowed. A list, because
        // unfolded old data can hold two rows for one name.
        if (idsOfName(type, normalized).stream().anyMatch(other -> !other.equals(id)))
        {
            return Optional.of(new NameTaken(type, normalized));
        }
        var standing = ruleService.ruleFor(type, newName);
        if (standing.isPresent() && !id.equals(standing.get().getTargetId()))
        {
            return standing.map(rule -> new RuleConflict(type, rule.getSourceNameLower(), ruleService.targetName(type, rule)));
        }
        standing.ifPresent(rule -> ruleService.delete(rule.getId()));

        String oldName = ((Metadata) entity).getName();
        ((Metadata) entity).setName(normalized);
        em.merge(entity);
        // Not for a change of case or spacing only: the rule would rule out the row's own name.
        if (createRule && !oldName.equalsIgnoreCase(normalized))
        {
            ruleService.recordRewrite(type, oldName, id);
        }
        return Optional.empty();
    }

    private Optional<RuleConflict> conflictFor(MetadataType type, String name)
    {
        return ruleService.ruleFor(type, name)
                .map(rule -> new RuleConflict(type, rule.getSourceNameLower(), ruleService.targetName(type, rule)));
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.METADATA, key = "#type"),
            @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    })
    public void remove(MetadataType type, Integer id, boolean createRule)
    {
        Object found = em.find(type.getEntityClass(), id);
        if (found == null)
        {
            return;
        }
        // Read before the row goes: the rule needs it.
        String name = ((Metadata) found).getName();

        deleteLinks(type.getChapterJoinTable(), type.getFkColumn(), id);
        deleteLinks(type.getSeriesJoinTable(), type.getFkColumn(), id);
        deleteLinks(type.getSeriesEffectiveJoinTable(), type.getFkColumn(), id);
        // The native deletes bypassed Hibernate; clear so no managed entity still references this row.
        em.flush();
        em.clear();
        Object entity = em.find(type.getEntityClass(), id);
        if (entity != null)
        {
            em.remove(entity);
        }
        // Not conditional on createRule: rules pointing at this item must stop naming a row that is gone.
        ruleService.targetRemoved(type, id);
        if (createRule)
        {
            ruleService.recordBlock(type, name);
        }
    }

    /** Reassigns every link from {@code sourceId} to {@code targetId}, then deletes the source. */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.METADATA, key = "#type"),
            @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    })
    public void merge(MetadataType type, Integer sourceId, Integer targetId, boolean createRule)
    {
        if (sourceId == null || sourceId.equals(targetId))
        {
            return;
        }
        Object found = em.find(type.getEntityClass(), sourceId);
        // A missing target would get every link (and maybe a rule) pointing at an id with no row.
        if (found == null || em.find(type.getEntityClass(), targetId) == null)
        {
            return;
        }
        // Read before the row goes: the rule needs it.
        String sourceName = ((Metadata) found).getName();

        reassignLinks(type.getChapterJoinTable(), "chapter_id", type.getFkColumn(), sourceId, targetId);
        reassignLinks(type.getSeriesJoinTable(), "series_id", type.getFkColumn(), sourceId, targetId);
        reassignLinks(type.getSeriesEffectiveJoinTable(), "series_id", type.getFkColumn(), sourceId, targetId);
        // The native updates bypassed Hibernate; clear so no managed entity still references the source.
        em.flush();
        em.clear();
        Object source = em.find(type.getEntityClass(), sourceId);
        if (source != null)
        {
            em.remove(source);
        }
        // Not conditional on createRule: rules pointing at the source must follow it onto the target.
        ruleService.targetMerged(type, sourceId, targetId);
        if (createRule)
        {
            ruleService.recordRewrite(type, sourceName, targetId);
        }
    }

    /**
     * Ids for imported <i>names</i>, creating what does not exist yet; case-insensitive, so an import joins
     * the existing row instead of forking a near-duplicate.
     * <p>
     * <b>The only place metadata rules are applied</b>, because it is the only seam where a name can become
     * a row. Create/edit forms take ids the user picked, and running rules there could only discard them.
     * <p>
     * <b>Queries the database, not the cached {@link MetadataCatalog#all}</b>: these ids are written to a
     * join table, and the cache can name a row from a rolled-back transaction.
     */
    @Transactional
    public List<Integer> resolveOrCreate(MetadataType type, Collection<String> names)
    {
        if (names == null || names.isEmpty())
        {
            return List.of();
        }
        // Fold, drop blanks and collapse two spellings of one name, keeping the caller's order.
        var wanted = new LinkedHashSet<String>();
        names.stream().map(MetadataService::normalize).filter(StringUtils::isNotBlank).forEach(wanted::add);
        if (wanted.isEmpty())
        {
            return List.of();
        }

        // Rules first: a name the user deleted, merged or renamed away must never reach the create below.
        var verdicts = ruleService.verdicts(type, wanted);
        var toResolve = new LinkedHashSet<>(wanted);
        toResolve.removeAll(verdicts.blocked());
        toResolve.removeAll(verdicts.rewrites().keySet());
        if (!verdicts.isEmpty())
        {
            log.debug("Metadata rules applied to imported {}: {} dropped, {} rewritten",
                    type.getKey(), verdicts.blocked().size(), verdicts.rewrites().size());
        }

        Map<String, Integer> existing = toResolve.isEmpty() ? Map.of() : findByNames(type, toResolve);
        // A set: a rewrite can land on a value the item already carries.
        var ids = new LinkedHashSet<Integer>();
        boolean created = false;
        for (String name : wanted)
        {
            Integer rewritten = verdicts.rewrites().get(name);
            if (rewritten != null)
            {
                ids.add(rewritten);
                continue;
            }
            if (verdicts.blocked().contains(name))
            {
                continue;
            }
            Integer id = existing.get(name);
            if (id == null)
            {
                id = create(type, name);
                created = true;
            }
            ids.add(id);
        }
        if (created)
        {
            evictAll(type);
        }
        return List.copyOf(ids);
    }

    /**
     * Keyed by the folded name; {@code lowerNames} are already {@link #normalize(String) normalized}.
     * <p>
     * <b>The match is decided in Java, never by the database</b>: SQLite's {@code lower()} folds ASCII only,
     * so against a stored {@code "Ärger"} it would miss {@code "ärger"} and fork a duplicate row.
     * <p>
     * The raw-name equality finds every folded row through the UNIQUE index; the {@code lower()} predicate
     * catches older unfolded rows. A non-ASCII capital in an unfolded row is out of reach of SQL;
     * {@link MetadataNameFolder} repairs those.
     */
    private Map<String, Integer> findByNames(MetadataType type, Collection<String> lowerNames)
    {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<?> cq = cb.createQuery(type.getEntityClass());
        Root<?> root = cq.from(type.getEntityClass());
        Path<String> name = root.get(type.getNameProperty());
        cq.where(cb.or(name.in(lowerNames), cb.lower(name).in(lowerNames)));

        Set<String> wanted = Set.copyOf(lowerNames);
        Map<String, Integer> byName = new HashMap<>();
        for (Object entity : em.createQuery(cq).getResultList())
        {
            Metadata m = (Metadata) entity;
            String folded = fold(m.getName());
            if (!wanted.contains(folded))
            {
                continue;   // SQLite's fold matched a name Java's does not
            }
            // Two rows differing only in case are old data; the first wins, so imports do not switch between them.
            byName.putIfAbsent(folded, m.getId());
        }
        return byName;
    }

    private Integer create(MetadataType type, String name)
    {
        try
        {
            Metadata entity = (Metadata) type.getEntityClass().getDeclaredConstructor().newInstance();
            entity.setName(name);
            em.persist(entity);
            em.flush();   // the caller needs the generated id straight away
            return entity.getId();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Cannot create " + type, e);
        }
    }

    /** Not a {@code @CacheEvict}: {@link #resolveOrCreate} evicts only when it created something. */
    private void evictAll(MetadataType type)
    {
        catalog.evict(type);
    }

    private boolean existsByName(MetadataType type, String name)
    {
        return !idsOfName(type, normalize(name)).isEmpty();
    }

    /**
     * The rows writing {@code normalized} would collide with. More than one only in unfolded old data.
     * Decided in Java for the same reason as {@link #findByNames}.
     */
    private List<Integer> idsOfName(MetadataType type, String normalized)
    {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<?> root = cq.from(type.getEntityClass());
        Path<String> name = root.get(type.getNameProperty());
        cq.multiselect(root.<Integer>get("id"), name)
                .where(cb.or(cb.equal(name, normalized), cb.equal(cb.lower(name), normalized)));

        var ids = new ArrayList<Integer>();
        for (Object[] row : em.createQuery(cq).getResultList())
        {
            if (normalized.equals(fold((String) row[1])))
            {
                ids.add((Integer) row[0]);
            }
        }
        return ids;
    }

    private void deleteLinks(String joinTable, String fkColumn, Integer id)
    {
        em.createNativeQuery("delete from " + joinTable + " where " + fkColumn + " = :id") // NOSONAR - safe, no user input
                .setParameter("id", id)
                .executeUpdate();
    }

    private void reassignLinks(String joinTable, String ownerColumn, String fkColumn, Integer sourceId, Integer targetId)
    {
        // Remove source links where the owner already has the target (avoids duplicate keys). No alias on
        // the DELETE target: SQLite rejects `delete from <t> alias`, so the sub-query names the table.
        em.createNativeQuery("delete from " + joinTable + " where " + fkColumn + " = :source "
                        + "and exists (select 1 from " + joinTable + " t2 where t2." + fkColumn + " = :target "
                        + "and t2." + ownerColumn + " = " + joinTable + "." + ownerColumn + ")")
                .setParameter("source", sourceId)
                .setParameter("target", targetId)
                .executeUpdate();
        em.createNativeQuery("update " + joinTable + " set " + fkColumn + " = :target where " + fkColumn + " = :source")
                .setParameter("target", targetId)
                .setParameter("source", sourceId)
                .executeUpdate();
    }
}

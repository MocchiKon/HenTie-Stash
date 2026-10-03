package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.dto.MetadataRuleDto;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.Metadata;
import io.github.mocchikon.hentie.entity.MetadataRule;
import io.github.mocchikon.hentie.repository.MetadataRuleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Standing decisions about metadata names ({@link MetadataRule}). This service never decides <i>whether</i>
 * a rule is recorded - {@link MetadataService} does - only keeps existing rules true.
 */
@Service
public class MetadataRuleService
{
    private static final Logger log = LoggerFactory.getLogger(MetadataRuleService.class);

    /** A long-curated library holds thousands of rules. */
    public static final int PAGE_SIZE = 25;

    @PersistenceContext
    private EntityManager em;

    private final MetadataRuleRepository repository;

    public MetadataRuleService(MetadataRuleRepository repository)
    {
        this.repository = repository;
    }

    /**
     * Keyed by the <b>lower-case</b> name. A rewrite whose target has vanished counts as blocked, rather than
     * writing a dangling id into a join table. {@code targetNames} names every rewrite's target, because a tag's
     * rule turns its {@code ♀}/{@code ♂} versions into the target's ({@link MetadataService#resolveOrCreate}).
     */
    public record Verdicts(Set<String> blocked, Map<String, Integer> rewrites, Map<Integer, String> targetNames)
    {
        static final Verdicts NONE = new Verdicts(Set.of(), Map.of(), Map.of());

        public boolean isEmpty()
        {
            return blocked.isEmpty() && rewrites.isEmpty();
        }
    }

    @Transactional(readOnly = true)
    public Verdicts verdicts(MetadataType type, Collection<String> lowerNames)
    {
        if (lowerNames == null || lowerNames.isEmpty())
        {
            return Verdicts.NONE;
        }
        List<MetadataRule> rules = repository.findByTypeAndSourceNameLowerIn(type, lowerNames);
        if (rules.isEmpty())
        {
            return Verdicts.NONE;
        }
        var targets = new LinkedHashSet<Integer>();
        rules.stream().map(MetadataRule::getTargetId).filter(Objects::nonNull).forEach(targets::add);
        Map<Integer, String> targetNames = namesByIds(type, targets);
        Set<Integer> alive = targetNames.keySet();

        var blocked = new HashSet<String>();
        var rewrites = new HashMap<String, Integer>();
        for (MetadataRule rule : rules)
        {
            if (rule.getTargetId() != null && alive.contains(rule.getTargetId()))
            {
                rewrites.put(rule.getSourceNameLower(), rule.getTargetId());
            }
            else
            {
                if (rule.getTargetId() != null)
                {
                    log.debug("Rule for {} \"{}\" rewrites to an id that no longer exists ({}); dropping the name instead",
                            type.getKey(), rule.getSourceNameLower(), rule.getTargetId());
                }
                blocked.add(rule.getSourceNameLower());
            }
        }
        return new Verdicts(blocked, rewrites, targetNames);
    }

    @Transactional(readOnly = true)
    public Optional<MetadataRule> ruleFor(MetadataType type, String name)
    {
        if (StringUtils.isBlank(name))
        {
            return Optional.empty();
        }
        return repository.findByTypeAndSourceNameLower(type, MetadataService.canonical(type, name));
    }

    @Transactional
    public void recordBlock(MetadataType type, String name)
    {
        upsert(type, name, null);
    }

    /** The target is kept by id, so renaming it later carries the rule along. */
    @Transactional
    public void recordRewrite(MetadataType type, String name, Integer targetId)
    {
        upsert(type, name, targetId);
    }

    /** An upsert, because one name has one fate; replacing keeps re-doing an operation on a name safe. */
    private void upsert(MetadataType type, String name, Integer targetId)
    {
        if (StringUtils.isBlank(name))
        {
            return;
        }
        String folded = MetadataService.canonical(type, name);
        MetadataRule rule = repository.findByTypeAndSourceNameLower(type, folded).orElseGet(MetadataRule::new);
        rule.setType(type);
        rule.setSourceNameLower(folded);
        rule.setTargetId(targetId);
        repository.save(rule);
    }

    /**
     * Turns rules pointing at a removed row into blocking ones. Runs <b>whether or not</b> the user asked for
     * a rule: it is not a new decision, only keeps the standing ones from naming a row that is gone.
     */
    @Transactional
    public void targetRemoved(MetadataType type, Integer targetId)
    {
        int changed = repository.clearTarget(type, targetId);
        if (changed > 0)
        {
            log.debug("{} {} rule(s) now drop their name: the item they rewrote to was removed", changed, type.getKey());
        }
    }

    @Transactional
    public void targetMerged(MetadataType type, Integer sourceId, Integer targetId)
    {
        int changed = repository.retarget(type, sourceId, targetId);
        if (changed > 0)
        {
            log.debug("{} {} rule(s) now rewrite to the merge target instead", changed, type.getKey());
        }
    }

    @Transactional
    public void delete(Integer id)
    {
        repository.deleteById(id);
    }

    /** Targets are resolved to their current names. */
    @Transactional(readOnly = true)
    public Page<MetadataRuleDto> page(MetadataType type, int pageNumber)
    {
        Pageable pageable = PageRequest.of(Math.max(pageNumber, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"));
        Page<MetadataRule> rules = repository.findByType(type, pageable);

        var targets = new LinkedHashSet<Integer>();
        rules.getContent().stream().map(MetadataRule::getTargetId).filter(Objects::nonNull).forEach(targets::add);
        Map<Integer, String> names = namesByIds(type, targets);

        var rows = new ArrayList<MetadataRuleDto>();
        for (MetadataRule rule : rules)
        {
            rows.add(new MetadataRuleDto(rule.getId(), rule.getSourceNameLower(),
                    rule.getTargetId() == null ? null : names.get(rule.getTargetId())));
        }
        return new PageImpl<>(rows, pageable, rules.getTotalElements());
    }

    /** {@code null} for a blocking rule or a vanished target. */
    @Transactional(readOnly = true)
    public String targetName(MetadataType type, MetadataRule rule)
    {
        return rule.getTargetId() == null
                ? null
                : namesByIds(type, List.of(rule.getTargetId())).get(rule.getTargetId());
    }

    /** Ids with no row are absent from the map. */
    private Map<Integer, String> namesByIds(MetadataType type, Collection<Integer> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            return Map.of();
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<?> cq = cb.createQuery(type.getEntityClass());
        Root<?> root = cq.from(type.getEntityClass());
        cq.where(root.get("id").in(ids));

        var names = new HashMap<Integer, String>();
        for (Object entity : em.createQuery(cq).getResultList())
        {
            Metadata m = (Metadata) entity;
            names.put(m.getId(), m.getName());
        }
        return names;
    }
}

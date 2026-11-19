package io.github.mocchikon.hentie.service.compress;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.CompressionRuleForm;
import io.github.mocchikon.hentie.dto.CompressionRuleView;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.ImageCompressionRule;
import io.github.mocchikon.hentie.repository.ImageCompressionRuleRepository;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Image compression rules: the mode a gallery queued "By rules" gets, decided by its chapter's metadata once the
 * gallery is in the library, and whether a chapter already there is worth downloading again in a better one.
 * <p>
 * <b>A decision</b>: the rules whose every condition the chapter carries match, and so do the rules without
 * conditions. Of these only the lowest priority number counts; among them the best quality wins (the older rule on a
 * tie, so the answer never depends on the query plan), and upgrading is allowed when any of them allows it. When no
 * rule matches, Settings' mode applies, which allows no upgrade.
 * <p>
 * <b>A decision is one query that starts from the chapter's own values</b>, an index seek per value into the rules
 * naming it, so it costs the same with ten rules or ten thousand (one per favourite artist).
 * <p>
 * Conditions are metadata <b>ids</b>, so renaming a value keeps its rules, a merge moves them
 * ({@link MetadataService}), and a removal leaves them unable to match (see {@link ImageCompressionRule}).
 */
@Service
@RequiredArgsConstructor
public class ImageCompressionRuleService
{
    /** Rules per page: there can be thousands. */
    public static final int PAGE_SIZE = 50;

    /** Each row a rule that matches the chapter: id, priority, compression_mode, allow_upgrade. */
    private static final String MATCHING_RULES = matchingRulesSql();

    private final ImageCompressionRuleRepository repository;
    private final ImageCompressionModeService modeService;
    private final MetadataService metadataService;
    private final SettingsService settingsService;

    @PersistenceContext
    private EntityManager em;

    /** A form field the save refused, worded for the user. */
    @Getter
    public static final class Refused extends RuntimeException
    {
        private final String field;

        public Refused(String field, String message)
        {
            super(message);
            this.field = field;
        }
    }

    /**
     * @param modeKey      the mode the gallery gets; never "By rules", and None for a rule whose mode was deleted
     * @param allowUpgrade whether a chapter already in the library may be downloaded again in that mode
     * @param ruleId       the rule that named the mode; null when none matched and Settings' mode applies
     */
    public record Decision(String modeKey, boolean allowUpgrade, Integer ruleId)
    {
    }

    /**
     * @param modeKey the mode the chapter's pages get; never "By rules"
     * @param upgrade the pages it has are downloaded again in that mode, replacing theirs
     */
    public record Plan(String modeKey, boolean upgrade)
    {
    }

    /** The form and whether the rule lost a value since, which the form warns about: saving revives it. */
    public record Editing(CompressionRuleForm form, boolean broken)
    {
    }

    // ---- deciding -------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Decision decide(int chapterId)
    {
        @SuppressWarnings("unchecked")
        List<Object[]> matching = em.createNativeQuery(MATCHING_RULES).setParameter("chapter", chapterId)
                .getResultList();
        if (matching.isEmpty())
        {
            return new Decision(modeService.storableKey(settingsService.getImageCompressionMode()), false, null);
        }
        Map<String, Integer> qualities = modeService.qualities();
        int top = matching.stream().mapToInt(row -> intOf(row[1])).min().orElseThrow();
        Candidate best = null;
        boolean allowUpgrade = false;
        for (Object[] row : matching)
        {
            if (intOf(row[1]) != top)
            {
                continue;
            }
            // A deleted mode is applied as None, so it is compared, and named, as None.
            String stored = (String) row[2];
            String mode = qualities.containsKey(stored) ? stored : BuiltInCompressionMode.NONE.getKey();
            var candidate = new Candidate(intOf(row[0]), mode, ImageCompressionModeService.qualityOf(qualities, mode));
            allowUpgrade |= booleanOf(row[3]);
            if (best == null || candidate.beats(best))
            {
                best = candidate;
            }
        }
        return new Decision(best.mode(), allowUpgrade, best.ruleId());
    }

    private record Candidate(int ruleId, String mode, int quality)
    {
        boolean beats(Candidate other)
        {
            return quality < other.quality || (quality == other.quality && ruleId < other.ruleId);
        }
    }

    /**
     * What a download does to a chapter: the mode its pages get, and whether the pages it has are downloaded again
     * in that mode, which takes one strictly better than the mode that compressed them.
     *
     * @param queuedMode   the queue row's: a mode, or "By rules" to {@link #decide}
     * @param allowUpgrade the queue row's; "By rules" takes the rules' answer instead
     * @param recordedMode the chapter's {@code compression_mode}; null for original pages, which nothing beats
     */
    @Transactional(readOnly = true)
    public Plan plan(String queuedMode, boolean allowUpgrade, int chapterId, String recordedMode)
    {
        String mode = queuedMode;
        boolean allowed = allowUpgrade;
        if (ImageCompressionModeService.isRules(queuedMode))
        {
            Decision decision = decide(chapterId);
            mode = decision.modeKey();
            allowed = decision.allowUpgrade();
        }
        return new Plan(mode, allowed && recordedMode != null && modeService.isBetter(mode, recordedMode));
    }

    /**
     * Whether a download would replace the pages of a chapter already in the library ({@link #plan}), for a listing
     * that must not queue every gallery it finds there. Asks the rules only about compressed pages: nothing beats
     * original ones.
     */
    @Transactional(readOnly = true)
    public boolean wantsUpgrade(String queuedMode, boolean allowUpgrade, int chapterId, String recordedMode)
    {
        if (recordedMode == null || (!allowUpgrade && !ImageCompressionModeService.isRules(queuedMode)))
        {
            return false;
        }
        return plan(queuedMode, allowUpgrade, chapterId, recordedMode).upgrade();
    }

    /**
     * Matching rules: those without conditions, and those for which the chapter carries as many of their values as
     * they must match. Each branch seeks the chapter's links of one kind and, per value, the rules naming it.
     * {@code count(distinct ...)}, since nothing keeps a chapter from carrying a link twice.
     * <p>
     * <b>The plan is pinned</b> ({@code cross join}, which SQLite never reorders, and {@code indexed by}), so its cost
     * never depends on the planner statistics. Statistics skewed by a value many rules name made SQLite scan every
     * rule's conditions of that kind, even once per value of the chapter: with 10,000 rules sharing a tag, 7-15 ms
     * a decision against 6 ms pinned. One rule per artist costs well under a millisecond.
     */
    private static String matchingRulesSql()
    {
        var branches = new StringJoiner(" union all ");
        for (MetadataType type : MetadataType.values())
        {
            String fk = type.getFkColumn();
            branches.add("select c.rule_id, " + type.ordinal() + " || ':' || c." + fk + " as condition_key from "
                    + type.getChapterJoinTable() + " j cross join " + type.getCompressionRuleJoinTable() + " c "
                    + "indexed by " + valueIndexOf(type) + " on c." + fk + " = j." + fk + " where j.chapter_id = :chapter");
        }
        return "select r.id, r.priority, r.compression_mode, r.allow_upgrade from image_compression_rule r "
                + "where r.condition_count = 0 "
                + "union all "
                + "select r.id, r.priority, r.compression_mode, r.allow_upgrade from ("
                + "select m.rule_id, count(distinct m.condition_key) as matched from (" + branches + ") m "
                + "group by m.rule_id) g join image_compression_rule r on r.id = g.rule_id "
                + "where g.matched = r.condition_count";
    }

    /** V7's {@code (meta_id, rule_id)} index of a kind's conditions, e.g. {@code ix_compression_rule_tags__tag_rule}. */
    private static String valueIndexOf(MetadataType type)
    {
        return "ix_" + StringUtils.removeStart(type.getCompressionRuleJoinTable(), "image_") + "__"
                + StringUtils.removeEnd(type.getFkColumn(), "_id") + "_rule";
    }

    // ---- the page -------------------------------------------------------------------------------------------

    /**
     * In the order a decision ranks rules. With a value, only the rules naming it; a value that does not exist names
     * none.
     */
    @Transactional(readOnly = true)
    public Page<CompressionRuleView> page(int page, MetadataType type, String value)
    {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE);
        Page<ImageCompressionRule> rules;
        if (type == null || StringUtils.isBlank(value))
        {
            rules = repository.findAllByOrderByPriorityAscIdAsc(pageable);
        }
        else
        {
            Optional<Integer> valueId = metadataService.findId(type, value);
            rules = valueId.isEmpty() ? Page.empty(pageable) : naming(type, valueId.get(), pageable);
        }
        Map<Integer, List<CompressionRuleView.Condition>> conditions =
                conditionsOf(rules.getContent().stream().map(ImageCompressionRule::getId).toList());
        Map<String, String> names = modeService.namesByKey();
        Map<String, Integer> qualities = modeService.qualities();
        return rules.map(rule -> view(rule, conditions.getOrDefault(rule.getId(), List.of()), names, qualities));
    }

    /** The rule ids come off the {@code (meta_id, rule_id)} index; a value is named at most once per rule. */
    private Page<ImageCompressionRule> naming(MetadataType type, int valueId, Pageable pageable)
    {
        String table = type.getCompressionRuleJoinTable();
        String fk = type.getFkColumn();
        long total = ((Number) em.createNativeQuery("select count(*) from " + table + " where " + fk + " = :value")
                .setParameter("value", valueId).getSingleResult()).longValue();
        @SuppressWarnings("unchecked")
        List<ImageCompressionRule> rules = em.createNativeQuery("select r.* from image_compression_rule r "
                        + "where r.id in (select c.rule_id from " + table + " c indexed by " + valueIndexOf(type)
                        + " where c." + fk + " = :value) "
                        + "order by r.priority, r.id limit :limit offset :offset", ImageCompressionRule.class)
                .setParameter("value", valueId)
                .setParameter("limit", pageable.getPageSize())
                .setParameter("offset", pageable.getOffset())
                .getResultList();
        return new PageImpl<>(rules, pageable, total);
    }

    private static CompressionRuleView view(ImageCompressionRule rule, List<CompressionRuleView.Condition> conditions,
                                            Map<String, String> names, Map<String, Integer> qualities)
    {
        return new CompressionRuleView(rule.getId(), rule.getPriority(), names.get(rule.getCompressionMode()),
                ImageCompressionModeService.qualityOf(qualities, rule.getCompressionMode()), rule.isAllowUpgrade(),
                conditions, conditions.size() < rule.getConditionCount());
    }

    /** One query per kind for the links, one per kind for the names: never one per rule. */
    private Map<Integer, List<CompressionRuleView.Condition>> conditionsOf(Collection<Integer> ruleIds)
    {
        var conditions = new HashMap<Integer, List<CompressionRuleView.Condition>>();
        if (ruleIds.isEmpty())
        {
            return conditions;
        }
        for (MetadataType type : MetadataType.values())
        {
            @SuppressWarnings("unchecked")
            List<Object[]> links = em.createNativeQuery("select rule_id, " + type.getFkColumn() + " from "
                            + type.getCompressionRuleJoinTable() + " where rule_id in (:rules)")
                    .setParameter("rules", ruleIds)
                    .getResultList();
            if (links.isEmpty())
            {
                continue;
            }
            Map<Integer, String> names = namesOf(type, links.stream().map(link -> intOf(link[1])).toList());
            for (Object[] link : links)
            {
                String name = names.get(intOf(link[1]));
                if (name != null)
                {
                    conditions.computeIfAbsent(intOf(link[0]), id -> new ArrayList<>())
                            .add(new CompressionRuleView.Condition(type, name));
                }
            }
        }
        // Kind by kind, as the form lists them, then by name.
        conditions.values().forEach(list -> list.sort(Comparator
                .comparing((CompressionRuleView.Condition c) -> c.type().ordinal())
                .thenComparing(CompressionRuleView.Condition::name)));
        return conditions;
    }

    private Map<Integer, String> namesOf(MetadataType type, Collection<Integer> ids)
    {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<?> root = cq.from(type.getEntityClass());
        cq.multiselect(root.<Integer>get("id"), root.<String>get(type.getNameProperty()))
                .where(root.get("id").in(Set.copyOf(ids)));
        var names = new HashMap<Integer, String>();
        em.createQuery(cq).getResultList().forEach(row -> names.put((Integer) row[0], (String) row[1]));
        return names;
    }

    // ---- the form -------------------------------------------------------------------------------------------

    /** Starts at Settings' mode, as the Download page does. */
    @Transactional(readOnly = true)
    public CompressionRuleForm newForm()
    {
        var form = new CompressionRuleForm();
        form.setCompressionMode(modeService.storableKey(settingsService.getImageCompressionMode()));
        return form;
    }

    @Transactional(readOnly = true)
    public Optional<Editing> editing(int id)
    {
        return repository.findById(id).map(rule ->
        {
            var form = new CompressionRuleForm();
            form.setId(rule.getId());
            form.setPriority(rule.getPriority());
            form.setCompressionMode(modeService.storableKey(rule.getCompressionMode()));
            form.setAllowUpgrade(rule.isAllowUpgrade());
            List<CompressionRuleView.Condition> conditions = conditionsOf(List.of(id)).getOrDefault(id, List.of());
            conditions.forEach(condition ->
            {
                form.getConditionTypes().add(condition.type().getKey());
                form.getConditionValues().add(condition.name());
            });
            return new Editing(form, conditions.size() < rule.getConditionCount());
        });
    }

    /**
     * A name the library does not have yet is added, as on the Manage page, so a rule can name an artist before any
     * of their galleries is downloaded. A row left empty is no condition; no conditions at all match every gallery.
     *
     * @return the rule's id
     * @throws Refused                for a condition that cannot be stored; nothing is saved then
     * @throws NoSuchElementException when the rule was deleted meanwhile
     */
    @Transactional
    public int save(CompressionRuleForm form)
    {
        Map<MetadataType, Set<Integer>> conditions = conditionsOf(form);
        ImageCompressionRule rule = form.getId() == null ? new ImageCompressionRule()
                : repository.findById(form.getId()).orElseThrow(() -> new NoSuchElementException(
                "The rule was deleted meanwhile."));
        rule.setPriority(form.getPriority() == null ? ImageCompressionRule.DEFAULT_PRIORITY : form.getPriority());
        rule.setCompressionMode(modeService.storableKey(form.getCompressionMode()));
        rule.setAllowUpgrade(form.isAllowUpgrade());
        rule.setConditionCount(conditions.values().stream().mapToInt(Set::size).sum());
        int id = repository.saveAndFlush(rule).getId();
        for (MetadataType type : MetadataType.values())
        {
            String table = type.getCompressionRuleJoinTable();
            em.createNativeQuery("delete from " + table + " where rule_id = :rule").setParameter("rule", id)
                    .executeUpdate();
            for (Integer valueId : conditions.getOrDefault(type, Set.of()))
            {
                em.createNativeQuery("insert into " + table + " (rule_id, " + type.getFkColumn()
                                + ") values (:rule, :value)")
                        .setParameter("rule", id)
                        .setParameter("value", valueId)
                        .executeUpdate();
            }
        }
        return id;
    }

    /** Its conditions go with it, by the cascade. */
    @Transactional
    public void delete(int id)
    {
        repository.deleteById(id);
    }

    /** Every row of the form as a value id, so one refused row saves nothing. */
    private Map<MetadataType, Set<Integer>> conditionsOf(CompressionRuleForm form)
    {
        List<String> types = form.getConditionTypes() == null ? List.of() : form.getConditionTypes();
        List<String> values = form.getConditionValues() == null ? List.of() : form.getConditionValues();
        var conditions = new EnumMap<MetadataType, Set<Integer>>(MetadataType.class);
        for (int i = 0; i < values.size(); i++)
        {
            String name = StringUtils.trimToEmpty(values.get(i));
            if (name.isEmpty())
            {
                continue;
            }
            MetadataType type = typeOf(i < types.size() ? types.get(i) : null, name);
            if (name.length() > 255)
            {
                throw new Refused("conditions", kindOf(type) + " names can be at most 255 characters long.");
            }
            Optional<MetadataService.Refusal> refusal = metadataService.add(type, name);
            if (refusal.isPresent())
            {
                throw new Refused("conditions", describe(refusal.get()));
            }
            Integer id = metadataService.findId(type, name).orElseThrow(() -> new Refused("conditions",
                    kindOf(type) + " \"" + name + "\" is not a valid name."));
            conditions.computeIfAbsent(type, t -> new LinkedHashSet<>()).add(id);
        }
        return conditions;
    }

    private static MetadataType typeOf(String key, String name)
    {
        try
        {
            return MetadataType.fromKey(key);
        }
        catch (IllegalArgumentException e)
        {
            throw new Refused("conditions", "Choose a kind for \"" + name + "\".");
        }
    }

    /** Only these two refuse a name that is merely added. */
    private static String describe(MetadataService.Refusal refusal)
    {
        String kind = kindOf(refusal.type());
        return switch (refusal)
        {
            case MetadataService.RuleConflict rule -> rule.targetName() == null
                    ? "%s \"%s\" can't be used: your rules on the Manage page remove it from every download."
                    .formatted(kind, rule.name())
                    : "%s \"%s\" can't be used: your rules on the Manage page change it to \"%s\". Use that instead."
                    .formatted(kind, rule.name(), rule.targetName());
            case MetadataService.PipeInName piped ->
                    "%s \"%s\" can't be used: a name can't contain \"|\".".formatted(kind, piped.name());
            case MetadataService.NameTaken taken ->
                    "%s \"%s\" can't be used.".formatted(kind, taken.name());
            case MetadataService.GenderedName gendered ->
                    "%s \"%s\" can't be used.".formatted(kind, gendered.name());
        };
    }

    /** "Artist", from the kind's key: its display name is plural. */
    private static String kindOf(MetadataType type)
    {
        return StringUtils.capitalize(type.getKey());
    }

    private static int intOf(Object value)
    {
        return ((Number) value).intValue();
    }

    /** SQLite stores booleans as integers; the driver may hand back either. */
    private static boolean booleanOf(Object value)
    {
        return value instanceof Boolean flag ? flag : ((Number) value).intValue() != 0;
    }
}

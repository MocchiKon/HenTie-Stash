package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.OptionDto;
import io.github.mocchikon.hentie.entity.Metadata;
import io.github.mocchikon.hentie.entity.MetadataRule;
import io.github.mocchikon.hentie.service.MetadataRuleService.Verdicts;
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
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Add/rename/remove/merge of the six metadata kinds, generic over {@link MetadataType}.
 *
 * <p>Never add a cached list method here: a {@code @Cacheable} called from its own class is not proxied.
 * The cached list is {@link MetadataCatalog}.
 *
 * <p>Delete, merge and rename can leave a <b>rule</b> ({@link MetadataRuleService}), or the next import
 * would bring the name back. Rules are applied only in {@link #resolveOrCreate}, where names become rows.
 *
 * <p><b>A tag is managed through its plain name.</b> An import stores {@code halo ♀} with {@code halo}, and
 * renaming, merging or removing {@code halo} does the same to {@code halo ♀} and {@code halo ♂}, as does its
 * rule. So the Manage page lists plain tags only ({@link #recent}, {@link #autocompleteManaged}): a version
 * managed on its own would drift away from the plain tag that search finds it by.
 *
 * <p><b>A name with aliases, {@code "focalors | lady furina"}, is the name before the pipe</b>, and each alias
 * becomes a rule to it ({@link #recordAliases}). It is how e-hentai shows a tag with one of its aliases, and nhentai
 * stores that display as the name; e-hentai galleries carry the first name only. Kept whole, one character would be
 * two rows, and a search for either would miss the other's galleries. Only imports bring aliases: a typed name may not
 * contain a pipe ({@link PipeInName}).
 */
@Service
public class MetadataService
{
    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

    public static final String FEMALE = "♀";

    public static final String MALE = "♂";

    private static final List<String> GENDER_SYMBOLS = List.of(FEMALE, MALE);

    /** A namespace with a non-blank rest; the rest is never empty, so {@code "female:"} stays a name. */
    private static final Pattern TAG_NAMESPACE =
            Pattern.compile("(female|male|mixed|other|location|temp)\\s*:\\s*(\\S.*)", Pattern.CASE_INSENSITIVE);

    /** A pipe with whitespace on both sides. One that belongs to a name touches a letter or another pipe. */
    private static final Pattern ALIAS_SEPARATOR = Pattern.compile("(?<=\\s)\\|(?=\\s)");

    @PersistenceContext
    private EntityManager em;

    private final MetadataCatalog catalog;

    private final MetadataRuleService ruleService;

    private final SearchCountCache searchCountCache;

    public MetadataService(MetadataCatalog catalog, MetadataRuleService ruleService, SearchCountCache searchCountCache)
    {
        this.catalog = catalog;
        this.ruleService = ruleService;
        this.searchCountCache = searchCountCache;
    }

    /** Returned rather than thrown, so the Manage page can say what stood in the way. */
    public sealed interface Refusal
    {
        MetadataType type();

        /** Canonical ({@link #canonical}), except in {@link PipeInName}: the canonical name would hide the pipe. */
        String name();
    }

    /**
     * A typed name has no aliases. An import reads {@code "name | alias"} as a name and its alias, and merges a
     * row the alias already has into the name's ({@link #resolveOrCreate}); from the Manage page that merge would
     * run inside a quick request. Every pipe is refused, not only one between spaces, so the rule is easy to state.
     * Merging with a rule does what an alias does. {@code name} is as typed.
     */
    public record PipeInName(MetadataType type, String name) implements Refusal
    {
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

    /** A plain tag keeps a plain name: its versions take the new one with their own symbol. */
    public record GenderedName(MetadataType type, String name) implements Refusal
    {
    }

    /** What a name comes to under the rules: a row's id, a name to find or create, or neither (dropped). */
    private record Fate(Integer id, String name)
    {
        static final Fate DROPPED = new Fate(null, null);
    }

    /** Substring match, capped; an exact match is listed first. */
    public List<OptionDto> autocomplete(MetadataType type, String query, int limit)
    {
        return autocomplete(type, query, limit, option -> true);
    }

    /** {@link #autocomplete} over what the Manage page acts on: tags without their versions, which follow them. */
    public List<OptionDto> autocompleteManaged(MetadataType type, String query, int limit)
    {
        return autocomplete(type, query, limit, option -> !isVersion(type, option.getLabel()));
    }

    private List<OptionDto> autocomplete(MetadataType type, String query, int limit, Predicate<OptionDto> offered)
    {
        String needle = query == null ? "" : MetadataService.normalize(query);
        return catalog.all(type).stream()
                .filter(offered)
                .filter(o -> needle.isEmpty() || fold(o.getLabel()).contains(needle))
                // The list is already alphabetical; a stable sort floats an exact match to the top.
                .sorted(Comparator.comparing(o -> !o.getLabel().equalsIgnoreCase(needle)))
                .limit(limit)
                .toList();
    }

    /**
     * The most recently added items when {@code query} is blank, else a substring match with an exact hit first.
     * For the Manage page, so tags come without their versions.
     */
    public List<OptionDto> recent(MetadataType type, String query, int limit)
    {
        String needle = query == null ? "" : MetadataService.normalize(query);
        Comparator<OptionDto> order = needle.isEmpty()
                ? Comparator.comparing(OptionDto::getId, Comparator.reverseOrder())
                : Comparator.<OptionDto, Boolean>comparing(o -> !o.getLabel().equalsIgnoreCase(needle))
                            .thenComparing(o -> fold(o.getLabel()));
        return catalog.all(type).stream()
                .filter(o -> !isVersion(type, o.getLabel()))
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
     * look present and behave as if it were not. A tag's version comes with its plain tag, as from an import,
     * or the Manage page could not reach it.
     *
     * @return what refused the name, or empty when nothing did (added, blank or already there)
     */
    @Transactional
    @CacheEvict(value = CacheConfig.METADATA, key = "#type")
    public Optional<Refusal> add(MetadataType type, String name)
    {
        String canonical = canonical(type, name);
        if (StringUtils.isBlank(canonical))
        {
            return Optional.empty();
        }
        if (name.contains("|"))
        {
            return Optional.of(new PipeInName(type, name.trim()));
        }
        var names = new ArrayList<>(List.of(canonical));
        if (type == MetadataType.TAG)
        {
            plainTagOf(canonical).ifPresent(names::add);
        }
        List<String> missing = names.stream().filter(n -> idsOfName(type, n).isEmpty()).toList();
        for (String n : missing)
        {
            Optional<RuleConflict> conflict = conflictFor(type, n);
            if (conflict.isPresent())
            {
                return Optional.of(conflict.get());
            }
        }
        missing.forEach(n -> create(type, n));
        return Optional.empty();
    }

    /**
     * The one spelling a name is stored in. Every lookup folds case anyway, so storing the folded name makes
     * the chips and search agree with matching.
     * <p>
     * Not enough for a name about to be <b>written</b>: that goes through {@link #canonical}.
     */
    public static String normalize(String name)
    {
        return (name == null) ? null : fold(name.trim());
    }

    /**
     * The spelling a typed or imported name is stored under. For tags it also resolves e-hentai's namespaces,
     * which every source that copies its tagging uses: {@code female:x} becomes {@code x ♀}, {@code male:x}
     * becomes {@code x ♂}, and the namespaces that add nothing to a tag ({@code mixed:}, {@code other:},
     * {@code location:}, {@code temp:}) are dropped. One place, so a tag from any source, a typed one and a
     * repaired one agree on what is the same tag. Only listed namespaces: a colon may belong to a name.
     * <p>
     * A name with aliases is stored under the name before the first pipe ({@code "focalors | lady furina"} as
     * {@code focalors}); {@link #aliasesOf} gives the rest.
     * <p>
     * Not for search needles: a half-typed {@code female:ha} must still find {@code female:ha...} while the
     * user types.
     */
    public static String canonical(MetadataType type, String name)
    {
        return name == null ? null : canonicalPart(type, nameParts(type, name.trim()).getFirst());
    }

    /** {@link #canonical} of one name, without looking for aliases. */
    private static String canonicalPart(MetadataType type, String name)
    {
        String trimmed = name.trim();
        if (type == MetadataType.TAG)
        {
            Matcher namespaced = TAG_NAMESPACE.matcher(trimmed);
            if (namespaced.matches())
            {
                String rest = namespaced.group(2).trim();
                trimmed = switch (fold(namespaced.group(1)))
                {
                    case "female" -> withGenderSymbol(rest, FEMALE);
                    case "male" -> withGenderSymbol(rest, MALE);
                    default -> rest;
                };
            }
        }
        return normalize(trimmed);
    }

    /**
     * The aliases written after a name ({@code "focalors | lady furina"} gives {@code lady furina}), canonical and
     * without the name itself. Empty for a name without any.
     */
    static List<String> aliasesOf(MetadataType type, String name)
    {
        if (name == null)
        {
            return List.of();
        }
        List<String> parts = nameParts(type, name.trim());
        String first = canonicalPart(type, parts.getFirst());
        return parts.stream().skip(1)
                .map(part -> canonicalPart(type, part))
                .filter(alias -> !alias.equals(first))
                .distinct()
                .toList();
    }

    /**
     * The name and its aliases, or the whole name when it has none. Only when every part is a name, so
     * {@code "x | | y"} stays whole. Not for categories: a short list of kinds that no source writes with an alias.
     */
    private static List<String> nameParts(MetadataType type, String trimmed)
    {
        if (type == MetadataType.CATEGORY)
        {
            return List.of(trimmed);
        }
        List<String> parts = Arrays.stream(ALIAS_SEPARATOR.split(trimmed, -1)).map(String::strip).toList();
        return parts.size() > 1 && parts.stream().noneMatch(String::isEmpty) ? parts : List.of(trimmed);
    }

    /** Rules on tags name the plain tag; its versions follow the rule ({@link #fatesOf}). */
    private static String ruleKey(MetadataType type, String name)
    {
        return type == MetadataType.TAG ? plainTagOf(name).orElse(name) : name;
    }

    /**
     * The plain tag a gendered one also stands for: {@code "halo ♀"} gives {@code "halo"}. An import stores both
     * ({@link #resolveOrCreate}), so a search for {@code halo} finds the gallery whether its source tags by gender
     * (e-hentai, hitomi) or not (nhentai). Empty for a tag without a symbol.
     */
    public static Optional<String> plainTagOf(String tag)
    {
        return genderSymbolOf(tag).map(symbol -> tag.substring(0, tag.length() - symbol.length() - 1).trim());
    }

    /**
     * The plain tags whose {@code ♀}/{@code ♂} version is among {@code tags}. Beside its version a plain tag says
     * nothing new, so a detail page leaves it out; it is stored for search.
     */
    public static Set<String> plainTagsCoveredBy(Collection<String> tags)
    {
        return tags.stream().map(MetadataService::plainTagOf).flatMap(Optional::stream).collect(Collectors.toSet());
    }

    /** Empty for a plain tag, and for a bare symbol, which has no plain tag to stand for. */
    private static Optional<String> genderSymbolOf(String tag)
    {
        if (tag == null)
        {
            return Optional.empty();
        }
        for (String symbol : GENDER_SYMBOLS)
        {
            String suffix = " " + symbol;
            if (tag.endsWith(suffix) && StringUtils.isNotBlank(tag.substring(0, tag.length() - suffix.length())))
            {
                return Optional.of(symbol);
            }
        }
        return Optional.empty();
    }

    private static String versionName(String plainTag, String symbol)
    {
        return plainTag + " " + symbol;
    }

    private static boolean isVersion(MetadataType type, String name)
    {
        return type == MetadataType.TAG && genderSymbolOf(name).isPresent();
    }

    private static boolean isPlainTag(MetadataType type, String name)
    {
        return type == MetadataType.TAG && genderSymbolOf(name).isEmpty();
    }

    /** A tag that already carries a symbol keeps it, so {@link #canonical} is idempotent. */
    private static String withGenderSymbol(String tag, String symbol)
    {
        return tag.endsWith(" " + FEMALE) || tag.endsWith(" " + MALE) ? tag : tag + " " + symbol;
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
     * <p>
     * A plain tag takes its versions along ({@code halo} to {@code ring} renames {@code halo ♀} to
     * {@code ring ♀}), so the new name is plain too, and is refused unless every version can follow. A rule
     * removing the tag's gender belongs to the tag, not to its old name, so it moves to the new one.
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
        if (newName.contains("|"))
        {
            return Optional.of(new PipeInName(type, newName.trim()));
        }
        Metadata entity = (Metadata) em.find(type.getEntityClass(), id);
        if (entity == null)
        {
            return Optional.empty();
        }
        String normalized = canonical(type, newName);
        String oldName = entity.getName();
        if (!isPlainTag(type, oldName))
        {
            return renameRow(type, id, normalized, createRule);
        }
        if (genderSymbolOf(normalized).isPresent())
        {
            return Optional.of(new GenderedName(type, normalized));
        }

        Map<String, Integer> versions = versionsOf(type, oldName);
        var renames = new LinkedHashMap<Integer, String>();
        renames.put(id, normalized);
        versions.forEach((symbol, versionId) -> renames.put(versionId, versionName(normalized, symbol)));
        // Every row is checked before any changes, so a refused rename leaves the whole tag as it was.
        for (var rename : renames.entrySet())
        {
            Optional<Refusal> refusal = refusalFor(type, rename.getKey(), rename.getValue());
            if (refusal.isPresent())
            {
                return refusal;
            }
        }
        // Rule id to the name it moves to. A rule already on that name and pointing elsewhere is a decision about
        // another tag, which recording this one would overwrite.
        var genderRules = new LinkedHashMap<Integer, String>();
        for (String symbol : GENDER_SYMBOLS)
        {
            String from = normalize(versionName(oldName, symbol));
            String to = versionName(normalized, symbol);
            Optional<MetadataRule> rule = from.equals(to) ? Optional.empty()
                    : ruleService.ruleFor(type, from).filter(r -> id.equals(r.getTargetId()));
            if (rule.isPresent())
            {
                Optional<MetadataRule> taken = ruleService.ruleFor(type, to).filter(r -> !id.equals(r.getTargetId()));
                if (taken.isPresent())
                {
                    return Optional.of(conflict(type, taken.get()));
                }
                genderRules.put(rule.get().getId(), to);
            }
        }
        renames.forEach((rowId, name) -> applyRename(type, rowId, name));
        genderRules.forEach((ruleId, to) ->
        {
            ruleService.delete(ruleId);
            ruleService.recordRewrite(type, to, id);
        });
        // The plain name's rule covers the versions' old names too.
        recordRenameRule(type, id, oldName, normalized, createRule);
        return Optional.empty();
    }

    /**
     * One row's spelling, for {@link MetadataNameFolder}: a tag's versions are spellings of their own, which the
     * folder rewrites on their own. Records no rule.
     *
     * @return what refused the name, or empty when nothing did (respelled, or blank)
     */
    @Transactional
    @CacheEvict(value = CacheConfig.METADATA, key = "#type")
    public Optional<Refusal> respell(MetadataType type, Integer id, String name)
    {
        if (StringUtils.isBlank(name) || em.find(type.getEntityClass(), id) == null)
        {
            return Optional.empty();
        }
        return renameRow(type, id, canonical(type, name), false);
    }

    private Optional<Refusal> renameRow(MetadataType type, Integer id, String normalized, boolean createRule)
    {
        Optional<Refusal> refusal = refusalFor(type, id, normalized);
        if (refusal.isPresent())
        {
            return refusal;
        }
        String oldName = ((Metadata) em.find(type.getEntityClass(), id)).getName();
        applyRename(type, id, normalized);
        recordRenameRule(type, id, oldName, normalized, createRule);
        return Optional.empty();
    }

    /**
     * Checked before any rule is dropped, so a refused rename leaves nothing behind. Only a DIFFERENT row
     * counts: renaming onto its own name (in any case) is allowed. A list, because unfolded old data can hold
     * two rows for one name.
     */
    private Optional<Refusal> refusalFor(MetadataType type, Integer id, String normalized)
    {
        if (idsOfName(type, normalized).stream().anyMatch(other -> !other.equals(id)))
        {
            return Optional.of(new NameTaken(type, normalized));
        }
        return ruleService.ruleFor(type, normalized)
                .filter(rule -> !id.equals(rule.getTargetId()))
                .map(rule -> conflict(type, rule));
    }

    /** {@link #refusalFor} has passed, so a rule on the new name points at this row and is fulfilled. */
    private void applyRename(MetadataType type, Integer id, String normalized)
    {
        ruleService.ruleFor(type, normalized).ifPresent(rule -> ruleService.delete(rule.getId()));
        ((Metadata) em.find(type.getEntityClass(), id)).setName(normalized);
    }

    private void recordRenameRule(MetadataType type, Integer id, String oldName, String newName, boolean createRule)
    {
        // Not for a change of case or spacing only: the rule would rule out the row's own name.
        if (createRule && !oldName.equalsIgnoreCase(newName))
        {
            ruleService.recordRewrite(type, oldName, id);
        }
    }

    /** A tag's version is also ruled out by its plain tag's rule, which covers it. */
    private Optional<RuleConflict> conflictFor(MetadataType type, String name)
    {
        Optional<MetadataRule> rule = ruleService.ruleFor(type, name);
        if (rule.isEmpty() && type == MetadataType.TAG)
        {
            rule = plainTagOf(name).flatMap(plain -> ruleService.ruleFor(type, plain));
        }
        return rule.map(r -> conflict(type, r));
    }

    private RuleConflict conflict(MetadataType type, MetadataRule rule)
    {
        return new RuleConflict(type, rule.getSourceNameLower(), ruleService.targetName(type, rule));
    }

    /** A plain tag goes with its versions; its rule covers them. */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.METADATA, key = "#type"),
            @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    })
    public void remove(MetadataType type, Integer id, boolean createRule)
    {
        Metadata found = (Metadata) em.find(type.getEntityClass(), id);
        if (found == null)
        {
            return;
        }
        String name = found.getName();
        if (isPlainTag(type, name))
        {
            versionsOf(type, name).values().forEach(versionId -> removeRow(type, versionId, false));
        }
        removeRow(type, id, createRule);
    }

    private void removeRow(MetadataType type, Integer id, boolean createRule)
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

    /**
     * Reassigns every link from {@code sourceId} to {@code targetId}, then deletes the source. Two plain tags
     * merge with their versions: {@code halo ♀} goes where an import would now put it, into {@code ring ♀}
     * (renamed to it when there is none) or where a rule on {@code ring ♀} sends it.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.METADATA, key = "#type"),
            @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    })
    public void merge(MetadataType type, Integer sourceId, Integer targetId, boolean createRule)
    {
        mergeItem(type, sourceId, targetId, createRule);
    }

    /** {@link #merge} without its cache evictions, which a call from this class would skip. */
    private void mergeItem(MetadataType type, Integer sourceId, Integer targetId, boolean createRule)
    {
        if (sourceId == null || sourceId.equals(targetId))
        {
            return;
        }
        Metadata source = (Metadata) em.find(type.getEntityClass(), sourceId);
        Metadata target = (Metadata) em.find(type.getEntityClass(), targetId);
        if (source == null || target == null)
        {
            return;
        }
        String targetName = target.getName();
        if (isPlainTag(type, source.getName()) && isPlainTag(type, targetName))
        {
            versionsOf(type, source.getName()).forEach((symbol, versionId) ->
                    moveVersion(type, versionId, versionName(targetName, symbol)));
        }
        mergeRow(type, sourceId, targetId, createRule);
    }

    /** One row into another, for {@link MetadataNameFolder} (see {@link #respell}). Records no rule. */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.METADATA, key = "#type"),
            @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    })
    public void mergeSpellings(MetadataType type, Integer sourceId, Integer targetId)
    {
        mergeRow(type, sourceId, targetId, false);
    }

    /**
     * Merges a tag's {@code ♀} and {@code ♂} versions into it, for a tag whose gender says nothing; whatever
     * carried a version carries the plain tag afterwards. With a rule, imports rewrite both versions to the
     * plain tag from then on, so they never come back.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.METADATA, key = "T(io.github.mocchikon.hentie.dto.MetadataType).TAG"),
            @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    })
    public void removeGender(Integer tagId, boolean createRule)
    {
        MetadataType type = MetadataType.TAG;
        Metadata tag = tagId == null ? null : (Metadata) em.find(type.getEntityClass(), tagId);
        if (tag == null || !isPlainTag(type, tag.getName()))
        {
            return;
        }
        String plain = tag.getName();
        versionsOf(type, plain).values().forEach(versionId -> mergeRow(type, versionId, tagId, false));
        if (createRule)
        {
            GENDER_SYMBOLS.forEach(symbol -> ruleService.recordRewrite(type, versionName(plain, symbol), tagId));
        }
    }

    private void mergeRow(MetadataType type, Integer sourceId, Integer targetId, boolean createRule)
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

    /** Where an import would now put a version named {@code name}, so the merge agrees with later downloads. */
    private void moveVersion(MetadataType type, Integer versionId, String name)
    {
        Optional<MetadataRule> rule = ruleService.ruleFor(type, name);
        if (rule.isPresent())
        {
            Integer ruledTarget = rule.get().getTargetId();
            if (ruledTarget != null && em.find(type.getEntityClass(), ruledTarget) != null)
            {
                mergeRow(type, versionId, ruledTarget, false);
            }
            else
            {
                removeRow(type, versionId, false);
            }
            return;
        }
        Integer existing = findByNames(type, List.of(name)).get(name);
        if (existing != null)
        {
            mergeRow(type, versionId, existing, false);
        }
        else
        {
            ((Metadata) em.find(type.getEntityClass(), versionId)).setName(name);
        }
    }

    /** Symbol to id, for the versions of {@code plainTag} that exist. */
    private Map<String, Integer> versionsOf(MetadataType type, String plainTag)
    {
        String folded = fold(plainTag);
        Map<String, Integer> byName = findByNames(type,
                GENDER_SYMBOLS.stream().map(symbol -> versionName(folded, symbol)).toList());
        var versions = new LinkedHashMap<String, Integer>();
        for (String symbol : GENDER_SYMBOLS)
        {
            Integer id = byName.get(versionName(folded, symbol));
            if (id != null)
            {
                versions.put(symbol, id);
            }
        }
        return versions;
    }

    /**
     * Ids for imported <i>names</i>, creating what does not exist yet; case-insensitive, so an import joins
     * the existing row instead of forking a near-duplicate. A gendered tag also gives its plain tag
     * ({@link #plainTagOf}), and a name's aliases become rules to it ({@link #recordAliases}).
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
        // Before the names are resolved, so an alias the gallery also names on its own lands on its name too.
        for (var named : aliasesByRuleKey(type, names).entrySet())
        {
            Integer target = resolveNames(type, List.of(named.getKey())).stream().findFirst().orElse(null);
            recordAliases(type, target, named.getKey(), named.getValue());
        }
        return resolveNames(type, names);
    }

    /** A name's {@link #ruleKey} to its aliases, for the names that have any. */
    private static Map<String, Set<String>> aliasesByRuleKey(MetadataType type, Collection<String> names)
    {
        var byKey = new LinkedHashMap<String, Set<String>>();
        for (String name : names)
        {
            List<String> aliases = aliasesOf(type, name);
            if (!aliases.isEmpty())
            {
                byKey.computeIfAbsent(ruleKey(type, canonical(type, name)), k -> new LinkedHashSet<>()).addAll(aliases);
            }
        }
        return byKey;
    }

    /**
     * Rules each alias to the row its name came to, as a merge with "Add rule" leaves it, so an import naming the
     * alias lands there too. A row the alias already has is merged in, since a ruled-out name has no row. Like any
     * merge it is one write, here inside the import's transaction, so it holds the lock as long as moving the
     * alias's links takes (once per alias).
     * <p>
     * A rule already on an alias is a decision, the user's or an earlier alias's, and stays. Nothing is recorded
     * when a rule dropped the name ({@code targetId} null): a block on the alias would outlive the user taking the
     * name back.
     */
    private void recordAliases(MetadataType type, Integer targetId, String nameKey, Collection<String> aliases)
    {
        if (targetId == null)
        {
            return;
        }
        boolean merged = false;
        for (String alias : aliases)
        {
            String key = ruleKey(type, alias);
            if (key.equals(nameKey) || ruleService.ruleFor(type, key).isPresent())
            {
                continue;
            }
            List<Integer> rows = idsOfName(type, key);
            if (rows.isEmpty())
            {
                ruleService.recordRewrite(type, key, targetId);
            }
            else if (!rows.contains(targetId))
            {
                rows.forEach(row -> mergeItem(type, row, targetId, true));
                merged = true;
            }
        }
        if (merged)
        {
            evictAll(type);
            searchCountCache.clear();
        }
    }

    private List<Integer> resolveNames(MetadataType type, Collection<String> names)
    {
        // Canonicalize, drop blanks and collapse two spellings of one name, keeping the caller's order.
        var wanted = new LinkedHashSet<String>();
        names.stream().map(name -> canonical(type, name)).filter(StringUtils::isNotBlank).forEach(name ->
        {
            wanted.add(name);
            if (type == MetadataType.TAG)
            {
                plainTagOf(name).ifPresent(wanted::add);
            }
        });
        if (wanted.isEmpty())
        {
            return List.of();
        }

        // Rules first: a name the user deleted, merged or renamed away must never reach the create below.
        List<Fate> fates = fatesOf(type, wanted);
        var toResolve = new LinkedHashSet<String>();
        fates.stream().map(Fate::name).filter(Objects::nonNull).forEach(toResolve::add);
        Map<String, Integer> existing = toResolve.isEmpty() ? new HashMap<>() : findByNames(type, toResolve);
        // A set: a rewrite can land on a value the item already carries.
        var ids = new LinkedHashSet<Integer>();
        boolean created = false;
        for (Fate fate : fates)
        {
            if (fate.id() != null)
            {
                ids.add(fate.id());
                continue;
            }
            if (fate.name() == null)
            {
                continue;
            }
            Integer id = existing.get(fate.name());
            if (id == null)
            {
                id = create(type, fate.name());
                // Two names can come to one: a version that follows its plain tag's rule onto one the import names too.
                existing.put(fate.name(), id);
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
     * What each name comes to, in order; {@code names} hold the plain tag of every version among them.
     * <p>
     * A version without a rule of its own follows its plain tag's: it is dropped with it, or becomes the
     * target's version ({@code halo ♀} after {@code halo → ring} is {@code ring ♀}). That version can have a rule
     * of its own (a removed gender), which is the one second lookup; it is not followed further, so rules stay
     * one hop deep for each name.
     */
    private List<Fate> fatesOf(MetadataType type, Collection<String> names)
    {
        Verdicts verdicts = ruleService.verdicts(type, names);
        if (!verdicts.isEmpty())
        {
            log.debug("Metadata rules applied to imported {}: {} dropped, {} rewritten",
                    type.getKey(), verdicts.blocked().size(), verdicts.rewrites().size());
        }
        var fates = new ArrayList<Fate>(names.size());
        var followed = new ArrayList<Integer>();
        for (String name : names)
        {
            Optional<Fate> ruled = ruled(verdicts, name);
            if (ruled.isEmpty() && type == MetadataType.TAG)
            {
                ruled = followPlainTag(verdicts, name);
                if (ruled.map(Fate::name).isPresent())
                {
                    followed.add(fates.size());
                }
            }
            fates.add(ruled.orElse(new Fate(null, name)));
        }
        if (!followed.isEmpty())
        {
            Verdicts ofTargets = ruleService.verdicts(type, followed.stream().map(i -> fates.get(i).name()).toList());
            for (int i : followed)
            {
                ruled(ofTargets, fates.get(i).name()).ifPresent(fate -> fates.set(i, fate));
            }
        }
        return fates;
    }

    /** Empty when no rule names {@code name}. */
    private static Optional<Fate> ruled(Verdicts verdicts, String name)
    {
        Integer target = verdicts.rewrites().get(name);
        if (target != null)
        {
            return Optional.of(new Fate(target, null));
        }
        return verdicts.blocked().contains(name) ? Optional.of(Fate.DROPPED) : Optional.empty();
    }

    /** Empty for a plain name, and for a version whose plain tag no rule names. */
    private static Optional<Fate> followPlainTag(Verdicts verdicts, String name)
    {
        Optional<String> symbol = genderSymbolOf(name);
        Optional<String> plain = plainTagOf(name);
        if (symbol.isEmpty() || plain.isEmpty())
        {
            return Optional.empty();
        }
        if (verdicts.blocked().contains(plain.get()))
        {
            return Optional.of(Fate.DROPPED);
        }
        Integer target = verdicts.rewrites().get(plain.get());
        if (target == null)
        {
            return Optional.empty();
        }
        String targetName = verdicts.targetNames().get(target);
        // A rule's target is a plain tag (the Manage page offers no other); a version is taken as it is.
        return Optional.of(genderSymbolOf(targetName).isPresent()
                ? new Fate(target, null)
                : new Fate(null, versionName(targetName, symbol.get())));
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

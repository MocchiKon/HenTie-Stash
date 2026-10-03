package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.OptionDto;
import io.github.mocchikon.hentie.dto.SeriesForm;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.*;
import io.github.mocchikon.hentie.service.MetadataCatalog;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.SeriesService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MetadataServiceIT
{
    @Autowired MetadataService metadataService;
    @Autowired MetadataCatalog catalog;
    @Autowired CacheManager cacheManager;
    @Autowired SeriesService seriesService;
    @Autowired TagRepository tagRepository;
    @Autowired ChapterRepository chapterRepository;
    @Autowired GroupRepository groupRepository;
    @Autowired MetadataRuleRepository ruleRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    /**
     * Fixtures write through the repositories, so nothing evicts the metadata cache, and it outlives each
     * rolled-back test; one test's cached list would otherwise answer the next test's read.
     */
    @BeforeEach
    void clearMetadataCache()
    {
        cacheManager.getCache(CacheConfig.METADATA).clear();
    }

    private Tag tag(String name)
    {
        Tag t = new Tag();
        t.setName(name);
        return tagRepository.save(t);
    }

    private Group group(String name)
    {
        Group g = new Group();
        g.setName(name);
        return groupRepository.save(g);
    }

    private Chapter chapterWith(Tag tag)
    {
        return chapterWithTags("merge-chapter-" + tag.getName(), List.of(tag));
    }

    private Chapter chapterWithTags(String titleFull, List<Tag> tags)
    {
        Chapter c = new Chapter();
        c.setTitle("c");
        c.setTitleFull(titleFull);
        c.setNativeTitle("x");
        c.setUploadDate(LocalDate.of(2020, 1, 1));
        c.setLanguage("en");
        c.setTags(new ArrayList<>(tags));
        return chapterRepository.save(c);
    }

    // --- rename() / remove() / merge() -----------------------------------------------------
    // Chapter and series (via an override, so the effective set mirrors it) each carry the tag under test
    // plus an "other" control tag, proving the operation touches only its target in all three join tables.

    @Test
    void shouldRenameOnlyTheSpecifiedTagEverywhereItIsReferenced()
    {
        // GIVEN
        Tag target = tag("rename-target");
        Tag other = tag("rename-other");
        Chapter c = chapterWithTags("rename-chapter", List.of(target, other));
        int seriesId = seriesService.create(
                seriesFormWithTagOverride("rename-series", List.of(target.getId(), other.getId())));
        em.flush();

        // WHEN
        metadataService.rename(MetadataType.TAG, target.getId(), "renamed-target", false);
        em.flush();
        em.clear();

        // THEN the new name shows up everywhere; the other tag and every link are untouched.
        Chapter reloadedChapter = chapterRepository.findById(c.getId()).orElseThrow();
        assertThat(reloadedChapter.getTags()).extracting("name")
                .containsExactlyInAnyOrder("renamed-target", "rename-other");

        Series reloadedSeries = seriesRepository.findById(seriesId).orElseThrow();
        assertThat(reloadedSeries.getTags()).extracting("name")
                .containsExactlyInAnyOrder("renamed-target", "rename-other");
        assertThat(reloadedSeries.getEffectiveTags()).extracting("name")
                .containsExactlyInAnyOrder("renamed-target", "rename-other");
    }

    @Test
    void shouldRemoveOnlyTheSpecifiedTagEverywhereItIsReferenced()
    {
        // GIVEN
        Tag target = tag("remove-target");
        Tag other = tag("remove-other");
        Chapter c = chapterWithTags("remove-chapter", List.of(target, other));
        int seriesId = seriesService.create(
                seriesFormWithTagOverride("remove-series", List.of(target.getId(), other.getId())));
        em.flush();
        assertThat(chapterTagLink(c.getId(), target.getId())).isEqualTo(1);
        assertThat(seriesTagLink(seriesId, target.getId())).isEqualTo(1);
        assertThat(seriesEffectiveTagLink(seriesId, target.getId())).isEqualTo(1);

        // WHEN
        metadataService.remove(MetadataType.TAG, target.getId(), false);
        em.flush();
        em.clear();

        // THEN the tag is gone from the entity and all three join tables with no FK violation; the chapter,
        // the series and the "other" tag's links survive.
        assertThat(tagRepository.findById(target.getId())).isEmpty();
        assertThat(chapterTagLink(c.getId(), target.getId())).isZero();
        assertThat(seriesTagLink(seriesId, target.getId())).isZero();
        assertThat(seriesEffectiveTagLink(seriesId, target.getId())).isZero();

        assertThat(chapterRepository.findById(c.getId())).isPresent();
        assertThat(seriesRepository.findById(seriesId)).isPresent();
        assertThat(tagRepository.findById(other.getId())).isPresent();
        assertThat(chapterTagLink(c.getId(), other.getId())).isEqualTo(1);
        assertThat(seriesTagLink(seriesId, other.getId())).isEqualTo(1);
        assertThat(seriesEffectiveTagLink(seriesId, other.getId())).isEqualTo(1);
    }

    @Test
    void shouldMergeOnlyTheSpecifiedTagEverywhereItIsReferenced()
    {
        // GIVEN
        Tag source = tag("merge-src");
        Tag target = tag("merge-dst");
        Tag other = tag("merge-other");
        Chapter c = chapterWithTags("merge-chapter", List.of(source, other));
        Chapter c2 = chapterWithTags("merge-chapter2", List.of(source, other, target));
        Chapter c3 = chapterWithTags("merge-chapter3", List.of(other, target));
        Chapter c4 = chapterWithTags("merge-chapter4", List.of(other));
        int seriesId = seriesService.create(
                seriesFormWithTagOverride("merge-series", List.of(source.getId(), other.getId())));
        int seriesId2 = seriesService.create(
                seriesFormWithTagOverride("merge-series2", List.of(source.getId(), other.getId(), target.getId())));
        em.flush();

        // WHEN
        metadataService.merge(MetadataType.TAG, source.getId(), target.getId(), false);
        em.flush();
        em.clear();

        // THEN the source tag is deleted and all its links in the three join tables move to the target;
        // the "other" tag's links are untouched.
        assertThat(tagRepository.findById(source.getId())).isEmpty();
        assertThat(tagRepository.findById(other.getId())).isPresent();
        assertThat(tagRepository.findById(target.getId())).isPresent();

        assertThat(chapterTagLink(c.getId(), target.getId())).isEqualTo(1);
        assertThat(chapterTagLink(c.getId(), source.getId())).isZero();
        assertThat(chapterTagLink(c.getId(), other.getId())).isEqualTo(1);
        assertThat(chapterTagLink(c2.getId(), target.getId())).isEqualTo(1); // No duplicates
        assertThat(chapterTagLink(c2.getId(), source.getId())).isZero();
        assertThat(chapterTagLink(c2.getId(), other.getId())).isEqualTo(1);
        assertThat(chapterTagLink(c3.getId(), target.getId())).isEqualTo(1); // No duplicates
        assertThat(chapterTagLink(c3.getId(), source.getId())).isZero();
        assertThat(chapterTagLink(c3.getId(), other.getId())).isEqualTo(1);
        assertThat(chapterTagLink(c4.getId(), target.getId())).isZero(); // Should not gain extra tag
        assertThat(chapterTagLink(c4.getId(), source.getId())).isZero();
        assertThat(chapterTagLink(c4.getId(), other.getId())).isEqualTo(1);

        assertThat(seriesTagLink(seriesId, target.getId())).isEqualTo(1);
        assertThat(seriesTagLink(seriesId, source.getId())).isZero();
        assertThat(seriesTagLink(seriesId, other.getId())).isEqualTo(1);
        assertThat(seriesTagLink(seriesId2, target.getId())).isEqualTo(1); // No duplicates
        assertThat(seriesTagLink(seriesId2, source.getId())).isZero();
        assertThat(seriesTagLink(seriesId2, other.getId())).isEqualTo(1);

        assertThat(seriesEffectiveTagLink(seriesId, target.getId())).isEqualTo(1);
        assertThat(seriesEffectiveTagLink(seriesId, source.getId())).isZero();
        assertThat(seriesEffectiveTagLink(seriesId, other.getId())).isEqualTo(1);
        assertThat(seriesEffectiveTagLink(seriesId2, target.getId())).isEqualTo(1); // No duplicates
        assertThat(seriesEffectiveTagLink(seriesId2, source.getId())).isZero();
        assertThat(seriesEffectiveTagLink(seriesId2, other.getId())).isEqualTo(1);
    }

    // --- rename onto a name that is already taken -------------------------------------------
    // The name columns are UNIQUE, so such a rename must be refused up front: at commit SQLite would throw
    // from a dead transaction and the user would get a 500. Each test flushes, which fails without the check.

    @Test
    void shouldRefuseToRenameOntoANameAnotherItemAlreadyHas()
    {
        // GIVEN two tags, the one to be renamed carrying a chapter link so a half-done rename would show.
        Tag keeper = tag("taken-keeper");
        Tag renamed = tag("taken-renamed");
        Chapter c = chapterWithTags("taken-chapter", List.of(renamed));
        em.flush();

        // WHEN the user renames the second onto the first's name, asking for a rule as well.
        var refusal = metadataService.rename(MetadataType.TAG, renamed.getId(), "taken-keeper", true);
        em.flush();
        em.clear();

        // THEN it is refused, naming what is in the way.
        assertThat(refusal).get().isInstanceOfSatisfying(MetadataService.NameTaken.class, taken ->
        {
            assertThat(taken.type()).isEqualTo(MetadataType.TAG);
            assertThat(taken.name()).isEqualTo("taken-keeper");
        });
        // AND nothing happened: both names stand, the link is untouched, and no rule was recorded.
        assertThat(tagRepository.findById(renamed.getId()).orElseThrow().getName()).isEqualTo("taken-renamed");
        assertThat(tagRepository.findById(keeper.getId()).orElseThrow().getName()).isEqualTo("taken-keeper");
        assertThat(chapterTagLink(c.getId(), renamed.getId())).isEqualTo(1);
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "taken-renamed")).isEmpty();
    }

    @Test
    void shouldRefuseARenameThatCollidesOnlyOnceTheNameIsFolded()
    {
        // GIVEN two tags whose names differ by more than case.
        Tag keeper = tag("taken-fold-keeper");
        Tag renamed = tag("taken-fold-renamed");
        em.flush();

        // WHEN the new name differs from the existing one only in case and padding.
        var refusal = metadataService.rename(MetadataType.TAG, renamed.getId(), "  TAKEN-Fold-Keeper  ", false);
        em.flush();
        em.clear();

        // THEN it is refused under the folded name.
        assertThat(refusal).get().isInstanceOfSatisfying(MetadataService.NameTaken.class,
                taken -> assertThat(taken.name()).isEqualTo("taken-fold-keeper"));
        assertThat(tagRepository.findById(renamed.getId()).orElseThrow().getName()).isEqualTo("taken-fold-renamed");
        assertThat(tagRepository.findById(keeper.getId()).orElseThrow().getName()).isEqualTo("taken-fold-keeper");
    }

    @Test
    void shouldAllowRenamingAnItemOntoItsOwnName()
    {
        // GIVEN one tag.
        Tag subject = tag("taken-self");
        em.flush();

        // WHEN it is renamed onto another spelling of its own name.
        var refusal = metadataService.rename(MetadataType.TAG, subject.getId(), "TAKEN-Self", true);
        em.flush();
        em.clear();

        // THEN the no-op is allowed and no rule is recorded, since it would rule out the row's own name.
        assertThat(refusal).isEmpty();
        assertThat(tagRepository.findById(subject.getId()).orElseThrow().getName()).isEqualTo("taken-self");
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "taken-self")).isEmpty();
    }

    // --- merge an item into itself ----------------------------------------------------------

    @Test
    void shouldChangeNothingWhenAnItemIsMergedIntoItself()
    {
        // GIVEN a tag carried by a chapter and by a series override.
        Tag subject = tag("self-merge");
        Chapter c = chapterWithTags("self-merge-chapter", List.of(subject));
        int seriesId = seriesService.create(
                seriesFormWithTagOverride("self-merge-series", List.of(subject.getId())));
        em.flush();

        // WHEN source and target are the same item, which the Merge form lets the user pick.
        metadataService.merge(MetadataType.TAG, subject.getId(), subject.getId(), true);
        em.flush();
        em.clear();

        // THEN the row survives (a merge deletes its source), every link stays, and no rule is recorded:
        // it would refuse every later add or rename of that name.
        assertThat(tagRepository.findById(subject.getId())).isPresent();
        assertThat(chapterTagLink(c.getId(), subject.getId())).isEqualTo(1);
        assertThat(seriesTagLink(seriesId, subject.getId())).isEqualTo(1);
        assertThat(seriesEffectiveTagLink(seriesId, subject.getId())).isEqualTo(1);
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "self-merge")).isEmpty();
    }

    // --- recent() behaviour ---
    // A needle that expects an exact list is unique to this suite: the web suites commit tags such as
    // "mweb-add-tag", which a needle like "tag" would find too.

    @Test
    void shouldReturnNewestFirstWhenRecentHasNoQuery()
    {
        // GIVEN
        Tag first = tag("alpha");
        Tag second = tag("beta");
        Tag third = tag("gamma");
        em.flush();

        // WHEN
        var results = metadataService.recent(MetadataType.TAG, null, 3);

        // THEN
        assertThat(results).extracting("id")
                .containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    void shouldRespectLimitWhenRecentHasNoQuery()
    {
        // GIVEN
        tag("a");
        tag("b");
        tag("c");
        tag("d");
        em.flush();

        // THEN
        assertThat(metadataService.recent(MetadataType.TAG, null, 2)).hasSize(2);
    }

    @Test
    void shouldSortByNameAlphabeticallyWhenRecentHasQuery()
    {
        // GIVEN
        // Reverse alphabetical order, to prove insertion order is not used.
        tag("recent-sort-c");
        tag("recent-sort-a");
        tag("recent-sort-b");
        em.flush();

        // WHEN
        var results = metadataService.recent(MetadataType.TAG, "recent-sort", 10);

        // THEN
        assertThat(results).extracting("label")
                .containsExactly("recent-sort-a", "recent-sort-b", "recent-sort-c");
    }

    @Test
    void shouldFloatExactMatchFirstWhenRecentHasQuery()
    {
        // GIVEN
        tag("recent-exact");
        tag("recent-exact extended");
        tag("another recent-exact");
        em.flush();

        // WHEN
        var results = metadataService.recent(MetadataType.TAG, "recent-exact", 10);

        // THEN
        assertThat(results).extracting("label")
                .containsExactly("recent-exact", "another recent-exact", "recent-exact extended");
    }

    @Test
    void shouldReturnEmptyWhenRecentQueryHasNoMatch()
    {
        // GIVEN
        tag("apple");
        tag("banana");
        em.flush();

        // THEN
        assertThat(metadataService.recent(MetadataType.TAG, "zzz-no-match", 10)).isEmpty();
    }

    @Test
    void shouldBehaveLikeNoQueryWhenRecentQueryIsEmptyString()
    {
        // GIVEN
        Tag first = tag("x");
        Tag second = tag("y");
        em.flush();

        // WHEN
        var results = metadataService.recent(MetadataType.TAG, "", 2);

        // THEN
        assertThat(results).extracting("id")
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void shouldBehaveLikeNoQueryWhenRecentQueryIsBlank()
    {
        // GIVEN
        Tag first = tag("m");
        Tag second = tag("n");
        em.flush();

        // WHEN
        var results = metadataService.recent(MetadataType.TAG, "   ", 2);

        // THEN
        assertThat(results).extracting("id")
                .containsExactly(second.getId(), first.getId());
    }

    // --- autocomplete() --------------------------------------------------------

    @Test
    void shouldFloatExactMatchFirstThenAlphabeticalWhenAutocompleting()
    {
        // GIVEN
        // add() evicts the metadata cache, so autocomplete reads a fresh list.
        metadataService.add(MetadataType.TAG, "auto-apple");
        metadataService.add(MetadataType.TAG, "auto");           // exact match for the query below
        metadataService.add(MetadataType.TAG, "auto-apricot");
        em.flush();

        // WHEN
        List<OptionDto> results = metadataService.autocomplete(MetadataType.TAG, "auto", 10);

        // THEN
        assertThat(results).extracting("label")
                .containsSubsequence("auto", "auto-apple", "auto-apricot");
        assertThat(results.get(0).getLabel()).isEqualTo("auto");
    }

    @Test
    void shouldRespectLimitAndSubstringWhenAutocompleting()
    {
        // GIVEN
        metadataService.add(MetadataType.TAG, "ac-limit-1");
        metadataService.add(MetadataType.TAG, "ac-limit-2");
        metadataService.add(MetadataType.TAG, "ac-limit-3");
        metadataService.add(MetadataType.TAG, "unrelated-name");
        em.flush();

        // THEN
        assertThat(metadataService.autocomplete(MetadataType.TAG, "ac-limit", 2)).hasSize(2);
        assertThat(metadataService.autocomplete(MetadataType.TAG, "ac-limit", 10))
                .extracting("label").allMatch(l -> l.toString().contains("ac-limit"));
    }

    /**
     * The cached list must be reached through {@code MetadataCatalog}'s proxy, or every autocomplete keystroke
     * re-reads the whole table. Proven by a row added behind the service's back staying invisible until eviction.
     */
    @Test
    void shouldAnswerAutocompleteFromTheCachedListUntilItIsEvicted()
    {
        // GIVEN one tag, read once so the list is remembered.
        tag("cache-proof-first");
        em.flush();
        assertThat(metadataService.autocomplete(MetadataType.TAG, "cache-proof", 10))
                .extracting("label").containsExactly("cache-proof-first");
        assertThat(cacheManager.getCache(CacheConfig.METADATA).get(MetadataType.TAG)).isNotNull();

        // WHEN a second tag is created behind MetadataService's back, so nothing evicts.
        tag("cache-proof-second");
        em.flush();

        // THEN the cached list still answers.
        assertThat(metadataService.autocomplete(MetadataType.TAG, "cache-proof", 10))
                .extracting("label").containsExactly("cache-proof-first");
        assertThat(metadataService.recent(MetadataType.TAG, "cache-proof", 10))
                .extracting("label").containsExactly("cache-proof-first");

        // AND once it is evicted, both readers see the new row.
        catalog.evict(MetadataType.TAG);
        assertThat(metadataService.autocomplete(MetadataType.TAG, "cache-proof", 10))
                .extracting("label").containsExactly("cache-proof-first", "cache-proof-second");
        assertThat(metadataService.recent(MetadataType.TAG, "cache-proof", 10))
                .extracting("label").containsExactly("cache-proof-first", "cache-proof-second");
    }

    /** {@code allFresh} is the uncached read the write paths use; a warm cache must not affect it. */
    @Test
    void shouldIgnoreTheCachedListWhenReadingFresh()
    {
        // GIVEN a warm cache holding one tag.
        tag("fresh-proof-first");
        em.flush();
        assertThat(catalog.all(MetadataType.TAG)).extracting("label").contains("fresh-proof-first");

        // WHEN another tag is created behind MetadataService's back.
        tag("fresh-proof-second");
        em.flush();

        // THEN the cached read still misses it, while the fresh read sees both.
        assertThat(catalog.all(MetadataType.TAG))
                .extracting("label").doesNotContain("fresh-proof-second");
        assertThat(catalog.allFresh(MetadataType.TAG))
                .extracting("label").contains("fresh-proof-first", "fresh-proof-second");
    }

    // --- resolve() -------------------------------------------------------------

    @Test
    void shouldPreserveRequestedOrderAndSkipUnknownIdsWhenResolving()
    {
        // GIVEN
        metadataService.add(MetadataType.TAG, "resolve-x");
        metadataService.add(MetadataType.TAG, "resolve-y");
        em.flush();

        Integer x = idOf("resolve-x");
        Integer y = idOf("resolve-y");

        // WHEN
        // y then x, plus an unknown id that must be dropped.
        List<OptionDto> resolved = metadataService.resolve(MetadataType.TAG, List.of(y, 9_999_999, x));

        // THEN
        assertThat(resolved).extracting("label").containsExactly("resolve-y", "resolve-x");
    }

    @Test
    void shouldReturnEmptyWhenResolvingNullOrEmptyIds()
    {
        // THEN
        assertThat(metadataService.resolve(MetadataType.TAG, null)).isEmpty();
        assertThat(metadataService.resolve(MetadataType.TAG, List.of())).isEmpty();
    }

    // --- non-TAG types (Group has a legacy entity table name; exercise the generic path) ----

    @Test
    void shouldMergeGroupTypeWhenEntityHasLegacyTableName()
    {
        // GIVEN
        Group source = group("grp-src");
        Group target = group("grp-dst");
        Chapter c = chapterWithGroup(source);
        em.flush();

        // WHEN
        metadataService.merge(MetadataType.GROUP, source.getId(), target.getId(), false);
        em.flush();
        em.clear();

        // THEN
        assertThat(groupRepository.findById(source.getId())).isEmpty();
        Integer linkedToTarget = jdbc.queryForObject(
                "select count(*) from chapter_groups where chapter_id=? and group_id=?",
                Integer.class, c.getId(), target.getId());
        assertThat(linkedToTarget).isEqualTo(1);
    }

    // --- merge dedupe (owner already linked to both source and target) ---------------------

    @Test
    void shouldSkipTheSourceLinkWhenTheOwnerAlreadyHasTheTarget()
    {
        // GIVEN
        Tag source = tag("dedupe-src");
        Tag target = tag("dedupe-dst");
        Chapter c = chapterWith(source);
        c.setTags(new ArrayList<>(List.of(source, target)));   // already holds BOTH before the merge
        chapterRepository.save(c);
        em.flush();

        // WHEN
        // A naive "UPDATE ... SET tag_id = target" would duplicate the existing (c, target) link here.
        metadataService.merge(MetadataType.TAG, source.getId(), target.getId(), false);
        em.flush();
        em.clear();

        // THEN
        assertThat(tagRepository.findById(source.getId())).isEmpty();
        Integer targetLinks = jdbc.queryForObject(
                "select count(*) from chapter_tags where chapter_id=? and tag_id=?",
                Integer.class, c.getId(), target.getId());
        assertThat(targetLinks).isEqualTo(1);
    }

    // --- add() dedupe -----------------------------------------------------------------------

    @Test
    void shouldNotDuplicateWhenAddingSameNameTwice()
    {
        // WHEN
        metadataService.add(MetadataType.TAG, "unique-tag");
        metadataService.add(MetadataType.TAG, "unique-tag");
        em.flush();

        // THEN
        long count = tagRepository.findAll().stream()
                .filter(t -> t.getName().equals("unique-tag")).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldBlockDuplicateNameWhenAddingCaseInsensitively()
    {
        // WHEN
        metadataService.add(MetadataType.TAG, "Case-Tag");
        metadataService.add(MetadataType.TAG, "case-tag");
        metadataService.add(MetadataType.TAG, "CASE-TAG");
        em.flush();

        // THEN
        long count = tagRepository.findAll().stream()
                .filter(t -> t.getName().equalsIgnoreCase("case-tag")).count();
        assertThat(count).isEqualTo(1);
        // AND the one row holds the folded name, not the first capitalisation added.
        assertThat(tagRepository.findByNameIgnoreCase("case-tag").orElseThrow().getName()).isEqualTo("case-tag");
    }

    // --- names are stored folded ------------------------------------------------------------

    @Test
    void shouldStoreTheFoldedNameWhenAddingRenamingAndImporting()
    {
        // WHEN each of the three ways a name reaches the database is given a mixed-case one.
        metadataService.add(MetadataType.TAG, "  Fold-On-Add  ");
        List<Integer> imported = metadataService.resolveOrCreate(MetadataType.TAG, List.of(" Fold-On-Import "));
        Tag renamed = tag("fold-before-rename");
        assertThat(metadataService.rename(MetadataType.TAG, renamed.getId(), "Fold-On-Rename", false)).isEmpty();
        em.flush();
        em.clear();

        // THEN all three are stored trimmed and in lower case, so chips and search agree with name matching.
        assertThat(tagRepository.findByNameIgnoreCase("fold-on-add").orElseThrow().getName())
                .isEqualTo("fold-on-add");
        assertThat(imported).hasSize(1);
        assertThat(tagRepository.findById(imported.get(0)).orElseThrow().getName()).isEqualTo("fold-on-import");
        assertThat(tagRepository.findById(renamed.getId()).orElseThrow().getName()).isEqualTo("fold-on-rename");
    }

    @Test
    void shouldResolveToOneIdWhenAnImportCarriesTwoCapitalisationsOfOneName()
    {
        // WHEN one gallery spells the same tag three ways.
        List<Integer> ids = metadataService.resolveOrCreate(
                MetadataType.TAG, List.of("Fold-Dupe", "fold-dupe", "FOLD-DUPE"));
        em.flush();
        em.clear();

        // THEN they were one value all along: one id, one row.
        assertThat(ids).hasSize(1);
        assertThat(tagRepository.findAll().stream().filter(t -> t.getName().equalsIgnoreCase("fold-dupe")))
                .hasSize(1);
        assertThat(tagRepository.findById(ids.get(0)).orElseThrow().getName()).isEqualTo("fold-dupe");
    }

    /**
     * Folding with the default locale under a Turkish one ({@code "BIG SISTER"} -> {@code "bıg sıster"})
     * would match nothing and create a twin row no ASCII spelling can find. Imports are where names become rows.
     */
    @Test
    void shouldReuseTheExistingRowWhenImportingUnderALocaleThatFoldsTheLetterIDifferently()
    {
        // GIVEN a tag already stored folded, as every write path stores it.
        Tag existing = tag("big sister");
        em.flush();
        em.clear();
        var originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
        try
        {
            // WHEN a download brings the same tag in, spelled in capitals.
            List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG, List.of("BIG SISTER"));
            em.flush();
            em.clear();

            // THEN it resolved to the row that was already there...
            assertThat(ids).containsExactly(existing.getId());
            // ...no dotless-i twin was forked alongside it...
            assertThat(tagRepository.findAll()).extracting(Tag::getName).doesNotContain("bıg sıster");
            // ...and the in-memory filters fold the same way, so the chip the user searches for is found.
            assertThat(metadataService.autocomplete(MetadataType.TAG, "BIG SIS", 10))
                    .extracting(OptionDto::getLabel).contains("big sister");
        }
        finally
        {
            Locale.setDefault(originalLocale);
        }
    }

    private Chapter chapterWithGroup(Group group)

    {
        Chapter c = new Chapter();
        c.setTitle("c");
        c.setTitleFull("merge-group-chapter-" + group.getName());
        c.setNativeTitle("x");
        c.setUploadDate(LocalDate.of(2020, 1, 1));
        c.setLanguage("en");
        c.setGroups(new ArrayList<>(List.of(group)));
        return chapterRepository.save(c);
    }

    private Integer idOf(String label)
    {
        return catalog.allFresh(MetadataType.TAG).stream()
                .filter(o -> o.getLabel().equals(label)).findFirst().orElseThrow().getId();
    }

    private SeriesForm seriesFormWithTagOverride(String titleFull, List<Integer> tagIds)
    {
        SeriesForm f = new SeriesForm();
        f.setTitleFull(titleFull);
        f.setStatus(Status.REVIEWED);
        f.setTagIds(new ArrayList<>(tagIds));
        return f;
    }

    private int chapterTagLink(int chapterId, int tagId)
    {
        return jdbc.queryForObject(
                "select count(*) from chapter_tags where chapter_id=? and tag_id=?",
                Integer.class, chapterId, tagId);
    }

    private int seriesTagLink(int seriesId, int tagId)
    {
        return jdbc.queryForObject(
                "select count(*) from series_tags where series_id=? and tag_id=?",
                Integer.class, seriesId, tagId);
    }

    private int seriesEffectiveTagLink(int seriesId, int tagId)
    {
        return jdbc.queryForObject(
                "select count(*) from series_effective_tags where series_id=? and tag_id=?",
                Integer.class, seriesId, tagId);
    }

    // --- canonical tag names -----------------------------------------------------------------

    @Test
    void shouldStoreImportedTagsInTheirCanonicalSpelling()
    {
        // GIVEN a tag already stored in its canonical spelling.
        Tag existing = tag("canon-halo \u2640");

        // WHEN an import names it with e-hentai's namespace, alongside namespaces that add nothing.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG,
                List.of("female:Canon-Halo", "other:canon full color", "male:canon-halo", "mixed:canon-group"));
        em.flush();
        em.clear();

        // THEN the namespaced name joins the stored row, and the rest are stored without their namespace (a
        // gendered tag with its plain one beside it).
        assertThat(ids).hasSize(5).startsWith(existing.getId());
        assertThat(ids.subList(1, 5)).extracting(id -> tagRepository.findById(id).orElseThrow().getName())
                .containsExactly("canon-halo", "canon full color", "canon-halo \u2642", "canon-group");
    }

    @Test
    void shouldCanonicalizeATagAddedOrRenamedByHand()
    {
        // GIVEN a tag to rename.
        Tag subject = tag("canon-rename-me");

        // WHEN one tag is added and the other renamed with namespaces (a plain tag keeps a plain name).
        metadataService.add(MetadataType.TAG, "female:canon-added");
        var refusal = metadataService.rename(MetadataType.TAG, subject.getId(), "other:canon-renamed", false);
        em.flush();
        em.clear();

        // THEN both are stored in the canonical spelling.
        assertThat(refusal).isEmpty();
        assertThat(tagRepository.findAll()).extracting(Tag::getName)
                .contains("canon-added \u2640", "canon-renamed")
                .doesNotContain("female:canon-added", "other:canon-renamed");
    }

    @Test
    void shouldRefuseARenameWhoseCanonicalNameAnotherItemHas()
    {
        // GIVEN a tag holding a canonical name, and another tag.
        tag("canon-taken");
        Tag subject = tag("canon-taken-subject");

        // WHEN the second is renamed to the namespaced spelling of the first.
        var refusal = metadataService.rename(MetadataType.TAG, subject.getId(), "mixed:canon-taken", false);

        // THEN it is refused, naming the canonical name.
        assertThat(refusal).containsInstanceOf(MetadataService.NameTaken.class);
        assertThat(refusal.orElseThrow().name()).isEqualTo("canon-taken");
    }
}

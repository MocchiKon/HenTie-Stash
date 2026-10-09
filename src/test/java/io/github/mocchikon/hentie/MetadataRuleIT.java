package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.*;
import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.service.MetadataRuleService;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.download.GalleryImportService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metadata rules and {@code MetadataService.resolveOrCreate}, the one seam where they are applied. Without
 * them the next download brings a deleted, merged or renamed value straight back.
 * <p>
 * Two invariants break silently, so they get as much attention as the happy paths:
 * <ul>
 *   <li><b>a rule never names a row that is gone</b>, or a dangling id lands in a join table;</li>
 *   <li><b>a ruled-out name has no row</b>: add and rename refuse one, and imports never create one.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class MetadataRuleIT
{
    @Autowired MetadataService metadataService;
    @Autowired MetadataRuleService ruleService;
    @Autowired MetadataRuleRepository ruleRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired GroupRepository groupRepository;
    @Autowired ChapterRepository chapterRepository;
    @Autowired GalleryImportService importService;
    @PersistenceContext EntityManager em;

    // --- a removed item stays removed ------------------------------------------------------

    @Test
    void shouldDropTheNameWhenAnImportBringsBackARemovedItem()
    {
        // GIVEN a tag the user deleted, asking for the decision to be remembered.
        Tag removed = tag("rule-removed");
        metadataService.remove(MetadataType.TAG, removed.getId(), true);
        flushAndClear();

        // WHEN a download brings that very name in again.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG, List.of("rule-removed"));
        flushAndClear();

        // THEN nothing is linked and nothing is re-created.
        assertThat(ids).isEmpty();
        assertThat(tagRepository.findByNameIgnoreCase("rule-removed")).isEmpty();
        MetadataRule rule = rule(MetadataType.TAG, "rule-removed");
        assertThat(rule.isBlocking()).isTrue();
        assertThat(rule.getSourceNameLower()).isEqualTo("rule-removed");
        assertThat(rule.getType()).isEqualTo(MetadataType.TAG);
    }

    @Test
    void shouldKeepRecreatingTheNameWhenTheRuleWasNotRequested()
    {
        // GIVEN the same delete with the "Add rule" box cleared - a one-off, not a standing decision.
        Tag removed = tag("rule-no-rule");
        metadataService.remove(MetadataType.TAG, removed.getId(), false);
        flushAndClear();

        // WHEN the name comes back.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG, List.of("rule-no-rule"));
        flushAndClear();

        // THEN it is created again.
        assertThat(ids).hasSize(1);
        assertThat(tagRepository.findByNameIgnoreCase("rule-no-rule")).isPresent();
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "rule-no-rule")).isEmpty();
    }

    // --- a merged / renamed name maps onto the item it went to ------------------------------

    @Test
    void shouldRewriteToTheTargetWhenAnImportBringsBackAMergedName()
    {
        // GIVEN two tags merged into one.
        Tag source = tag("rule-merge-src");
        Tag target = tag("rule-merge-dst");
        metadataService.merge(MetadataType.TAG, source.getId(), target.getId(), true);
        flushAndClear();

        // WHEN a download brings the source name in.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG, List.of("rule-merge-src"));
        flushAndClear();

        // THEN it resolves to the surviving tag instead of re-creating the merged-away one.
        assertThat(ids).containsExactly(target.getId());
        assertThat(tagRepository.findByNameIgnoreCase("rule-merge-src")).isEmpty();
        assertThat(rule(MetadataType.TAG, "rule-merge-src").getTargetId()).isEqualTo(target.getId());
    }

    @Test
    void shouldRewriteToTheSameRowWhenAnImportBringsTheNameARenameReplaced()
    {
        // GIVEN a tag renamed, with the decision remembered.
        Tag renamed = tag("rule-rename-old");
        assertThat(metadataService.rename(MetadataType.TAG, renamed.getId(), "rule-rename-new", true)).isEmpty();
        flushAndClear();

        // WHEN a download brings the old name in.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG, List.of("rule-rename-old"));
        flushAndClear();

        // THEN it lands on the same row, still under its new name.
        assertThat(ids).containsExactly(renamed.getId());
        assertThat(tagRepository.findByNameIgnoreCase("rule-rename-old")).isEmpty();
        assertThat(tagRepository.findById(renamed.getId()).orElseThrow().getName()).isEqualTo("rule-rename-new");
    }

    // --- matching and batching ---------------------------------------------------------------

    @Test
    void shouldMatchTheRuleIgnoringCaseAndSurroundingSpace()
    {
        // GIVEN a blocking rule recorded for one exact spelling.
        Tag removed = tag("rule-Case");
        metadataService.remove(MetadataType.TAG, removed.getId(), true);
        flushAndClear();

        // WHEN the source spells it differently and pads it, as a scraper will.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG, List.of("  RULE-case  "));
        flushAndClear();

        // THEN it is still the same value.
        assertThat(ids).isEmpty();
        assertThat(tagRepository.findByNameIgnoreCase("rule-case")).isEmpty();
    }

    @Test
    void shouldNotListTheTargetTwiceWhenAnImportCarriesBothSidesOfAMerge()
    {
        // GIVEN a merge the user remembered.
        Tag source = tag("rule-both-src");
        Tag target = tag("rule-both-dst");
        metadataService.merge(MetadataType.TAG, source.getId(), target.getId(), true);
        flushAndClear();

        // WHEN one gallery carries both the old and the new name.
        List<Integer> ids = metadataService.resolveOrCreate(
                MetadataType.TAG, List.of("rule-both-src", "rule-both-dst"));
        flushAndClear();

        // THEN the rewrite collapses onto the value already there rather than naming it twice.
        assertThat(ids).containsExactly(target.getId());
    }

    @Test
    void shouldKeepTheOtherNamesInOrderWhenOneIsRuledOut()
    {
        // GIVEN one of three imported names ruled out.
        Tag removed = tag("rule-mixed-gone");
        metadataService.remove(MetadataType.TAG, removed.getId(), true);
        flushAndClear();

        // WHEN all three arrive together.
        List<Integer> ids = metadataService.resolveOrCreate(
                MetadataType.TAG, List.of("rule-mixed-first", "rule-mixed-gone", "rule-mixed-last"));
        flushAndClear();

        // THEN the survivors keep the source's order, and only the ruled-out one is missing.
        Integer first = tagRepository.findByNameIgnoreCase("rule-mixed-first").orElseThrow().getId();
        Integer last = tagRepository.findByNameIgnoreCase("rule-mixed-last").orElseThrow().getId();
        assertThat(ids).containsExactly(first, last);
        assertThat(tagRepository.findByNameIgnoreCase("rule-mixed-gone")).isEmpty();
    }

    // --- a rule never names a row that is gone -----------------------------------------------

    @Test
    void shouldDropTheNameWhenTheItemARuleRewroteToIsRemoved()
    {
        // GIVEN a rule rewriting one name to a tag...
        Tag source = tag("rule-orphan-src");
        Tag target = tag("rule-orphan-dst");
        metadataService.merge(MetadataType.TAG, source.getId(), target.getId(), true);
        flushAndClear();

        // WHEN that target is removed (without a rule of its own, to isolate the fixup).
        metadataService.remove(MetadataType.TAG, target.getId(), false);
        flushAndClear();

        // THEN the rule drops the name instead of keeping a dangling id.
        MetadataRule rule = rule(MetadataType.TAG, "rule-orphan-src");
        assertThat(rule.getTargetId()).isNull();
        assertThat(rule.isBlocking()).isTrue();

        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG, List.of("rule-orphan-src"));
        flushAndClear();
        assertThat(ids).isEmpty();
        assertThat(tagRepository.findByNameIgnoreCase("rule-orphan-src")).isEmpty();
    }

    @Test
    void shouldFollowTheTargetWhenTheItemARuleRewroteToIsMergedAway()
    {
        // GIVEN a rule rewriting one name to a tag...
        Tag first = tag("rule-chain-a");
        Tag second = tag("rule-chain-b");
        Tag third = tag("rule-chain-c");
        metadataService.merge(MetadataType.TAG, first.getId(), second.getId(), true);
        flushAndClear();

        // WHEN that target is merged into a third one.
        metadataService.merge(MetadataType.TAG, second.getId(), third.getId(), false);
        flushAndClear();

        // THEN the rule follows it, so there is one hop to resolve and never a chain.
        assertThat(rule(MetadataType.TAG, "rule-chain-a").getTargetId()).isEqualTo(third.getId());
        assertThat(metadataService.resolveOrCreate(MetadataType.TAG, List.of("rule-chain-a")))
                .containsExactly(third.getId());
    }

    @Test
    void shouldKeepRewritingToTheSameItemWhenItsNameChanges()
    {
        // GIVEN a rule rewriting one name to a tag...
        Tag source = tag("rule-follow-src");
        Tag target = tag("rule-follow-dst");
        metadataService.merge(MetadataType.TAG, source.getId(), target.getId(), true);
        flushAndClear();

        // WHEN the target is renamed (the reason a rule points at an id and not at a name).
        assertThat(metadataService.rename(MetadataType.TAG, target.getId(), "rule-follow-renamed", false)).isEmpty();
        flushAndClear();

        // THEN the rule still lands on that row, with no fixup needed.
        assertThat(metadataService.resolveOrCreate(MetadataType.TAG, List.of("rule-follow-src")))
                .containsExactly(target.getId());
        assertThat(tagRepository.findById(target.getId()).orElseThrow().getName()).isEqualTo("rule-follow-renamed");
        assertThat(tagRepository.findByNameIgnoreCase("rule-follow-dst")).isEmpty();
    }

    // --- a ruled-out name has no row ---------------------------------------------------------

    @Test
    void shouldRefuseToAddAnItemWhoseNameARuleRemoves()
    {
        // GIVEN a deleted tag whose name is now ruled out.
        Tag removed = tag("rule-readd");
        metadataService.remove(MetadataType.TAG, removed.getId(), true);
        flushAndClear();

        // WHEN the user tries to add that name back by hand.
        var refusal = metadataService.add(MetadataType.TAG, "rule-readd");
        flushAndClear();

        // THEN it is refused with a reason; a row the rule strips from every import would only look present.
        assertThat(refusal).get().isInstanceOfSatisfying(MetadataService.RuleConflict.class, conflict ->
        {
            assertThat(conflict.name()).isEqualTo("rule-readd");
            assertThat(conflict.targetName()).isNull();
            assertThat(conflict.type()).isEqualTo(MetadataType.TAG);
        });
        assertThat(tagRepository.findByNameIgnoreCase("rule-readd")).isEmpty();
    }

    @Test
    void shouldNameTheReplacementWhenRefusingANameARuleRewrites()
    {
        // GIVEN a merged-away name.
        Tag source = tag("rule-readd-src");
        Tag target = tag("rule-readd-dst");
        metadataService.merge(MetadataType.TAG, source.getId(), target.getId(), true);
        flushAndClear();

        // WHEN the user tries to add the source name back.
        var refusal = metadataService.add(MetadataType.TAG, "rule-readd-src");
        flushAndClear();

        // THEN the refusal says what the name maps to, so the page can explain it.
        assertThat(refusal).get().isInstanceOfSatisfying(MetadataService.RuleConflict.class, conflict ->
        {
            assertThat(conflict.name()).isEqualTo("rule-readd-src");
            assertThat(conflict.targetName()).isEqualTo("rule-readd-dst");
        });
        assertThat(tagRepository.findByNameIgnoreCase("rule-readd-src")).isEmpty();
    }

    @Test
    void shouldRefuseToRenameOntoANameARuleRulesOut()
    {
        // GIVEN a ruled-out name, and an unrelated tag.
        Tag removed = tag("rule-taken");
        metadataService.remove(MetadataType.TAG, removed.getId(), true);
        Tag other = tag("rule-renamer");
        flushAndClear();

        // WHEN the user tries to rename the other tag onto it.
        var conflict = metadataService.rename(MetadataType.TAG, other.getId(), "rule-taken", false);
        flushAndClear();

        // THEN the rule refuses the rename (no row holds the name), and nothing changed.
        assertThat(conflict).get().isInstanceOfSatisfying(MetadataService.RuleConflict.class, rule ->
        {
            assertThat(rule.type()).isEqualTo(MetadataType.TAG);
            assertThat(rule.name()).isEqualTo("rule-taken");
            assertThat(rule.targetName()).isNull();
        });
        assertThat(tagRepository.findById(other.getId()).orElseThrow().getName()).isEqualTo("rule-renamer");
    }

    @Test
    void shouldDropTheRedundantRuleWhenAnItemIsRenamedBackToItsOwnRuledName()
    {
        // GIVEN a tag renamed away, leaving "old name -> this row".
        Tag subject = tag("rule-undo-old");
        assertThat(metadataService.rename(MetadataType.TAG, subject.getId(), "rule-undo-new", true)).isEmpty();
        flushAndClear();
        assertThat(rule(MetadataType.TAG, "rule-undo-old").getTargetId()).isEqualTo(subject.getId());

        // WHEN the user renames the same row back.
        var conflict = metadataService.rename(MetadataType.TAG, subject.getId(), "rule-undo-old", false);
        flushAndClear();

        // THEN it is allowed, since the rule points at this very row, and the now pointless rule goes.
        assertThat(conflict).isEmpty();
        assertThat(tagRepository.findById(subject.getId()).orElseThrow().getName()).isEqualTo("rule-undo-old");
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "rule-undo-old")).isEmpty();
    }

    @Test
    void shouldRecordNoRuleWhenARenameOnlyChangesTheCaseOfTheName()
    {
        // GIVEN a tag being tidied up rather than renamed.
        Tag subject = tag("rule-case-only");

        // WHEN only its capitalisation changes, with "Add rule" left ticked.
        var conflict = metadataService.rename(MetadataType.TAG, subject.getId(), "Rule-Case-Only", true);
        flushAndClear();

        // THEN the stored (folded) name is unchanged and no rule is recorded: it would rule out the row's
        // own name.
        assertThat(conflict).isEmpty();
        assertThat(tagRepository.findById(subject.getId()).orElseThrow().getName()).isEqualTo("rule-case-only");
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "rule-case-only")).isEmpty();
    }

    // --- every metadata kind, not just tags ---------------------------------------------------

    @Test
    void shouldApplyRulesForTheOtherMetadataKindsToo()
    {
        // GIVEN a removed group (whose table is the legacy "group_artists") and a merged artist.
        Group removedGroup = group("rule-group-gone");
        metadataService.remove(MetadataType.GROUP, removedGroup.getId(), true);
        Artist artistSource = artist("rule-artist-src");
        Artist artistTarget = artist("rule-artist-dst");
        metadataService.merge(MetadataType.ARTIST, artistSource.getId(), artistTarget.getId(), true);
        flushAndClear();

        // WHEN an import brings both names in.
        List<Integer> groupIds = metadataService.resolveOrCreate(MetadataType.GROUP, List.of("rule-group-gone"));
        List<Integer> artistIds = metadataService.resolveOrCreate(MetadataType.ARTIST, List.of("rule-artist-src"));
        flushAndClear();

        // THEN the generic path treats them exactly like tags.
        assertThat(groupIds).isEmpty();
        assertThat(groupRepository.findByNameIgnoreCase("rule-group-gone")).isEmpty();
        assertThat(artistIds).containsExactly(artistTarget.getId());
        assertThat(artistRepository.findByNameIgnoreCase("rule-artist-src")).isEmpty();
    }

    // --- the download path end to end ---------------------------------------------------------

    @Test
    void shouldNotGiveADownloadedChapterAValueARuleRulesOut()
    {
        // GIVEN a deleted tag and a merged artist, both remembered as rules.
        Tag blocked = tag("rule-dl-blocked");
        metadataService.remove(MetadataType.TAG, blocked.getId(), true);
        Artist source = artist("rule-dl-src");
        Artist target = artist("rule-dl-dst");
        metadataService.merge(MetadataType.ARTIST, source.getId(), target.getId(), true);
        flushAndClear();

        // WHEN a gallery carrying both is imported.
        var data = GalleryData.builder()
                .id("7001")
                .fullTitle("[Test] Rule Gallery 7001")
                .language("english")
                .tags(Set.of("rule-dl-blocked", "rule-dl-kept"))
                .artists(Set.of("rule-dl-src"))
                .build();
        int chapterId = importService.importChapter(data, "mock:rule-7001");
        flushAndClear();

        // THEN the chapter carries what the rules say, and the ruled-out tag was not re-created.
        Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
        assertThat(chapter.getTags()).extracting("name").containsExactly("rule-dl-kept");
        assertThat(chapter.getArtists()).extracting("name").containsExactly("rule-dl-dst");
        assertThat(tagRepository.findByNameIgnoreCase("rule-dl-blocked")).isEmpty();
        assertThat(artistRepository.findByNameIgnoreCase("rule-dl-src")).isEmpty();
    }

    // --- recording, listing, paging -------------------------------------------------------------

    @Test
    void shouldReplaceTheStandingRuleWhenTheSameNameIsRuledAgain()
    {
        // GIVEN a name blocked outright.
        ruleService.recordBlock(MetadataType.PARODY, "rule-upsert");
        flushAndClear();

        // WHEN the same name is later ruled again, this time as a rewrite.
        ruleService.recordRewrite(MetadataType.PARODY, "RULE-Upsert", 4242);
        flushAndClear();

        // THEN there is still exactly one rule for it: one name has one fate.
        assertThat(ruleRepository.findByTypeAndSourceNameLowerIn(MetadataType.PARODY, List.of("rule-upsert")))
                .hasSize(1);
        MetadataRule rule = rule(MetadataType.PARODY, "rule-upsert");
        assertThat(rule.getTargetId()).isEqualTo(4242);
        // Stored under the folded name, whatever case it was given in.
        assertThat(rule.getSourceNameLower()).isEqualTo("rule-upsert");
    }

    @Test
    void shouldDropTheNameWhenARuleRewritesToAnItemThatIsNoLongerThere()
    {
        // GIVEN a rule pointing at an id with no row, as a hand-edited database could leave.
        ruleService.recordRewrite(MetadataType.TAG, "rule-dangling", 9_999_999);
        flushAndClear();

        // WHEN an import brings that name in.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG, List.of("rule-dangling"));
        flushAndClear();

        // THEN the name is dropped rather than written into a join table as an id that resolves to nothing.
        assertThat(ids).isEmpty();
        assertThat(tagRepository.findByNameIgnoreCase("rule-dangling")).isEmpty();
    }

    @Test
    void shouldPageTheRulesNewestFirstWhenListingThem()
    {
        // GIVEN more rules of one kind than fit on a page, recorded directly.
        int total = MetadataRuleService.PAGE_SIZE + 2;
        var recorded = new ArrayList<String>();
        for (int i = 0; i < total; i++)
        {
            String name = String.format("rule-page-%02d", i);
            ruleService.recordBlock(MetadataType.CHARACTER, name);
            recorded.add(name);
        }
        flushAndClear();

        // WHEN the two pages are read.
        var firstPage = ruleService.page(MetadataType.CHARACTER, 0);
        var secondPage = ruleService.page(MetadataType.CHARACTER, 1);

        // THEN the newest decision leads, the page is full, and the two oldest of these fall to page two.
        assertThat(firstPage.getContent()).hasSize(MetadataRuleService.PAGE_SIZE);
        assertThat(firstPage.getTotalElements()).isGreaterThanOrEqualTo(total);
        assertThat(firstPage.getContent().get(0).sourceName()).isEqualTo(recorded.get(total - 1));
        assertThat(firstPage.getContent()).extracting("sourceName")
                .doesNotContain(recorded.get(0), recorded.get(1));
        assertThat(secondPage.getContent()).extracting("sourceName")
                .startsWith(recorded.get(1), recorded.get(0));
        assertThat(firstPage.getContent()).allSatisfy(row -> assertThat(row.blocking()).isTrue());
    }

    @Test
    void shouldResolveTheTargetToItsCurrentNameWhenListingRules()
    {
        // GIVEN a rewrite rule whose target has since been renamed.
        Tag source = tag("rule-view-src");
        Tag target = tag("rule-view-dst");
        metadataService.merge(MetadataType.TAG, source.getId(), target.getId(), true);
        assertThat(metadataService.rename(MetadataType.TAG, target.getId(), "rule-view-renamed", false)).isEmpty();
        flushAndClear();

        // WHEN the rules page reads it.
        var row = ruleService.page(MetadataType.TAG, 0).getContent().stream()
                .filter(r -> "rule-view-src".equals(r.sourceName())).findFirst().orElseThrow();

        // THEN it shows the name the rule would actually produce today, not the one it was recorded with.
        assertThat(row.blocking()).isFalse();
        assertThat(row.targetName()).isEqualTo("rule-view-renamed");
        assertThat(row.id()).isNotNull();
    }

    @Test
    void shouldListTheFoldedNameWhenTheRuleWasRecordedFromAMixedCaseOne()
    {
        // GIVEN a tag deleted under a mixed-case name.
        Tag removed = tag("Rule-Listed-Case");
        metadataService.remove(MetadataType.TAG, removed.getId(), true);
        flushAndClear();

        // WHEN the rules page reads it.
        var row = ruleService.page(MetadataType.TAG, 0).getContent().stream()
                .filter(r -> "rule-listed-case".equals(r.sourceName())).findFirst().orElseThrow();

        // THEN it is listed folded; the original capitalisation would suggest other spellings slip past it.
        assertThat(row.sourceName()).isEqualTo("rule-listed-case");
        assertThat(row.blocking()).isTrue();
        assertThat(row.targetName()).isNull();
    }

    @Test
    void shouldForgetTheDecisionWhenTheRuleIsRemoved()
    {
        // GIVEN a removed tag whose name is ruled out.
        Tag removed = tag("rule-forget");
        metadataService.remove(MetadataType.TAG, removed.getId(), true);
        flushAndClear();
        assertThat(metadataService.resolveOrCreate(MetadataType.TAG, List.of("rule-forget"))).isEmpty();

        // WHEN the user removes the rule on the rules page.
        ruleService.delete(rule(MetadataType.TAG, "rule-forget").getId());
        flushAndClear();

        // THEN the name is ordinary again: imports create it, and it can be added by hand.
        assertThat(metadataService.resolveOrCreate(MetadataType.TAG, List.of("rule-forget"))).hasSize(1);
        assertThat(tagRepository.findByNameIgnoreCase("rule-forget")).isPresent();
        assertThat(metadataService.add(MetadataType.TAG, "rule-forget-2")).isEmpty();
    }

    private MetadataRule rule(MetadataType type, String lowerName)
    {
        return ruleRepository.findByTypeAndSourceNameLower(type, lowerName).orElseThrow();
    }

    private Tag tag(String name)
    {
        Tag t = new Tag();
        t.setName(name);
        return tagRepository.save(t);
    }

    private Artist artist(String name)
    {
        Artist a = new Artist();
        a.setName(name);
        return artistRepository.save(a);
    }

    private Group group(String name)
    {
        Group g = new Group();
        g.setName(name);
        return groupRepository.save(g);
    }

    private void flushAndClear()
    {
        em.flush();
        em.clear();
    }
}

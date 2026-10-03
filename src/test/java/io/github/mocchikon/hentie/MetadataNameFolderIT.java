package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.entity.Tag;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.MetadataRuleRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.MetadataNameFolder;
import io.github.mocchikon.hentie.service.MetadataRuleService;
import io.github.mocchikon.hentie.service.MetadataService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fold of names not yet stored in lower case. Two capitalisations of one name are two <i>rows</i>, so
 * folding is a merge: every link must end up on the survivor, none lost or duplicated.
 * <p>
 * Rows are created through the repositories, because {@code MetadataService} would fold them on the way in.
 */
@SpringBootTest
@Transactional
class MetadataNameFolderIT
{
    @Autowired MetadataNameFolder folder;
    @Autowired MetadataService metadataService;
    @Autowired MetadataRuleService ruleService;
    @Autowired MetadataRuleRepository ruleRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired ChapterRepository chapterRepository;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    @Test
    void shouldRewriteTheNameWhenNoOtherRowHoldsTheFoldedOne()
    {
        // GIVEN a legacy row with a capitalised name and a chapter carrying it.
        Tag legacy = tag("Fold-Lonely");
        Chapter chapter = chapterWith(legacy);
        em.flush();
        em.clear();

        // WHEN the fold runs.
        var result = folder.fold(MetadataType.TAG);
        em.flush();
        em.clear();

        // THEN the row is renamed in place and keeps its id, so every link still points at it.
        assertThat(result.renamed()).isGreaterThanOrEqualTo(1);
        assertThat(result.blocked()).isZero();
        assertThat(tagRepository.findById(legacy.getId()).orElseThrow().getName()).isEqualTo("fold-lonely");
        assertThat(chapterTagLink(chapter.getId(), legacy.getId())).isEqualTo(1);
    }

    @Test
    void shouldMergeOntoTheSurvivorWhenTwoRowsDifferOnlyInCase()
    {
        // GIVEN the same value as two rows, each carried by its own chapter.
        Tag survivor = tag("fold-pair");
        Tag duplicate = tag("Fold-Pair");
        Chapter onSurvivor = chapterWith(survivor);
        Chapter onDuplicate = chapterWith(duplicate);
        em.flush();
        em.clear();

        // WHEN the fold runs.
        var result = folder.fold(MetadataType.TAG);
        em.flush();
        em.clear();

        // THEN the duplicate is folded into the lower id and BOTH chapters carry the survivor.
        assertThat(result.merged()).isGreaterThanOrEqualTo(1);
        assertThat(result.blocked()).isZero();
        assertThat(tagRepository.findById(duplicate.getId())).isEmpty();
        assertThat(tagRepository.findById(survivor.getId()).orElseThrow().getName()).isEqualTo("fold-pair");
        assertThat(chapterTagLink(onSurvivor.getId(), survivor.getId())).isEqualTo(1);
        assertThat(chapterTagLink(onDuplicate.getId(), survivor.getId())).isEqualTo(1);
    }

    @Test
    void shouldNotDuplicateTheLinkWhenOneChapterCarriesBothCapitalisations()
    {
        // GIVEN one chapter carrying both rows - the merge's duplicate-key case.
        Tag survivor = tag("fold-both");
        Tag duplicate = tag("FOLD-BOTH");
        Chapter chapter = chapterWithTags(List.of(survivor, duplicate));
        em.flush();
        em.clear();

        // WHEN the fold runs.
        folder.fold(MetadataType.TAG);
        em.flush();
        em.clear();

        // THEN it holds the survivor exactly once, not twice.
        assertThat(chapterTagLink(chapter.getId(), survivor.getId())).isEqualTo(1);
        assertThat(tagRepository.findById(duplicate.getId())).isEmpty();
    }

    @Test
    void shouldCollapseOntoOneSurvivorWhenSeveralCapitalisationsExist()
    {
        // GIVEN three rows for one value, none of them already folded.
        Tag first = tag("Fold-Trio");
        Tag second = tag("FOLD-TRIO");
        Tag third = tag("fold-TRIO");
        em.flush();
        em.clear();

        // WHEN the fold runs.
        var result = folder.fold(MetadataType.TAG);
        em.flush();
        em.clear();

        // THEN the lowest id survives under the folded name: two merges and one rename, not pairwise merges.
        assertThat(result.merged()).isGreaterThanOrEqualTo(2);
        assertThat(result.renamed()).isGreaterThanOrEqualTo(1);
        assertThat(tagRepository.findById(first.getId()).orElseThrow().getName()).isEqualTo("fold-trio");
        assertThat(tagRepository.findById(second.getId())).isEmpty();
        assertThat(tagRepository.findById(third.getId())).isEmpty();
    }

    @Test
    void shouldMoveARuleOntoTheSurvivorWhenItsTargetIsFoldedAway()
    {
        // GIVEN a rule rewriting a name to the row that the fold is about to merge away.
        Tag survivor = tag("fold-ruled");
        Tag duplicate = tag("Fold-Ruled");
        ruleService.recordRewrite(MetadataType.TAG, "fold-ruled-source", duplicate.getId());
        em.flush();
        em.clear();

        // WHEN the fold runs.
        folder.fold(MetadataType.TAG);
        em.flush();
        em.clear();

        // THEN the rule follows the merge instead of pointing at a row that is gone.
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "fold-ruled-source")
                .orElseThrow().getTargetId()).isEqualTo(survivor.getId());
        assertThat(metadataService.resolveOrCreate(MetadataType.TAG, List.of("fold-ruled-source")))
                .containsExactly(survivor.getId());
    }

    @Test
    void shouldRecordNoRuleWhenFolding()
    {
        // GIVEN a legacy capitalised row and a duplicate to merge into it.
        Tag survivor = tag("fold-no-rules");
        tag("Fold-No-Rules");
        Tag lonely = tag("Fold-No-Rules-Too");
        em.flush();
        em.clear();

        // WHEN the fold runs (merge and rename can each record a rule).
        folder.fold(MetadataType.TAG);
        em.flush();
        em.clear();

        // THEN it recorded none: a rule for either spelling would rule out the survivor's own name.
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "fold-no-rules")).isEmpty();
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "fold-no-rules-too")).isEmpty();
        assertThat(tagRepository.findById(survivor.getId()).orElseThrow().getName()).isEqualTo("fold-no-rules");
        assertThat(tagRepository.findById(lonely.getId()).orElseThrow().getName()).isEqualTo("fold-no-rules-too");
    }

    @Test
    void shouldLeaveTheRowAloneWhenARuleRulesTheFoldedNameOut()
    {
        // GIVEN a rule blocking a name that a differently-cased legacy row still carries.
        Tag blocked = tag("Fold-Blocked");
        ruleService.recordBlock(MetadataType.TAG, "fold-blocked");
        em.flush();
        em.clear();

        // WHEN the fold runs.
        var result = folder.fold(MetadataType.TAG);
        em.flush();
        em.clear();

        // THEN it is counted and left alone: removing the rule or the row is the user's call.
        assertThat(result.blocked()).isEqualTo(1);
        assertThat(tagRepository.findById(blocked.getId()).orElseThrow().getName()).isEqualTo("Fold-Blocked");
    }

    @Test
    void shouldChangeNothingWhenEveryNameIsAlreadyFolded()
    {
        // GIVEN only folded names.
        tag("fold-clean-one");
        tag("fold-clean-two");
        em.flush();
        em.clear();

        // WHEN the fold runs.
        var result = folder.fold(MetadataType.TAG);

        // THEN nothing was touched.
        assertThat(result.renamed()).isZero();
        assertThat(result.merged()).isZero();
        assertThat(result.blocked()).isZero();
        assertThat(result.total()).isZero();
    }

    @Test
    void shouldFoldEveryMetadataKindWhenRunningTheWholeLibrary()
    {
        // GIVEN a capitalised name of two different kinds.
        Tag legacyTag = tag("Fold-All-Tag");
        Artist legacyArtist = artist("Fold-All-Artist");
        em.flush();
        em.clear();

        // WHEN the maintenance action runs.
        var result = folder.foldAll();
        em.flush();
        em.clear();

        // THEN both are folded, not just the tag.
        assertThat(result.renamed()).isGreaterThanOrEqualTo(2);
        assertThat(tagRepository.findById(legacyTag.getId()).orElseThrow().getName()).isEqualTo("fold-all-tag");
        assertThat(artistRepository.findById(legacyArtist.getId()).orElseThrow().getName())
                .isEqualTo("fold-all-artist");
    }

    private int chapterTagLink(int chapterId, int tagId)
    {
        return jdbc.queryForObject("select count(*) from chapter_tags where chapter_id=? and tag_id=?",
                Integer.class, chapterId, tagId);
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

    private Chapter chapterWith(Tag tag)
    {
        return chapterWithTags(List.of(tag));
    }

    private Chapter chapterWithTags(List<Tag> tags)
    {
        Chapter c = new Chapter();
        c.setTitle("fold");
        c.setTitleFull("fold-chapter-" + tags.get(0).getId());
        c.setLanguage("English");
        c.setStatus(Status.NEW);
        c.setUploadDate(LocalDate.of(2020, 1, 1));
        c.setTags(new ArrayList<>(tags));
        return chapterRepository.save(c);
    }
}

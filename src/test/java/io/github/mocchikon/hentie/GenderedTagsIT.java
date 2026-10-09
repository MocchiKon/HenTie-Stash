package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.*;
import io.github.mocchikon.hentie.entity.MetadataRule;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.entity.Tag;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.MetadataRuleRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.SearchService;
import io.github.mocchikon.hentie.service.SeriesService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gendered tag ({@code halo ♀}) is imported with its plain tag ({@code halo}) beside it: both can be searched,
 * so a gallery is found by the plain tag whether or not its source tags by gender, but the detail pages show only
 * the gendered one. The plain tag is what the Manage page acts on: its versions follow every rename, merge and
 * removal, and so does its rule.
 */
@SpringBootTest
@Transactional
class GenderedTagsIT
{
    @Autowired MetadataService metadataService;
    @Autowired ChapterService chapterService;
    @Autowired SeriesService seriesService;
    @Autowired SearchService searchService;
    @Autowired TagRepository tagRepository;
    @Autowired ChapterRepository chapterRepository;
    @Autowired MetadataRuleRepository ruleRepository;
    @Autowired CacheManager cacheManager;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void clearCaches()
    {
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.METADATA)).clear();
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();
    }

    // --- importing and showing ----------------------------------------------------------------

    @Test
    void shouldStoreThePlainTagBesideEveryGenderedOne()
    {
        // WHEN e-hentai's spelling, gallery-dl's hitomi spelling and a plain tag are imported
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG,
                List.of("female:gtag-halo", "male:gtag-halo", "Gtag-Wings ♀", "gtag-plain"));
        em.flush();
        em.clear();

        // THEN each gendered tag brings its plain one, once.
        assertThat(names(ids)).containsExactly("gtag-halo ♀", "gtag-halo", "gtag-halo ♂",
                "gtag-wings ♀", "gtag-wings", "gtag-plain");
    }

    @Test
    void shouldShowOnlyTheGenderedTagOnTheChapterAndSeriesPagesButFindBothInSearch()
    {
        // GIVEN a chapter imported with a gendered tag, a plain one, and a series of it
        List<Integer> tagIds = metadataService.resolveOrCreate(MetadataType.TAG,
                List.of("female:gtag-view", "gtag-only-plain"));
        int chapterId = chapter("gendered tags chapter", tagIds);
        int seriesId = series("gendered tags series", chapterId);
        em.flush();
        em.clear();

        // THEN the pages show the gendered tag, not the plain one beside it; a plain tag of its own stays.
        assertThat(tagLabels(chapterService.buildView(chapterId).getDetailGroups()))
                .containsExactlyInAnyOrder("gtag-view ♀", "gtag-only-plain");
        assertThat(tagLabels(seriesService.buildView(seriesId, null).getDetailGroups()))
                .containsExactlyInAnyOrder("gtag-view ♀", "gtag-only-plain");

        // AND both find the chapter.
        int plainId = tagIds.get(1);
        int genderedId = tagIds.getFirst();
        assertThat(searchByTag(SearchType.CHAPTER, plainId)).containsExactly(chapterId);
        assertThat(searchByTag(SearchType.CHAPTER, genderedId)).containsExactly(chapterId);
        assertThat(searchByTag(SearchType.SERIES, plainId)).containsExactly(seriesId);
    }

    @Test
    void shouldShowThePlainTagOnTheSeriesPageWhereItCountsChaptersNoVersionShows()
    {
        // GIVEN a series of a gendered chapter and a chapter from a source without gender (nhentai)
        int gendered = chapter("gtag-count gendered", imported("female:gtag-count"));
        int plain = chapter("gtag-count plain", imported("gtag-count"));
        int mixed = series("gtag-count series", gendered, plain);
        // AND a series whose every chapter carries a version beside the plain tag
        int female = chapter("gtag-both female", imported("female:gtag-both"));
        int male = chapter("gtag-both male", imported("male:gtag-both"));
        int covered = series("gtag-both series", female, male);
        em.flush();
        em.clear();

        // THEN the first shows the plain tag with its own count, the second only the versions.
        assertThat(tagChips(seriesService.buildView(mixed, null).getDetailGroups()))
                .containsExactlyInAnyOrder("gtag-count (2)", "gtag-count ♀ (1)");
        assertThat(tagChips(seriesService.buildView(covered, null).getDetailGroups()))
                .containsExactlyInAnyOrder("gtag-both ♀ (1)", "gtag-both ♂ (1)");
    }

    @Test
    void shouldAddThePlainTagWhenAGenderedOneIsAddedByHand()
    {
        // WHEN a gendered tag is typed on the Manage page
        assertThat(metadataService.add(MetadataType.TAG, "female:gtag-typed")).isEmpty();
        em.flush();
        em.clear();

        // THEN its plain tag exists too, which is what the Manage page lists.
        assertThat(idOf("gtag-typed ♀")).isNotNull();
        assertThat(idOf("gtag-typed")).isNotNull();
        assertThat(metadataService.recent(MetadataType.TAG, "gtag-typed", 10)).extracting(OptionDto::getLabel)
                .containsExactly("gtag-typed");
    }

    @Test
    void shouldOfferOnlyPlainTagsToTheManagePageButEveryTagToSearch()
    {
        // GIVEN a tag with both versions, and a plain one
        imported("female:gtag-offer", "male:gtag-offer", "gtag-offer-plain");

        // THEN the Manage list and pickers leave the versions out, the search form offers them.
        assertThat(metadataService.recent(MetadataType.TAG, "gtag-offer", 10)).extracting(OptionDto::getLabel)
                .containsExactlyInAnyOrder("gtag-offer", "gtag-offer-plain");
        assertThat(metadataService.autocompleteManaged(MetadataType.TAG, "gtag-offer", 10))
                .extracting(OptionDto::getLabel).containsExactlyInAnyOrder("gtag-offer", "gtag-offer-plain");
        assertThat(metadataService.autocomplete(MetadataType.TAG, "gtag-offer", 10)).extracting(OptionDto::getLabel)
                .containsExactlyInAnyOrder("gtag-offer", "gtag-offer ♀", "gtag-offer ♂", "gtag-offer-plain");
    }

    // --- the plain tag takes its versions along -------------------------------------------------

    @Test
    void shouldRemoveEveryVersionAndKeepThemOutWhenThePlainTagIsRemovedWithARule()
    {
        // GIVEN a chapter carrying a tag with both versions
        List<Integer> ids = imported("female:gtag-gone", "male:gtag-gone");
        int chapterId = chapter("gtag-gone chapter", ids);
        em.flush();
        em.clear();

        // WHEN the plain tag is deleted with a rule
        metadataService.remove(MetadataType.TAG, idOf("gtag-gone"), true);
        em.flush();
        em.clear();

        // THEN the versions went with it
        assertThat(idOf("gtag-gone ♀")).isNull();
        assertThat(idOf("gtag-gone ♂")).isNull();
        assertThat(tagsOf(chapterId)).isEmpty();
        // AND the one rule keeps all three out of the next download and the Add form.
        assertThat(imported("female:gtag-gone", "male:gtag-gone", "gtag-gone")).isEmpty();
        assertThat(idOf("gtag-gone ♀")).isNull();
        assertThat(metadataService.add(MetadataType.TAG, "gtag-gone ♀")).get()
                .extracting(MetadataService.Refusal::name).isEqualTo("gtag-gone");
    }

    @Test
    void shouldRenameEveryVersionWithThePlainTag()
    {
        // GIVEN a chapter carrying a tag with both versions
        List<Integer> ids = imported("female:gtag-old", "male:gtag-old");
        int chapterId = chapter("gtag-old chapter", ids);
        Integer plain = idOf("gtag-old");
        Integer female = idOf("gtag-old ♀");
        Integer male = idOf("gtag-old ♂");
        em.flush();
        em.clear();

        // WHEN the plain tag is renamed with a rule
        assertThat(metadataService.rename(MetadataType.TAG, plain, "Gtag-New", true)).isEmpty();
        em.flush();
        em.clear();

        // THEN the same rows carry the new name with their symbols
        assertThat(nameOf(plain)).isEqualTo("gtag-new");
        assertThat(nameOf(female)).isEqualTo("gtag-new ♀");
        assertThat(nameOf(male)).isEqualTo("gtag-new ♂");
        assertThat(tagsOf(chapterId)).containsExactlyInAnyOrder("gtag-new", "gtag-new ♀", "gtag-new ♂");
        // AND the plain name's rule sends the old versions to the new ones.
        assertThat(imported("female:gtag-old")).containsExactly(female, plain);
        assertThat(idOf("gtag-old")).isNull();
        assertThat(idOf("gtag-old ♀")).isNull();
    }

    @Test
    void shouldRefuseToRenameAPlainTagToAGenderedName()
    {
        // GIVEN a tag with a version
        imported("female:gtag-plainname");
        Integer plain = idOf("gtag-plainname");

        // WHEN it is renamed to a gendered name
        var refusal = metadataService.rename(MetadataType.TAG, plain, "gtag-other ♀", true);

        // THEN nothing is renamed, and the refusal names the name.
        assertThat(refusal).get().isInstanceOf(MetadataService.GenderedName.class)
                .extracting(MetadataService.Refusal::name).isEqualTo("gtag-other ♀");
        assertThat(nameOf(plain)).isEqualTo("gtag-plainname");
    }

    @Test
    void shouldRefuseTheWholeRenameWhenAVersionCannotTakeItsNewName()
    {
        // GIVEN a tag with a version, and a stray row already holding that version's new name
        imported("female:gtag-blocked");
        Integer plain = idOf("gtag-blocked");
        tag("gtag-target ♀");

        // WHEN the plain tag is renamed
        var refusal = metadataService.rename(MetadataType.TAG, plain, "gtag-target", true);
        em.flush();
        em.clear();

        // THEN it is refused for the version, and neither row changed.
        assertThat(refusal).get().isInstanceOf(MetadataService.NameTaken.class)
                .extracting(MetadataService.Refusal::name).isEqualTo("gtag-target ♀");
        assertThat(idOf("gtag-blocked")).isEqualTo(plain);
        assertThat(idOf("gtag-blocked ♀")).isNotNull();
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "gtag-blocked")).isEmpty();
    }

    @Test
    void shouldMergeEveryVersionIntoTheTargetsVersion()
    {
        // GIVEN a source tag with both versions and a target with one of them, each on a chapter
        int femaleChapter = chapter("gtag-src female", imported("female:gtag-src"));
        int maleChapter = chapter("gtag-src male", imported("male:gtag-src"));
        int targetChapter = chapter("gtag-dst female", imported("female:gtag-dst"));
        Integer sourceMale = idOf("gtag-src ♂");
        Integer target = idOf("gtag-dst");
        Integer targetFemale = idOf("gtag-dst ♀");
        em.flush();
        em.clear();

        // WHEN the plain tags are merged with a rule
        metadataService.merge(MetadataType.TAG, idOf("gtag-src"), target, true);
        em.flush();
        em.clear();

        // THEN each version joined the target's, made from the source's where the target had none
        assertThat(idOf("gtag-src")).isNull();
        assertThat(idOf("gtag-src ♀")).isNull();
        assertThat(idOf("gtag-dst ♂")).isEqualTo(sourceMale);
        assertThat(tagsOf(femaleChapter)).containsExactlyInAnyOrder("gtag-dst", "gtag-dst ♀");
        assertThat(tagsOf(maleChapter)).containsExactlyInAnyOrder("gtag-dst", "gtag-dst ♂");
        assertThat(tagsOf(targetChapter)).containsExactlyInAnyOrder("gtag-dst", "gtag-dst ♀");
        // AND the plain name's rule sends the source's versions to the target's.
        assertThat(imported("female:gtag-src", "male:gtag-src")).containsExactly(targetFemale, target, sourceMale);
        assertThat(idOf("gtag-src ♀")).isNull();
    }

    @Test
    void shouldCreateTheTargetsVersionWhenAnImportBringsAVersionOfAMergedTag()
    {
        // GIVEN a plain tag merged into another, neither with versions
        Integer source = imported("gtag-merged").getFirst();
        Integer target = imported("gtag-kept").getFirst();
        metadataService.merge(MetadataType.TAG, source, target, true);
        em.flush();
        em.clear();

        // WHEN a download brings a version of the merged one
        List<Integer> ids = imported("female:gtag-merged");
        em.flush();
        em.clear();

        // THEN it becomes the target's version, never the merged name's.
        assertThat(ids).containsExactly(idOf("gtag-kept ♀"), target);
        assertThat(idOf("gtag-merged ♀")).isNull();
    }

    // --- removing the gender ----------------------------------------------------------------------

    @Test
    void shouldMergeBothVersionsIntoThePlainTagAndRewriteThemFromThenOn()
    {
        // GIVEN a tag with both versions: one imported beside the plain tag, one picked alone on an edit form
        int importedChapter = chapter("gtag-strip imported", imported("female:gtag-strip"));
        imported("male:gtag-strip");
        int pickedChapter = chapter("gtag-strip picked", List.of(idOf("gtag-strip ♂")));
        Integer plain = idOf("gtag-strip");
        em.flush();
        em.clear();

        // WHEN its gender is removed with a rule
        metadataService.removeGender(plain, true);
        em.flush();
        em.clear();

        // THEN both versions went into the plain tag, also where it was not beside them
        assertThat(idOf("gtag-strip ♀")).isNull();
        assertThat(idOf("gtag-strip ♂")).isNull();
        assertThat(tagsOf(importedChapter)).containsExactly("gtag-strip");
        assertThat(tagsOf(pickedChapter)).containsExactly("gtag-strip");
        // AND the versions are rewritten to the plain tag from now on, so they cannot come back.
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "gtag-strip ♀"))
                .get().extracting(MetadataRule::getTargetId).isEqualTo(plain);
        assertThat(imported("female:gtag-strip", "male:gtag-strip")).containsExactly(plain);
        assertThat(idOf("gtag-strip ♀")).isNull();
        assertThat(metadataService.add(MetadataType.TAG, "gtag-strip ♂")).get()
                .isInstanceOfSatisfying(MetadataService.RuleConflict.class,
                        conflict -> assertThat(conflict.targetName()).isEqualTo("gtag-strip"));
    }

    @Test
    void shouldKeepTheGenderRemovedWhenTheTagIsRenamed()
    {
        // GIVEN a tag whose gender was removed
        Integer plain = imported("gtag-ungendered").getFirst();
        metadataService.removeGender(plain, true);
        em.flush();
        em.clear();

        // WHEN it is renamed, as a one-off
        assertThat(metadataService.rename(MetadataType.TAG, plain, "gtag-renamed", false)).isEmpty();
        em.flush();
        em.clear();

        // THEN its new versions are rewritten to it
        assertThat(imported("female:gtag-renamed")).containsExactly(plain);
        assertThat(idOf("gtag-renamed ♀")).isNull();
        // AND its old name is free again, versions included.
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "gtag-ungendered ♀")).isEmpty();
        assertThat(imported("female:gtag-ungendered")).doesNotContain(plain).hasSize(2);
    }

    @Test
    void shouldGiveAVersionTheTargetsRemovedGenderWhenItFollowsItsPlainTagsRule()
    {
        // GIVEN a tag merged into another whose gender was removed
        Integer source = imported("gtag-hop-src").getFirst();
        Integer target = imported("gtag-hop-dst").getFirst();
        metadataService.removeGender(target, true);
        metadataService.merge(MetadataType.TAG, source, target, true);
        em.flush();
        em.clear();

        // WHEN a download brings a version of the merged tag
        List<Integer> ids = imported("female:gtag-hop-src");
        em.flush();
        em.clear();

        // THEN it follows the merge, then the target's removed gender.
        assertThat(ids).containsExactly(target);
        assertThat(idOf("gtag-hop-dst ♀")).isNull();
    }

    private List<Integer> imported(String... names)
    {
        return metadataService.resolveOrCreate(MetadataType.TAG, List.of(names));
    }

    private int chapter(String title, List<Integer> tagIds)
    {
        var form = new ChapterForm();
        form.setTitleFull(title);
        form.setLanguage("English");
        form.setTagIds(new ArrayList<>(tagIds));
        return chapterService.create(form);
    }

    private int series(String title, Integer... chapterIds)
    {
        var series = new SeriesForm();
        series.setTitleFull(title);
        series.setStatus(Status.REVIEWED);
        series.setChapterIds(new ArrayList<>(List.of(chapterIds)));
        return seriesService.create(series);
    }

    private void tag(String name)
    {
        Tag tag = new Tag();
        tag.setName(name);
        tagRepository.save(tag);
    }

    private Integer idOf(String name)
    {
        return tagRepository.findByNameIgnoreCase(name).map(Tag::getId).orElse(null);
    }

    private String nameOf(Integer id)
    {
        return tagRepository.findById(id).map(Tag::getName).orElse(null);
    }

    private List<String> tagsOf(int chapterId)
    {
        return chapterRepository.findById(chapterId).orElseThrow().getTags().stream().map(Tag::getName).toList();
    }

    private List<String> names(List<Integer> ids)
    {
        return metadataService.resolve(MetadataType.TAG, ids).stream().map(OptionDto::getLabel).toList();
    }

    private static List<String> tagLabels(List<ChipGroup> groups)
    {
        return tagGroup(groups).map(g -> g.getChips().stream().map(ChipDto::getLabel).toList()).orElseThrow();
    }

    private static List<String> tagChips(List<ChipGroup> groups)
    {
        return tagGroup(groups).map(g -> g.getChips().stream()
                .map(chip -> chip.getLabel() + " (" + chip.getCount() + ")").toList()).orElseThrow();
    }

    private static Optional<ChipGroup> tagGroup(List<ChipGroup> groups)
    {
        return groups.stream().filter(g -> g.getTitle().equals("Tags")).findFirst();
    }

    private List<Integer> searchByTag(SearchType type, int tagId)
    {
        var criteria = new SearchCriteria();
        criteria.setType(type);
        criteria.setTagIds(new ArrayList<>(List.of(tagId)));
        criteria.normalize();
        return searchService.search(criteria).getContent().stream().map(CardDto::getId).toList();
    }
}

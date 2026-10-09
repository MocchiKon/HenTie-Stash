package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Character;
import io.github.mocchikon.hentie.entity.MetadataRule;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Names written as e-hentai shows a tag with an alias, {@code "focalors | lady furina"}: nhentai stores that display
 * as the name. The name before the pipe is kept, and each alias becomes a rule to it, from every source. A name typed
 * on the Manage page may not contain a pipe.
 */
@SpringBootTest
@Transactional
class MetadataAliasIT
{
    @Autowired MetadataService metadataService;
    @Autowired MetadataRuleService ruleService;
    @Autowired MetadataRuleRepository ruleRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired CharacterRepository characterRepository;
    @Autowired ChapterRepository chapterRepository;
    @Autowired GalleryImportService importService;
    @PersistenceContext EntityManager em;

    // --- imports ---------------------------------------------------------------------------------

    @Test
    void shouldKeepTheNameAndRuleTheAliasToItWhenAnImportNamesBoth()
    {
        // WHEN a source names a character with its alias, as nhentai does.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.CHARACTER, List.of("Focalors | Lady Furina"));
        flushAndClear();

        // THEN only the name before the pipe is a row, and the alias is ruled to it.
        Integer focalors = characterRepository.findByNameIgnoreCase("focalors").orElseThrow().getId();
        assertThat(ids).containsExactly(focalors);
        assertThat(characterRepository.findByNameIgnoreCase("lady furina")).isEmpty();
        assertThat(characterRepository.findByNameIgnoreCase("focalors | lady furina")).isEmpty();
        assertThat(rule(MetadataType.CHARACTER, "lady furina").getTargetId()).isEqualTo(focalors);

        // AND a later import naming the alias alone lands on the same row.
        assertThat(metadataService.resolveOrCreate(MetadataType.CHARACTER, List.of("lady furina")))
                .containsExactly(focalors);
        flushAndClear();
        assertThat(characterRepository.findByNameIgnoreCase("lady furina")).isEmpty();
    }

    @Test
    void shouldGiveADownloadedChapterTheNamesBeforeThePipesOfEveryKindButCategories()
    {
        // WHEN a gallery names every kind with an alias.
        var data = GalleryData.builder()
                .id("8101")
                .fullTitle("[Test] Alias Gallery 8101")
                .language("english")
                .tags(Set.of("alias tag | alias tag other"))
                .artists(Set.of("kitaku | nakamachi machi"))
                .characters(Set.of("focalors | lady furina"))
                .parodies(Set.of("alias parody | alias parody other"))
                .groups(Set.of("alias group | alias group other"))
                .categories(Set.of("alias category | alias category other"))
                .build();
        int chapterId = importService.importChapter(data, "mock:alias-8101");
        flushAndClear();

        // THEN the chapter carries the first names, and each alias is ruled to its name.
        Chapter chapter = chapterRepository.findById(chapterId).orElseThrow();
        assertThat(chapter.getTags()).extracting("name").containsExactly("alias tag");
        assertThat(chapter.getArtists()).extracting("name").containsExactly("kitaku");
        assertThat(chapter.getCharacters()).extracting("name").containsExactly("focalors");
        assertThat(chapter.getParodies()).extracting("name").containsExactly("alias parody");
        assertThat(chapter.getGroups()).extracting("name").containsExactly("alias group");
        assertThat(rule(MetadataType.TAG, "alias tag other").getTargetId())
                .isEqualTo(chapter.getTags().getFirst().getId());
        assertThat(rule(MetadataType.ARTIST, "nakamachi machi").getTargetId())
                .isEqualTo(chapter.getArtists().getFirst().getId());
        assertThat(rule(MetadataType.CHARACTER, "lady furina").getTargetId())
                .isEqualTo(chapter.getCharacters().getFirst().getId());
        assertThat(rule(MetadataType.PARODY, "alias parody other").getTargetId())
                .isEqualTo(chapter.getParodies().getFirst().getId());
        assertThat(rule(MetadataType.GROUP, "alias group other").getTargetId())
                .isEqualTo(chapter.getGroups().getFirst().getId());
        assertThat(artistRepository.findByNameIgnoreCase("nakamachi machi")).isEmpty();

        // AND a category, which no source writes with an alias, stays whole.
        assertThat(chapter.getCategories()).extracting("name").containsExactly("alias category | alias category other");
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.CATEGORY, "alias category other")).isEmpty();
    }

    @Test
    void shouldRuleEveryAliasWhenANameHasSeveral()
    {
        // WHEN
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.ARTIST, List.of("alias-a | alias-b | alias-c"));
        flushAndClear();

        // THEN
        Integer first = artistRepository.findByNameIgnoreCase("alias-a").orElseThrow().getId();
        assertThat(ids).containsExactly(first);
        assertThat(rule(MetadataType.ARTIST, "alias-b").getTargetId()).isEqualTo(first);
        assertThat(rule(MetadataType.ARTIST, "alias-c").getTargetId()).isEqualTo(first);
    }

    @Test
    void shouldLandAnAliasTheSameGalleryNamesOnItsOwnOnItsName()
    {
        // WHEN one gallery names the alias alone, before the name that brings it.
        List<Integer> ids = metadataService.resolveOrCreate(
                MetadataType.CHARACTER, List.of("lady furina", "focalors | lady furina"));
        flushAndClear();

        // THEN both are the one row, and the alias never became a row of its own.
        Integer focalors = characterRepository.findByNameIgnoreCase("focalors").orElseThrow().getId();
        assertThat(ids).containsExactly(focalors);
        assertThat(characterRepository.findByNameIgnoreCase("lady furina")).isEmpty();
    }

    @Test
    void shouldMergeTheRowTheAliasAlreadyHasIntoTheName()
    {
        // GIVEN a chapter with the alias as a row of its own, from before its name was known.
        var data = GalleryData.builder()
                .id("8102")
                .fullTitle("[Test] Alias Gallery 8102")
                .language("english")
                .characters(Set.of("lady furina"))
                .build();
        int chapterId = importService.importChapter(data, "mock:alias-8102");
        flushAndClear();
        Integer aliasRow = characterRepository.findByNameIgnoreCase("lady furina").orElseThrow().getId();

        // WHEN a later import names it as the alias of a name.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.CHARACTER, List.of("focalors | lady furina"));
        flushAndClear();

        // THEN the alias's row went into the name's, chapters and all, as a merge with a rule leaves it.
        Integer focalors = characterRepository.findByNameIgnoreCase("focalors").orElseThrow().getId();
        assertThat(ids).containsExactly(focalors);
        assertThat(characterRepository.findById(aliasRow)).isEmpty();
        assertThat(chapterRepository.findById(chapterId).orElseThrow().getCharacters())
                .extracting("name").containsExactly("focalors");
        assertThat(rule(MetadataType.CHARACTER, "lady furina").getTargetId()).isEqualTo(focalors);
    }

    // --- rules already there -----------------------------------------------------------------------

    @Test
    void shouldLeaveARuleAlreadyOnTheAlias()
    {
        // GIVEN the user's decision about the alias.
        ruleService.recordBlock(MetadataType.CHARACTER, "lady furina");
        flushAndClear();

        // WHEN an import names it as the alias of a name.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.CHARACTER, List.of("focalors | lady furina"));
        flushAndClear();

        // THEN the name is kept, and the user's rule stands.
        assertThat(ids).containsExactly(characterRepository.findByNameIgnoreCase("focalors").orElseThrow().getId());
        assertThat(rule(MetadataType.CHARACTER, "lady furina").isBlocking()).isTrue();
    }

    @Test
    void shouldRuleTheAliasToWhereARuleSendsTheName()
    {
        // GIVEN the name merged into another, with a rule.
        Character focalors = character("focalors");
        Character furina = character("furina");
        metadataService.merge(MetadataType.CHARACTER, focalors.getId(), furina.getId(), true);
        flushAndClear();

        // WHEN
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.CHARACTER, List.of("focalors | lady furina"));
        flushAndClear();

        // THEN the alias goes where its name goes, one hop like every rule.
        assertThat(ids).containsExactly(furina.getId());
        assertThat(rule(MetadataType.CHARACTER, "lady furina").getTargetId()).isEqualTo(furina.getId());
    }

    @Test
    void shouldRecordNothingForTheAliasOfARemovedName()
    {
        // GIVEN the name removed, with a rule.
        Character focalors = character("focalors");
        metadataService.remove(MetadataType.CHARACTER, focalors.getId(), true);
        flushAndClear();

        // WHEN
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.CHARACTER, List.of("focalors | lady furina"));
        flushAndClear();

        // THEN both are dropped, and no block of the alias outlives the user taking the name back.
        assertThat(ids).isEmpty();
        assertThat(characterRepository.findByNameIgnoreCase("lady furina")).isEmpty();
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.CHARACTER, "lady furina")).isEmpty();
    }

    @Test
    void shouldRuleATagsAliasOnItsPlainNameSoItsVersionsFollow()
    {
        // GIVEN a tag with an alias.
        metadataService.resolveOrCreate(MetadataType.TAG, List.of("alias breasts | alias bosom"));
        flushAndClear();
        Integer plain = tagRepository.findByNameIgnoreCase("alias breasts").orElseThrow().getId();
        assertThat(rule(MetadataType.TAG, "alias bosom").getTargetId()).isEqualTo(plain);

        // WHEN a source tags the alias by gender.
        List<Integer> ids = metadataService.resolveOrCreate(MetadataType.TAG, List.of("female:alias bosom"));
        flushAndClear();

        // THEN it becomes the name's version, as a rule on a plain tag does.
        Integer version = tagRepository.findByNameIgnoreCase("alias breasts ♀").orElseThrow().getId();
        assertThat(ids).containsExactly(version, plain);
        assertThat(tagRepository.findByNameIgnoreCase("alias bosom ♀")).isEmpty();
        assertThat(tagRepository.findByNameIgnoreCase("alias bosom")).isEmpty();
    }

    // --- the Manage page ---------------------------------------------------------------------------

    @Test
    void shouldRefuseATypedNameWithAPipe()
    {
        // WHEN the user adds a name with an alias, and one with pipes of its own.
        var aliased = metadataService.add(MetadataType.CHARACTER, " Alias-Typed | Alias-Typed-Other ");
        var piped = metadataService.add(MetadataType.ARTIST, "|||alias-typed|||");
        flushAndClear();

        // THEN both are refused as typed, and nothing was added or ruled.
        assertThat(aliased).contains(new MetadataService.PipeInName(MetadataType.CHARACTER,
                "Alias-Typed | Alias-Typed-Other"));
        assertThat(piped).contains(new MetadataService.PipeInName(MetadataType.ARTIST, "|||alias-typed|||"));
        assertThat(characterRepository.findByNameIgnoreCase("alias-typed")).isEmpty();
        assertThat(artistRepository.findByNameIgnoreCase("|||alias-typed|||")).isEmpty();
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.CHARACTER, "alias-typed-other")).isEmpty();
    }

    @Test
    void shouldRefuseARenameToANameWithAPipe()
    {
        // GIVEN
        Character subject = character("alias-rename-old");

        // WHEN the user renames it to a name with an alias.
        var refusal = metadataService.rename(MetadataType.CHARACTER, subject.getId(), "alias-renamed | alias-other", true);
        flushAndClear();

        // THEN it is refused, and neither the row nor the rules changed.
        assertThat(refusal).contains(new MetadataService.PipeInName(MetadataType.CHARACTER, "alias-renamed | alias-other"));
        assertThat(characterRepository.findById(subject.getId()).orElseThrow().getName()).isEqualTo("alias-rename-old");
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.CHARACTER, "alias-other")).isEmpty();
        assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.CHARACTER, "alias-rename-old")).isEmpty();
    }

    private MetadataRule rule(MetadataType type, String lowerName)
    {
        return ruleRepository.findByTypeAndSourceNameLower(type, lowerName).orElseThrow();
    }

    private Character character(String name)
    {
        Character c = new Character();
        c.setName(name);
        return characterRepository.save(c);
    }

    private void flushAndClear()
    {
        em.flush();
        em.clear();
    }
}

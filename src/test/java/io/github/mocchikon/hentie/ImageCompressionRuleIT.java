package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.CompressionRuleForm;
import io.github.mocchikon.hentie.dto.CompressionRuleView;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.ImageCompressionMode;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.repository.ImageCompressionRuleRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionRuleService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionRuleService.Decision;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Image compression rules: which rule decides a chapter, what metadata changes do to a rule, and what the form
 * accepts. The download pipeline's use of a decision is {@link CompressionUpgradeIT}'s.
 */
@SpringBootTest
@Transactional
class ImageCompressionRuleIT
{
    private static final String NONE = BuiltInCompressionMode.NONE.getKey();
    private static final String LOSSLESS = BuiltInCompressionMode.LOSSLESS.getKey();
    private static final String HIGH = BuiltInCompressionMode.HIGH_REDUCTION.getKey();
    private static final String VERY_HIGH = BuiltInCompressionMode.VERY_HIGH_REDUCTION.getKey();

    @Autowired ImageCompressionRuleService ruleService;
    @Autowired ImageCompressionRuleRepository ruleRepository;
    @Autowired ImageCompressionModeService modeService;
    @Autowired MetadataService metadataService;
    @Autowired ChapterService chapterService;
    @Autowired SettingsService settingsService;
    @PersistenceContext EntityManager em;

    @AfterEach
    void restoreSettings()
    {
        // Settings keep an in-memory copy the test's rollback does not reach.
        settingsService.setImageCompressionMode(NONE);
    }

    // ---- deciding ----------------------------------------------------------------------------------------------

    /** Conditions are AND-ed: a gallery must carry every value a rule names. */
    @Test
    void shouldMatchOnlyRulesWhoseEveryValueTheChapterCarries()
    {
        // GIVEN a chapter by an artist with one tag, and rules naming the artist, both, and the artist with a tag
        // the chapter lacks
        int artist = value(MetadataType.ARTIST, "icr-artist");
        int tag = value(MetadataType.TAG, "icr-tag");
        value(MetadataType.TAG, "icr-other-tag");
        int chapter = chapter(List.of(artist), List.of(tag));
        int artistOnly = rule(5, HIGH, false, "artist", "icr-artist");
        int both = rule(5, VERY_HIGH, false, "artist", "icr-artist", "tag", "icr-tag");
        rule(5, LOSSLESS, false, "artist", "icr-artist", "tag", "icr-other-tag");

        // WHEN
        Decision decision = ruleService.decide(chapter);

        // THEN the two that match compete, and the third (which would win on quality) does not
        assertThat(decision.modeKey()).isEqualTo(HIGH);
        assertThat(decision.ruleId()).isEqualTo(artistOnly);
        assertThat(both).isNotEqualTo(artistOnly);
    }

    /** Only the lowest priority number counts, even against a better quality. */
    @Test
    void shouldLetTheHighestPriorityDecideBeforeQuality()
    {
        // GIVEN
        int artist = value(MetadataType.ARTIST, "icr-priority-artist");
        int chapter = chapter(List.of(artist), List.of());
        rule(1, LOSSLESS, true, "artist", "icr-priority-artist");
        int favourite = rule(0, VERY_HIGH, false, "artist", "icr-priority-artist");

        // WHEN
        Decision decision = ruleService.decide(chapter);

        // THEN the priority 0 rule decides alone: its mode, and no upgrade, which only the other one allowed
        assertThat(decision).isEqualTo(new Decision(VERY_HIGH, false, favourite));
    }

    /** Among the rules of the top priority the best quality wins, and any one allowing upgrades is enough. */
    @Test
    void shouldPickTheBestQualityAndAllowUpgradesWhenAnyTopRuleDoes()
    {
        // GIVEN
        int artist = value(MetadataType.ARTIST, "icr-quality-artist");
        int tag = value(MetadataType.TAG, "icr-quality-tag");
        int chapter = chapter(List.of(artist), List.of(tag));
        rule(2, HIGH, true, "tag", "icr-quality-tag");
        int lossless = rule(2, LOSSLESS, false, "artist", "icr-quality-artist");

        // WHEN
        Decision decision = ruleService.decide(chapter);

        // THEN
        assertThat(decision).isEqualTo(new Decision(LOSSLESS, true, lossless));
    }

    /** None and Lossless are both quality 0, so the older rule wins: the answer must not depend on the query plan. */
    @Test
    void shouldLetTheOlderRuleWinATieOnQuality()
    {
        // GIVEN
        int artist = value(MetadataType.ARTIST, "icr-tie-artist");
        int chapter = chapter(List.of(artist), List.of());
        int older = rule(1, LOSSLESS, false, "artist", "icr-tie-artist");
        rule(1, NONE, false, "artist", "icr-tie-artist");

        // WHEN + THEN
        assertThat(ruleService.decide(chapter).ruleId()).isEqualTo(older);
    }

    /** A rule without conditions applies to every gallery: with a high number it is the default. */
    @Test
    void shouldApplyARuleWithoutConditionsToEveryChapter()
    {
        // GIVEN a default rule, and a rule for an artist above it
        int artist = value(MetadataType.ARTIST, "icr-default-artist");
        int favourite = chapter(List.of(artist), List.of());
        int other = chapter(List.of(), List.of());
        int fallback = rule(1000, VERY_HIGH, false);
        int favourites = rule(0, LOSSLESS, true, "artist", "icr-default-artist");

        // WHEN + THEN
        assertThat(ruleService.decide(favourite)).isEqualTo(new Decision(LOSSLESS, true, favourites));
        assertThat(ruleService.decide(other)).isEqualTo(new Decision(VERY_HIGH, false, fallback));
    }

    @Test
    void shouldFallBackOnTheSettingsModeWhenNoRuleMatches()
    {
        // GIVEN
        settingsService.setImageCompressionMode(HIGH);
        rule(0, LOSSLESS, true, "artist", "icr-somebody-else");
        int chapter = chapter(List.of(), List.of());

        // WHEN + THEN
        assertThat(ruleService.decide(chapter)).isEqualTo(new Decision(HIGH, false, null));
    }

    /** A key that no longer resolves means None, here too: compared as None, and named as None. */
    @Test
    void shouldApplyARuleWhoseModeWasDeletedAsNone()
    {
        // GIVEN a rule with a custom mode that is then deleted
        String custom = modeService.save(mode("icr-doomed mode", 5));
        int artist = value(MetadataType.ARTIST, "icr-doomed-artist");
        int chapter = chapter(List.of(artist), List.of());
        int doomed = rule(0, custom, false, "artist", "icr-doomed-artist");
        modeService.delete(ImageCompressionModeService.customId(custom));

        // WHEN + THEN
        assertThat(ruleService.decide(chapter)).isEqualTo(new Decision(NONE, false, doomed));
    }

    /** Custom modes rank by the quality the user gave them. */
    @Test
    void shouldRankACustomModeByItsQuality()
    {
        // GIVEN a custom mode better than High reduction but worse than Lossless
        String custom = modeService.save(mode("icr-custom mode", 50));
        int tag = value(MetadataType.TAG, "icr-custom-tag");
        int chapter = chapter(List.of(), List.of(tag));
        rule(0, HIGH, false, "tag", "icr-custom-tag");
        rule(0, custom, false, "tag", "icr-custom-tag");

        // WHEN + THEN
        assertThat(ruleService.decide(chapter).modeKey()).isEqualTo(custom);
        assertThat(modeService.isBetter(custom, HIGH)).isTrue();
        assertThat(modeService.isBetter(LOSSLESS, custom)).isTrue();
    }

    // ---- upgrades ----------------------------------------------------------------------------------------------

    /** Original pages and an equal quality are never worth downloading again; a deleted mode always is. */
    @Test
    void shouldWantAnUpgradeOnlyForAStrictlyBetterMode()
    {
        // GIVEN a rule allowing upgrades to Lossless for every gallery
        rule(0, LOSSLESS, true);
        int chapter = chapter(List.of(), List.of());
        String rules = BuiltInCompressionMode.RULES_KEY;

        // WHEN + THEN
        assertThat(ruleService.wantsUpgrade(rules, false, chapter, HIGH)).isTrue();
        assertThat(ruleService.wantsUpgrade(rules, false, chapter, null)).as("original pages").isFalse();
        assertThat(ruleService.wantsUpgrade(rules, false, chapter, LOSSLESS)).as("the same mode").isFalse();
        assertThat(ruleService.wantsUpgrade(rules, false, chapter, "custom:987654")).as("a mode since deleted")
                .isTrue();
        // A mode picked by hand upgrades only when the row allows it, whatever the rules say.
        assertThat(ruleService.wantsUpgrade(NONE, false, chapter, HIGH)).isFalse();
        assertThat(ruleService.wantsUpgrade(NONE, true, chapter, HIGH)).isTrue();
        assertThat(ruleService.wantsUpgrade(VERY_HIGH, true, chapter, HIGH)).as("a worse mode").isFalse();
    }

    @Test
    void shouldNotWantAnUpgradeWhenTheWinningRulesDoNotAllowIt()
    {
        // GIVEN
        rule(0, LOSSLESS, false);
        int chapter = chapter(List.of(), List.of());

        // WHEN + THEN
        assertThat(ruleService.wantsUpgrade(BuiltInCompressionMode.RULES_KEY, true, chapter, HIGH)).isFalse();
    }

    // ---- what metadata changes do to a rule --------------------------------------------------------------------

    /** Conditions are ids, so a renamed value keeps its rule. */
    @Test
    void shouldKeepARuleWhenItsValueIsRenamed()
    {
        // GIVEN
        int artist = value(MetadataType.ARTIST, "icr-before-rename");
        int chapter = chapter(List.of(artist), List.of());
        int rule = rule(0, LOSSLESS, false, "artist", "icr-before-rename");

        // WHEN
        assertThat(metadataService.rename(MetadataType.ARTIST, artist, "icr-after-rename", false)).isEmpty();
        flush();

        // THEN
        assertThat(ruleService.decide(chapter).ruleId()).isEqualTo(rule);
        assertThat(conditionsOf(rule)).containsExactly(condition(MetadataType.ARTIST, "icr-after-rename"));
    }

    /** A merged value's rules follow it onto the target, so galleries carrying the target match them. */
    @Test
    void shouldMoveARuleOntoTheTargetWhenItsValueIsMerged()
    {
        // GIVEN a rule on an artist merged into another, and a chapter by the other one
        int source = value(MetadataType.ARTIST, "icr-merge-source");
        int target = value(MetadataType.ARTIST, "icr-merge-target");
        int chapter = chapter(List.of(target), List.of());
        int rule = rule(0, LOSSLESS, false, "artist", "icr-merge-source");

        // WHEN
        metadataService.merge(MetadataType.ARTIST, source, target, false);
        flush();

        // THEN
        assertThat(ruleService.decide(chapter).ruleId()).isEqualTo(rule);
        assertThat(conditionsOf(rule)).containsExactly(condition(MetadataType.ARTIST, "icr-merge-target"));
    }

    /** A rule naming both lose one condition, and the count it must match with it, or it could never match again. */
    @Test
    void shouldCountOneConditionLessWhenARuleNamedBothMergedValues()
    {
        // GIVEN a rule naming two tags, merged into one
        int source = value(MetadataType.TAG, "icr-merge-both-source");
        int target = value(MetadataType.TAG, "icr-merge-both-target");
        int chapter = chapter(List.of(), List.of(target));
        int rule = rule(0, LOSSLESS, false, "tag", "icr-merge-both-source", "tag", "icr-merge-both-target");

        // WHEN
        metadataService.merge(MetadataType.TAG, source, target, false);
        flush();

        // THEN
        assertThat(ruleService.decide(chapter).ruleId()).isEqualTo(rule);
        assertThat(ruleRepository.findById(rule).orElseThrow().getConditionCount()).isOne();
        assertThat(view(rule).broken()).isFalse();
    }

    /**
     * "Artist X and tag Y" must not become "artist X" when Y is removed: the rule then matches nothing, and its page
     * says so.
     */
    @Test
    void shouldStopMatchingWhenAValueTheRuleNamesIsRemoved()
    {
        // GIVEN a rule naming an artist and a tag, and a chapter by the artist
        settingsService.setImageCompressionMode(VERY_HIGH);
        int artist = value(MetadataType.ARTIST, "icr-removed-artist");
        int tag = value(MetadataType.TAG, "icr-removed-tag");
        int chapter = chapter(List.of(artist), List.of());
        int rule = rule(0, LOSSLESS, true, "artist", "icr-removed-artist", "tag", "icr-removed-tag");

        // WHEN the tag is removed
        metadataService.remove(MetadataType.TAG, tag, false);
        flush();

        // THEN the rule is kept, matches no gallery, and is shown as such
        assertThat(ruleService.decide(chapter)).isEqualTo(new Decision(VERY_HIGH, false, null));
        CompressionRuleView view = view(rule);
        assertThat(view.broken()).isTrue();
        assertThat(view.conditions()).containsExactly(condition(MetadataType.ARTIST, "icr-removed-artist"));
        assertThat(ruleService.editing(rule).orElseThrow().broken()).isTrue();
    }

    // ---- the form ----------------------------------------------------------------------------------------------

    /** A rule may name an artist before any of their galleries is downloaded. */
    @Test
    void shouldAddAValueTheLibraryDoesNotHaveYet()
    {
        // GIVEN
        assertThat(metadataService.findId(MetadataType.ARTIST, "icr-not-yet")).isEmpty();

        // WHEN
        int rule = rule(0, LOSSLESS, false, "artist", "  ICR-Not-Yet ");

        // THEN the value exists, stored as every name is, and the rule names it
        assertThat(metadataService.findId(MetadataType.ARTIST, "icr-not-yet")).isPresent();
        assertThat(conditionsOf(rule)).containsExactly(condition(MetadataType.ARTIST, "icr-not-yet"));
    }

    /** A row left empty is no condition, and a value named twice counts once. */
    @Test
    void shouldIgnoreEmptyRowsAndRepeatedValues()
    {
        // WHEN
        int rule = rule(0, LOSSLESS, false, "tag", "icr-twice", "artist", "  ", "tag", "ICR-TWICE");

        // THEN
        assertThat(ruleRepository.findById(rule).orElseThrow().getConditionCount()).isOne();
        assertThat(conditionsOf(rule)).containsExactly(condition(MetadataType.TAG, "icr-twice"));
    }

    /** A ruled-out name has no row, so no gallery can carry it; saving nothing says why. */
    @Test
    void shouldRefuseANameAMetadataRuleRulesOut()
    {
        // GIVEN a tag removed with a rule, so imports drop it
        int tag = value(MetadataType.TAG, "icr-blocked");
        metadataService.remove(MetadataType.TAG, tag, true);
        flush();
        long rulesBefore = ruleRepository.count();

        // WHEN + THEN
        assertThatThrownBy(() -> rule(0, LOSSLESS, false, "artist", "icr-fine", "tag", "icr-blocked"))
                .isInstanceOf(ImageCompressionRuleService.Refused.class)
                .hasMessageContaining("icr-blocked")
                .hasMessageContaining("remove it from every download");
        assertThat(ruleRepository.count()).isEqualTo(rulesBefore);
    }

    @Test
    void shouldRefuseANameWithAPipe()
    {
        // WHEN + THEN
        assertThatThrownBy(() -> rule(0, LOSSLESS, false, "character", "icr a | icr b"))
                .isInstanceOf(ImageCompressionRuleService.Refused.class)
                .hasMessageContaining("can't contain \"|\"");
    }

    /** Editing replaces the conditions; the old ones are not kept beside the new. */
    @Test
    void shouldReplaceTheConditionsWhenARuleIsEdited()
    {
        // GIVEN
        int rule = rule(3, HIGH, false, "artist", "icr-edit-old");
        CompressionRuleForm form = ruleService.editing(rule).orElseThrow().form();
        assertThat(form.getConditionValues()).containsExactly("icr-edit-old");

        // WHEN
        form.setConditionTypes(new ArrayList<>(List.of("parody")));
        form.setConditionValues(new ArrayList<>(List.of("icr-edit-new")));
        form.setPriority(0);
        form.setAllowUpgrade(true);
        ruleService.save(form);
        flush();

        // THEN
        CompressionRuleView view = view(rule);
        assertThat(view.priority()).isZero();
        assertThat(view.allowUpgrade()).isTrue();
        assertThat(view.conditions()).containsExactly(condition(MetadataType.PARODY, "icr-edit-new"));
    }

    // ---- the page ----------------------------------------------------------------------------------------------

    @Test
    void shouldListRulesInPriorityOrderAndFilterByAValue()
    {
        // GIVEN
        int second = rule(4, HIGH, false, "artist", "icr-list-a");
        int first = rule(2, LOSSLESS, false, "artist", "icr-list-a", "tag", "icr-list-t");
        int other = rule(3, VERY_HIGH, false, "artist", "icr-list-b");

        // WHEN
        Page<CompressionRuleView> all = ruleService.page(0, null, null);
        Page<CompressionRuleView> naming = ruleService.page(0, MetadataType.ARTIST, "ICR-LIST-A");
        Page<CompressionRuleView> unknown = ruleService.page(0, MetadataType.ARTIST, "icr-nobody");

        // THEN
        assertThat(all.getContent()).extracting(CompressionRuleView::id).containsSubsequence(first, other, second);
        assertThat(naming.getContent()).extracting(CompressionRuleView::id).containsExactly(first, second);
        assertThat(naming.getTotalElements()).isEqualTo(2);
        assertThat(naming.getContent().getFirst().modeName()).isEqualTo("Lossless");
        assertThat(naming.getContent().getFirst().conditions()).containsExactly(
                condition(MetadataType.TAG, "icr-list-t"), condition(MetadataType.ARTIST, "icr-list-a"));
        assertThat(unknown.getContent()).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private int value(MetadataType type, String name)
    {
        assertThat(metadataService.add(type, name)).isEmpty();
        return metadataService.findId(type, name).orElseThrow();
    }

    private int chapter(List<Integer> artistIds, List<Integer> tagIds)
    {
        var form = new ChapterForm();
        form.setTitleFull("Image compression rule chapter " + System.nanoTime());
        form.setLanguage("English");
        form.setArtistIds(new ArrayList<>(artistIds));
        form.setTagIds(new ArrayList<>(tagIds));
        int id = chapterService.create(form);
        flush();
        return id;
    }

    /** @param conditions kind key and name, in pairs */
    private int rule(int priority, String mode, boolean allowUpgrade, String... conditions)
    {
        var form = new CompressionRuleForm();
        form.setPriority(priority);
        form.setCompressionMode(mode);
        form.setAllowUpgrade(allowUpgrade);
        for (int i = 0; i < conditions.length; i += 2)
        {
            form.getConditionTypes().add(conditions[i]);
            form.getConditionValues().add(conditions[i + 1]);
        }
        int id = ruleService.save(form);
        flush();
        return id;
    }

    private CompressionRuleView view(int ruleId)
    {
        return ruleService.page(0, null, null).getContent().stream()
                .filter(rule -> rule.id() == ruleId)
                .findFirst()
                .orElseThrow();
    }

    private List<CompressionRuleView.Condition> conditionsOf(int ruleId)
    {
        return view(ruleId).conditions();
    }

    private static CompressionRuleView.Condition condition(MetadataType type, String name)
    {
        return new CompressionRuleView.Condition(type, name);
    }

    private static ImageCompressionMode mode(String name, int quality)
    {
        var mode = new ImageCompressionMode();
        mode.setName(name);
        mode.setEncoder(ImageEncoder.JXL);
        mode.setEncoderArgs("-q 80");
        mode.setQuality(quality);
        return mode;
    }

    private void flush()
    {
        em.flush();
        em.clear();
    }
}

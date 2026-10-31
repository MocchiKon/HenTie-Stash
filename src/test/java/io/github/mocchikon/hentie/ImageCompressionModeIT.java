package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.CompressionModeOption;
import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.entity.ImageCompressionMode;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.repository.ImageCompressionModeRepository;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

/**
 * Compression modes and the dropdown. The feature rests on <b>a choice being stored as a key</b>, so this is
 * mostly about keys surviving: a rename keeps the key, a delete degrades to "None", built-in keys never move.
 */
@SpringBootTest
@Transactional
class ImageCompressionModeIT
{
    @Autowired ImageCompressionModeService modeService;
    @Autowired ImageCompressionModeRepository repository;

    /** "Custom" is flagged rather than a normal option, because nothing may ever store it. */
    @Test
    void shouldListNoneTheBuiltInModesTheUserModesAndCustomInThatOrder()
    {
        // GIVEN two user-defined modes.
        modeService.save(mode("Zebra", ImageEncoder.JXL));
        modeService.save(mode("Alpha", ImageEncoder.AVIF));

        // WHEN
        List<CompressionModeOption> options = modeService.options();

        // THEN the built-ins come first, in their fixed order...
        assertThat(options).extracting(CompressionModeOption::label)
                .startsWith("None", "Lossless", "High reduction", "Very high reduction")
                // ...then the user's own, by creation order, not name...
                .containsSubsequence("Zebra", "Alpha")
                // ...and "Custom" is last.
                .endsWith("Custom");
        assertThat(options.get(options.size() - 1).custom()).isTrue();
        assertThat(options.stream().filter(CompressionModeOption::custom)).hasSize(1);
    }

    /** Asserts the values that actually ship, not a copy of them. */
    @Test
    void shouldConfigureTheThreeBuiltInModesFromTheApplicationProperties()
    {
        // WHEN + THEN Lossless: JXL, no ImageMagick, no thresholds, JPG and PNG only.
        CompressionProfile lossless = modeService.resolve("LOSSLESS").orElseThrow();
        assertThat(lossless.encoder()).isEqualTo(ImageEncoder.JXL);
        assertThat(lossless.encoderArgs()).containsExactly(
                "-q", "100", "-e", "10", "--lossless_jpeg=1", "--allow_jpeg_reconstruction=0");
        assertThat(lossless.magickArgs()).isEmpty();
        assertThat(lossless.usesImageMagick()).isFalse();
        assertThat(lossless.imageHeightLimit()).isNull();
        assertThat(lossless.minRelativeReduction()).isZero();
        assertThat(lossless.minAbsoluteReduction()).isZero();
        assertThat(lossless.formats()).containsExactlyInAnyOrder("jpg", "png");

        // WHEN + THEN High reduction: source formats only, so re-running it over its own output is a no-op
        // rather than a second halving.
        CompressionProfile high = modeService.resolve("HIGH_REDUCTION").orElseThrow();
        assertThat(high.encoder()).isEqualTo(ImageEncoder.JXL);
        assertThat(high.encoderArgs()).containsExactly(
                "-q", "60", "-e", "10", "--lossless_jpeg=0", "--allow_jpeg_reconstruction=0");
        assertThat(high.magickArgs()).containsExactly("-filter", "lanczos", "-resize", "50%");
        assertThat(high.imageHeightLimit()).isNull();
        assertThat(high.minRelativeReduction()).isEqualTo(20);
        assertThat(high.minAbsoluteReduction()).isEqualTo(20);
        assertThat(high.formats()).containsExactlyInAnyOrder("jpg", "png", "webp");

        // WHEN + THEN Very high reduction: the same, but AVIF.
        CompressionProfile veryHigh = modeService.resolve("VERY_HIGH_REDUCTION").orElseThrow();
        assertThat(veryHigh.encoder()).isEqualTo(ImageEncoder.AVIF);
        assertThat(veryHigh.encoderArgs()).containsExactly("-q", "35", "-s", "0");
        assertThat(veryHigh.magickArgs()).containsExactly("-filter", "lanczos", "-resize", "50%");
        assertThat(veryHigh.imageHeightLimit()).isNull();
        assertThat(veryHigh.minRelativeReduction()).isEqualTo(20);
        assertThat(veryHigh.minAbsoluteReduction()).isEqualTo(20);
        assertThat(veryHigh.formats()).containsExactlyInAnyOrder("jpg", "png", "webp");
    }

    @Test
    void shouldResolveNoneAndTheCustomSentinelToNoCompression()
    {
        // WHEN + THEN
        assertThat(modeService.resolve("NONE")).isEmpty();
        assertThat(modeService.resolve("CUSTOM")).isEmpty();
        assertThat(modeService.resolve("")).isEmpty();
        assertThat(modeService.resolve(null)).isEmpty();
    }

    /** An unknown key (a hand-edited database, a renamed built-in) must not stop a download. */
    @Test
    void shouldResolveAnUnknownKeyToNoCompressionRatherThanFailing()
    {
        // WHEN + THEN
        assertThat(modeService.resolve("NO_SUCH_MODE")).isEmpty();
        assertThat(modeService.resolve("custom:not-a-number")).isEmpty();
        assertThat(modeService.resolve("custom:999999")).isEmpty();
    }

    /**
     * Only {@code app.js} keeps the "Custom" sentinel from being submitted, and SQLite enforces no
     * {@code VARCHAR(n)}, so the whitelist is all that stops a hand-made POST storing anything.
     */
    @Test
    void shouldReduceAnythingThatIsNotARealModeToNoneBeforeItCanBeStored()
    {
        // WHEN + THEN the sentinel, the unknown, the malformed and the absurdly long all become NONE.
        assertThat(modeService.storableKey("CUSTOM")).isEqualTo("NONE");
        assertThat(modeService.storableKey("NO_SUCH_MODE")).isEqualTo("NONE");
        assertThat(modeService.storableKey("custom:not-a-number")).isEqualTo("NONE");
        assertThat(modeService.storableKey("custom:999999")).isEqualTo("NONE");
        assertThat(modeService.storableKey("x".repeat(10_000))).isEqualTo("NONE");
        assertThat(modeService.storableKey("")).isEqualTo("NONE");
        assertThat(modeService.storableKey(null)).isEqualTo("NONE");
    }

    /** Canonical spelling, so one mode is never stored under two keys. */
    @Test
    void shouldKeepARealModeAndCanonicaliseItsSpellingWhenStoring()
    {
        // GIVEN a user-defined mode alongside the built-in ones.
        String key = modeService.save(mode("Storable", ImageEncoder.JXL));

        // WHEN + THEN built-in keys survive whatever case they arrive in...
        assertThat(modeService.storableKey("LOSSLESS")).isEqualTo("LOSSLESS");
        assertThat(modeService.storableKey("  lossless  ")).isEqualTo("LOSSLESS");
        // ...and so does a custom one.
        assertThat(modeService.storableKey(key)).isEqualTo(key);
        assertThat(modeService.storableKey(key.toUpperCase(Locale.ROOT))).isEqualTo(key);
    }

    @Test
    void shouldRoundTripAUserDefinedModeWhenItIsSavedAndResolved()
    {
        // GIVEN
        ImageCompressionMode mode = mode("Archive", ImageEncoder.AVIF);
        mode.setEncoderArgs("-q 60 -s 4");
        mode.setMagickArgs("-resize 75%");
        mode.setImageHeightLimit(1000);
        mode.setMinRelativeReduction(30);
        mode.setMinAbsoluteReduction(150);
        mode.setFormats("JPG,GIF,PNG");

        // WHEN
        String key = modeService.save(mode);

        // THEN it is addressed by key, not by name...
        assertThat(key).isEqualTo("custom:" + mode.getId());
        // ...and every field comes back in the shape the built-in modes use.
        CompressionProfile profile = modeService.resolve(key).orElseThrow();
        assertThat(profile.name()).isEqualTo("Archive");
        assertThat(profile.encoder()).isEqualTo(ImageEncoder.AVIF);
        assertThat(profile.encoderArgs()).containsExactly("-q", "60", "-s", "4");
        assertThat(profile.magickArgs()).containsExactly("-resize", "75%");
        assertThat(profile.imageHeightLimit()).isEqualTo(1000);
        assertThat(profile.minRelativeReduction()).isEqualTo(30);
        assertThat(profile.minAbsoluteReduction()).isEqualTo(150);
        assertThat(profile.formats()).containsExactlyInAnyOrder("jpg", "gif", "png");
    }

    /**
     * cjxl compresses a JPEG losslessly by default, whatever quality the mode asks for, so the stored mode has
     * to say when it is not lossless. The switches are part of the mode so an edit shows what is really run.
     */
    @Test
    void shouldAddTheJpegSwitchesThatTheJxlEncoderNeeds()
    {
        // WHEN the mode asks for lossless quality...
        String lossless = modeService.save(mode("Lossless JPEG", "-q 100 -e 10"));
        // THEN it opts into lossless JPEG, and turns reconstruction metadata off.
        assertThat(modeService.resolve(lossless).orElseThrow().encoderArgs()).containsExactly(
                "-q", "100", "-e", "10", "--lossless_jpeg=1", "--allow_jpeg_reconstruction=0");

        // WHEN the quality is below 100...
        String reduced = modeService.save(mode("Reduced JPEG", "-q 80"));
        // THEN JPEG is compressed lossily after all, and the reconstruction switch is still there.
        assertThat(modeService.resolve(reduced).orElseThrow().encoderArgs()).containsExactly(
                "-q", "80", "--lossless_jpeg=0", "--allow_jpeg_reconstruction=0");

        // WHEN the user gives either switch...
        String chosen = modeService.save(mode("Chosen JPEG",
                "-q 100 --lossless_jpeg=0 --allow_jpeg_reconstruction=1"));
        // THEN their value wins, and nothing is appended twice.
        assertThat(modeService.resolve(chosen).orElseThrow().encoderArgs()).containsExactly(
                "-q", "100", "--lossless_jpeg=0", "--allow_jpeg_reconstruction=1");

        // WHEN an existing mode is edited to another quality...
        ImageCompressionMode edited = modeService.find(ImageCompressionModeService.customId(reduced)).orElseThrow();
        edited.setEncoderArgs("-q 100");
        modeService.save(edited);
        // THEN the stored switch follows the new quality rather than staying at the old one.
        assertThat(modeService.resolve(reduced).orElseThrow().encoderArgs()).containsExactly(
                "-q", "100", "--lossless_jpeg=1", "--allow_jpeg_reconstruction=0");
    }

    /** The switches are cjxl's; avifenc would reject them. */
    @Test
    void shouldLeaveTheArgumentsOfAnAvifModeAlone()
    {
        // WHEN
        ImageCompressionMode avif = mode("Plain AVIF", ImageEncoder.AVIF);
        avif.setEncoderArgs("-q 55 -s 2");
        String key = modeService.save(avif);

        // THEN
        assertThat(modeService.resolve(key).orElseThrow().encoderArgs())
                .containsExactly("-q", "55", "-s", "2");
    }

    /** Why a choice is stored as {@code custom:<id>}: a rename must not orphan the rows that picked it. */
    @Test
    void shouldKeepTheKeyWhenAModeIsRenamed()
    {
        // GIVEN
        String key = modeService.save(mode("Before", ImageEncoder.JXL));

        // WHEN the mode is renamed.
        ImageCompressionMode saved = modeService.find(ImageCompressionModeService.customId(key)).orElseThrow();
        saved.setName("After");
        assertThat(modeService.save(saved)).isEqualTo(key);

        // THEN the same key still resolves, now under the new name.
        assertThat(modeService.resolve(key).orElseThrow().name()).isEqualTo("After");
    }

    /** Refusing the delete instead would force the user to empty the queue before removing a mode. */
    @Test
    void shouldResolveToNoCompressionWhenTheModeItPointsAtHasBeenDeleted()
    {
        // GIVEN a saved mode that something still refers to.
        String key = modeService.save(mode("Temporary", ImageEncoder.JXL));
        assertThat(modeService.resolve(key)).isPresent();

        // WHEN it is deleted.
        modeService.delete(ImageCompressionModeService.customId(key));

        assertThat(modeService.resolve(key)).isEmpty();
        assertThat(modeService.displayName(key)).isEqualTo("None");
    }

    /** The name is UNIQUE; at commit a violation comes from a dead transaction that cannot name the field. */
    @Test
    void shouldRefuseANameAnotherModeAlreadyHas()
    {
        // GIVEN
        modeService.save(mode("Shared name", ImageEncoder.JXL));

        // WHEN + THEN a second mode cannot take it, whatever the capitalisation.
        assertThatThrownBy(() -> modeService.save(mode("shared NAME", ImageEncoder.AVIF)))
                .isInstanceOf(ImageCompressionModeService.NameTaken.class);
    }

    /** A second "Lossless" in the list would be unreadable, and a stored choice ambiguous. */
    @Test
    void shouldRefuseTheNameOfABuiltInMode()
    {
        // WHEN + THEN
        assertThatThrownBy(() -> modeService.save(mode("Lossless", ImageEncoder.JXL)))
                .isInstanceOf(ImageCompressionModeService.NameTaken.class);
        assertThatThrownBy(() -> modeService.save(mode("none", ImageEncoder.JXL)))
                .isInstanceOf(ImageCompressionModeService.NameTaken.class);
    }

    /** A mode named "Custom" would put two identical entries in the list, one selecting it, one leaving the page. */
    @Test
    void shouldRefuseTheNameOfTheCustomEntry()
    {
        // WHEN + THEN, whatever the capitalisation - and nothing is stored.
        assertThatThrownBy(() -> modeService.save(mode("custom", ImageEncoder.JXL)))
                .isInstanceOf(ImageCompressionModeService.NameTaken.class)
                .hasMessageContaining("defining a new mode");
        assertThat(modeService.customModes()).isEmpty();
    }

    @Test
    void shouldAllowSavingAModeUnderItsOwnName()
    {
        // GIVEN
        String key = modeService.save(mode("Keeps its name", ImageEncoder.JXL));
        ImageCompressionMode saved = modeService.find(ImageCompressionModeService.customId(key)).orElseThrow();

        // WHEN + THEN
        saved.setEncoderArgs("-q 70");
        assertThatCode(() -> modeService.save(saved)).doesNotThrowAnyException();
        assertThat(repository.findFirstByNameIgnoreCase("Keeps its name")).isPresent();
    }

    /** The queue page asks once for the whole map rather than once per row. */
    @Test
    void shouldMapEveryStorableKeyToItsLabelForTheQueuePage()
    {
        // GIVEN
        String key = modeService.save(mode("Mine", ImageEncoder.JXL));

        // WHEN
        var names = modeService.namesByKey();

        // THEN every key that can be stored is there...
        assertThat(names)
                .containsEntry(BuiltInCompressionMode.NONE.getKey(), "None")
                .containsEntry(BuiltInCompressionMode.LOSSLESS.getKey(), "Lossless")
                .containsEntry(key, "Mine");
        // ...and the one that cannot be stored is not.
        assertThat(names).doesNotContainKey(BuiltInCompressionMode.CUSTOM_KEY);
    }

    private static ImageCompressionMode mode(String name, ImageEncoder encoder)
    {
        var mode = new ImageCompressionMode();
        mode.setName(name);
        mode.setEncoder(encoder);
        return mode;
    }

    private static ImageCompressionMode mode(String name, String encoderArgs)
    {
        ImageCompressionMode mode = mode(name, ImageEncoder.JXL);
        mode.setEncoderArgs(encoderArgs);
        return mode;
    }
}

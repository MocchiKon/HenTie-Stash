package io.github.mocchikon.hentie.dto;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mocchikon.hentie.entity.ImageEncoder;

import static org.assertj.core.api.Assertions.*;

/** All of it is user-typed text that ends up on a command line, where a bad edge case makes a codec refuse a file. */
class CompressionProfileTest
{
    @Test
    void shouldSplitArgumentsOnWhitespaceWhenBuildingTheCommandLine()
    {
        // WHEN + THEN
        assertThat(CompressionProfile.splitArgs("-q 100 -e 10")).containsExactly("-q", "100", "-e", "10");
        assertThat(CompressionProfile.splitArgs("-filter lanczos -resize 50%"))
                .containsExactly("-filter", "lanczos", "-resize", "50%");
    }

    /** An empty argument is not harmless: {@code cjxl} reads it as a positional argument and refuses the job. */
    @Test
    void shouldIgnoreSurroundingAndRepeatedWhitespaceWhenSplittingArguments()
    {
        // WHEN + THEN
        assertThat(CompressionProfile.splitArgs("   -q    100\t-e 10  "))
                .containsExactly("-q", "100", "-e", "10");
        assertThat(CompressionProfile.splitArgs("   ")).isEmpty();
        assertThat(CompressionProfile.splitArgs("")).isEmpty();
        assertThat(CompressionProfile.splitArgs(null)).isEmpty();
    }

    /** Quoting is the only way to pass an argument that legitimately contains a space. */
    @Test
    void shouldKeepAQuotedArgumentTogetherWhenSplittingArguments()
    {
        // WHEN + THEN
        assertThat(CompressionProfile.splitArgs("-define \"png:compression-level=9\" -resize 50%"))
                .containsExactly("-define", "png:compression-level=9", "-resize", "50%");
        // An empty quoted argument is still an argument: the user asked for it.
        assertThat(CompressionProfile.splitArgs("-set \"\"")).containsExactly("-set", "");
    }

    @Test
    void shouldLowerCaseAndStripDotsWhenParsingTheFormatList()
    {
        // WHEN + THEN
        assertThat(CompressionProfile.parseFormats("JPG,PNG")).containsExactlyInAnyOrder("jpg", "png");
        assertThat(CompressionProfile.parseFormats(" .JPG ,  gif ; PNG"))
                .containsExactlyInAnyOrder("jpg", "gif", "png");
        // Blank means "every image", which is what the two reduction modes ship with.
        assertThat(CompressionProfile.parseFormats("")).isEmpty();
        assertThat(CompressionProfile.parseFormats(null)).isEmpty();
    }

    /** Guards the regex's double backslash: a single one is Java's {@code \s} escape, a plain space only. */
    @Test
    void shouldSeparateFormatsOnTabsAndNewlinesWhenParsingTheFormatList()
    {
        // WHEN
        Set<String> formats = CompressionProfile.parseFormats("JPG\tPNG\nGIF\r\nwebp");

        // THEN
        assertThat(formats).containsExactly("jpg", "png", "gif", "webp");
    }

    @Test
    void shouldProcessEveryImageWhenTheFormatListIsEmpty()
    {
        // GIVEN a mode with no format restriction.
        var profile = profile("");

        // WHEN + THEN
        assertThat(profile.processes("png")).isTrue();
        assertThat(profile.processes("webp")).isTrue();
        assertThat(profile.processes("bmp")).isTrue();
    }

    @Test
    void shouldProcessOnlyTheListedFormatsWhenTheFormatListIsSet()
    {
        // GIVEN the built-in Lossless mode's list.
        var profile = profile("JPG,PNG");

        // WHEN + THEN
        assertThat(profile.processes("png")).isTrue();
        assertThat(profile.processes("PNG")).isTrue();
        assertThat(profile.processes("gif")).isFalse();
        assertThat(profile.processes("webp")).isFalse();
    }

    /** The extension comes straight from the source's URL, so either spelling must cover both, or pages go unprocessed. */
    @Test
    void shouldTreatJpgAndJpegAsOneFormatWhenMatchingTheFormatList()
    {
        // WHEN + THEN
        assertThat(profile("JPG").processes("jpeg")).isTrue();
        assertThat(profile("JPEG").processes("jpg")).isTrue();
    }

    @Test
    void shouldSayImageMagickIsUsedOnlyWhenItHasArguments()
    {
        // WHEN + THEN
        assertThat(new CompressionProfile("K", "n", ImageEncoder.JXL, List.of(), List.of(), 0, 0, Set.of())
                .usesImageMagick()).isFalse();
        assertThat(new CompressionProfile("K", "n", ImageEncoder.JXL, List.of(),
                List.of("-resize", "50%"), 0, 0, Set.of()).usesImageMagick()).isTrue();
    }

    /**
     * {@code cjxl} is {@code INPUT OUTPUT [OPTIONS]}, {@code avifenc} {@code [OPTIONS] INPUT OUTPUT}; the wrong
     * order makes the encoder read an option as a filename and fail on every image.
     */
    @Test
    void shouldPutTheArgumentsWhereEachEncoderExpectsThemWhenBuildingTheCommandLine()
    {
        // GIVEN
        var args = List.of("-q", "50");

        // WHEN + THEN
        assertThat(ImageEncoder.JXL.commandLine("cjxl", "in.png", "out.jxl", args))
                .containsExactly("cjxl", "in.png", "out.jxl", "-q", "50");
        assertThat(ImageEncoder.AVIF.commandLine("avifenc", "in.png", "out.avif", args))
                .containsExactly("avifenc", "-q", "50", "in.png", "out.avif");
    }

    /** What each encoder will read decides whether a lossless PNG detour is needed first. */
    @Test
    void shouldKnowWhichInputsEachEncoderReads()
    {
        // WHEN + THEN
        assertThat(ImageEncoder.JXL.accepts("gif")).isTrue();
        assertThat(ImageEncoder.JXL.accepts("JPEG")).isTrue();
        assertThat(ImageEncoder.JXL.accepts("webp")).isFalse();
        assertThat(ImageEncoder.AVIF.accepts("png")).isTrue();
        assertThat(ImageEncoder.AVIF.accepts("gif")).isFalse();
        assertThat(ImageEncoder.AVIF.accepts("webp")).isFalse();
    }

    private static CompressionProfile profile(String formats)
    {
        return new CompressionProfile("K", "n", ImageEncoder.JXL, List.of(), List.of(), 0, 0,
                CompressionProfile.parseFormats(formats));
    }
}

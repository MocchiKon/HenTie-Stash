package io.github.mocchikon.hentie.service.compress;

import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * An encoder that reports success without writing a usable file. The runner is stubbed because a working
 * encoder never produces this case. Judged on size alone, an empty file would replace the page and the
 * original would be deleted, a loss no rescan or re-download repairs (e.g. a short write on a full RAM disk).
 */
class ImageCompressorTest
{
    /** A plausible JPEG XL: the raw-codestream signature, then anything. */
    private static final byte[] REAL_JXL = {(byte) 0xFF, 0x0A, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    @TempDir
    private Path tmp;

    private ImageToolRunner runner;
    private ImageCompressor compressor;
    private Path source;
    private Path workDir;

    @BeforeEach
    void setUp() throws IOException
    {
        var locator = mock(ImageToolLocator.class);
        when(locator.find(anyString())).thenReturn(Optional.of("cjxl"));
        runner = mock(ImageToolRunner.class);
        compressor = new ImageCompressor(locator, runner);

        var dir = Files.createDirectories(tmp.resolve("data"));
        source = dir.resolve("3.png");
        Files.write(source, new byte[4096]);
        workDir = tmp.resolve("work");
    }

    @Test
    void shouldKeepTheOriginalWhenTheEncoderSucceedsButWritesAnEmptyFile()
    {
        // GIVEN an encoder that "succeeds" and leaves a zero-byte file behind.
        encoderWrites(new byte[0]);

        // WHEN
        ImageCompressor.Result result = compressor.compress(source, profile(), workDir);

        // THEN the page is exactly as it was - not replaced by the empty file, and not deleted.
        assertThat(result.replaced()).isFalse();
        assertThat(result.file()).isEqualTo(source);
        assertThat(result.originalBytes()).isEqualTo(4096);
        // Or the run would report a saving.
        assertThat(result.finalBytes()).isEqualTo(result.originalBytes());
        assertThat(result.saved()).isZero();
        assertThat(source).exists();
        assertThat(source.resolveSibling("3.jxl")).doesNotExist();
    }

    @Test
    void shouldKeepTheOriginalWhenTheEncoderSucceedsButWritesSomethingThatIsNotAJpegXl()
    {
        // GIVEN a small file that would easily clear both thresholds on size alone.
        encoderWrites("not an image, but certainly smaller".getBytes());

        // WHEN
        ImageCompressor.Result result = compressor.compress(source, profile(), workDir);

        // THEN
        assertThat(result.replaced()).isFalse();
        assertThat(result.file()).isEqualTo(source);
        assertThat(result.originalBytes()).isEqualTo(4096);
        assertThat(result.finalBytes()).isEqualTo(result.originalBytes());
        assertThat(result.saved()).isZero();
        assertThat(source).exists();
        assertThat(source.resolveSibling("3.jxl")).doesNotExist();
    }

    @Test
    void shouldReplaceThePageWhenTheEncoderProducesARealSmallerFile()
    {
        // GIVEN
        encoderWrites(REAL_JXL);

        // WHEN
        ImageCompressor.Result result = compressor.compress(source, profile(), workDir);

        // THEN the page is page 3 still, now as .jxl, and the original is gone.
        assertThat(result.replaced()).isTrue();
        assertThat(result.file()).isEqualTo(source.resolveSibling("3.jxl"));
        assertThat(result.file()).exists();
        assertThat(result.originalBytes()).isEqualTo(4096);
        assertThat(result.finalBytes()).isEqualTo(REAL_JXL.length);
        assertThat(result.saved()).isEqualTo(4096 - REAL_JXL.length);
        assertThat(source).doesNotExist();
    }

    /** A file that only parses to the same page number is a page of its own. */
    @Test
    void shouldKeepFilesThatOnlyShareThePageNumberWhenReplacingAPage() throws IOException
    {
        // GIVEN page 3 beside a separate "03.png" page and a non-image that shares the number.
        encoderWrites(REAL_JXL);
        Files.writeString(source.resolveSibling("03.png"), "a page of its own");
        Files.writeString(source.resolveSibling("3.json"), "{}");

        // WHEN
        ImageCompressor.Result result = compressor.compress(source, profile(), workDir);

        // THEN only 3.png - the file the encoder output replaced - is gone.
        assertThat(result.replaced()).isTrue();
        assertThat(result.file()).isEqualTo(source.resolveSibling("3.jxl"));
        assertThat(source).doesNotExist();
        assertThat(source.resolveSibling("03.png")).hasContent("a page of its own");
        assertThat(source.resolveSibling("3.json")).hasContent("{}");
    }

    /**
     * The work folder is per chapter, so names per page would let a download and a compression of page 3
     * delete each other's output, or publish the other's half-written file.
     */
    @Test
    void shouldNotTouchAnotherCallsIntermediateForTheSamePage() throws IOException
    {
        // GIVEN another run's encoder output sitting at the name a per-page scheme would use.
        encoderWrites(REAL_JXL);
        Files.createDirectories(workDir);
        Path othersOutput = workDir.resolve("3.jxl");
        Files.writeString(othersOutput, "another run's output, still being written");

        // WHEN
        ImageCompressor.Result result = compressor.compress(source, profile(), workDir);

        // THEN this call wrote elsewhere, published its own result, and left the other file alone.
        assertThat(result.replaced()).isTrue();
        assertThat(result.finalBytes()).isEqualTo(REAL_JXL.length);
        assertThat(result.file()).hasBinaryContent(REAL_JXL);
        assertThat(othersOutput).hasContent("another run's output, still being written");
        verify(runner).run(argThat(command -> !Path.of(command.get(2)).equals(othersOutput)));
    }

    /** Lossless mode's shape: straight to the encoder, no ImageMagick, every format in scope. */
    private static CompressionProfile profile()
    {
        return new CompressionProfile("TEST", "Test", ImageEncoder.JXL,
                List.of(), List.of(), 0, 0, Set.of());
    }

    /** For cjxl the command is {@code [exe, input, output, args...]}, so the output is the third element. */
    private void encoderWrites(byte[] bytes)
    {
        when(runner.run(anyList())).thenAnswer(invocation ->
        {
            List<String> command = invocation.getArgument(0);
            Files.write(Path.of(command.get(2)), bytes);
            return true;
        });
    }
}

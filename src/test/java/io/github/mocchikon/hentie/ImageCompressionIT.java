package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import io.github.mocchikon.hentie.service.compress.ImageCompressor;
import io.github.mocchikon.hentie.service.compress.ImageToolLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Image Compression against the <b>real</b> binaries in {@code bin/}: the interaction with those tools is the
 * feature, and a mock would only assert our own assumptions back at us. A platform missing a binary skips the
 * tests that need it. No database; it works in its own folders under {@code ./target}.
 */
@SpringBootTest
class ImageCompressionIT
{
    /** Big enough for an unambiguous lossy saving, small enough to stay fast. */
    private static final int SIZE = 400;

    @Autowired ImageCompressor compressor;
    @Autowired ImageCompressionService compressionService;
    @Autowired ImageToolLocator toolLocator;
    @Autowired AppProperties appProperties;

    private Path dir;
    private Path work;
    private String originalBinDir;

    @BeforeEach
    void setUp() throws IOException
    {
        dir = Paths.get("./target/test-compress/pages").toAbsolutePath().normalize();
        work = Paths.get("./target/test-compress/work").toAbsolutePath().normalize();
        deleteRecursively(dir.getParent());
        Files.createDirectories(dir);
        Files.createDirectories(work);
        originalBinDir = appProperties.getImageCompression().getBinDir();
    }

    @AfterEach
    void tearDown()
    {
        // A shared singleton: left repointed, every later suite would look for the binaries in the wrong place.
        appProperties.getImageCompression().setBinDir(originalBinDir);
        deleteRecursively(dir.getParent());
    }

    // ---- encoding ----------------------------------------------------------

    /** The page keeps its <b>number</b>, which the listing, the stats and the download pipeline all rely on. */
    @Test
    void shouldReplaceThePageWithAJpegXlOfTheSameNumberWhenEncodingSaves() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN page 3 as a PNG.
        Path source = TestImages.writePng(dir.resolve("3.png"), SIZE, SIZE);
        long before = Files.size(source);

        // WHEN it is compressed with a lossy JPEG XL mode.
        ImageCompressor.Result result = compressor.compress(source, jxl("-q 40 -e 1"), work);

        // THEN the page is now 3.jxl, and 3.png is gone.
        assertThat(result.replaced()).isTrue();
        assertThat(result.file()).isEqualTo(dir.resolve("3.jxl"));
        assertThat(Files.exists(dir.resolve("3.png"))).isFalse();
        assertThat(pageFiles()).containsExactly("3.jxl");
        // AND it really is smaller.
        assertThat(result.finalBytes()).isLessThan(before);
        assertThat(Files.size(dir.resolve("3.jxl"))).isEqualTo(result.finalBytes());
    }

    /**
     * Without a RAM disk the work folder is in the data folder, on the page's filesystem, so the encoder's output
     * becomes the page by a rename, flushed first. The page must be the whole output, under the page's name only.
     */
    @Test
    void shouldStoreTheWholeEncodedPageWhenTheWorkFolderIsOnThePagesFilesystem() throws IOException
    {
        assumeTool("cjxl");
        assumeTool("djxl");
        // GIVEN page 3 as a PNG, and the work folder the app picks without a RAM disk.
        Path source = TestImages.writePng(dir.resolve("3.png"), SIZE, SIZE);
        Path automaticWork = Files.createDirectories(compressionService.workDir(987_656));
        assertThat(Files.getFileStore(automaticWork)).isEqualTo(Files.getFileStore(dir));

        // WHEN
        ImageCompressionService.Summary summary =
                compressionService.compressFiles(987_656, List.of(source), jxl("-q 40 -e 1"));

        // THEN 3.jxl is the only file left, and it decodes to the whole page.
        assertThat(summary.replaced()).isEqualTo(1);
        assertThat(listNames(dir)).containsExactly("3.jxl");
        assertThat(decodedSize(dir.resolve("3.jxl"))).isEqualTo(SIZE);
        // AND the run left nothing in its work folder.
        assertThat(automaticWork).doesNotExist();
    }

    @Test
    void shouldProduceAnAvifWhenTheModeUsesTheAvifEncoder() throws IOException
    {
        assumeTool("avifenc");
        // GIVEN
        Path source = TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE);

        // WHEN
        ImageCompressor.Result result = compressor.compress(source,
                profile(ImageEncoder.AVIF, "-q 40 -s 10", "", 0, 0, ""), work);

        // THEN the page is an AVIF, checked by its signature, since this code chose the extension itself.
        assertThat(result.replaced()).isTrue();
        assertThat(pageFiles()).containsExactly("1.avif");
        assertThat(new String(Files.readAllBytes(dir.resolve("1.avif")), StandardCharsets.ISO_8859_1))
                .contains("ftypavif");
    }

    /** Asserted by the decoded dimensions: "smaller" would also hold if only the lossy encoder had run. */
    @Test
    void shouldDownscaleWithImageMagickBeforeEncodingWhenTheModeHasMagickArguments() throws IOException
    {
        assumeTool(ImageToolLocator.MAGICK);
        assumeTool("cjxl");
        assumeTool("djxl");
        // GIVEN a 400x400 page.
        Path source = TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE);

        // WHEN it is compressed with the built-in High reduction shape.
        ImageCompressor.Result result = compressor.compress(source,
                jxlWithMagick("-q 50 -e 1", "-filter lanczos -resize 50%"), work);

        // THEN the stored page is half the size in each dimension.
        assertThat(result.replaced()).isTrue();
        assertThat(decodedSize(dir.resolve("1.jxl"))).isEqualTo(SIZE / 2);
    }

    /**
     * Without the lossless PNG detour, a format the encoder cannot read would never be compressed. WebP is
     * the real case: sources serve it and neither cjxl nor avifenc reads it.
     */
    @Test
    void shouldConvertToPngFirstWhenTheEncoderCannotReadTheInputFormat() throws IOException
    {
        assumeTool(ImageToolLocator.MAGICK);
        assumeTool("cjxl");
        // GIVEN a page in a format cjxl does not read...
        Path png = TestImages.writePng(dir.resolve("source.png"), SIZE, SIZE);
        Path source = dir.resolve("1.webp");
        assertThat(runMagick(png, source)).isTrue();
        Files.delete(png);
        assertThat(ImageEncoder.JXL.accepts("webp")).isFalse();

        // WHEN it is compressed with a mode that asks for no resizing at all.
        ImageCompressor.Result result = compressor.compress(source, jxl("-q 40 -e 1"), work);

        // THEN it was still encoded, though the mode never mentions ImageMagick.
        assertThat(result.replaced()).isTrue();
        assertThat(pageFiles()).containsExactly("1.jxl");
    }

    @Test
    void shouldLeaveNoIntermediateFilesBehindWhenCompressingAPage() throws IOException
    {
        assumeTool(ImageToolLocator.MAGICK);
        assumeTool("cjxl");
        // GIVEN
        Path source = TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE);

        // WHEN
        compressor.compress(source, jxlWithMagick("-q 50 -e 1", "-resize 50%"), work);

        // THEN neither the ImageMagick output nor the encoder output is left anywhere.
        assertThat(listNames(work)).isEmpty();
        assertThat(pageFiles()).containsExactly("1.jxl");
    }

    // ---- the reduction thresholds -------------------------------------------

    @Test
    void shouldKeepTheOriginalWhenTheSavingIsBelowTheRelativeThreshold() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a mode that will only accept a 99% reduction.
        Path source = TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE);
        long before = Files.size(source);

        // WHEN
        ImageCompressor.Result result = compressor.compress(source,
                profile(ImageEncoder.JXL, "-q 40 -e 1", "", 99, 0, ""), work);

        // THEN nothing was replaced and the file is byte for byte the one we wrote.
        assertThat(result.replaced()).isFalse();
        assertThat(result.file()).isEqualTo(source);
        assertThat(pageFiles()).containsExactly("1.png");
        assertThat(Files.size(source)).isEqualTo(before);
    }

    @Test
    void shouldKeepTheOriginalWhenTheSavingIsBelowTheAbsoluteThreshold() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a mode demanding 100 MB of saving from a page of a few hundred KB.
        Path source = TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE);

        // WHEN
        ImageCompressor.Result result = compressor.compress(source,
                profile(ImageEncoder.JXL, "-q 40 -e 1", "", 0, 100_000, ""), work);

        // THEN
        assertThat(result.replaced()).isFalse();
        assertThat(pageFiles()).containsExactly("1.png");
    }

    /** Thresholds at 0 mean "no particular saving required", not "store a bigger file". */
    @Test
    void shouldKeepTheOriginalWhenEncodingProducesNoSavingAtAllEvenWithoutThresholds() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a page that is already a JPEG XL, re-encoded losslessly at maximum quality: nothing to gain.
        Path png = TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE);
        assertThat(compressor.compress(png, jxl("-q 100 -e 1"), work).replaced()).isTrue();
        Path jxlPage = dir.resolve("1.jxl");
        long before = Files.size(jxlPage);

        // WHEN it is put through the same lossless mode again, with both thresholds off.
        ImageCompressor.Result result = compressor.compress(jxlPage, jxl("-q 100 -e 1"), work);

        // THEN the page is left exactly as it was.
        assertThat(result.replaced()).isFalse();
        assertThat(Files.size(jxlPage)).isEqualTo(before);
    }

    // ---- scope --------------------------------------------------------------

    @Test
    void shouldSkipAPageWhoseFormatTheModeDoesNotList() throws IOException
    {
        // GIVEN a PNG page and a mode that only processes GIFs.
        Path source = TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE);
        long before = Files.size(source);

        // WHEN
        ImageCompressor.Result result = compressor.compress(source,
                profile(ImageEncoder.JXL, "-q 40 -e 1", "", 0, 0, "GIF"), work);

        // THEN it was not even attempted, so no tool had to be present.
        assertThat(result.replaced()).isFalse();
        assertThat(result.originalBytes()).isEqualTo(before);
        assertThat(pageFiles()).containsExactly("1.png");
    }

    // ---- failure -------------------------------------------------------------

    /** Every failure keeps the page as it is. A missing binary is the failure that really happens. */
    @Test
    void shouldKeepTheOriginalWhenTheEncoderBinaryIsMissing() throws IOException
    {
        // GIVEN a bin folder with nothing in it.
        appProperties.getImageCompression().setBinDir("./target/test-compress/no-binaries");
        Path source = TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE);
        long before = Files.size(source);

        // WHEN
        ImageCompressor.Result result = compressor.compress(source, jxl("-q 40 -e 1"), work);

        // THEN the page survives untouched, and the caller is told it was not replaced.
        assertThat(result.replaced()).isFalse();
        assertThat(result.file()).isEqualTo(source);
        assertThat(Files.size(source)).isEqualTo(before);
        assertThat(pageFiles()).containsExactly("1.png");
    }

    @Test
    void shouldKeepTheOriginalWhenTheEncoderRejectsTheFile() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN a "page" that is not an image at all, of a format cjxl claims to read.
        Path source = dir.resolve("1.png");
        Files.writeString(source, "this is not a PNG");

        // WHEN
        ImageCompressor.Result result = compressor.compress(source, jxl("-q 40 -e 1"), work);

        // THEN
        assertThat(result.replaced()).isFalse();
        assertThat(pageFiles()).containsExactly("1.png");
        assertThat(Files.readString(source)).isEqualTo("this is not a PNG");
    }

    // ---- many pages at once --------------------------------------------------

    @Test
    void shouldCompressEveryPageAndSummariseWhatHappenedWhenGivenAWholeSetOfFiles() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN three PNG pages and a fourth in a format the mode does not list.
        Path skipped = dir.resolve("4.gif");
        Files.writeString(skipped, "not processed by a PNG-only mode");
        var pages = List.of(
                TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE),
                TestImages.writePng(dir.resolve("2.png"), SIZE, SIZE),
                TestImages.writePng(dir.resolve("3.png"), SIZE, SIZE),
                skipped);

        // WHEN
        ImageCompressionService.Summary summary = compressionService.compressFiles(
                987_654, pages, profile(ImageEncoder.JXL, "-q 40 -e 1", "", 0, 0, "PNG"));

        // THEN every page is accounted for, including the one deliberately left alone...
        assertThat(summary.files()).isEqualTo(4);
        assertThat(summary.replaced()).isEqualTo(3);
        assertThat(summary.saved()).isPositive();
        assertThat(summary.after()).isLessThan(summary.before());
        assertThat(summary.describe()).startsWith("3 of 4 image(s) re-encoded,");
        assertThat(pageFiles()).containsExactly("1.jxl", "2.jxl", "3.jxl", "4.gif");
    }

    @Test
    void shouldDoNothingWhenTheModeIsNone() throws IOException
    {
        // GIVEN
        Path source = TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE);

        // WHEN
        ImageCompressionService.Summary summary =
                compressionService.compressFiles(987_655, List.of(source), null);

        // THEN
        assertThat(summary).isEqualTo(ImageCompressionService.Summary.NOTHING);
        assertThat(pageFiles()).containsExactly("1.png");
    }

    /** A run interrupted between writing the new file and deleting the old leaves both; the next must clean up. */
    @Test
    void shouldRemoveALeftoverWithTheSameBaseNameWhenReplacingAPage() throws IOException
    {
        assumeTool("cjxl");
        // GIVEN page 1 present as both a PNG and a leftover JPEG from an interrupted run.
        Path source = TestImages.writePng(dir.resolve("1.png"), SIZE, SIZE);
        Files.writeString(dir.resolve("1.jpg"), "leftover");

        // WHEN the PNG is compressed.
        compressor.compress(source, jxl("-q 40 -e 1"), work);

        // THEN only the new page remains.
        assertThat(pageFiles()).containsExactly("1.jxl");
    }

    // ---- helpers -------------------------------------------------------------

    /** Skips the test when this platform's copy of a tool is not in {@code bin/}. */
    private void assumeTool(String tool)
    {
        Assumptions.assumeTrue(toolLocator.find(tool).isPresent(),
                () -> "No bundled " + tool + " for this platform - skipping");
    }

    private boolean runMagick(Path input, Path output) throws IOException
    {
        var command = List.of(toolLocator.find(ImageToolLocator.MAGICK).orElseThrow(),
                input.toString(), output.toString());
        try
        {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0 && Files.isRegularFile(output);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Via djxl, because ImageIO cannot read JPEG XL. */
    private int decodedSize(Path jxlPage) throws IOException
    {
        Path png = work.resolve("decoded.png");
        var command = List.of(toolLocator.find("djxl").orElseThrow(), jxlPage.toString(), png.toString());
        try
        {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertThat(process.waitFor()).isZero();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return -1;
        }
        var image = ImageIO.read(png.toFile());
        Files.deleteIfExists(png);
        return image.getWidth();
    }

    private List<String> pageFiles() throws IOException
    {
        return listNames(dir).stream()
                .filter(ImageDirectory::isImage)
                .sorted(Comparator.comparingInt(ImageDirectory::pageNumber).thenComparing(name -> name))
                .toList();
    }

    private static List<String> listNames(Path directory) throws IOException
    {
        if (!Files.isDirectory(directory))
        {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory))
        {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static CompressionProfile jxl(String encoderArgs)
    {
        return profile(ImageEncoder.JXL, encoderArgs, "", 0, 0, "");
    }

    private static CompressionProfile jxlWithMagick(String encoderArgs, String magickArgs)
    {
        return profile(ImageEncoder.JXL, encoderArgs, magickArgs, 0, 0, "");
    }

    private static CompressionProfile profile(ImageEncoder encoder, String encoderArgs, String magickArgs,
                                               int relative, int absolute, String formats)
    {
        return new CompressionProfile("test", "Test mode", encoder,
                CompressionProfile.splitArgs(encoderArgs), CompressionProfile.splitArgs(magickArgs),
                relative, absolute, CompressionProfile.parseFormats(formats));
    }

    private static void deleteRecursively(Path root)
    {
        if (!Files.isDirectory(root))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p ->
            {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException ignored)
                {
                    // best-effort cleanup
                }
            });
        }
        catch (IOException ignored)
        {
            // best-effort cleanup
        }
    }
}

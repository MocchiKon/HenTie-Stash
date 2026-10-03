package io.github.mocchikon.hentie.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mocchikon.hentie.TestLinks;

import static org.assertj.core.api.Assertions.*;

class ImageServiceUnitTest
{
    /** Called directly because staging on another filesystem (a RAM disk) cannot be arranged portably. */
    @Test
    void shouldPublishThroughATemporaryFileWhenCopyingAStagedPage(@TempDir Path dir) throws IOException
    {
        // GIVEN a staged page and a target that does not exist yet.
        Path staged = Files.writeString(dir.resolve("staged.webp"), "the page bytes");
        Path target = dir.resolve("published").resolve("1.webp");
        Files.createDirectories(target.getParent());

        // WHEN it is published.
        ImageService.copyThenRename(staged, target);

        // THEN the page is complete, the staged copy is gone, and no .part file is left in the chapter's folder.
        assertThat(Files.readString(target)).isEqualTo("the page bytes");
        assertThat(staged).doesNotExist();
        try (var entries = Files.list(target.getParent()))
        {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("1.webp");
        }
    }

    /** The JDK reports a junction as an ordinary directory, so a plain walk would empty its target on any drive. */
    @Test
    void shouldNotDeleteThroughAJunction(@TempDir Path dir) throws Exception
    {
        // GIVEN a folder being deleted that holds a page and a junction to a folder that is not ours.
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path precious = Files.writeString(elsewhere.resolve("keep.txt"), "not ours");
        Path doomed = Files.createDirectories(dir.resolve("12"));
        Files.writeString(doomed.resolve("1.png"), "a page");
        TestLinks.junction(doomed.resolve("junction"), elsewhere);

        // WHEN
        ImageService.deleteRecursively(doomed);

        // THEN the folder is gone, junction included, and what it pointed at is untouched.
        assertThat(doomed).doesNotExist();
        assertThat(precious).hasContent("not ours");
    }

    @Test
    void shouldNotDeleteThroughASymbolicLink(@TempDir Path dir) throws Exception
    {
        // GIVEN
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path precious = Files.writeString(elsewhere.resolve("keep.txt"), "not ours");
        Path doomed = Files.createDirectories(dir.resolve("12"));
        TestLinks.symbolicLink(doomed.resolve("link"), elsewhere);

        // WHEN
        ImageService.deleteRecursively(doomed);

        // THEN
        assertThat(doomed).doesNotExist();
        assertThat(precious).hasContent("not ours");
    }

    /** The folder named for deletion may itself be a link - a chapter folder moved to another drive. */
    @Test
    void shouldDeleteOnlyTheLinkWhenTheFolderItselfIsOne(@TempDir Path dir) throws Exception
    {
        // GIVEN
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path precious = Files.writeString(elsewhere.resolve("1.png"), "not ours");
        Path link = TestLinks.directoryLink(dir.resolve("12"), elsewhere);

        // WHEN
        ImageService.deleteRecursively(link);

        // THEN
        assertThat(link).doesNotExist();
        assertThat(precious).hasContent("not ours");
    }

    @Test
    void shouldReplaceAnExistingPageWhenCopyingAStagedPage(@TempDir Path dir) throws IOException
    {
        // GIVEN a target that already holds an older page, left by an interrupted move.
        Path staged = Files.writeString(dir.resolve("staged.webp"), "new bytes");
        Path target = Files.writeString(dir.resolve("1.webp"), "old bytes");

        // WHEN
        ImageService.copyThenRename(staged, target);

        // THEN the new content wins rather than the publish failing on an existing file.
        assertThat(Files.readString(target)).isEqualTo("new bytes");
        assertThat(staged).doesNotExist();
    }

    @Test
    void shouldRenderBytesWhenBelowOneKilobyte()
    {
        // WHEN + THEN sub-1024 values stay in plain bytes.
        assertThat(ImageService.humanReadableSize(0)).isEqualTo("0 B");
        assertThat(ImageService.humanReadableSize(1)).isEqualTo("1 B");
        assertThat(ImageService.humanReadableSize(1023)).isEqualTo("1023 B");
    }

    @Test
    void shouldScaleToTheLargestFittingUnitWhenAboveOneKilobyte()
    {
        // WHEN + THEN each threshold rolls over to the next unit with one decimal.
        assertThat(ImageService.humanReadableSize(1024)).isEqualTo("1.0 KB");
        assertThat(ImageService.humanReadableSize(1536)).isEqualTo("1.5 KB");
        assertThat(ImageService.humanReadableSize(1024L * 1024)).isEqualTo("1.0 MB");
        assertThat(ImageService.humanReadableSize(1024L * 1024 * 1024)).isEqualTo("1.0 GB");
        assertThat(ImageService.humanReadableSize(5L * 1024 * 1024 * 1024 * 1024)).isEqualTo("5.0 TB");
    }
}

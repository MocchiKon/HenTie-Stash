package io.github.mocchikon.hentie.service.scratch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Nothing is deleted unless it is shaped like one of the cache's own entries at {@code <root>/<digits>/}:
 * the root sits inside a folder the user chose, and anything else there is not the cache's.
 */
class CacheFolderTest
{
    /** Entries named like the results cache: {@code <page>.<n>.png}, a write in progress with {@code .part}. */
    private static final Pattern ENTRY = Pattern.compile(".+\\.\\d+\\.png(\\.[0-9a-f-]+\\.part)?");

    @TempDir Path tmp;

    private Path root;
    private long cap;
    private long abandonedAfter;
    private CacheFolder folder;

    @BeforeEach
    void setUp()
    {
        root = tmp.resolve("cache");
        cap = 1_000;
        abandonedAfter = 60_000;
        folder = new CacheFolder("test cache", () -> root, () -> cap, ENTRY, ".part", () -> abandonedAfter);
    }

    @Test
    void shouldPruneTheOldestEntriesOnceEnoughHasBeenWritten() throws IOException
    {
        // GIVEN 1500 bytes of entries against a cap of 1000, oldest first.
        Path oldest = entry(1, "1.png.1.png", 500, 1_000);
        Path middle = entry(1, "2.png.1.png", 500, 2_000);
        Path newest = entry(2, "1.png.1.png", 500, 3_000);

        // WHEN a write below a quarter of the cap is recorded, nothing is walked...
        folder.wrote(100);
        assertThat(oldest).exists();
        // ...and once the tally reaches it, the cache goes below the cap (to 80% of it), oldest first.
        folder.wrote(200);

        // THEN
        assertThat(oldest).doesNotExist();
        assertThat(middle).doesNotExist();
        assertThat(newest).exists();
    }

    @Test
    void shouldNeverPruneWhenTheCapIsZero() throws IOException
    {
        cap = 0;
        Path entry = entry(1, "1.png.1.png", 5_000, 1_000);

        folder.wrote(10_000);

        assertThat(entry).exists();
    }

    @Test
    void shouldClearOnlyItsOwnEntries() throws IOException
    {
        // GIVEN the cache's entries beside things the user keeps in the same folders.
        Path mine = entry(7, "1.png.1.png", 10, 1_000);
        Path foreignBeside = Files.writeString(root.resolve("7").resolve("notes.txt"), "mine");
        Path atRoot = Files.writeString(root.resolve("1.png.1.png"), "not in a chapter folder");
        Path deeper = Files.writeString(Files.createDirectories(root.resolve("7/sub")).resolve("1.png.1.png"), "too deep");
        Path notAChapter = Files.writeString(Files.createDirectories(root.resolve("2024x")).resolve("1.png.1.png"), "x");

        // WHEN
        folder.clear(root);

        // THEN
        assertThat(mine).doesNotExist();
        assertThat(foreignBeside).hasContent("mine");
        assertThat(atRoot).exists();
        assertThat(deeper).exists();
        assertThat(notAChapter).exists();
    }

    /** Deleting a write in progress would fail that write. */
    @Test
    void shouldLeaveAWriteInProgressAloneUntilItIsAbandoned() throws IOException
    {
        // GIVEN
        Path writing = entry(3, "1.png.1.png.0a1b-2c.part", 10, System.currentTimeMillis());
        Path abandoned = entry(3, "2.png.1.png.3d4e-5f.part", 10, System.currentTimeMillis() - 120_000);

        // WHEN
        folder.clear(root);

        // THEN
        assertThat(writing).exists();
        assertThat(abandoned).doesNotExist();
    }

    @Test
    void shouldEvictAChaptersEntriesAndItsFolderOnlyOnceEmpty() throws IOException
    {
        // GIVEN chapter 4 holds only entries, chapter 5 also a file of the user's.
        entry(4, "1.png.1.png", 10, 1_000);
        entry(5, "1.png.1.png", 10, 1_000);
        Path foreign = Files.writeString(root.resolve("5").resolve("keep.txt"), "mine");

        // WHEN
        folder.evict(4);
        folder.evict(5);
        folder.evict(6);   // never had a folder: nothing to do, nothing thrown

        // THEN
        assertThat(root.resolve("4")).doesNotExist();
        assertThat(root.resolve("5").resolve("1.png.1.png")).doesNotExist();
        assertThat(foreign).hasContent("mine");
    }

    @Test
    void shouldDeleteTheVersionsItIsToldToExceptTheOneToKeep() throws IOException
    {
        // GIVEN three versions of page 1, and page 10 whose name starts the same way.
        Path v1 = entry(8, "1.png.1.png", 10, 1_000);
        Path v2 = entry(8, "1.png.2.png", 10, 2_000);
        Path v3 = entry(8, "1.png.3.png", 10, 3_000);
        Path otherPage = entry(8, "10.png.1.png", 10, 1_000);
        Path inProgress = entry(8, "1.png.4.png.9f-8e.part", 10, System.currentTimeMillis());

        // WHEN
        folder.deleteVersions(root.resolve("8"), Pattern.compile(Pattern.quote("1.png") + "\\.\\d+\\.png"), "1.png.3.png");

        // THEN
        assertThat(v1).doesNotExist();
        assertThat(v2).doesNotExist();
        assertThat(v3).exists();
        assertThat(otherPage).exists();
        assertThat(inProgress).exists();
    }

    @Test
    void shouldAddUpOnlyItsEntries() throws IOException
    {
        entry(1, "1.png.1.png", 300, 1_000);
        entry(2, "1.png.1.png", 200, 1_000);
        Files.writeString(root.resolve("1").resolve("big.txt"), "x".repeat(10_000));

        assertThat(folder.sizeBytes(root)).isEqualTo(500);
        assertThat(folder.sizeBytes(tmp.resolve("missing"))).isZero();
    }

    @Test
    void shouldNameATemporaryFileOfItsOwnBesideTheEntry()
    {
        Path entry = root.resolve("1").resolve("1.png.1.png");

        Path part = folder.partFor(entry);

        assertThat(part.getParent()).isEqualTo(entry.getParent());
        assertThat(part.getFileName().toString()).startsWith("1.png.1.png.").endsWith(".part");
        assertThat(folder.partFor(entry)).isNotEqualTo(part);
        assertThat(ENTRY.matcher(part.getFileName().toString()).matches()).isTrue();
    }

    private Path entry(int chapter, String name, int size, long modifiedMillis) throws IOException
    {
        Path file = Files.createDirectories(root.resolve(String.valueOf(chapter))).resolve(name);
        Files.write(file, new byte[size]);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedMillis));
        return file;
    }
}

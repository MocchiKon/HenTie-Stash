package io.github.mocchikon.hentie.service.scratch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mocchikon.hentie.TestLinks;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace.Origin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The RAM disk is a plain folder here, since detecting one is {@link RamDiskDetector}'s job; so this runs anywhere. */
class ScratchSpaceTest
{
    private static final long MIB = 1024L * 1024L;

    @TempDir Path tmp;

    private final AppProperties appProperties = new AppProperties();
    private final SettingsService settingsService = mock(SettingsService.class);
    private final RamDiskDetector detector = mock(RamDiskDetector.class);
    private final Map<String, String> settings = new HashMap<>();

    private Path data;
    private Path ram;

    @BeforeEach
    void setUp() throws IOException
    {
        data = tmp.resolve("data");
        ram = Files.createDirectories(tmp.resolve("ram"));
        // The folder a real detector hands over is owner-only, and ScratchSpace checks that on every use.
        if (ram.getFileSystem().supportedFileAttributeViews().contains("posix"))
        {
            Files.setPosixFilePermissions(ram, PosixFilePermissions.fromString("rwx------"));
        }
        appProperties.setDataDir(data.toString());
        appProperties.getStorage().setRamDiskMinFreeMb(0);
        when(settingsService.get(anyString(), anyString()))
                .thenAnswer(call -> settings.getOrDefault(call.getArgument(0), call.getArgument(1)));
    }

    // ---- the automatic choice ----------------------------------------------

    @Test
    void shouldPutOnlyCompressedStagingOnTheRamDisk()
    {
        // GIVEN
        ScratchSpace space = withRamDisk(400 * MIB);

        // WHEN + THEN uncompressed staging stays beside the pages, where publishing is a rename...
        assertThat(space.downloadStaging(false)).isEqualTo(location(data.resolve(".staging"), Origin.DATA_DIR));
        // ...while everything that only exists because of compression goes to RAM, and so do both caches.
        assertThat(space.downloadStaging(true)).isEqualTo(location(ram.resolve("staging"), Origin.RAM_DISK));
        assertThat(space.compressionWork()).isEqualTo(location(ram.resolve("work"), Origin.RAM_DISK));
        assertThat(space.transcodeCache()).isEqualTo(location(ram.resolve("transcoded"), Origin.RAM_DISK));
        assertThat(space.comfyResults()).isEqualTo(location(ram.resolve("comfyui"), Origin.RAM_DISK));
    }

    @Test
    void shouldUseTheDataFolderForEverythingWithoutARamDisk()
    {
        // GIVEN
        ScratchSpace space = withoutRamDisk();

        // WHEN + THEN
        assertThat(space.downloadStaging(true)).isEqualTo(location(data.resolve(".staging"), Origin.DATA_DIR));
        assertThat(space.downloadStaging(false)).isEqualTo(location(data.resolve(".staging"), Origin.DATA_DIR));
        assertThat(space.compressionWork()).isEqualTo(location(data.resolve(".work"), Origin.DATA_DIR));
        assertThat(space.transcodeCache()).isEqualTo(location(data.resolve(".transcoded"), Origin.DATA_DIR));
        assertThat(space.comfyResults()).isEqualTo(location(data.resolve(".comfyui"), Origin.DATA_DIR));
    }

    /**
     * Staging and intermediates move to disk rather than risk failing a gallery part-way. The caches stay: a
     * location that moved with free space would orphan everything cached so far.
     */
    @Test
    void shouldLeaveTheRamDiskForStagingAndIntermediatesWhenItIsBelowTheFloor()
    {
        // GIVEN a floor no real folder can meet.
        appProperties.getStorage().setRamDiskMinFreeMb(Long.MAX_VALUE / MIB);
        ScratchSpace space = withRamDisk(400 * MIB);

        // WHEN + THEN
        assertThat(space.downloadStaging(true)).isEqualTo(location(data.resolve(".staging"), Origin.DATA_DIR));
        assertThat(space.compressionWork()).isEqualTo(location(data.resolve(".work"), Origin.DATA_DIR));
        assertThat(space.transcodeCache()).isEqualTo(location(ram.resolve("transcoded"), Origin.RAM_DISK));
        assertThat(space.comfyResults()).isEqualTo(location(ram.resolve("comfyui"), Origin.RAM_DISK));
    }

    // ---- explicit folders --------------------------------------------------

    @Test
    void shouldUseASettingsFolderWhetherOrNotTheDownloadIsCompressed()
    {
        // GIVEN staging set in Settings, the other two left blank.
        settings.put(ScratchArea.DOWNLOAD_STAGING.getSettingKey(), tmp.resolve("from-settings").toString());

        // WHEN
        ScratchSpace space = withRamDisk(400 * MIB);

        // THEN the explicit folder (a subfolder of it) is used for every download, and blank means automatic.
        var staging = location(inside(tmp.resolve("from-settings"), "staging"), Origin.SETTINGS);
        assertThat(space.downloadStaging(true)).isEqualTo(staging);
        assertThat(space.downloadStaging(false)).isEqualTo(staging);
        assertThat(space.compressionWork()).isEqualTo(location(ram.resolve("work"), Origin.RAM_DISK));
        assertThat(space.transcodeCache()).isEqualTo(location(ram.resolve("transcoded"), Origin.RAM_DISK));
    }

    /** A discard must reach pages a run staged under a choice that has since changed. */
    @Test
    void shouldNameEveryFolderAnAreaCouldHaveUsed()
    {
        // GIVEN
        settings.put(ScratchArea.DOWNLOAD_STAGING.getSettingKey(), tmp.resolve("explicit").toString());
        ScratchSpace space = withRamDisk(400 * MIB);

        // WHEN + THEN
        assertThat(space.everyRoot(ScratchArea.DOWNLOAD_STAGING))
                .containsExactly(inside(tmp.resolve("explicit"), "staging"), ram.resolve("staging"),
                        data.resolve(".staging"));
        assertThat(space.everyRoot(ScratchArea.COMPRESSION_WORK))
                .containsExactly(ram.resolve("work"), data.resolve(".work"));
    }

    @Test
    void shouldSaveFoldersThatDoNotOverlap()
    {
        // GIVEN
        ScratchSpace space = withoutRamDisk();
        Path cache = data.resolve("my-cache");   // inside the data folder, but not in a chapter folder

        // WHEN
        Optional<String> refusal = space.update(Map.of(ScratchArea.TRANSCODE_CACHE, "  " + cache + "  "));

        // THEN the value is stored trimmed, with the others left as they were, and used at once.
        assertThat(refusal).isEmpty();
        verify(settingsService).setScratchDirs(Map.of(
                ScratchArea.DOWNLOAD_STAGING, "",
                ScratchArea.COMPRESSION_WORK, "",
                ScratchArea.TRANSCODE_CACHE, cache.toString(),
                ScratchArea.COMFYUI_RESULTS, ""));
        assertThat(space.transcodeCache()).isEqualTo(location(inside(cache, "transcoded"), Origin.SETTINGS));
    }

    /**
     * A chosen folder is a parent, so cleanups stay inside folders the app created: one folder can serve all
     * areas, and even the data folder is harmless.
     */
    @Test
    void shouldGiveEachAreaAFolderOfItsOwnInsideTheChosenOne()
    {
        // GIVEN
        ScratchSpace space = withoutRamDisk();
        Path shared = tmp.resolve("shared");

        // WHEN
        Optional<String> refusal = space.update(Map.of(ScratchArea.DOWNLOAD_STAGING, shared.toString(),
                ScratchArea.COMPRESSION_WORK, shared.toString(), ScratchArea.TRANSCODE_CACHE, data.toString()));

        // THEN
        assertThat(refusal).isEmpty();
        assertThat(space.downloadStaging(false)).isEqualTo(location(inside(shared, "staging"), Origin.SETTINGS));
        assertThat(space.compressionWork()).isEqualTo(location(inside(shared, "work"), Origin.SETTINGS));
        assertThat(space.transcodeCache()).isEqualTo(location(inside(data, "transcoded"), Origin.SETTINGS));
    }

    /**
     * Every area deletes {@code <root>/<chapterId>} recursively, so an overlap is data loss: staging inside a
     * chapter folder would make "discard staged pages" delete that chapter's own images.
     */
    @Test
    void shouldRefuseFoldersThatOverlapTheDataFolderOrEachOther()
    {
        // GIVEN
        ScratchSpace space = withoutRamDisk();
        Path work = tmp.resolve("w");

        // WHEN + THEN a chapter folder inside the data folder...
        assertThat(space.update(Map.of(ScratchArea.COMPRESSION_WORK, data.resolve("12").toString())))
                .hasValueSatisfying(reason -> assertThat(reason).contains("Image Compression intermediates",
                        "data folder"));
        // ...(a parent of the data folder is fine: "data" is no chapter folder, so no cleanup reaches the pages)...
        assertThat(space.update(Map.of(ScratchArea.TRANSCODE_CACHE, data.getParent().toString()))).isEmpty();
        // ...one area inside a chapter folder of another's...
        assertThat(space.update(Map.of(ScratchArea.COMPRESSION_WORK, work.toString(),
                ScratchArea.DOWNLOAD_STAGING, inside(work, "work").resolve("3").toString())))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("Download staging and Image Compression intermediates need folders of their own"));
        // ...and an explicit folder inside a chapter folder of another area's automatic one.
        assertThat(space.update(Map.of(ScratchArea.COMPRESSION_WORK, data.resolve(".staging/9").toString())))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("Image Compression intermediates and Download staging"));

        // THEN only the harmless set was written, and nothing refused took effect.
        verify(settingsService, times(1)).setScratchDirs(anyMap());
        assertThat(space.downloadStaging(false)).isEqualTo(location(data.resolve(".staging"), Origin.DATA_DIR));
        assertThat(space.compressionWork()).isEqualTo(location(data.resolve(".work"), Origin.DATA_DIR));
    }

    /** A discard looks in every folder an area could have used, automatic ones included, so no other area may sit there. */
    @Test
    void shouldRefuseAFolderOnTheAutomaticFolderOfAnAreaThatIsSetElsewhere()
    {
        // GIVEN staging moved away from the data folder...
        ScratchSpace space = withoutRamDisk();
        assertThat(space.update(Map.of(ScratchArea.DOWNLOAD_STAGING, tmp.resolve("stage").toString()))).isEmpty();

        // WHEN ...and the intermediates put in a chapter folder of where automatic staging would be.
        Optional<String> refusal = space.update(Map.of(ScratchArea.COMPRESSION_WORK,
                data.resolve(".staging/9").toString()));

        // THEN
        assertThat(refusal).hasValueSatisfying(reason -> assertThat(reason)
                .contains("Image Compression intermediates and Download staging need folders of their own"));
        assertThat(space.compressionWork()).isEqualTo(location(data.resolve(".work"), Origin.DATA_DIR));
        assertThat(space.downloadStaging(false))
                .isEqualTo(location(inside(tmp.resolve("stage"), "staging"), Origin.SETTINGS));
    }

    /** Checked when chosen, or every download would fail in turn with nothing on the Settings page saying why. */
    @Test
    void shouldRefuseAFolderThatCannotBeCreated() throws IOException
    {
        // GIVEN a file where the chosen folder would have to be.
        ScratchSpace space = withoutRamDisk();
        Path file = Files.writeString(tmp.resolve("a-file"), "not a directory");

        // WHEN
        Optional<String> refusal = space.update(Map.of(ScratchArea.DOWNLOAD_STAGING, file.toString()));

        // THEN
        assertThat(refusal).hasValueSatisfying(reason -> assertThat(reason)
                .contains("Download staging", inside(file, "staging").toString(), "cannot be created"));
        verify(settingsService, never()).setScratchDirs(anyMap());
        assertThat(space.downloadStaging(false)).isEqualTo(location(data.resolve(".staging"), Origin.DATA_DIR));
    }

    /** A run keeps the folder it started with, so a discard must still reach one replaced since. */
    @Test
    void shouldKeepDiscardingInAFolderReplacedInSettings()
    {
        // GIVEN staging moved from one explicit folder to another.
        ScratchSpace space = withoutRamDisk();
        assertThat(space.update(Map.of(ScratchArea.DOWNLOAD_STAGING, tmp.resolve("first").toString()))).isEmpty();

        // WHEN
        assertThat(space.update(Map.of(ScratchArea.DOWNLOAD_STAGING, tmp.resolve("second").toString()))).isEmpty();

        // THEN
        assertThat(space.downloadStaging(false))
                .isEqualTo(location(inside(tmp.resolve("second"), "staging"), Origin.SETTINGS));
        assertThat(space.everyRoot(ScratchArea.DOWNLOAD_STAGING)).containsExactly(
                inside(tmp.resolve("second"), "staging"), inside(tmp.resolve("first"), "staging"),
                data.resolve(".staging"));
    }

    @Test
    void shouldRefuseAPathThatIsTooLong()
    {
        // GIVEN
        ScratchSpace space = withoutRamDisk();

        // WHEN
        Optional<String> refusal = space.update(Map.of(ScratchArea.DOWNLOAD_STAGING,
                "x".repeat(ScratchSpace.MAX_PATH_LENGTH + 1)));

        // THEN
        assertThat(refusal).hasValueSatisfying(reason -> assertThat(reason).contains("longer than"));
        verify(settingsService, never()).setScratchDirs(anyMap());
    }

    /** Not fatal at startup - that would lock the user out of the Settings page that fixes it. */
    @Test
    void shouldIgnoreAnOverlappingSettingAtStartup()
    {
        // GIVEN a stored value inside a chapter folder of the data folder.
        settings.put(ScratchArea.DOWNLOAD_STAGING.getSettingKey(), data.resolve("12").toString());

        // WHEN
        ScratchSpace space = withoutRamDisk();

        // THEN
        assertThat(space.downloadStaging(false)).isEqualTo(location(data.resolve(".staging"), Origin.DATA_DIR));
    }

    // ---- memory ------------------------------------------------------------

    @Test
    void shouldHoldTheDecodeCacheToAQuarterOfAnAutomaticRamDisk()
    {
        // GIVEN a 400 MB RAM disk and a configured cap above its share.
        appProperties.getImageCompression().setTranscodeCacheMaxMb(512);
        ScratchSpace space = withRamDisk(400 * MIB);

        // WHEN + THEN the share wins over the configured cap...
        assertThat(space.transcodeCacheCapBytes()).isEqualTo(100 * MIB);
        // ...and over "never prune", which on a RAM disk means "until memory runs out"...
        appProperties.getImageCompression().setTranscodeCacheMaxMb(0);
        assertThat(space.transcodeCacheCapBytes()).isEqualTo(100 * MIB);
        // ...but a smaller configured cap is kept.
        appProperties.getImageCompression().setTranscodeCacheMaxMb(64);
        assertThat(space.transcodeCacheCapBytes()).isEqualTo(64 * MIB);
    }

    @Test
    void shouldKeepTheConfiguredDecodeCacheCapOffTheRamDisk()
    {
        // GIVEN the cache in an explicit folder, even though there is a RAM disk.
        appProperties.getImageCompression().setTranscodeCacheMaxMb(512);
        settings.put(ScratchArea.TRANSCODE_CACHE.getSettingKey(), tmp.resolve("cache").toString());
        ScratchSpace space = withRamDisk(400 * MIB);

        // WHEN + THEN
        assertThat(space.transcodeCacheCapBytes()).isEqualTo(512 * MIB);
    }

    /** The ComfyUI results get a share of their own, so neither cache can prune the other's entries away. */
    @Test
    void shouldHoldTheComfyUiResultsToAQuarterOfAnAutomaticRamDiskOfTheirOwn()
    {
        // GIVEN a 400 MB RAM disk, and the results cache configured larger than its share.
        appProperties.getComfyui().setResultCacheMaxMb(2048);
        appProperties.getImageCompression().setTranscodeCacheMaxMb(64);
        ScratchSpace space = withRamDisk(400 * MIB);

        // WHEN + THEN each is capped by itself: the share for one, its own smaller cap for the other.
        assertThat(space.comfyResultsCapBytes()).isEqualTo(100 * MIB);
        assertThat(space.transcodeCacheCapBytes()).isEqualTo(64 * MIB);
        // In an explicit folder the configured cap is taken at its word.
        settings.put(ScratchArea.COMFYUI_RESULTS.getSettingKey(), tmp.resolve("results").toString());
        assertThat(withRamDisk(400 * MIB).comfyResultsCapBytes()).isEqualTo(2048 * MIB);
    }

    /** Otherwise a killed process's leftovers hold RAM until a reboot. Only numbered chapter folders are ours. */
    @Test
    void shouldRemoveLeftoverChapterFoldersAtStartup() throws IOException
    {
        // GIVEN leftovers on the RAM disk and in the data folder, beside files that are not ours.
        Path ramStaged = Files.createDirectories(ram.resolve("staging/5"));
        Files.writeString(ramStaged.resolve("1.png"), "half a page");
        Path diskWork = Files.createDirectories(data.resolve(".work/6"));
        Files.writeString(diskWork.resolve("1.magick.png"), "an intermediate");
        Path notOurs = Files.createDirectories(ram.resolve("staging/keep-me"));
        Path cached = Files.createDirectories(ram.resolve("transcoded/5"));
        ScratchSpace space = withRamDisk(400 * MIB);

        // WHEN
        space.removeLeftovers();

        // THEN
        assertThat(ramStaged).doesNotExist();
        assertThat(diskWork).doesNotExist();
        assertThat(notOurs).isDirectory();
        // The decode cache is not a leftover: it stays valid across restarts.
        assertThat(cached).isDirectory();
    }

    /** The sweep deletes numbered folders, so a chosen {@code D:\} must keep the user's {@code D:\2024}. */
    @Test
    void shouldNeverRemoveNumberedFoldersOfTheChosenFolderItself() throws IOException
    {
        // GIVEN staging chosen on a folder that also holds the user's own numbered folder.
        Path drive = tmp.resolve("drive");
        Path usersOwn = Files.createDirectories(drive.resolve("2024"));
        Files.writeString(usersOwn.resolve("holiday.jpg"), "not ours");
        Path leftover = Files.createDirectories(inside(drive, "staging").resolve("5"));
        settings.put(ScratchArea.DOWNLOAD_STAGING.getSettingKey(), drive.toString());
        ScratchSpace space = withoutRamDisk();

        // WHEN
        space.removeLeftovers();

        // THEN
        assertThat(leftover).doesNotExist();
        assertThat(usersOwn.resolve("holiday.jpg")).hasContent("not ours");
    }

    /** A user's folder under an area's plain name ({@code D:\work\2023}) must never be adopted and swept. */
    @Test
    void shouldNeverAdoptAFolderTheUserAlreadyHasUnderAnAreasName() throws IOException
    {
        // GIVEN the chosen folder holds the user's own "staging" and "work" folders, each with a numbered one.
        Path drive = tmp.resolve("drive");
        Path staging = Files.writeString(Files.createDirectories(drive.resolve("staging/2024")).resolve("a.txt"),
                "mine");
        Path work = Files.writeString(Files.createDirectories(drive.resolve("work/2023")).resolve("b.txt"), "mine");
        for (ScratchArea area : ScratchArea.values())
        {
            settings.put(area.getSettingKey(), drive.toString());
        }

        // WHEN
        ScratchSpace space = withoutRamDisk();
        space.removeLeftovers();

        // THEN none of them is used, and nothing in them is touched.
        assertThat(space.downloadStaging(false).root()).isEqualTo(inside(drive, "staging"));
        assertThat(space.compressionWork().root()).isEqualTo(inside(drive, "work"));
        assertThat(space.transcodeCache().root()).isEqualTo(inside(drive, "transcoded"));
        assertThat(staging).hasContent("mine");
        assertThat(work).hasContent("mine");
    }

    /**
     * Chapter ids are only unique within one library, so two libraries given the same folder must not share
     * {@code <root>/<chapterId>}: each one's startup would clear the other's in-flight downloads.
     */
    @Test
    void shouldGiveEachLibraryAFolderOfItsOwnInsideAChosenFolder()
    {
        // GIVEN two libraries with the same chosen staging folder.
        Path drive = tmp.resolve("drive");
        settings.put(ScratchArea.DOWNLOAD_STAGING.getSettingKey(), drive.toString());
        ScratchSpace first = withoutRamDisk();
        appProperties.setDataDir(tmp.resolve("other-library").toString());

        // WHEN
        ScratchSpace second = withoutRamDisk();

        // THEN both live inside the chosen folder, each in a folder of its own.
        Path firstRoot = first.downloadStaging(false).root();
        Path secondRoot = second.downloadStaging(false).root();
        assertThat(firstRoot.getParent().getParent()).isEqualTo(drive);
        assertThat(secondRoot.getParent().getParent()).isEqualTo(drive);
        assertThat(firstRoot).isNotEqualTo(secondRoot);
    }

    /**
     * One name per user and per data folder: {@code ./a/../library} and {@code ./library} are one library,
     * and a user name is not trusted to be a safe file name.
     */
    @Test
    void shouldNameTheLibraryFolderAfterTheUserAndTheDataFolder()
    {
        // WHEN
        String library = ScratchSpace.libraryFolderName("alice", Path.of("./target/library"));
        String sameLibrary = ScratchSpace.libraryFolderName("alice", Path.of("./target/other/../library"));
        String otherLibrary = ScratchSpace.libraryFolderName("alice", Path.of("./target/other-library"));
        String otherUser = ScratchSpace.libraryFolderName("bob", Path.of("./target/library"));

        // THEN
        assertThat(library).startsWith("hentie-alice-").isEqualTo(sameLibrary);
        assertThat(otherLibrary).startsWith("hentie-alice-").isNotEqualTo(library);
        assertThat(otherUser).startsWith("hentie-bob-");
        assertThat(ScratchSpace.libraryFolderName("../x y", Path.of("."))).startsWith("hentie-.._x_y-");
    }

    /**
     * A lexical comparison is blind to a link: a chosen folder that reaches a chapter folder of the data
     * folder through one would put staging inside that chapter's own folder.
     */
    @Test
    void shouldRefuseAFolderThatReachesAChapterFolderThroughALink() throws Exception
    {
        // GIVEN a link elsewhere that leads into chapter 12's folder.
        Path chapter = Files.createDirectories(data.resolve("12"));
        Path alias = TestLinks.directoryLink(tmp.resolve("alias"), chapter);
        ScratchSpace space = withoutRamDisk();

        // WHEN
        Optional<String> refusal = space.update(Map.of(ScratchArea.DOWNLOAD_STAGING, alias.toString()));

        // THEN
        assertThat(refusal).hasValueSatisfying(reason -> assertThat(reason).contains("Download staging",
                "data folder"));
        verify(settingsService, never()).setScratchDirs(anyMap());
        assertThat(space.downloadStaging(false)).isEqualTo(location(data.resolve(".staging"), Origin.DATA_DIR));
    }

    /** All or nothing includes the disk: a refused save leaves no folder of its own behind. */
    @Test
    void shouldLeaveNoFolderBehindWhenASaveIsRefused() throws IOException
    {
        // GIVEN a set where staging is fine but the cache cannot be created (a file is in the way).
        ScratchSpace space = withoutRamDisk();
        Path fine = tmp.resolve("fine");
        Path file = Files.writeString(tmp.resolve("a-file"), "not a directory");

        // WHEN
        Optional<String> refusal = space.update(Map.of(ScratchArea.DOWNLOAD_STAGING, fine.toString(),
                ScratchArea.TRANSCODE_CACHE, file.toString()));

        // THEN
        assertThat(refusal).hasValueSatisfying(reason -> assertThat(reason).contains("Decoded JPEG XL cache",
                "cannot be created"));
        assertThat(fine).doesNotExist();
        verify(settingsService, never()).setScratchDirs(anyMap());
    }

    /** A digit of another script is still a digit to {@code Character.isDigit}, but never a chapter id. */
    @Test
    void shouldTakeOnlyAsciiDigitsForAChapterFolder()
    {
        // WHEN + THEN
        assertThat(ScratchSpace.isChapterFolderName("120")).isTrue();
        assertThat(ScratchSpace.isChapterFolderName("\u0661\u0662")).isFalse();
        assertThat(ScratchSpace.isChapterFolderName("")).isFalse();
        assertThat(ScratchSpace.isChapterFolderName("12a")).isFalse();
    }

    /**
     * Once the folder is gone, a plain createDirectories would recreate it with the umask's permissions. One
     * that cannot be made private (here a file in its place) is not used until it is fixed.
     */
    @Test
    void shouldUseTheDataFolderWhileTheRamDiskFolderIsNotPrivate() throws IOException
    {
        // GIVEN
        ScratchSpace space = withRamDisk(400 * MIB);
        Files.delete(ram);
        Files.writeString(ram, "not a directory");

        // WHEN + THEN every area leaves it...
        assertThat(space.downloadStaging(true)).isEqualTo(location(data.resolve(".staging"), Origin.DATA_DIR));
        assertThat(space.compressionWork()).isEqualTo(location(data.resolve(".work"), Origin.DATA_DIR));
        assertThat(space.transcodeCache()).isEqualTo(location(data.resolve(".transcoded"), Origin.DATA_DIR));

        // ...and once the obstacle is gone, the folder is recreated and used again.
        Files.delete(ram);
        assertThat(space.compressionWork()).isEqualTo(location(ram.resolve("work"), Origin.RAM_DISK));
        assertThat(ram).isDirectory();
        if (ram.getFileSystem().supportedFileAttributeViews().contains("posix"))
        {
            assertThat(Files.getPosixFilePermissions(ram)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
    }

    // ---- helpers -----------------------------------------------------------

    private ScratchSpace withRamDisk(long totalBytes)
    {
        when(detector.detect()).thenReturn(Optional.of(new RamDisk(ram, totalBytes)));
        return new ScratchSpace(appProperties, settingsService, detector);
    }

    private ScratchSpace withoutRamDisk()
    {
        when(detector.detect()).thenReturn(Optional.empty());
        return new ScratchSpace(appProperties, settingsService, detector);
    }

    /** Where an area lives inside a folder chosen in Settings. */
    private Path inside(Path chosen, String ownFolder)
    {
        return chosen.toAbsolutePath().normalize()
                .resolve(ScratchSpace.libraryFolderName(System.getProperty("user.name"), data))
                .resolve(ownFolder);
    }

    private static ScratchSpace.Location location(Path root, Origin origin)
    {
        return new ScratchSpace.Location(root.toAbsolutePath().normalize(), origin);
    }
}

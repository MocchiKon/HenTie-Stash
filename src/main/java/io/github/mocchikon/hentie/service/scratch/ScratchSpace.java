package io.github.mocchikon.hentie.service.scratch;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Decides where each {@link ScratchArea} lives: the Settings folder, else automatic - a RAM disk when there is
 * a usable one, else a dot-named folder of the data folder. Uncompressed download staging always stays in the
 * data folder, where publishing is a free atomic rename.
 *
 * <p><b>A Settings folder is a parent, never the root</b>: the area lives in
 * {@code <chosen>/<libraryFolderName>/<ownFolder>}. Cleanups delete {@code <root>/<chapterId>} recursively,
 * so rooting at the chosen folder, or adopting a plain {@code staging} folder the user already had, would put
 * the user's own numbered folders at risk.
 *
 * <p>Staging and intermediates check the RAM disk's free space when each run starts, since the mount is shared.
 * The caches do not: they are read on every page view, and moving one would orphan it whole; their caps bound
 * them instead.
 */
@Service
public class ScratchSpace
{
    private static final Logger log = LoggerFactory.getLogger(ScratchSpace.class);

    /** Each cache may use at most 1/n of an automatically chosen RAM disk. */
    private static final int RAM_DISK_CACHE_SHARE = 4;

    static final long MIB = 1024L * 1024L;

    /** Half of {@code app_setting.setting_value}'s 2000, and far beyond any real path. */
    public static final int MAX_PATH_LENGTH = 1000;

    @Getter
    @RequiredArgsConstructor
    public enum Origin
    {
        SETTINGS("set in Settings"),
        RAM_DISK("RAM disk, chosen automatically"),
        DATA_DIR("data folder, chosen automatically");

        private final String label;
    }

    public record Location(Path root, Origin origin)
    {
        public Path chapter(int chapterId)
        {
            return chapterFolder(root, chapterId);
        }
    }

    private final AppProperties appProperties;
    private final SettingsService settingsService;

    /** Detected once: mounts do not come and go under a running app, and probing reads the mount table. */
    private final Optional<RamDisk> ramDisk;

    private final String libraryFolder;

    /** So a failing RAM disk is logged once, not per request. */
    private volatile boolean ramDiskWasReady = true;

    /** An area missing from the map is automatic. Replaced, never mutated. */
    private volatile Map<ScratchArea, Location> explicit;

    /**
     * Explicit roots replaced in Settings during this process. A run keeps its folder, so a discard must still
     * look here; after a restart nothing knows the folder was used.
     */
    private final Map<ScratchArea, Set<Path>> replaced = new ConcurrentHashMap<>();

    public ScratchSpace(AppProperties appProperties, SettingsService settingsService, RamDiskDetector detector)
    {
        this.appProperties = appProperties;
        this.settingsService = settingsService;
        this.ramDisk = detector.detect();
        this.libraryFolder = libraryFolderName(System.getProperty("user.name"), dataDir());
        var problems = new ArrayList<String>();
        Map<ScratchArea, Location> accepted = validate(settingValues(), problems);
        createAll(accepted, problems);
        this.explicit = accepted;
        // Not fatal, or a bad value would also lock the user out of the Settings page that fixes it.
        problems.forEach(problem -> log.error("{} Using the automatic folder instead.", problem));
    }

    /**
     * Safe because nothing runs yet (the download worker starts on {@code ApplicationReadyEvent}). On a RAM disk
     * a killed process's leftovers would otherwise hold memory until a reboot.
     */
    @PostConstruct
    void removeLeftovers()
    {
        for (ScratchArea area : List.of(ScratchArea.DOWNLOAD_STAGING, ScratchArea.COMPRESSION_WORK))
        {
            for (Path root : everyRoot(area))
            {
                if (!Files.isDirectory(root))
                {
                    continue;
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(root))
                {
                    for (Path entry : entries)
                    {
                        if (isChapterFolderName(entry.getFileName().toString()) && Files.isDirectory(entry))
                        {
                            ImageService.deleteRecursively(entry);
                        }
                    }
                }
                catch (IOException e)
                {
                    log.warn("Could not clear leftovers in {}", root, e);
                }
            }
        }
    }

    // ---- where things go ---------------------------------------------------

    public Location downloadStaging(boolean compressed)
    {
        return resolve(ScratchArea.DOWNLOAD_STAGING, () -> compressed && ramDiskHasRoom());
    }

    public Location compressionWork()
    {
        return resolve(ScratchArea.COMPRESSION_WORK, this::ramDiskHasRoom);
    }

    public Location transcodeCache()
    {
        return resolve(ScratchArea.TRANSCODE_CACHE, this::ramDiskReady);
    }

    public Location comfyResults()
    {
        return resolve(ScratchArea.COMFYUI_RESULTS, this::ramDiskReady);
    }

    /**
     * A discard must look in all of them: which one a run used depended on its mode, free space and Settings at
     * the time, possibly in a process that has since died.
     */
    public Set<Path> everyRoot(ScratchArea area)
    {
        var roots = new LinkedHashSet<Path>();
        Location set = explicit.get(area);
        if (set != null)
        {
            roots.add(set.root());
        }
        roots.addAll(replaced.getOrDefault(area, Set.of()));
        roots.addAll(automaticRoots(area));
        return roots;
    }

    /**
     * The data folder or any scratch root. Their files come from uploads and the internet, so nothing there may
     * be run as a program. Compared as written and as resolved, so a link cannot walk a file out.
     */
    public boolean isInAppFolder(Path file)
    {
        var folders = new ArrayList<Path>();
        folders.add(dataDir());
        for (ScratchArea area : ScratchArea.values())
        {
            folders.addAll(everyRoot(area));
        }
        Set<Path> spellingsOfFile = spellings(file.toAbsolutePath().normalize());
        for (Path folder : folders)
        {
            for (Path spelling : spellings(folder))
            {
                if (spellingsOfFile.stream().anyMatch(candidate -> candidate.startsWith(spelling)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * On an automatic RAM disk it is held to a share of the mount whatever {@code transcode-cache-max-mb} says,
     * "never prune" included. An explicit folder is taken at its word.
     */
    public long transcodeCacheCapBytes()
    {
        return capBytes(transcodeCache(), appProperties.getImageCompression().getTranscodeCacheMaxMb());
    }

    /** Bounded on an automatic RAM disk like {@link #transcodeCacheCapBytes}. */
    public long comfyResultsCapBytes()
    {
        return capBytes(comfyResults(), appProperties.getComfyui().getResultCacheMaxMb());
    }

    private long capBytes(Location location, long configuredMb)
    {
        long configured = configuredMb * MIB;
        if (location.origin() != Origin.RAM_DISK)
        {
            return configured;
        }
        long share = ramDisk.orElseThrow().totalBytes() / RAM_DISK_CACHE_SHARE;
        return configured <= 0 ? share : Math.min(configured, share);
    }

    public Optional<RamDisk> ramDisk()
    {
        return ramDisk;
    }

    /** For the Settings page to name. */
    public String libraryFolder()
    {
        return libraryFolder;
    }

    // ---- the Settings page -------------------------------------------------

    public String settingValue(ScratchArea area)
    {
        return settingsService.get(area.getSettingKey(), "");
    }

    /**
     * All or nothing, because whether a folder overlaps depends on the others. A refused set leaves no created
     * folder behind.
     *
     * <p>Synchronized: two racing saves could lose a replaced root, which {@link #everyRoot} must keep.
     *
     * @param requested an area left out keeps its current value, blank clears it
     * @return why the values were refused, or empty when they were saved
     */
    public synchronized Optional<String> update(Map<ScratchArea, String> requested)
    {
        Map<ScratchArea, String> values = settingValues();
        requested.forEach((area, value) -> values.put(area, StringUtils.strip(StringUtils.defaultString(value))));
        var problems = new ArrayList<String>();
        Map<ScratchArea, Location> accepted = validate(values, problems);
        if (problems.isEmpty())
        {
            List<Path> created = createAll(accepted, problems);
            if (!problems.isEmpty())
            {
                removeCreated(created);
            }
        }
        if (!problems.isEmpty())
        {
            return Optional.of(String.join(" ", problems));
        }
        settingsService.setScratchDirs(values);
        Map<ScratchArea, Location> before = explicit;
        before.forEach((area, location) ->
        {
            if (!location.equals(accepted.get(area)))
            {
                replaced.computeIfAbsent(area, a -> ConcurrentHashMap.newKeySet()).add(location.root());
            }
        });
        explicit = accepted;
        return Optional.empty();
    }

    // ---- plumbing ----------------------------------------------------------

    /** {@code onRamDisk} is asked only for an automatic area. */
    private Location resolve(ScratchArea area, BooleanSupplier onRamDisk)
    {
        Location set = explicit.get(area);
        if (set != null)
        {
            return set;
        }
        if (onRamDisk.getAsBoolean())
        {
            return new Location(ramDisk.orElseThrow().root().resolve(area.getOwnFolder()), Origin.RAM_DISK);
        }
        return new Location(dataDir().resolve(area.getDataDirFolder()), Origin.DATA_DIR);
    }

    private boolean ramDiskHasRoom()
    {
        return ramDiskReady()
                && ramDisk.get().usableBytes() >= appProperties.getStorage().getRamDiskMinFreeMb() * MIB;
    }

    private boolean ramDiskReady()
    {
        if (ramDisk.isEmpty())
        {
            return false;
        }
        try
        {
            ramDisk.get().ensurePrivate();
            if (!ramDiskWasReady)
            {
                ramDiskWasReady = true;
                log.info("The RAM disk folder {} is usable again", ramDisk.get().root());
            }
            return true;
        }
        catch (IOException e)
        {
            if (ramDiskWasReady)
            {
                ramDiskWasReady = false;
                log.warn("Not using the RAM disk while its folder is not private - temporary image files go into "
                        + "the data folder: {}", e.getMessage());
            }
            return false;
        }
    }

    private List<Path> automaticRoots(ScratchArea area)
    {
        var roots = new ArrayList<Path>();
        ramDisk.ifPresent(disk -> roots.add(disk.root().resolve(area.getOwnFolder())));
        roots.add(dataDir().resolve(area.getDataDirFolder()));
        return roots;
    }

    private Path dataDir()
    {
        return Paths.get(appProperties.getDataDir()).toAbsolutePath().normalize();
    }

    private Map<ScratchArea, String> settingValues()
    {
        var values = new EnumMap<ScratchArea, String>(ScratchArea.class);
        for (ScratchArea area : ScratchArea.values())
        {
            values.put(area, settingValue(area));
        }
        return values;
    }

    /** Writes nothing to disk; see {@link #createAll}. */
    private Map<ScratchArea, Location> validate(Map<ScratchArea, String> settingValues, List<String> problems)
    {
        var wanted = new EnumMap<ScratchArea, Location>(ScratchArea.class);
        for (ScratchArea area : ScratchArea.values())
        {
            String value = settingValues.get(area);
            if (StringUtils.isBlank(value))
            {
                continue;
            }
            if (value.length() > MAX_PATH_LENGTH)
            {
                // SQLite would store it anyway - it enforces no VARCHAR(n).
                problems.add(area.getDisplayName() + ": the path is longer than " + MAX_PATH_LENGTH + " characters.");
                continue;
            }
            try
            {
                Path chosen = Paths.get(value).toAbsolutePath().normalize();
                wanted.put(area, new Location(chosen.resolve(libraryFolder).resolve(area.getOwnFolder()),
                        Origin.SETTINGS));
            }
            catch (InvalidPathException e)
            {
                problems.add(area.getDisplayName() + ": \"" + value + "\" is not a valid path.");
            }
        }

        var accepted = new EnumMap<>(wanted);
        var reportedPairs = new HashSet<Set<ScratchArea>>();
        wanted.forEach((area, location) ->
        {
            Path root = location.root();
            if (overlaps(root, dataDir()))
            {
                problems.add(area.getDisplayName() + ": " + root + " cannot be the data folder or inside a "
                        + "chapter folder of it.");
                accepted.remove(area);
            }
            for (ScratchArea other : ScratchArea.values())
            {
                if (other == area)
                {
                    continue;
                }
                // Automatic and replaced roots count even when the other area is explicit: a discard still
                // looks in them (everyRoot).
                var otherRoots = new ArrayList<>(automaticRoots(other));
                otherRoots.addAll(replaced.getOrDefault(other, Set.of()));
                if (wanted.containsKey(other))
                {
                    otherRoots.add(wanted.get(other).root());
                }
                if (otherRoots.stream().anyMatch(otherRoot -> overlaps(root, otherRoot)))
                {
                    // Once per pair: two explicit folders are found from both sides.
                    if (reportedPairs.add(Set.of(area, other)))
                    {
                        problems.add(area.getDisplayName() + " and " + other.getDisplayName()
                                + " need folders of their own (" + root + ").");
                    }
                    accepted.remove(area);
                }
            }
        });
        return accepted;
    }

    /**
     * Checked when chosen, not when first written: an unusable staging folder would otherwise fail every
     * download with nothing on the Settings page saying why. Runs only on folders that passed {@link #validate},
     * so a refused path never leaves a folder behind.
     *
     * @return the folders this call created, innermost last, so a refused save can remove them
     */
    private static List<Path> createAll(Map<ScratchArea, Location> accepted, List<String> problems)
    {
        var created = new ArrayList<Path>();
        for (ScratchArea area : List.copyOf(accepted.keySet()))
        {
            Path root = accepted.get(area).root();
            Optional<String> unusable;
            try
            {
                created.addAll(createDirectories(root));
                unusable = Files.isWritable(root) ? Optional.empty() : Optional.of(root + " is not writable.");
            }
            catch (IOException e)
            {
                unusable = Optional.of(root + " cannot be created (" + e.getMessage() + ").");
            }
            if (unusable.isPresent())
            {
                problems.add(area.getDisplayName() + ": " + unusable.get());
                accepted.remove(area);
            }
        }
        return created;
    }

    /** @return the folders that did not exist before, outermost first */
    private static List<Path> createDirectories(Path root) throws IOException
    {
        var missing = new ArrayList<Path>();
        for (Path folder = root; folder != null && Files.notExists(folder, LinkOption.NOFOLLOW_LINKS);
             folder = folder.getParent())
        {
            missing.addFirst(folder);
        }
        Files.createDirectories(root);
        return missing;
    }

    /** Only empty folders go, so one written into meanwhile stays. */
    private static void removeCreated(List<Path> created)
    {
        for (Path folder : created.reversed())
        {
            try
            {
                Files.deleteIfExists(folder);
            }
            catch (IOException notEmptyOrBusy)
            {
                // Not empty: no longer only ours to remove.
            }
        }
    }

    /**
     * Whether a cleanup in one root could delete the other: the same folder, or one inside a chapter folder
     * ({@code <root>/<digits>/...}) of the other. Nesting under a non-numeric name is harmless.
     *
     * <p>Compared as written <b>and</b> as resolved: a link, junction, {@code subst} drive or 8.3 short name
     * lets two spellings name one folder.
     */
    static boolean overlaps(Path a, Path b)
    {
        for (Path x : spellings(a))
        {
            for (Path y : spellings(b))
            {
                if (x.equals(y) || insideChapterFolder(x, y) || insideChapterFolder(y, x))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** As written, and with its existing part resolved; a part that does not exist yet cannot be a link. */
    private static Set<Path> spellings(Path path)
    {
        var spellings = new LinkedHashSet<Path>();
        spellings.add(path);
        Path existing = path;
        while (existing != null && !Files.exists(existing))
        {
            existing = existing.getParent();
        }
        if (existing != null)
        {
            try
            {
                spellings.add(existing.toRealPath().resolve(existing.relativize(path)));
            }
            catch (IOException e)
            {
                // Unresolvable: the spelling as written is all there is.
            }
        }
        return spellings;
    }

    private static boolean insideChapterFolder(Path path, Path root)
    {
        return path.startsWith(root) && !path.equals(root)
                && isChapterFolderName(root.relativize(path).getName(0).toString());
    }

    public static Path chapterFolder(Path root, int chapterId)
    {
        return root.resolve(String.valueOf(chapterId));
    }

    /**
     * ASCII digits only ({@code Character.isDigit} takes other scripts' digits): this decides what a cleanup
     * may delete.
     */
    public static boolean isChapterFolderName(String name)
    {
        return !name.isEmpty() && name.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /**
     * One per user <b>and per data folder</b>: chapter ids are unique only within one library, so two
     * libraries sharing a folder would clear each other's staging. The unusual name also means the app never
     * adopts, and then cleans, a folder it did not make.
     */
    public static String libraryFolderName(String user, Path dataDir)
    {
        String safeUser = StringUtils.defaultIfBlank(user, "user").replaceAll("[^A-Za-z0-9._-]", "_");
        return "hentie-" + safeUser + "-" + shortHash(dataDir.toAbsolutePath().normalize().toString());
    }

    private static String shortHash(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("Every JVM ships SHA-256", e);
        }
    }
}

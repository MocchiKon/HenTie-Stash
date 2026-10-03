package io.github.mocchikon.hentie.service.scratch;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.ImageService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;

/**
 * <b>Linux only</b>: the one platform where a RAM mount is standard, recognizable ({@code tmpfs}) and has a
 * readable size limit. Windows/macOS RAM disks report NTFS/APFS, so there the user sets the folders in Settings.
 *
 * <p>{@code ramfs} is refused: with no size limit, one large gallery could use all memory.
 */
@Component
@RequiredArgsConstructor
public class RamDiskDetector
{
    private static final Logger log = LoggerFactory.getLogger(RamDiskDetector.class);

    private static final String RAM_FILESYSTEM = "tmpfs";

    private final AppProperties appProperties;

    /** The first usable candidate, or empty - which means "use the data folder". */
    public Optional<RamDisk> detect()
    {
        if (!SystemUtils.IS_OS_LINUX)
        {
            return Optional.empty();
        }
        for (String candidate : appProperties.getStorage().getRamDiskCandidates())
        {
            if (StringUtils.isBlank(candidate))
            {
                continue;
            }
            Optional<RamDisk> found = probe(candidate.strip());
            if (found.isPresent())
            {
                log.info("Using the RAM disk at {} ({} of {} free) for temporary image files",
                        found.get().root(), ImageService.humanReadableSize(found.get().usableBytes()),
                        ImageService.humanReadableSize(found.get().totalBytes()));
                return found;
            }
        }
        log.info("No usable RAM disk found - temporary image files go into the data folder");
        return Optional.empty();
    }

    Optional<RamDisk> probe(String mount)
    {
        try
        {
            Path path = Path.of(mount);
            FileStore store = Files.getFileStore(path);
            if (!RAM_FILESYSTEM.equals(store.type()))
            {
                return Optional.empty();
            }
            long minFree = appProperties.getStorage().getRamDiskMinFreeMb() * ScratchSpace.MIB;
            if (store.getUsableSpace() < minFree)
            {
                // Docker's default /dev/shm (64 MB) is the common way to land here.
                log.info("{} is a RAM disk but has only {} free, below the {} floor - not using it", mount,
                        ImageService.humanReadableSize(store.getUsableSpace()), ImageService.humanReadableSize(minFree));
                return Optional.empty();
            }
            Path root = path.resolve(ScratchSpace.libraryFolderName(System.getProperty("user.name"),
                    Paths.get(appProperties.getDataDir())));
            RamDisk.claim(root);
            // Only here, not per use: a read-only mount does not change while the app runs.
            if (!Files.isWritable(root))
            {
                throw new IOException(root + " is not writable");
            }
            return Optional.of(new RamDisk(root, store.getTotalSpace()));
        }
        catch (IOException | InvalidPathException | UnsupportedOperationException e)
        {
            log.info("Not using {} as a RAM disk: {}", mount, e.toString());
            return Optional.empty();
        }
    }
}

package io.github.mocchikon.hentie.service.scratch;

import io.github.mocchikon.hentie.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** The positive cases need a real tmpfs, so they run on Linux only and skip where {@code /dev/shm} is not one. */
class RamDiskDetectorTest
{
    private static final Path SHM = Path.of("/dev/shm");

    private final AppProperties appProperties = new AppProperties();
    private final RamDiskDetector detector = new RamDiskDetector(appProperties);

    @Test
    void shouldNotTakeAnOrdinaryDiskForARamDisk() throws IOException
    {
        // GIVEN the build folder, which is on whatever the checkout is on.
        Path target = Files.createDirectories(Path.of("./target"));

        // WHEN
        Optional<RamDisk> found = detector.probe(target.toString());

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    void shouldFindNothingWhenACandidateDoesNotExist()
    {
        // WHEN
        Optional<RamDisk> found = detector.probe("./target/no-such-mount");

        // THEN
        assertThat(found).isEmpty();
    }

    /** Windows and macOS RAM disks report NTFS/APFS, so there is nothing to detect them by. */
    @Test
    @DisabledOnOs(OS.LINUX)
    void shouldNeverDetectOffLinux()
    {
        // GIVEN candidates that exist on every machine.
        appProperties.getStorage().setRamDiskCandidates(List.of(".", "./target"));
        appProperties.getStorage().setRamDiskMinFreeMb(0);

        // WHEN
        Optional<RamDisk> found = detector.detect();

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void shouldUseAPrivateFolderOnTmpfs() throws IOException
    {
        assumeThat(Files.getFileStore(SHM).type()).isEqualTo("tmpfs");
        // GIVEN no floor.
        appProperties.getStorage().setRamDiskMinFreeMb(0);

        // WHEN
        Optional<RamDisk> found = detector.probe(SHM.toString());

        // THEN the app's own folder on it, readable by nobody else, and the mount's real size.
        assertThat(found).hasValueSatisfying(disk ->
        {
            assertThat(disk.root().getParent()).isEqualTo(SHM);
            assertThat(disk.root().getFileName().toString()).startsWith("hentie-");
            assertThat(disk.totalBytes()).isPositive();
        });
        assertThat(Files.getPosixFilePermissions(found.get().root()))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    /** Docker's default 64 MB /dev/shm is the common case this exists for. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void shouldRefuseATmpfsBelowTheFreeSpaceFloor() throws IOException
    {
        assumeThat(Files.getFileStore(SHM).type()).isEqualTo("tmpfs");
        // GIVEN a floor no mount can meet.
        appProperties.getStorage().setRamDiskMinFreeMb(Long.MAX_VALUE / (1024L * 1024L));

        // WHEN
        Optional<RamDisk> found = detector.probe(SHM.toString());

        // THEN
        assertThat(found).isEmpty();
    }
}

package io.github.mocchikon.hentie.service.compress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.SettingsService;
import lombok.RequiredArgsConstructor;

/**
 * Resolves a tool name to the bundled copy under {@code <bin-dir>/<platform>/}, or, with "use the image tools
 * installed on this system", to the bare name on {@code PATH}. The bundled layout is not uniform
 * (ImageMagick is a folder on Windows, one AppImage on Linux); this class is the only one that knows it.
 *
 * <p>Not cached: the setting can change between two images, and a binary just put in place should work
 * without a restart.
 */
@Component
@RequiredArgsConstructor
public class ImageToolLocator
{
    /** ImageMagick's driver, the one command all of its tools are reached through since v7. */
    public static final String MAGICK = "magick";

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final AppProperties appProperties;
    private final SettingsService settingsService;

    /**
     * Empty when the bundled copy is missing. A system-tools lookup is never empty and never checked:
     * {@code PATH} is resolved inside {@code exec}, so a missing tool fails to start like any other failure.
     */
    public Optional<String> find(String tool)
    {
        if (settingsService.isSystemImageToolsEnabled())
        {
            return Optional.of(tool);
        }
        return bundled(tool).map(Path::toString);
    }

    private Optional<Path> bundled(String tool)
    {
        var platformDir = binDir().resolve(WINDOWS ? "win" : "linux");
        Path candidate = tool.equals(MAGICK)
                ? (WINDOWS ? platformDir.resolve("ImageMagick").resolve("magick.exe")
                           : platformDir.resolve("ImageMagick.AppImage"))
                : platformDir.resolve(WINDOWS ? tool + ".exe" : tool);
        if (!Files.isRegularFile(candidate))
        {
            return Optional.empty();
        }
        // Git checkouts and unpacked distributions can lose the executable bit.
        var file = candidate.toFile();
        if (!file.canExecute())
        {
            file.setExecutable(true);   // NOSONAR - best effort; a failure surfaces as the exec failing
        }
        return Optional.of(candidate);
    }

    private Path binDir()
    {
        return Paths.get(appProperties.getImageCompression().getBinDir()).toAbsolutePath().normalize();
    }
}

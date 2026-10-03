package io.github.mocchikon.hentie;

import org.apache.commons.lang3.SystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Links for the tests proving no delete reaches through one. A machine may be unable to make either kind (a
 * Windows symlink needs Developer Mode or elevation; junctions are Windows-only), so each skips, not fails.
 */
public final class TestLinks
{
    private TestLinks()
    {
    }

    public static Path symbolicLink(Path link, Path target)
    {
        try
        {
            return Files.createSymbolicLink(link, target);
        }
        catch (IOException | UnsupportedOperationException e)
        {
            assumeThat(false).as("cannot create a symbolic link here: " + e).isTrue();
            throw new IllegalStateException(e);
        }
    }

    /** Needs its own test: the JDK reports a junction as an ordinary directory, so a plain walk goes into it. */
    public static Path junction(Path link, Path target) throws IOException, InterruptedException
    {
        assumeThat(SystemUtils.IS_OS_WINDOWS).as("junctions exist only on Windows").isTrue();
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toAbsolutePath().toString(), target.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        mklink.getInputStream().readAllBytes();
        assumeThat(mklink.waitFor(30, TimeUnit.SECONDS) && mklink.exitValue() == 0)
                .as("mklink /J failed").isTrue();
        return link;
    }

    /** Prefers the symbolic link every OS has, else a junction. */
    public static Path directoryLink(Path link, Path target) throws IOException, InterruptedException
    {
        try
        {
            return Files.createSymbolicLink(link, target);
        }
        catch (IOException | UnsupportedOperationException e)
        {
            return junction(link, target);
        }
    }
}

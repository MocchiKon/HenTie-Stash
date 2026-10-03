package io.github.mocchikon.hentie.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** The {@code null} cases matter too: otherwise a URL naming no file gets a literal {@code :memory:} folder created. */
class DatabaseDirectoryTest
{
    @Test
    void shouldReturnParentFolderWhenUrlIsARelativeFile()
    {
        assertThat(DatabaseDirectory.directoryOf("jdbc:sqlite:./db/mydbNew.db")).isEqualTo(absolute("./db"));
    }

    @Test
    void shouldIgnoreConnectionParametersWhenUrlHasAQueryString()
    {
        // The production URL shape: everything from '?' on is driver configuration.
        assertThat(DatabaseDirectory.directoryOf(
                "jdbc:sqlite:./db/mydbNew.db?journal_mode=WAL&synchronous=NORMAL&cache_size=-524288"))
                .isEqualTo(absolute("./db"));
    }

    @Test
    void shouldReturnNestedParentWhenUrlHasSeveralMissingLevels()
    {
        assertThat(DatabaseDirectory.directoryOf("jdbc:sqlite:./target/a/b/c/perf.db"))
                .isEqualTo(absolute("./target/a/b/c"));
    }

    @Test
    void shouldReturnParentWhenUrlIsAnAbsoluteFile()
    {
        Path file = Paths.get("./db/mydbNew.db").toAbsolutePath().normalize();
        assertThat(DatabaseDirectory.directoryOf("jdbc:sqlite:" + file)).isEqualTo(file.getParent());
    }

    @Test
    void shouldReturnNullWhenDatabaseIsInMemory()
    {
        assertThat(DatabaseDirectory.directoryOf("jdbc:sqlite::memory:")).isNull();
    }

    @Test
    void shouldReturnNullWhenUrlNamesNoFile()
    {
        // Bare URL = driver-managed temp file; :resource: reads from the classpath. Neither needs a folder.
        assertThat(DatabaseDirectory.directoryOf("jdbc:sqlite:")).isNull();
        assertThat(DatabaseDirectory.directoryOf("jdbc:sqlite::resource:db/seed.db")).isNull();
    }

    @Test
    void shouldReturnNullWhenUrlIsNotSqlite()
    {
        assertThat(DatabaseDirectory.directoryOf("jdbc:h2:file:./db/mydbNew")).isNull();
        assertThat(DatabaseDirectory.directoryOf(null)).isNull();
    }

    private static Path absolute(String path)
    {
        return Paths.get(path).toAbsolutePath().normalize();
    }
}

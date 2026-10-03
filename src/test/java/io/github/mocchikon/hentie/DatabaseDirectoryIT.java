package io.github.mocchikon.hentie;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.*;

/**
 * A first run of the bare jar has no {@code ./db}, and xerial fails with {@code SQLITE_CANTOPEN} on a missing
 * parent folder. Every other suite uses {@code ./target}, which always exists, so only this one covers it.
 */
@SpringBootTest
class DatabaseDirectoryIT
{
    /** Two levels deep, so creating intermediate folders is covered too. */
    private static final Path FOLDER = Paths.get("./target/db-folder-it/nested").toAbsolutePath().normalize();

    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void missingDatabaseFolder(DynamicPropertyRegistry registry) throws IOException
    {
        // Runs before the context is created; @BeforeAll would be too late.
        deleteRecursively(FOLDER.getParent());
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + FOLDER.resolve("app.db") + "?journal_mode=WAL&foreign_keys=ON");
    }

    @Test
    void shouldCreateTheFolderAndMigrateWhenDatabaseFolderIsMissingOnStartup()
    {
        assertThat(FOLDER).isDirectory();
        assertThat(FOLDER.resolve("app.db")).isRegularFile();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success = 1",
                Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from chapter", Integer.class)).isZero();
    }

    private static void deleteRecursively(Path root) throws IOException
    {
        if (!Files.exists(root))
        {
            return;
        }
        try (var paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.delete(path);
            }
        }
    }
}

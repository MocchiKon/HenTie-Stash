package io.github.mocchikon.hentie;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V8 on a library that already has chapters and series: it edits the stored table definitions, so it must keep
 * the rows, leave a database SQLite still finds sound, and reach a connection opened before it, as the pool's are.
 */
class UpdatedStatusMigrationIT
{
    private static final Path DB = Paths.get("./target/migration-v8-test.sqlite");
    private static final String URL = "jdbc:sqlite:" + DB + "?foreign_keys=ON";

    @BeforeEach
    @AfterEach
    void removeDatabase() throws IOException
    {
        Files.deleteIfExists(DB);
        Files.deleteIfExists(Paths.get(DB + "-wal"));
        Files.deleteIfExists(Paths.get(DB + "-shm"));
    }

    @Test
    void shouldLetChaptersAndSeriesHoldTheUpdatedStatusAndKeepTheirRows() throws SQLException
    {
        // GIVEN a library at V7 with a series and a chapter in it, which cannot hold status 3 yet
        flyway("7").migrate();
        try (Connection before = DriverManager.getConnection(URL); Statement old = before.createStatement())
        {
            old.execute("insert into series (created_date, score_source, status, title_full, title_pretty) "
                    + "values ('2024-01-01', 0, 2, 'Kept Series', 'Kept Series')");
            old.execute("insert into chapter (series_id, status, upload_date, language, title_full, title_pretty) "
                    + "values ((select id from series), 1, '2024-01-01', 'english', 'Kept Chapter', 'Kept Chapter')");
            assertThatThrownBy(() -> old.execute("update series set status = 3")).isInstanceOf(SQLException.class);

            // WHEN
            flyway(null).migrate();

            // THEN the connection opened before the migration sees the new definitions...
            old.execute("update series set status = 3");
            old.execute("update chapter set status = 3");
            assertThat(single(old, "select status from series")).isEqualTo("3");
        }
        try (Connection connection = DriverManager.getConnection(URL); Statement sql = connection.createStatement())
        {
            // ...so does a new one, still refusing values past the last status...
            assertThatThrownBy(() -> sql.execute("update series set status = 4")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.execute("update chapter set status = 4")).isInstanceOf(SQLException.class);
            // ...the rows are all there...
            assertThat(single(sql, "select c.title_full || ' / ' || s.title_full from chapter c join series s "
                    + "on s.id = c.series_id")).isEqualTo("Kept Chapter / Kept Series");
            // ...and the database is sound, its search index still following the table.
            assertThat(single(sql, "pragma integrity_check")).isEqualTo("ok");
            assertThat(single(sql, "select count(*) from chapter_fts where chapter_fts = 'Kept'")).isEqualTo("1");
            assertThat(single(sql, "select count(*) from pragma_foreign_key_check")).isEqualTo("0");
        }
    }

    private static Flyway flyway(String target)
    {
        var configuration = Flyway.configure().dataSource(URL, null, null).locations("classpath:db/migration");
        return (target == null ? configuration : configuration.target(target)).load();
    }

    private static String single(Statement sql, String query) throws SQLException
    {
        try (ResultSet rows = sql.executeQuery(query))
        {
            assertThat(rows.next()).as(query).isTrue();
            return rows.getString(1);
        }
    }
}

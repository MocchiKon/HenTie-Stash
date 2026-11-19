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

/**
 * V8 on a library that already has favourites, which a schema built from an empty file never shows: status 2 was
 * the favourite, and must come out as a reviewed favourite, leaving status 2 to UPDATED.
 */
class FavouriteMigrationIT
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
    void shouldTurnFavouriteStatusIntoAReviewedFavourite() throws SQLException
    {
        // GIVEN a library at V7 with a chapter and a series of each status
        flyway("7").migrate();
        try (Connection connection = DriverManager.getConnection(URL); Statement sql = connection.createStatement())
        {
            for (int status = 0; status <= 2; status++)
            {
                sql.execute("insert into series (created_date, score_source, status, title_full, title_pretty) "
                        + "values ('2024-01-01', 0, " + status + ", 'S" + status + "', 'S" + status + "')");
                sql.execute("insert into chapter (status, upload_date, language, title_full, title_pretty) "
                        + "values (" + status + ", '2024-01-01', 'english', 'C" + status + "', 'C" + status + "')");
            }
        }

        // WHEN
        flyway(null).migrate();

        // THEN the favourites are reviewed favourites, and nothing else is a favourite or changed status
        try (Connection connection = DriverManager.getConnection(URL); Statement sql = connection.createStatement())
        {
            assertThat(single(sql, "select group_concat(title_full || '=' || status || '/' || favourite, ' ') "
                    + "from (select title_full, status, favourite from chapter order by title_full)"))
                    .isEqualTo("C0=0/0 C1=1/0 C2=1/1");
            assertThat(single(sql, "select group_concat(title_full || '=' || status || '/' || favourite, ' ') "
                    + "from (select title_full, status, favourite from series order by title_full)"))
                    .isEqualTo("S0=0/0 S1=1/0 S2=1/1");
            assertThat(single(sql, "select count(*) from pragma_foreign_key_check")).isEqualTo("0");
            assertThat(single(sql, "pragma integrity_check")).isEqualTo("ok");
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

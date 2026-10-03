package io.github.mocchikon.hentie;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

/**
 * The statistics pass of every start ({@code PlannerStatistics}, a Flyway callback), driven by
 * {@code flyway.migrate()} as a start drives it.
 * <p>
 * Pooled connections outlive a test and keep the statistics they read when they opened, so a test that
 * gathers statistics itself also makes every connection re-read them, as a start does.
 */
@SpringBootTest
class PlannerStatisticsIT
{
    private static final String PROBE_TITLE = "statsprobe ";

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired DataSource dataSource;

    /** On an empty table SQLite writes no statistics, hence the rows. */
    @Test
    void shouldGatherStatisticsForATableWithoutThemWhenMigrating()
    {
        // GIVEN a table with rows and no statistics
        jdbc.update("INSERT INTO tag (name) VALUES ('stats-a'), ('stats-b'), ('stats-c')");
        try
        {
            if (jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE name = 'sqlite_stat1'", Integer.class) > 0)
            {
                jdbc.update("DELETE FROM sqlite_stat1 WHERE tbl = 'tag'");
                reloadEverywhere();
            }

            // WHEN
            flyway.migrate();

            // THEN
            assertThat(gatheredAt("tag")).isEqualTo(count("tag"));
        }
        finally
        {
            jdbc.update("DELETE FROM tag WHERE name LIKE 'stats-%'");
        }
    }

    @Test
    void shouldRefreshTheStatisticsWhenTheLibraryGrewByMoreThanAQuarter()
    {
        try
        {
            // GIVEN statistics gathered at the current chapter count
            insertChapters(200);
            analyzeEverything();
            long gathered = gatheredAt("chapter");

            // WHEN a third more chapters arrive and the app starts
            insertChapters(gathered / 3 + 1);
            flyway.migrate();

            // THEN the statistics describe the library as it is now
            assertThat(gatheredAt("chapter")).isEqualTo(count("chapter")).isGreaterThan(gathered);
        }
        finally
        {
            deleteChapters();
        }
    }

    /** Below the threshold a start pays nothing, and optimize alone waits for a 10-fold change. */
    @Test
    void shouldKeepTheStatisticsWhenTheLibraryGrewByLessThanAQuarter()
    {
        try
        {
            // GIVEN statistics gathered at the current chapter count
            insertChapters(200);
            analyzeEverything();
            long gathered = gatheredAt("chapter");

            // WHEN a tenth more chapters arrive and the app starts
            insertChapters(gathered / 10);
            flyway.migrate();

            // THEN the statistics still describe the library as it was
            assertThat(gatheredAt("chapter")).isEqualTo(gathered).isLessThan(count("chapter"));
        }
        finally
        {
            deleteChapters();
        }
    }

    /**
     * ANALYZE writes rows, not schema, so without a schema change afterwards a connection opened before it
     * keeps planning without the new statistics. The pool opens its connections while Flyway runs.
     */
    @Test
    void shouldGiveAConnectionOpenedBeforeTheAnalysisItsStatistics() throws SQLException
    {
        // GIVEN a table whose statistics change the plan, none gathered yet
        jdbc.execute("CREATE TABLE stats_probe (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER)");
        try
        {
            jdbc.execute("CREATE INDEX stats_probe_a ON stats_probe (a)");
            jdbc.execute("CREATE INDEX stats_probe_b ON stats_probe (b)");
            // Every row has the same a, so with statistics the b range is the selective one.
            jdbc.execute("INSERT INTO stats_probe (a, b) WITH RECURSIVE n(i) AS "
                    + "(SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 20000) SELECT 1, i FROM n");
            try (Connection pooled = dataSource.getConnection())
            {
                // ... and a pooled connection that read the schema before the analysis
                assertThat(plan(pooled)).contains("stats_probe_a");

                // WHEN the app starts
                flyway.migrate();

                // THEN that connection plans with the new statistics
                assertThat(plan(pooled)).contains("stats_probe_b");
            }
        }
        finally
        {
            jdbc.execute("DROP TABLE stats_probe");
        }
    }

    /** A schema change makes every connection re-read the schema, so it is kept for starts that analyzed. */
    @Test
    void shouldLeaveTheSchemaAloneWhenNothingNeedsAnalyzing()
    {
        // GIVEN statistics a start has just brought up to date
        flyway.migrate();
        long version = schemaVersion();

        // WHEN the app starts again
        flyway.migrate();

        // THEN
        assertThat(schemaVersion()).isEqualTo(version);
    }

    /** A real statement first: only that notices a changed schema and re-reads it; EXPLAIN alone never does. */
    private static String plan(Connection connection) throws SQLException
    {
        try (var statement = connection.createStatement())
        {
            try (var rs = statement.executeQuery("SELECT count(*) FROM stats_probe WHERE a = 1 AND b < 100"))
            {
                rs.next();
            }
            try (var rs = statement.executeQuery("EXPLAIN QUERY PLAN SELECT * FROM stats_probe WHERE a = 1 AND b < 100"))
            {
                rs.next();
                return rs.getString("detail");
            }
        }
    }

    private void insertChapters(long n)
    {
        jdbc.update("INSERT INTO chapter (title_full, title_pretty, status, upload_date, language) "
                + "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?) "
                + "SELECT ? || i, ? || i, 0, 0, 'English' FROM n", n, PROBE_TITLE, PROBE_TITLE);
    }

    private void deleteChapters()
    {
        jdbc.update("DELETE FROM chapter WHERE title_full LIKE ?", PROBE_TITLE + "%");
    }

    private void analyzeEverything()
    {
        jdbc.execute("ANALYZE");
        reloadEverywhere();
    }

    private void reloadEverywhere()
    {
        jdbc.execute("CREATE VIEW stats_test_reload AS SELECT 1");
        jdbc.execute("DROP VIEW stats_test_reload");
    }

    /** The row count the table's statistics were gathered at, 0 without any. */
    private long gatheredAt(String table)
    {
        return jdbc.queryForObject("SELECT coalesce(max(cast(stat AS INTEGER)), 0) FROM sqlite_stat1 WHERE tbl = ?",
                Long.class, table);
    }

    private long count(String table)
    {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long schemaVersion()
    {
        return jdbc.queryForObject("PRAGMA schema_version", Long.class);
    }
}

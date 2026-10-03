package io.github.mocchikon.hentie.config;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps the query planner's statistics in step with the library. SQLite gathers none by itself, and without
 * them, or with ones gathered before the library grew, some searches sort every match (a 1-year date range,
 * last page of 1.5M chapters: 10 s against 0.1 s; the 300k chapters added since the last analysis, first
 * page: 430 ms against 0 ms).
 * <ul>
 *   <li>{@code PRAGMA optimize=0x10002} analyzes each table with an index that has no statistics, or whose
 *       size changed about 10-fold. 0x10000 checks every table, not only those this connection has queried.
 *       0x10, the analysis limit, stays off: with it ANALYZE also deletes the table's {@code sqlite_stat4}
 *       rows, and several searches go back to sorting every match.</li>
 *   <li>A chapter or series count a quarter away from the one the statistics were gathered at re-analyzes
 *       everything. By the 10-fold rule alone a library would keep its first statistics for good.</li>
 *   <li>New statistics end with a schema change. ANALYZE writes rows, not schema, so pooled connections opened
 *       before it (Hikari fills the pool while Flyway runs) would plan without its results until replaced.</li>
 * </ul>
 * A Flyway callback, so it runs on every start before the web server and the download worker. ANALYZE holds
 * SQLite's only write lock (~7 s for the chapters at 1.5M, 16 s for every table), so in the background it would hold
 * up every write in the app for that long.
 */
@Component
public class PlannerStatistics extends BaseCallback
{
    private static final Logger log = LoggerFactory.getLogger(PlannerStatistics.class);

    /** Their row counts stand for the library's size; the other tables grow with them. */
    private static final List<String> OWNERS = List.of("chapter", "series");

    /** A quarter more, or a fifth fewer. */
    private static final double REFRESH_RATIO = 1.25;

    @Override
    public boolean supports(Event event, Context context)
    {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public void handle(Event event, Context context)
    {
        try (var statement = context.getConnection().createStatement())
        {
            String before = statistics(statement);
            var start = System.nanoTime();
            // The refresh first: after it, optimize finds nothing left to do.
            refreshIfLibraryChanged(statement);
            statement.execute("PRAGMA optimize=0x10002");
            // Compared rather than taken from optimize's own list, which names every empty table without an
            // index on every start: ANALYZE writes nothing for an empty table, so it never drops off.
            if (!statistics(statement).equals(before))
            {
                log.info("Gathered the query planner's statistics in {} ms", (System.nanoTime() - start) / 1_000_000);
                reloadOnEveryConnection(statement);
            }
        }
        catch (SQLException e)
        {
            throw new IllegalStateException("Could not gather the query planner's statistics", e);
        }
    }

    private static void refreshIfLibraryChanged(Statement statement) throws SQLException
    {
        if (!hasStatistics(statement))
        {
            return;
        }
        for (String table : OWNERS)
        {
            // Every stat1 row of a table starts with its row count at the time; CAST reads that leading number.
            long gatheredAt = count(statement, "select coalesce(max(cast(stat as integer)), 0) from sqlite_stat1"
                    + " where tbl = '" + table + "'");
            if (gatheredAt == 0)
            {
                continue;   // never analyzed, or empty then: optimize takes it
            }
            long now = count(statement, "select count(*) from " + table);
            if (Math.max(now, gatheredAt) >= REFRESH_RATIO * Math.min(now, gatheredAt))
            {
                log.info("Refreshing the query planner's statistics: {} holds {} rows, {} when they were gathered",
                        table, now, gatheredAt);
                statement.execute("ANALYZE");
                return;
            }
        }
    }

    /** All of {@code sqlite_stat1}: an analysis that finds rows rewrites a table's lines, with its row count. */
    private static String statistics(Statement statement) throws SQLException
    {
        if (!hasStatistics(statement))
        {
            return "";
        }
        try (var rs = statement.executeQuery("select coalesce(string_agg(tbl || ' ' || coalesce(idx, '')"
                + " || ' ' || stat, char(10) order by tbl, idx), '') from sqlite_stat1"))
        {
            rs.next();
            return rs.getString(1);
        }
    }

    private static boolean hasStatistics(Statement statement) throws SQLException
    {
        return count(statement, "select count(*) from sqlite_master where type = 'table' and name = 'sqlite_stat1'") > 0;
    }

    /** Each connection re-reads the schema, statistics included, on its next statement after a schema change. */
    private static void reloadOnEveryConnection(Statement statement) throws SQLException
    {
        statement.execute("CREATE VIEW planner_statistics_reload AS SELECT 1");
        statement.execute("DROP VIEW planner_statistics_reload");
    }

    private static long count(Statement statement, String sql) throws SQLException
    {
        try (var rs = statement.executeQuery(sql))
        {
            rs.next();
            return rs.getLong(1);
        }
    }
}

package io.github.mocchikon.hentie;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives every {@code @SpringBootTest} context a fresh schema built from the <b>real</b> Flyway migrations,
 * so the suite exercises the scripts instead of trusting them. Found by component scan (it sits in the
 * application base package); {@code test.flyway.clean=false} keeps the data, which {@code PerformanceIT} uses
 * to reuse a seeded dataset.
 * <p>
 * It drops objects itself because {@code flyway.clean()} cannot: FTS5 shadow tables refuse a direct
 * {@code DROP} and the FTS triggers stay behind, so the next {@code migrate()} fails on a non-empty schema.
 * <p>
 * The connection comes from Flyway's configuration because Boot creates {@code JdbcTemplate} after the
 * Flyway initializer, so injecting one here would be a circular dependency.
 */
@Configuration
public class TestSchemaConfig
{
    @Bean
    FlywayMigrationStrategy freshSchemaPerRun(@Value("${test.flyway.clean:true}") boolean clean)
    {
        return flyway ->
        {
            if (clean)
            {
                wipe(flyway.getConfiguration().getDataSource());
            }
            flyway.migrate();
        };
    }

    private static void wipe(DataSource dataSource)
    {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            // Off, so the drop order does not matter and no cascade deletes rows from a table about to go. The
            // connection goes back to the pool, so the finally turns them on again.
            statement.execute("PRAGMA foreign_keys=OFF");
            try
            {
                dropAll(statement);
            }
            finally
            {
                statement.execute("PRAGMA foreign_keys=ON");
            }
        }
        catch (SQLException e)
        {
            throw new IllegalStateException("Could not wipe the test schema before migrating", e);
        }
    }

    private static void dropAll(Statement statement) throws SQLException
    {
        drop(statement, "DROP TRIGGER IF EXISTS ", names(statement, "type = 'trigger'"));
        // Virtual tables first: dropping one removes its shadow tables, which refuse a DROP of their own.
        drop(statement, "DROP TABLE IF EXISTS ",
                names(statement, "type = 'table' AND sql LIKE 'CREATE VIRTUAL TABLE%'"));
        drop(statement, "DROP VIEW IF EXISTS ", names(statement, "type = 'view'"));
        drop(statement, "DROP TABLE IF EXISTS ",
                names(statement, "type = 'table' AND name NOT LIKE 'sqlite_%'"));
        // The planner statistics PlannerStatistics gathers after every migrate: Flyway counts them as schema
        // content, so left behind they would make migrate() refuse the "non-empty" schema.
        drop(statement, "DROP TABLE IF EXISTS ", names(statement, "type = 'table' AND name LIKE 'sqlite_stat%'"));
    }

    private static List<String> names(Statement statement, String where) throws SQLException
    {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("SELECT name FROM sqlite_master WHERE " + where))
        {
            while (rs.next())
            {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static void drop(Statement statement, String dropStatement, List<String> names) throws SQLException
    {
        for (String name : names)
        {
            statement.executeUpdate(dropStatement + '"' + name + '"');
        }
    }
}

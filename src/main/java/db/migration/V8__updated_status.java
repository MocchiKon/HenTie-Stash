package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Lets {@code chapter.status} and {@code series.status} hold {@code Status.UPDATED} (3), by widening their
 * {@code CHECK (status between 0 and 2)} to 3.
 * <p>
 * SQLite cannot alter a constraint, and rebuilding {@code chapter} would copy millions of rows and recreate its
 * indexes, FTS triggers and the foreign keys pointing at it, which needs {@code foreign_keys=OFF}, impossible
 * inside Flyway's transaction. A wider CHECK changes nothing stored, so the table definitions are edited in
 * {@code sqlite_schema} instead, the procedure SQLite's ALTER TABLE documentation gives for such changes:
 * edit the text, then raise {@code schema_version} so every connection, the pooled ones included, reads the
 * new definition.
 * <p>
 * Java, not SQL: the new version is the old one plus one, and each definition is checked to have held exactly
 * the expected text, so one edited by hand fails the migration instead of being left as it was.
 */
public class V8__updated_status extends BaseJavaMigration
{
    private static final String OLD_CHECK = "status tinyint not null check ((status between 0 and 2))";
    private static final String NEW_CHECK = "status tinyint not null check ((status between 0 and 3))";

    @Override
    public void migrate(Context context) throws SQLException
    {
        Connection connection = context.getConnection();
        try (Statement sql = connection.createStatement())
        {
            int version = schemaVersion(sql);
            sql.execute("PRAGMA writable_schema = ON");
            try
            {
                for (String table : List.of("chapter", "series"))
                {
                    widen(connection, table);
                }
            }
            finally
            {
                sql.execute("PRAGMA writable_schema = OFF");
            }
            sql.execute("PRAGMA schema_version = " + (version + 1));
            // Prepares a statement on each table, so a definition SQLite cannot read fails here, inside the
            // transaction, and is rolled back.
            sql.executeQuery("SELECT count(*) FROM chapter WHERE status = 3").close();
            sql.executeQuery("SELECT count(*) FROM series WHERE status = 3").close();
        }
    }

    private static int schemaVersion(Statement sql) throws SQLException
    {
        try (ResultSet rs = sql.executeQuery("PRAGMA schema_version"))
        {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void widen(Connection connection, String table) throws SQLException
    {
        try (var update = connection.prepareStatement(
                "UPDATE sqlite_schema SET sql = replace(sql, ?, ?) WHERE type = 'table' AND name = ? "
                        + "AND instr(sql, ?) > 0"))
        {
            update.setString(1, OLD_CHECK);
            update.setString(2, NEW_CHECK);
            update.setString(3, table);
            update.setString(4, OLD_CHECK);
            if (update.executeUpdate() != 1)
            {
                throw new IllegalStateException("The definition of table '" + table + "' does not contain '"
                        + OLD_CHECK + "', so its status constraint cannot be widened");
            }
        }
    }
}

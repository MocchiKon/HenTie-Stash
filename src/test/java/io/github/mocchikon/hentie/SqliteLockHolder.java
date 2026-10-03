package io.github.mocchikon.hentie;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * Holds SQLite's write lock from a connection of its own, as a program outside the app would (a {@code sqlite3}
 * session, an ANALYZE run by hand): the write gate cannot see it. Closing it rolls back, which releases the lock.
 */
public final class SqliteLockHolder implements AutoCloseable
{
    private final Connection connection;

    private SqliteLockHolder(String jdbcUrl) throws SQLException
    {
        connection = DriverManager.getConnection(jdbcUrl);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement())
        {
            // A write that changes nothing still takes the lock, and keeps it until the rollback.
            statement.executeUpdate("DELETE FROM app_setting WHERE 0");
        }
    }

    /** @param jdbcUrl the test datasource's URL, so it is the same database file */
    public static SqliteLockHolder hold(String jdbcUrl) throws SQLException
    {
        return new SqliteLockHolder(jdbcUrl);
    }

    /** For a hold that ends on its own while the test waits for it. */
    public SqliteLockHolder releaseIn(Duration delay)
    {
        Thread.ofVirtual().start(() ->
        {
            try
            {
                Thread.sleep(delay);
                close();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            catch (SQLException e)
            {
                throw new IllegalStateException("Could not release SQLite's write lock", e);
            }
        });
        return this;
    }

    /** Safe to call twice, since a timed release may have run first. */
    @Override
    public synchronized void close() throws SQLException
    {
        if (connection.isClosed())
        {
            return;
        }
        try
        {
            connection.rollback();
        }
        finally
        {
            connection.close();
        }
    }
}

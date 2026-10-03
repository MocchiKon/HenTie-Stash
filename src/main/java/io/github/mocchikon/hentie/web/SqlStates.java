package io.github.mocchikon.hentie.web;

import java.sql.SQLException;

/**
 * Never matches on the driver's message text, which is locale-dependent. Uses SQLite's result code (its
 * {@code SQLSTATE} is null), then the SQL-standard {@code SQLSTATE} for other engines. SQLite's own
 * message is the one exception: it is always English.
 */
final class SqlStates
{
    private SqlStates() {}

    // SQLException.getErrorCode() values.
    private static final int SQLITE_CONSTRAINT = 19;             // base code; the message tells which constraint
    private static final int SQLITE_CONSTRAINT_PRIMARYKEY = 1555;
    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;

    static boolean isUniqueViolation(Throwable t)
    {
        SQLException sql = firstSqlException(t);
        if (sql == null)
        {
            return false;
        }
        int code = sql.getErrorCode();
        if (code == SQLITE_CONSTRAINT_UNIQUE || code == SQLITE_CONSTRAINT_PRIMARYKEY)
        {
            return true;
        }
        // The base code also covers NOT NULL / CHECK / FK.
        String message = sql.getMessage();
        if (code == SQLITE_CONSTRAINT && message != null
                && (message.contains("UNIQUE constraint failed")
                    || message.contains("PRIMARY KEY constraint failed")))
        {
            return true;
        }
        String state = sql.getSQLState();
        return "23505".equals(state) || "23001".equals(state);
    }

    /** SQLite never raises this (it ignores {@code VARCHAR(n)}); kept in case the engine is swapped. */
    static boolean isValueTooLong(Throwable t)
    {
        SQLException sql = firstSqlException(t);
        return sql != null && "22001".equals(sql.getSQLState());
    }

    private static SQLException firstSqlException(Throwable t)
    {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause())
        {
            if (c instanceof SQLException sql)
            {
                return sql;
            }
        }
        return null;
    }
}

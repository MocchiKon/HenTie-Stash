package io.github.mocchikon.hentie.config;

import java.sql.SQLException;

/**
 * Recognizes SQLite's lock errors by the driver's result code, found anywhere in the cause chain. Never by
 * exception type: the same error reaches a caller as a JPA {@code PessimisticLockException}, a Spring
 * {@code PessimisticLockingFailureException} or {@code UncategorizedSQLException}, depending on which API ran
 * the statement.
 */
public final class SqliteLocks
{
    /** xerial reports the primary result code, so {@code SQLITE_BUSY_SNAPSHOT} (517) arrives as this too. */
    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;

    private SqliteLocks() {}

    public static boolean isLockError(Throwable failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause())
        {
            if (cause instanceof SQLException sql
                    && (sql.getErrorCode() == SQLITE_BUSY || sql.getErrorCode() == SQLITE_LOCKED))
            {
                return true;
            }
        }
        return false;
    }
}

package io.github.mocchikon.hentie.config;

import org.springframework.transaction.CannotCreateTransactionException;

/**
 * A write that did not get its turn, so it changed nothing (see {@link WriteGate}). Its message is for the user.
 * <p>
 * A {@code TransactionException}, because it is thrown while a transaction begins, and Spring passes those on
 * unwrapped after closing the half-opened transaction; anything else it would wrap.
 */
public class LibraryBusyException extends CannotCreateTransactionException
{
    private static final String NOTHING_CHANGED = " Nothing was changed; try again in a moment.";

    private LibraryBusyException(String message)
    {
        super(message);
    }

    /** @param activity what holds the gate, as {@link WriteGate#background} was told; null when unnamed */
    static LibraryBusyException heldBy(String activity)
    {
        return new LibraryBusyException("The library is busy with "
                + (activity == null ? "a background task" : activity) + "." + NOTHING_CHANGED);
    }

    /** The gate was free, but SQLite's lock was not: a writer outside the app, such as an {@code sqlite3} session. */
    public static LibraryBusyException otherProgram()
    {
        return new LibraryBusyException("The library's database is in use by another program." + NOTHING_CHANGED);
    }

    static LibraryBusyException interrupted()
    {
        return new LibraryBusyException("The app was interrupted while waiting to change the library."
                + NOTHING_CHANGED);
    }
}

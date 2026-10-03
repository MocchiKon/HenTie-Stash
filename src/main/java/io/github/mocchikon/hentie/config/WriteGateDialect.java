package io.github.mocchikon.hentie.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

/**
 * Begins a write transaction in three steps: its turn at the {@link WriteGate}, the JDBC transaction, and then
 * SQLite's write lock, taken by a first statement that writes nothing. Read-only transactions skip all three.
 * <ul>
 *   <li><b>The gate comes first</b>, before a pooled connection is taken, so waiting holds no connection.</li>
 *   <li><b>The lock is taken up front</b> because {@code busy_timeout} only helps a transaction whose first
 *       statement writes; a transaction that reads first cannot wait for the lock later. So the only statement
 *       that can meet a writer outside the app (a {@code sqlite3} session, a hand-run ANALYZE) is this one,
 *       before any work, and the transaction can never fail later with {@code SQLITE_BUSY} or
 *       {@code SQLITE_BUSY_SNAPSHOT}.</li>
 *   <li><b>Not xerial's {@code transaction_mode=IMMEDIATE}</b>: xerial begins the next transaction right after
 *       every commit, so each commit would take the lock again, read-only transactions included. Its
 *       {@code explicit_readonly} mode upgrades only at the first statement, after whatever file work the method
 *       did first, and leaves {@code query_only} set on the pooled connection for later statements.</li>
 * </ul>
 * The gate is released when the transaction is cleaned up, after its commit or rollback. A begin that fails is
 * never cleaned up, so there the transaction manager releases it.
 */
class WriteGateDialect extends HibernateJpaDialect
{
    private static final Logger log = LoggerFactory.getLogger(WriteGateDialect.class);

    /** Any table would do: the statement matches no row, but SQLite takes the write lock to run it. */
    static final String TAKE_WRITE_LOCK = "DELETE FROM app_setting WHERE 0";

    private final WriteGate gate;

    /**
     * The {@code busy_timeout} a pooled connection has outside this dialect's first statement; -1 until read. Read
     * once, since every connection is opened from the same URL and this dialect always puts the value back.
     */
    private volatile int configuredBusyTimeout = -1;

    WriteGateDialect(WriteGate gate)
    {
        this.gate = gate;
    }

    /** Lets {@link #cleanupTransaction} tell a write transaction, which holds the gate, from a read-only one. */
    private record GatedTransaction(Object hibernateData)
    {
    }

    @Override
    public Object beginTransaction(EntityManager entityManager, TransactionDefinition definition)
            throws PersistenceException, SQLException, TransactionException
    {
        if (definition.isReadOnly())
        {
            return super.beginTransaction(entityManager, definition);
        }
        if (gate.isHeldByCurrentThread())
        {
            // Its lock step would wait for the lock this thread's suspended transaction holds, until busy_timeout.
            throw new IllegalTransactionStateException("A write transaction cannot begin while another write "
                    + "transaction of the same thread is suspended: that one holds SQLite's only write lock");
        }
        WriteGate.Turn turn = gate.enter();
        Object hibernateData = super.beginTransaction(entityManager, definition);
        entityManager.unwrap(Session.class).doWork(connection ->
        {
            try (Statement statement = connection.createStatement())
            {
                takeWriteLock(statement, turn);
            }
        });
        gate.began(turn);
        return new GatedTransaction(hibernateData);
    }

    @Override
    public void cleanupTransaction(Object transactionData)
    {
        if (!(transactionData instanceof GatedTransaction gated))
        {
            super.cleanupTransaction(transactionData);
            return;
        }
        try
        {
            super.cleanupTransaction(gated.hibernateData());
        }
        finally
        {
            gate.exit();
        }
    }

    /**
     * First without waiting, which is enough unless a program outside the app writes; then within the turn's
     * wait, naming that program to anyone who gives up meanwhile. The connection's own {@code busy_timeout} is
     * put back, since the pool hands the connection on.
     */
    private void takeWriteLock(Statement statement, WriteGate.Turn turn) throws SQLException
    {
        int configured = configuredBusyTimeout(statement);
        try
        {
            setBusyTimeout(statement, 0);
            if (lockTaken(statement))
            {
                return;
            }
            gate.waitingForOtherProgram(true);
            try
            {
                awaitLock(statement, turn);
            }
            finally
            {
                gate.waitingForOtherProgram(false);
            }
        }
        finally
        {
            setBusyTimeout(statement, configured);
        }
    }

    private void awaitLock(Statement statement, WriteGate.Turn turn) throws SQLException
    {
        long waited = 0;
        while (true)
        {
            long wait = turn.isPatient() ? WriteGate.PATIENCE_REPORT_MILLIS : turn.remainingMillis();
            if (wait <= 0)
            {
                throw LibraryBusyException.otherProgram();
            }
            setBusyTimeout(statement, (int) Math.min(wait, Integer.MAX_VALUE));
            if (lockTaken(statement))
            {
                return;
            }
            if (!turn.isPatient())
            {
                throw LibraryBusyException.otherProgram();
            }
            waited += wait;
            log.warn("Waited {} s so far for SQLite's write lock, which a program outside the app holds",
                    waited / 1000);
        }
    }

    /** @return false when another connection holds the lock */
    private static boolean lockTaken(Statement statement) throws SQLException
    {
        try
        {
            statement.executeUpdate(TAKE_WRITE_LOCK);
            return true;
        }
        catch (SQLException e)
        {
            if (SqliteLocks.isLockError(e))
            {
                return false;
            }
            throw e;
        }
    }

    private int configuredBusyTimeout(Statement statement) throws SQLException
    {
        int known = configuredBusyTimeout;
        if (known < 0)
        {
            known = busyTimeout(statement);
            configuredBusyTimeout = known;
        }
        return known;
    }

    private static int busyTimeout(Statement statement) throws SQLException
    {
        try (ResultSet rs = statement.executeQuery("PRAGMA busy_timeout"))
        {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void setBusyTimeout(Statement statement, int millis) throws SQLException
    {
        statement.execute("PRAGMA busy_timeout = " + millis);
    }
}

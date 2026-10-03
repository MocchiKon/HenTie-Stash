package io.github.mocchikon.hentie;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.config.LibraryBusyException;
import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.Tag;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.MetadataService;

import static org.assertj.core.api.Assertions.*;

/**
 * The write gate against what it exists for: a background writer holding it, a loop of short transactions, a
 * program outside the app holding SQLite's lock, and transactions that read before they write.
 * <p>
 * Not {@code @Transactional}: every write here must begin a transaction of its own, as in the app, and the holders
 * are other threads and connections. It commits to the shared database, so its tags carry a prefix and go after
 * each test. A request is simulated by {@link WriteGate#serveRequest}, as the servlet filter opens it.
 */
@SpringBootTest
class WriteGateIT
{
    private static final String PREFIX = "wgit-";

    @Autowired WriteGate writeGate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MetadataService metadataService;
    @Autowired TagRepository tagRepository;
    @Autowired AppProperties appProperties;
    @Autowired CacheManager cacheManager;
    @Value("${spring.datasource.url}") String datasourceUrl;

    private long budget;

    @BeforeEach
    void setUp()
    {
        budget = appProperties.getWrites().getRequestWaitMillis();
        cacheManager.getCache(CacheConfig.METADATA).clear();
    }

    @AfterEach
    void removeTags()
    {
        for (Tag tag : tagRepository.findAll())
        {
            if (tag.getName().startsWith(PREFIX))
            {
                metadataService.remove(MetadataType.TAG, tag.getId(), false);
            }
        }
    }

    // ---- requests -----------------------------------------------------------------------------------------------

    @Test
    void shouldLetARequestWaitOutAHoldShorterThanItsBudget() throws Exception
    {
        // GIVEN a background task holding the gate, done well within a request's budget.
        try (GateHolder ignored = GateHolder.hold(transactionManager, writeGate, "a short task")
                .releaseIn(Duration.ofMillis(budget / 3)))
        {
            // WHEN a request adds a tag meanwhile.
            long start = System.nanoTime();
            asRequest(() -> metadataService.add(MetadataType.TAG, PREFIX + "short"));

            // THEN it waited for the task, then wrote.
            assertThat(millisSince(start)).isGreaterThanOrEqualTo(budget / 3 - 30);
        }
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "short")).isPresent();
    }

    @Test
    void shouldRefuseARequestsFirstWriteOnceItsBudgetIsSpentAndChangeNothing() throws Exception
    {
        // GIVEN a background task holding the gate for longer than a request waits.
        try (GateHolder ignored = GateHolder.hold(transactionManager, writeGate, "the test's sweep"))
        {
            // WHEN a request tries to add a tag.
            long start = System.nanoTime();
            assertThatThrownBy(() -> asRequest(() -> metadataService.add(MetadataType.TAG, PREFIX + "refused")))
                    // THEN it gives up after its budget, saying what the library is busy with.
                    .isInstanceOf(LibraryBusyException.class)
                    .hasMessage("The library is busy with the test's sweep. Nothing was changed; try again in a moment.");
            assertThat(millisSince(start)).isGreaterThanOrEqualTo(budget - 30);
        }
        // AND nothing was written.
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "refused")).isEmpty();
    }

    /** A long-running form runs on a request thread, and must not fail for waiting. */
    @Test
    void shouldLetBackgroundWorkOnARequestThreadWaitAsLongAsItMust() throws Exception
    {
        // GIVEN a hold three times as long as a request's budget.
        try (GateHolder ignored = GateHolder.hold(transactionManager, writeGate, "a long task")
                .releaseIn(Duration.ofMillis(3 * budget)))
        {
            // WHEN a request runs background work meanwhile.
            long start = System.nanoTime();
            asRequest(() -> writeGate.background("the test's merge",
                    () -> metadataService.add(MetadataType.TAG, PREFIX + "patient")));

            // THEN it waited for all of it, then wrote.
            assertThat(millisSince(start)).isGreaterThanOrEqualTo(3 * budget - 30);
        }
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "patient")).isPresent();
    }

    /** Its first write may already have changed something, so giving up later would leave it half done. */
    @Test
    void shouldLetARequestThatHadATurnFinishWhateverItMeetsLater() throws Exception
    {
        try (WriteGate.Scope ignored = writeGate.serveRequest())
        {
            // GIVEN a request whose first write went through.
            metadataService.add(MetadataType.TAG, PREFIX + "first");

            // WHEN a sweep takes the gate before its second write, for longer than a request's budget.
            try (GateHolder holder = GateHolder.hold(transactionManager, writeGate, "a sweep that started meanwhile")
                    .releaseIn(Duration.ofMillis(3 * budget)))
            {
                long start = System.nanoTime();
                metadataService.add(MetadataType.TAG, PREFIX + "second");

                // THEN the second write waited for it instead of giving up.
                assertThat(millisSince(start)).isGreaterThanOrEqualTo(3 * budget - 30);
            }
        }
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "first")).isPresent();
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "second")).isPresent();
    }

    @Test
    void shouldRefuseToClaimATurnWhileTheGateStaysHeld() throws Exception
    {
        try (GateHolder ignored = GateHolder.hold(transactionManager, writeGate, "the test's sweep"))
        {
            assertThatThrownBy(() -> asRequest(() ->
            {
                writeGate.claimTurn();
                return null;
            })).isInstanceOf(LibraryBusyException.class).hasMessageContaining("busy with the test's sweep");
        }
    }

    /** For an action that saves files first: once it claimed its turn, its writes wait instead of failing. */
    @Test
    void shouldLetARequestThatClaimedItsTurnWaitForItsWrites() throws Exception
    {
        try (WriteGate.Scope ignored = writeGate.serveRequest())
        {
            // GIVEN a request that claimed its turn, as before changing files.
            writeGate.claimTurn();

            // WHEN a sweep holds the gate for longer than a request's budget before the request's first write.
            try (GateHolder holder = GateHolder.hold(transactionManager, writeGate, "a sweep that started meanwhile")
                    .releaseIn(Duration.ofMillis(3 * budget)))
            {
                metadataService.add(MetadataType.TAG, PREFIX + "claimed");
            }
        }
        // THEN the write went through.
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "claimed")).isPresent();
    }

    @Test
    void shouldSkipAWriteThatMayBeSkippedWithoutWaiting() throws Exception
    {
        try (GateHolder ignored = GateHolder.hold(transactionManager, writeGate, "the test's sweep"))
        {
            // WHEN a repair that may be skipped runs while the gate is held.
            long start = System.nanoTime();
            Optional<Boolean> skipped = asRequest(() -> writeGate.ifFree(() ->
            {
                metadataService.add(MetadataType.TAG, PREFIX + "skipped");
                return true;
            }));

            // THEN it is skipped at once.
            assertThat(skipped).isEmpty();
            assertThat(millisSince(start)).isLessThan(budget / 2);
        }
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "skipped")).isEmpty();

        // AND with the gate free, it runs.
        assertThat(asRequest(() -> writeGate.ifFree(() ->
        {
            metadataService.add(MetadataType.TAG, PREFIX + "not-skipped");
            return true;
        }))).contains(true);
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "not-skipped")).isPresent();
    }

    // ---- fairness -----------------------------------------------------------------------------------------------

    /**
     * SQLite's busy handler would let the loop take the lock back before the request woke up, until the loop ended
     * or the request gave up. The gate hands the request the next turn.
     */
    @Test
    void shouldGiveAWaitingRequestTheNextTurnBetweenTwoUnitsOfALoop() throws Exception
    {
        // GIVEN a background loop of short write transactions back to back, as a sweep's slices.
        int units = 20;
        long unitMillis = budget / 3;
        var unitsDone = new AtomicInteger();
        var twoUnitsIn = new CountDownLatch(2);
        var transactions = new TransactionTemplate(transactionManager);
        var loop = Thread.ofPlatform().name("test-loop").start(() -> writeGate.background("the test's loop", () ->
        {
            for (int unit = 0; unit < units; unit++)
            {
                transactions.executeWithoutResult(status -> sleep(unitMillis));
                unitsDone.incrementAndGet();
                twoUnitsIn.countDown();
            }
        }));
        try
        {
            assertThat(twoUnitsIn.await(10, TimeUnit.SECONDS)).isTrue();

            // WHEN a request writes meanwhile.
            long start = System.nanoTime();
            asRequest(() -> metadataService.add(MetadataType.TAG, PREFIX + "fair"));
            long waited = millisSince(start);
            int doneWhenServed = unitsDone.get();

            // THEN it waited for about the unit in progress, not for the loop.
            assertThat(waited).isLessThan(2 * unitMillis + 50);
            assertThat(doneWhenServed).isLessThan(units / 2);
        }
        finally
        {
            loop.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertThat(unitsDone).hasValue(units);
    }

    // ---- SQLite's own lock ----------------------------------------------------------------------------------------

    /**
     * Without the lock taken up front, the outsider would commit between the transaction's read and its write,
     * which then fails with SQLITE_BUSY_SNAPSHOT.
     */
    @Test
    void shouldCommitAWriteThatReadFirstWhileAWriterOutsideTheAppTriedToCommitInBetween() throws Exception
    {
        // GIVEN a program outside the app that tries to write as soon as the app's transaction has read.
        var read = new CountDownLatch(1);
        var outsiderStarted = new CountDownLatch(1);
        var outsiderWroteAt = new AtomicLong();
        var outsider = CompletableFuture.runAsync(() ->
        {
            await(read);
            try (Connection connection = DriverManager.getConnection(datasourceUrl);
                 Statement statement = connection.createStatement())
            {
                connection.setAutoCommit(false);
                outsiderStarted.countDown();
                statement.executeUpdate("INSERT INTO tag(name) VALUES ('" + PREFIX + "outsider')");
                outsiderWroteAt.set(System.nanoTime());
                connection.commit();
            }
            catch (SQLException e)
            {
                throw new IllegalStateException(e);
            }
        });

        // WHEN the app's transaction reads, lets the outsider try, and then writes.
        long wroteAt = new TransactionTemplate(transactionManager).execute(status ->
        {
            tagRepository.count();
            read.countDown();
            await(outsiderStarted);
            sleep(budget / 2);
            metadataService.add(MetadataType.TAG, PREFIX + "read-first");
            return System.nanoTime();
        });
        outsider.get(20, TimeUnit.SECONDS);

        // THEN both committed, and the outsider could write only after the app's transaction had written too.
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "read-first")).isPresent();
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "outsider")).isPresent();
        assertThat(outsiderWroteAt.get()).isGreaterThan(wroteAt);
    }

    /** The other order: the transaction's first statement waits for the outsider, then reads what it committed. */
    @Test
    void shouldLetABackgroundWriteWaitForAWriterOutsideTheAppAndSeeWhatItCommitted() throws Exception
    {
        try (Connection outsider = DriverManager.getConnection(datasourceUrl);
             Statement statement = outsider.createStatement())
        {
            // GIVEN a program outside the app in the middle of a write.
            outsider.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO tag(name) VALUES ('" + PREFIX + "outsider-first')");
            CompletableFuture.runAsync(() ->
            {
                sleep(3 * budget);
                try
                {
                    outsider.commit();
                }
                catch (SQLException e)
                {
                    throw new IllegalStateException(e);
                }
            });

            // WHEN a background write transaction (this thread serves no request) reads and writes meanwhile.
            long start = System.nanoTime();
            boolean sawTheOutsider = new TransactionTemplate(transactionManager).execute(status ->
            {
                boolean seen = tagRepository.findByNameIgnoreCase(PREFIX + "outsider-first").isPresent();
                metadataService.add(MetadataType.TAG, PREFIX + "after-outsider");
                return seen;
            });

            // THEN it waited for the outsider and read its commit, instead of failing.
            assertThat(millisSince(start)).isGreaterThanOrEqualTo(3 * budget - 30);
            assertThat(sawTheOutsider).isTrue();
        }
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "after-outsider")).isPresent();
    }

    @Test
    void shouldRefuseARequestWhileAProgramOutsideTheAppHoldsTheLockPastItsBudget() throws Exception
    {
        try (SqliteLockHolder ignored = SqliteLockHolder.hold(datasourceUrl))
        {
            long start = System.nanoTime();
            assertThatThrownBy(() -> asRequest(() -> metadataService.add(MetadataType.TAG, PREFIX + "outside")))
                    .isInstanceOf(LibraryBusyException.class)
                    .hasMessage("The library's database is in use by another program. Nothing was changed; try "
                            + "again in a moment.");
            assertThat(millisSince(start)).isGreaterThanOrEqualTo(budget - 30);
        }
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "outside")).isEmpty();
    }

    @Test
    void shouldLetARequestWaitOutAShortHoldOutsideTheApp() throws Exception
    {
        try (SqliteLockHolder ignored = SqliteLockHolder.hold(datasourceUrl).releaseIn(Duration.ofMillis(budget / 3)))
        {
            asRequest(() -> metadataService.add(MetadataType.TAG, PREFIX + "outside-short"));
        }
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "outside-short")).isPresent();
    }

    /** The writer at the gate is only waiting for the other program, so naming it would mislead. */
    @Test
    void shouldNameTheOtherProgramWhenTheWriterAheadWaitsForIt() throws Exception
    {
        try (SqliteLockHolder ignored = SqliteLockHolder.hold(datasourceUrl))
        {
            // GIVEN a background write that got the gate and now waits for SQLite's lock.
            var backgroundWrite = CompletableFuture.runAsync(() -> writeGate.background("the test's sweep",
                    () -> metadataService.add(MetadataType.TAG, PREFIX + "behind-outsider")));
            sleep(budget / 3);

            // WHEN a request waits behind it.
            assertThatThrownBy(() -> asRequest(() -> metadataService.add(MetadataType.TAG, PREFIX + "queued")))
                    // THEN it is told about the other program, not the sweep.
                    .isInstanceOf(LibraryBusyException.class)
                    .hasMessageStartingWith("The library's database is in use by another program.");
            assertThat(backgroundWrite).isNotDone();
            ignored.close();
            backgroundWrite.get(10, TimeUnit.SECONDS);
        }
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "behind-outsider")).isPresent();
    }

    // ---- misuse -------------------------------------------------------------------------------------------------

    /** Its lock step would wait for the lock the suspended transaction on the same thread holds. */
    @Test
    void shouldRefuseAWriteTransactionInsideAnother()
    {
        var outer = new TransactionTemplate(transactionManager);
        var inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> inner.executeWithoutResult(nested -> {})))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("suspended");
    }

    /** Joining would skip the gate, and Hibernate would not flush it either. */
    @Test
    void shouldRefuseAWriteThatWouldJoinAReadOnlyTransaction()
    {
        var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(
                status -> metadataService.add(MetadataType.TAG, PREFIX + "joined")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "joined")).isEmpty();
    }

    /** Spring never cleans up a begin that failed, and a gate left held would stop every write until a restart. */
    @Test
    void shouldReleaseTheGateWhenABeginFailsAfterTheGateWasTaken() throws Exception
    {
        // GIVEN a JDBC connection already exposed for the data source, so the begin fails only after the dialect
        // took the gate and SQLite's lock, when the manager exposes its own connection.
        DataSource dataSource = ((JpaTransactionManager) transactionManager).getDataSource();
        try (Connection connection = dataSource.getConnection())
        {
            var preBound = new ConnectionHolder(connection);
            preBound.setSynchronizedWithTransaction(true);
            TransactionSynchronizationManager.bindResource(dataSource, preBound);
            try
            {
                // WHEN a write transaction begins.
                assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {}))
                        .isInstanceOf(CannotCreateTransactionException.class);
            }
            finally
            {
                TransactionSynchronizationManager.unbindResource(dataSource);
            }
        }

        // THEN a request on another thread gets its turn at once.
        CompletableFuture.runAsync(() -> asRequest(() -> metadataService.add(MetadataType.TAG, PREFIX + "after-failed")))
                .get(10, TimeUnit.SECONDS);
        assertThat(tagRepository.findByNameIgnoreCase(PREFIX + "after-failed")).isPresent();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private <T> T asRequest(Supplier<T> work)
    {
        try (WriteGate.Scope ignored = writeGate.serveRequest())
        {
            return work.get();
        }
    }

    private static long millisSince(long startNanos)
    {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            if (!latch.await(10, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("Timed out waiting for the other side of the test");
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

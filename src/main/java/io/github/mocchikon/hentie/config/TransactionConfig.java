package io.github.mocchikon.hentie.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import jakarta.persistence.EntityManagerFactory;

/**
 * Replaces Boot's transaction manager, so every write transaction begins through the {@link WriteGate}. Named
 * {@code transactionManager}, the bean Spring Data's repositories look up.
 */
@Configuration
public class TransactionConfig
{
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory, WriteGate writeGate)
    {
        return new GatedTransactionManager(entityManagerFactory, new WriteGateDialect(writeGate), writeGate);
    }

    /**
     * <b>Joining is validated</b>: a write joining a read-only transaction would begin none of its own, so it would
     * pass the gate by, and Hibernate would not even flush it. Spring refuses the join at once instead.
     */
    private static final class GatedTransactionManager extends JpaTransactionManager
    {
        private final WriteGateDialect dialect;
        private final WriteGate writeGate;

        private GatedTransactionManager(EntityManagerFactory entityManagerFactory, WriteGateDialect dialect,
                                        WriteGate writeGate)
        {
            this.dialect = dialect;
            this.writeGate = writeGate;
            setEntityManagerFactory(entityManagerFactory);
            setValidateExistingTransaction(true);
        }

        /** The factory's own dialect is set here too, so this runs after it, also when the container calls it. */
        @Override
        public void afterPropertiesSet()
        {
            super.afterPropertiesSet();
            setJpaDialect(dialect);
        }

        /**
         * <b>Releases the gate when the begin fails</b>, wherever it fails. Spring closes a failed begin without
         * {@code cleanupTransaction}, where the dialect releases it otherwise, and a gate left held would stop every
         * write in the app until a restart. A gate held before this begin belongs to a suspended transaction.
         */
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition)
        {
            boolean heldBefore = writeGate.isHeldByCurrentThread();
            try
            {
                super.doBegin(transaction, definition);
            }
            catch (RuntimeException | Error e)
            {
                if (!heldBefore && writeGate.isHeldByCurrentThread())
                {
                    writeGate.exit();
                }
                throw e;
            }
        }
    }
}

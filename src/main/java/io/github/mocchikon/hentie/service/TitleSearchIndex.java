package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.WriteGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Uses and repairs the FTS5 trigram title index. Flyway creates it and SQL triggers keep it in sync, so it
 * always exists and every write path maintains it.
 * <p>
 * {@code LIKE '%term%'} cannot use a B-tree and full-scans (~0.6 s over 1.5M chapters against ~10 ms for a
 * trigram seek). The index is also what makes title search case-insensitive: SQLite {@code LIKE} folds
 * ASCII only, the trigram tokenizer folds Unicode.
 * <p>
 * A term shorter than {@link #MIN_TERM_LENGTH} forms no trigram and the index silently returns nothing, so
 * those terms (and only those) use {@code TitlePredicate}'s {@code LIKE} path.
 */
@Service
public class TitleSearchIndex
{
    private static final Logger log = LoggerFactory.getLogger(TitleSearchIndex.class);

    public static final int MIN_TERM_LENGTH = 3;

    /** Each has a {@code <owner>_fts} index. */
    private static final List<String> OWNERS = List.of("chapter", "series");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WriteGate writeGate;

    public TitleSearchIndex(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, WriteGate writeGate)
    {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.writeGate = writeGate;
    }

    /**
     * Reads every row: 14-30 s per 1.5M chapters, the longest the write lock is ever held. It cannot be sliced:
     * a trigger deleting a row not yet indexed again would corrupt an external-content index. In a transaction
     * of its own, which takes its turn at the gate like any write; autocommit would bypass it.
     */
    public void rebuild(String owner)
    {
        String fts = owner + "_fts";
        transactions.executeWithoutResult(
                status -> jdbc.update("INSERT INTO " + fts + "(" + fts + ") VALUES ('rebuild')"));
        log.info("Rebuilt the {} title index", fts);
    }

    /**
     * The repair for what the triggers cannot see: rows loaded by a tool that bypassed them, or an index
     * dropped or truncated behind the app. One owner at a time, so requests get their turns in between.
     */
    public void rebuildAll()
    {
        writeGate.background("rebuilding the title search index", () -> OWNERS.forEach(this::rebuild));
    }

    /**
     * A quoted FTS5 phrase keeps {@code LIKE '%x%'} substring semantics and makes the term literal (no FTS5
     * operators). Empty when the term is too short and the caller must use {@code LIKE}.
     */
    public Optional<String> phraseFor(String rawTerm)
    {
        String term = rawTerm == null ? "" : rawTerm.trim();
        if (term.length() < MIN_TERM_LENGTH)
        {
            return Optional.empty();
        }
        return Optional.of(phrase(term));
    }

    /** Doubling a quote is FTS5's escape, not SQL's: the result is still bound as a parameter. */
    private static String phrase(String term)
    {
        return '"' + term.replace("\"", "\"\"") + '"';
    }
}

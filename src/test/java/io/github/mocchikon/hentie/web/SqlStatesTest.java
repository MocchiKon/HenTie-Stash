package io.github.mocchikon.hentie.web;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.*;

/** Classification must never depend on the locale of the message text: that text is translated. */
class SqlStatesTest
{
    /** As Spring/Hibernate wrap it: the SQLException sits deeper in the cause chain. */
    private static DataIntegrityViolationException wrap(SQLException sql)
    {
        return new DataIntegrityViolationException("constraint violation", new RuntimeException(sql));
    }

    /** SQLite's shape: null SQLSTATE, the result code in the vendor code. */
    private static SQLException sqlite(int resultCode, String message)
    {
        return new SQLException(message, null, resultCode);
    }

    @Test
    void shouldDetectUniqueViolationFromSqliteExtendedCode()
    {
        // GIVEN a SQLite unique violation (extended code 2067 = SQLITE_CONSTRAINT_UNIQUE)
        DataIntegrityViolationException ex = wrap(sqlite(2067, "[SQLITE_CONSTRAINT_UNIQUE] ... (UNIQUE constraint failed: chapter.title_full)"));

        // THEN
        assertThat(SqlStates.isUniqueViolation(ex)).isTrue();
        assertThat(SqlStates.isValueTooLong(ex)).isFalse();
    }

    @Test
    void shouldDetectPrimaryKeyViolationFromSqliteExtendedCode()
    {
        // GIVEN a SQLite primary-key violation (extended code 1555 = SQLITE_CONSTRAINT_PRIMARYKEY)
        DataIntegrityViolationException ex = wrap(sqlite(1555, "[SQLITE_CONSTRAINT_PRIMARYKEY] ... (PRIMARY KEY constraint failed: chapter.id)"));

        // THEN
        assertThat(SqlStates.isUniqueViolation(ex)).isTrue();
    }

    @Test
    void shouldDetectUniqueViolationFromSqliteBaseCodeAndMessage()
    {
        // GIVEN the base constraint code (19) - disambiguated by the stable-English message
        DataIntegrityViolationException ex = wrap(sqlite(19, "[SQLITE_CONSTRAINT] Abort due to constraint violation (UNIQUE constraint failed: chapter.gallery_id)"));

        // THEN
        assertThat(SqlStates.isUniqueViolation(ex)).isTrue();
    }

    @Test
    void shouldNotClassifyBaseConstraintWhenMessageIsNotUnique()
    {
        // GIVEN the base constraint code (19) for a NOT NULL failure - not a unique violation
        DataIntegrityViolationException ex = wrap(sqlite(19, "[SQLITE_CONSTRAINT] Abort due to constraint violation (NOT NULL constraint failed: chapter.title_full)"));

        // THEN
        assertThat(SqlStates.isUniqueViolation(ex)).isFalse();
        assertThat(SqlStates.isValueTooLong(ex)).isFalse();
    }

    @Test
    void shouldRecogniseUniqueViolationFromStandardSqlState()
    {
        // GIVEN a SQL-standard duplicate-key SQLSTATE (locale-invariant); non-English message is irrelevant
        DataIntegrityViolationException ex = wrap(new SQLException("Naruszenie unikalnego indeksu", "23505"));

        // THEN
        assertThat(SqlStates.isUniqueViolation(ex)).isTrue();
        assertThat(SqlStates.isValueTooLong(ex)).isFalse();
    }

    @Test
    void shouldRecogniseUniqueViolationWhenStateIsLegacyCode()
    {
        // WHEN
        boolean uniqueViolation = SqlStates.isUniqueViolation(wrap(new SQLException("anything", "23001")));

        // THEN
        assertThat(uniqueViolation).isTrue();
    }

    @Test
    void shouldDetectValueTooLongFromStandardSqlState()
    {
        // GIVEN 22001 = string data right truncation; a non-English message must not affect the verdict
        DataIntegrityViolationException ex = wrap(new SQLException("Wartość za długa dla kolumny TITLE_FULL", "22001"));

        // THEN
        assertThat(SqlStates.isValueTooLong(ex)).isTrue();
        assertThat(SqlStates.isUniqueViolation(ex)).isFalse();
    }

    @Test
    void shouldClassifyAsNeitherWhenChainHasNoSqlException()
    {
        // GIVEN
        DataIntegrityViolationException plain = new DataIntegrityViolationException("no jdbc cause");

        // THEN
        assertThat(SqlStates.isUniqueViolation(plain)).isFalse();
        assertThat(SqlStates.isValueTooLong(plain)).isFalse();
    }
}

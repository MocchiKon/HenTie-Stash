package io.github.mocchikon.hentie.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backstop for a unique-key violation that slips past the pre-save checks (e.g. a concurrent insert).
 * Tested directly because MVC tests only ever reach the pre-save path.
 */
class ChapterControllerRejectDuplicateTest
{
    private static DataIntegrityViolationException wrap(String sqlState)
    {
        SQLException sql = new SQLException("boom", sqlState);
        return new DataIntegrityViolationException("constraint violation", new RuntimeException(sql));
    }

    private static BindingResult bindingResult()
    {
        return new BeanPropertyBindingResult(new Object(), "form");
    }

    @Test
    void shouldRejectWithLengthMessageWhenSqlStateIsValueTooLong()
    {
        // GIVEN
        BindingResult result = bindingResult();

        // WHEN
        ChapterController.rejectDuplicate(result, wrap("22001"));

        // THEN
        ObjectError error = result.getGlobalError();
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo("toolong");
        assertThat(error.getDefaultMessage()).contains("maximum 255 characters");
    }

    @Test
    void shouldRejectWithDuplicateMessageWhenSqlStateIsUniqueViolation()
    {
        // GIVEN
        BindingResult result = bindingResult();

        // WHEN
        ChapterController.rejectDuplicate(result, wrap("23505"));

        // THEN
        ObjectError error = result.getGlobalError();
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo("duplicate");
        assertThat(error.getDefaultMessage()).contains("already in use");
    }

    @Test
    void shouldRejectWithDuplicateMessageWhenSqlStateIsLegacyUnique()
    {
        // GIVEN
        BindingResult result = bindingResult();

        // WHEN
        ChapterController.rejectDuplicate(result, wrap("23001"));

        // THEN
        assertThat(result.getGlobalError().getCode()).isEqualTo("duplicate");
    }

    @Test
    void shouldFallBackToGenericIntegrityMessageWhenSqlStateIsUnrecognised()
    {
        // GIVEN
        BindingResult result = bindingResult();

        // WHEN
        ChapterController.rejectDuplicate(result, wrap("23502"));   // not-null violation

        // THEN
        ObjectError error = result.getGlobalError();
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo("integrity");
        assertThat(error.getDefaultMessage()).contains("check the values");
    }
}

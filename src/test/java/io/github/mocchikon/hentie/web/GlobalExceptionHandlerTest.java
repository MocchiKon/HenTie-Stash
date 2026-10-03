package io.github.mocchikon.hentie.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.LibraryBusyException;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tested directly because the pre-save checks stop every integration test before this net is reached. */
class GlobalExceptionHandlerTest
{
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new AppProperties());

    /** Throws what it was given, so Spring MVC, not the test, picks the handler. */
    @Controller
    static class FailingController
    {
        private final RuntimeException failure;

        FailingController(RuntimeException failure)
        {
            this.failure = failure;
        }

        @PostMapping("/fail")
        String fail()
        {
            throw failure;
        }
    }

    private MockMvc mvcThrowing(RuntimeException failure)
    {
        return MockMvcBuilders.standaloneSetup(new FailingController(failure)).setControllerAdvice(handler).build();
    }

    private static DataIntegrityViolationException wrap(String sqlState)
    {
        SQLException sql = new SQLException("boom", sqlState);
        return new DataIntegrityViolationException("constraint violation", new RuntimeException(sql));
    }

    @Test
    void shouldRenderErrorViewWithBadRequestAndLengthMessageWhenValueTooLong()
    {
        // GIVEN
        Model model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // WHEN
        String view = handler.handleDataIntegrity(wrap("22001"), model, response);

        // THEN
        assertThat(view).isEqualTo("error");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(model.getAttribute("message").toString()).contains("limited to 255 characters");
    }

    @Test
    void shouldRenderDuplicateMessageWhenUniqueViolation()
    {
        // GIVEN
        Model model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // WHEN
        handler.handleDataIntegrity(wrap("23505"), model, response);

        // THEN
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(model.getAttribute("message").toString())
                .contains("gallery ID", "already in use")
                .doesNotContainIgnoringCase("title");   // title_full is deliberately not unique
    }

    @Test
    void shouldFallBackToGenericMessageWhenSqlStateIsUnrecognised()
    {
        // GIVEN
        Model model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // WHEN
        handler.handleDataIntegrity(wrap("23502"), model, response);   // not-null violation

        // THEN
        assertThat(model.getAttribute("message").toString()).contains("check the values you entered");
    }

    /** A lock error can reach a controller wrapped in anything, and must never become a 500. */
    @Test
    void shouldAnswerALockErrorWrappedInAnyExceptionWithTheBusyPage() throws Exception
    {
        // GIVEN SQLITE_BUSY wrapped the way a lambda or a future passes it on.
        var wrapped = new IllegalStateException(new UncheckedIOException(new IOException(
                new SQLException("[SQLITE_BUSY] The database file is locked", null, 5))));
        Model model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // WHEN
        String view = handler.handleBusy(wrapped, new MockHttpServletRequest("POST", "/chapter/1"), response, model);

        // THEN
        assertThat(view).isEqualTo("error");
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(model.getAttribute("error").toString()).contains("in use by another program");
    }

    @Test
    void shouldAnswerAWrappedBusyRefusalWithItsOwnMessage() throws Exception
    {
        // GIVEN a refusal that a future passed on.
        var wrapped = new CompletionException(LibraryBusyException.otherProgram());

        // WHEN
        var answer = handler.handleBusyForScript(wrapped, new MockHttpServletRequest("POST", "/chapter/1/mark-reviewed"));

        // THEN
        assertThat(answer.getStatusCode().value()).isEqualTo(503);
        assertThat(answer.getBody().message()).isEqualTo(LibraryBusyException.otherProgram().getMessage());
    }

    /** Anything else keeps the handling it would get without this handler. */
    @Test
    void shouldPassOnAnExceptionThatIsNotAboutTheLibraryBeingBusy()
    {
        var failure = new IllegalStateException(new SQLException("disk I/O error", null, 10));
        Model model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> handler.handleBusy(failure, new MockHttpServletRequest("GET", "/"), response, model))
                .isSameAs(failure);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /** The handlers name no wrapper, so a lock error wrapped in anything reaches them through the cause chain. */
    @Test
    void shouldFindTheBusyAnswerThroughTheCauseChain() throws Exception
    {
        // GIVEN SQLITE_BUSY wrapped the way a lambda or a future passes it on.
        var wrapped = new IllegalStateException(new UncheckedIOException(new IOException(
                new SQLException("[SQLITE_BUSY] The database file is locked", null, 5))));

        // WHEN / THEN
        mvcThrowing(wrapped).perform(post("/fail"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));
        mvcThrowing(new CompletionException(LibraryBusyException.otherProgram()))
                .perform(post("/fail").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(LibraryBusyException.otherProgram().getMessage()));
    }

    /** An SQL error that is no lock error, and any other exception, fail as they would without these handlers. */
    @Test
    void shouldLeaveOtherFailuresToTheDefaultHandling()
    {
        var sqlFailure = new IllegalStateException(new SQLException("disk I/O error", null, 10));
        var otherFailure = new IllegalStateException("not about the database");

        assertThatThrownBy(() -> mvcThrowing(sqlFailure).perform(post("/fail")))
                .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(sqlFailure));
        assertThatThrownBy(() -> mvcThrowing(otherFailure).perform(post("/fail")))
                .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(otherFailure));
    }
}

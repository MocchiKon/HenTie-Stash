package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.LibraryBusyException;
import io.github.mocchikon.hentie.config.SqliteLocks;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.sql.SQLException;

/**
 * Server-side safety nets: length and uniqueness are normally checked before a save reaches the database, and a
 * write that could not get its turn is answered with 503, never 500.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AppProperties appProperties;

    @ExceptionHandler(DataIntegrityViolationException.class)
    public String handleDataIntegrity(DataIntegrityViolationException ex, Model model, HttpServletResponse response)
    {
        log.warn("Rejected a write that violated a data constraint: {}", ex.getMostSpecificCause().getMessage());
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("status", "Could not save");
        model.addAttribute("error", "Your changes could not be saved because they break a data rule.");
        model.addAttribute("message", friendlyMessage(ex));
        return "error";
    }

    private String friendlyMessage(DataIntegrityViolationException ex)
    {
        if (SqlStates.isValueTooLong(ex))
        {
            return "One of the text fields is too long. Titles and similar fields are limited to 255 characters.";
        }
        if (SqlStates.isUniqueViolation(ex))
        {
            return "A value that must be unique, such as a gallery ID, is already in use.";
        }
        return "Please check the values you entered and try again.";
    }

    /** What a {@code fetch()} that asked for JSON gets, so the script can say it and leave the page as it is. */
    public record BusyAnswer(String message, long retryAfterSeconds)
    {
    }

    /**
     * Also any lock error that reached a caller some other way: the gate leaves none, but a 500 would say the app is
     * broken. Spring matches either type anywhere in the cause chain, since both arrive wrapped (a JPA or Spring
     * exception, a future, an unchecked I/O wrapper). {@code ex} is the exception as thrown, so that a
     * {@link SQLException} that is not a lock error is passed on untouched to the handling it would get without this.
     *
     * @throws Exception {@code ex} itself when it is not about the library being busy
     */
    @ExceptionHandler({LibraryBusyException.class, SQLException.class})
    public String handleBusy(Exception ex, HttpServletRequest request, HttpServletResponse response, Model model)
            throws Exception
    {
        String message = busyMessage(ex, request);
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds()));
        model.addAttribute("status", "The library is busy");
        model.addAttribute("error", message);
        model.addAttribute("retryable", true);
        return "error";
    }

    /** @throws Exception {@code ex} itself when it is not about the library being busy */
    @ExceptionHandler(value = {LibraryBusyException.class, SQLException.class},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BusyAnswer> handleBusyForScript(Exception ex, HttpServletRequest request) throws Exception
    {
        String message = busyMessage(ex, request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds()))
                .body(new BusyAnswer(message, retryAfterSeconds()));
    }

    private static String busyMessage(Exception ex, HttpServletRequest request) throws Exception
    {
        LibraryBusyException busy = ExceptionUtils.throwableOfType(ex, LibraryBusyException.class);
        if (busy != null)
        {
            log.warn("Refused {} {}: {}", request.getMethod(), request.getRequestURI(), busy.getMessage());
            return busy.getMessage();
        }
        if (SqliteLocks.isLockError(ex))
        {
            // Only a writer outside the app can still cause one; the trace shows which statement met it.
            String message = LibraryBusyException.otherProgram().getMessage();
            log.warn("Refused {} {}: {}", request.getMethod(), request.getRequestURI(), message, ex);
            return message;
        }
        // Rethrowing the handled exception passes it on to the default error handling.
        throw ex;
    }

    /** As long as the request just waited: the holder has been busy at least that long. */
    private long retryAfterSeconds()
    {
        return Math.max(1, (appProperties.getWrites().getRequestWaitMillis() + 999) / 1000);
    }
}

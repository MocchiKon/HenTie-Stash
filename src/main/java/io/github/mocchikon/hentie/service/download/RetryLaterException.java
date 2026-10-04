package io.github.mocchikon.hentie.service.download;

import java.time.Instant;
import java.util.Optional;

/**
 * A failure that says nothing about the link (a tool being updated, say), so the worker runs the item again
 * without counting an attempt; otherwise every item would use up its attempts while the cause lasts. A source
 * throws it, so the worker needs to know nothing about what the source waits for.
 */
public class RetryLaterException extends RuntimeException
{
    /** When asking again makes sense, if the source knows (the end of a ban it sits out). */
    private final transient Instant retryAt;

    public RetryLaterException(String message, Throwable cause)
    {
        this(message, cause, null);
    }

    public RetryLaterException(String message, Throwable cause, Instant retryAt)
    {
        super(message, cause);
        this.retryAt = retryAt;
    }

    public Optional<Instant> retryAt()
    {
        return Optional.ofNullable(retryAt);
    }

    /** Under any wrapper, since a source or the pipeline may wrap what it throws. */
    public static boolean isIn(Throwable failure)
    {
        return find(failure).isPresent();
    }

    public static Optional<RetryLaterException> find(Throwable failure)
    {
        for (Throwable t = failure; t != null; t = t.getCause())
        {
            if (t instanceof RetryLaterException retryLater)
            {
                return Optional.of(retryLater);
            }
        }
        return Optional.empty();
    }
}

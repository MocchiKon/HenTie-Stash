package io.github.mocchikon.hentie.service.download;

/**
 * A failure that says nothing about the link (a tool being updated, say), so the worker runs the item again
 * without counting an attempt; otherwise every item would use up its attempts while the cause lasts. A source
 * throws it, so the worker needs to know nothing about what the source waits for.
 */
public class RetryLaterException extends RuntimeException
{
    public RetryLaterException(String message, Throwable cause)
    {
        super(message, cause);
    }

    /** Under any wrapper, since a source or the pipeline may wrap what it throws. */
    public static boolean isIn(Throwable failure)
    {
        for (Throwable t = failure; t != null; t = t.getCause())
        {
            if (t instanceof RetryLaterException)
            {
                return true;
            }
        }
        return false;
    }
}

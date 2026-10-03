package io.github.mocchikon.hentie.service.download;

/**
 * A failure retrying cannot fix (unknown link, missing gallery, metadata with no title or language), so the
 * item fails at once instead of using up its attempts. Any other exception is treated as transient.
 */
public class PermanentDownloadException extends RuntimeException
{
    public PermanentDownloadException(String message)
    {
        super(message);
    }

    public PermanentDownloadException(String message, Throwable cause)
    {
        super(message, cause);
    }
}

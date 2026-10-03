package io.github.mocchikon.hentie.scrapper;

/** A <b>permanent</b> failure, so the queue fails the item at once instead of retrying it. */
public class GalleryNotFoundException extends RuntimeException
{
    public GalleryNotFoundException(String message)
    {
        super(message);
    }

    public GalleryNotFoundException(String message, Throwable cause)
    {
        super(message, cause);
    }
}

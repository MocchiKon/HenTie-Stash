package io.github.mocchikon.hentie.service;

/**
 * Thrown by a pre-save uniqueness check. It names the form field, because the column cannot be read
 * reliably from a database error message (localized, engine-specific).
 */
public class DuplicateValueException extends RuntimeException
{
    private final String field;

    public DuplicateValueException(String field, String message)
    {
        super(message);
        this.field = field;
    }

    /** The {@code *Form} property to reject, e.g. {@code "galleryId"}. */
    public String getField()
    {
        return field;
    }
}

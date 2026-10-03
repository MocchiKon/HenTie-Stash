package io.github.mocchikon.hentie.scrapper.gallerydl;

import io.github.mocchikon.hentie.scrapper.GalleryNotFoundException;
import io.github.mocchikon.hentie.service.download.PermanentDownloadException;
import io.github.mocchikon.hentie.service.download.RetryLaterException;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A gallery-dl run that failed in a way a source or the pipeline must tell apart. The kinds come from gallery-dl's
 * exit status bits and its wording ({@link GalleryDl#classify}), the only things it reports.
 */
public class GalleryDlException extends IOException
{
    public enum Kind
    {
        /** The site has no such gallery. */
        NOT_FOUND,
        /** e-hentai banned this IP for a while ("Temporarily Banned"). */
        BANNED,
        /** e-hentai's image limit is used up ("Image limit exceeded"). */
        IMAGE_LIMIT,
        /** Originals need GP the account does not have. */
        NO_GP,
        /** The site refused the account or its cookies (exit bit 16). */
        REFUSED,
        /** The site showed a browser check, e.g. Cloudflare's (exit bit 8). */
        CHALLENGE,
        /** gallery-dl does not recognize the address (exit bits 32, 64). */
        UNSUPPORTED,
        /** Writing a file failed (exit bit 128). Never a page to skip: the next one would fail the same way. */
        WRITE,
        /** gallery-dl could not be started at all. */
        NOT_RUNNABLE,
        /** gallery-dl is being updated. */
        UPDATING,
        /**
         * gallery-dl was killed or crashed, so it reported nothing of its own. Never a page to skip: every page
         * after it would fail the same way.
         */
        ABORTED,
        /** Anything else: a network failure, a page that would not download. */
        OTHER
    }

    @Getter
    @Accessors(fluent = true)
    private final Kind kind;

    public GalleryDlException(Kind kind, String message)
    {
        super(message);
        this.kind = kind;
    }

    public GalleryDlException(Kind kind, String message, Throwable cause)
    {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * What the download worker should make of it when the source has nothing to add: retrying cannot help the
     * permanent kinds, so they fail the item at once instead of using up its attempts.
     */
    public RuntimeException forWorker()
    {
        return switch (kind)
        {
            case NOT_FOUND -> new GalleryNotFoundException(getMessage());
            case BANNED, IMAGE_LIMIT, NO_GP, REFUSED, CHALLENGE, UNSUPPORTED, NOT_RUNNABLE ->
                    new PermanentDownloadException(getMessage(), this);
            // An update takes a minute or so; counting the refusals would fail every gallery-dl item meanwhile.
            case UPDATING -> new RetryLaterException(getMessage(), this);
            case WRITE, ABORTED, OTHER -> new UncheckedIOException(getMessage(), this);
        };
    }
}

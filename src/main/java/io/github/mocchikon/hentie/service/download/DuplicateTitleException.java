package io.github.mocchikon.hentie.service.download;

/**
 * The duplicate-title check refused a gallery. Permanent because retrying with the check on refuses it again,
 * and a rejection rather than a failure because nothing about the download went wrong: the queue lists it
 * under "Rejected duplicates", where the only useful actions are to check again or to allow the title.
 */
public class DuplicateTitleException extends PermanentDownloadException
{
    private final Integer chapterId;

    /**
     * @param chapterId the chapter holding the title, or null when it is a past download's chapter that is gone
     */
    public DuplicateTitleException(String message, Integer chapterId)
    {
        super(message);
        this.chapterId = chapterId;
    }

    /** A chapter in the app the rejected duplicate can link to, or null when there is none. */
    public Integer chapterId()
    {
        return chapterId;
    }
}

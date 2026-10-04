package io.github.mocchikon.hentie.service.download;

/**
 * Progress is per batch, not a lifetime total: successful rows are deleted, so a lifetime total cannot be
 * counted.
 *
 * @param done      items finished (succeeded or given up) since the queue was last empty
 * @param total     {@code done} plus everything that runs now; grows if links are queued mid-run
 * @param pending   includes the item being worked on, and rows waiting for their site's ban to end
 * @param runnable  {@code pending} without the rows waiting for a ban: a batch cannot finish them
 * @param currentLink null between items
 * @param currentPhase null between items
 */
public record DownloadProgress(int done, long total, long pending, long runnable, long failed,
                               String currentLink, String currentPhase, boolean paused)
{
    /** Drives the queue page's auto-refresh; rows waiting hours for a ban are nothing to watch. */
    public boolean isActive()
    {
        return runnable > 0;
    }
}

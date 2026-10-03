package io.github.mocchikon.hentie.entity;

/**
 * Decides what a download re-run may touch. A page count cannot tell "download cut short" from "user deleted
 * a page", and those need opposite treatment.
 * <ul>
 *     <li>{@link #NONE} - not from the pipeline; its images are never touched.</li>
 *     <li>{@link #PENDING} - the only state that lets the pipeline back in, and only for absent pages.</li>
 *     <li>{@link #SUCCESSFUL} - pages are the user's from now on, so re-queueing is a no-op. Includes a
 *     chapter finished by "retry ignoring image errors".</li>
 * </ul>
 * ORDINAL: never reorder. {@code NONE} is first so the column's {@code DEFAULT 0} means "not from a download".
 */
public enum DownloadStatus
{
    NONE,
    PENDING,
    SUCCESSFUL
}

package io.github.mocchikon.hentie.scrapper;

/**
 * A source's site accepted the account from Settings, after it had not yet, or had refused it. Published by the
 * source, so the downloads that failed for want of a login can run again.
 *
 * @param sourcePrefix the source's {@link DataDownloader#sourcePrefix}
 */
public record LoginAccepted(String sourcePrefix)
{
}

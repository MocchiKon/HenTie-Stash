package io.github.mocchikon.hentie.service.download;

/**
 * The site shows the gallery only to an account it accepts, and it accepted none. Permanent, because every attempt
 * would end the same until the account changes; marked on the row ({@code needs_login}) so it goes back to waiting
 * once the site accepts the account ({@link io.github.mocchikon.hentie.scrapper.LoginAccepted}), without the user
 * having to find and retry it.
 */
public class LoginRequiredException extends PermanentDownloadException
{
    public LoginRequiredException(String message, Throwable cause)
    {
        super(message, cause);
    }
}

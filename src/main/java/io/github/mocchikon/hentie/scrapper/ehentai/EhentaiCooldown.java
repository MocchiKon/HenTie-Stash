package io.github.mocchikon.hentie.scrapper.ehentai;

import io.github.mocchikon.hentie.service.download.PermanentDownloadException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Stops asking e-hentai for a while after it banned this IP or the image limit ran out: requests during a ban can
 * extend it, and every item would only fail the same way. In memory only: a restart is the user's way past it.
 */
final class EhentaiCooldown
{
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private volatile Instant until;
    private volatile String reason;

    /** @throws PermanentDownloadException while cooling down, saying why and until when */
    void check()
    {
        Instant end = until;
        if (end != null && Instant.now().isBefore(end))
        {
            throw new PermanentDownloadException(reason + " - e-hentai is not asked again until " + at(end)
                    + "; retry this item from the Failed list after that.");
        }
    }

    /** @return the message for the item that found out */
    String start(String why, Duration length)
    {
        Instant end = Instant.now().plus(length);
        until = end;
        reason = why;
        return why + " - e-hentai items are not attempted until " + at(end)
                + "; retry them from the Failed list after that.";
    }

    void clear()
    {
        until = null;
        reason = null;
    }

    private static String at(Instant instant)
    {
        return LocalTime.ofInstant(instant, ZoneId.systemDefault()).format(TIME);
    }
}

package io.github.mocchikon.hentie.scrapper.ehentai;

import io.github.mocchikon.hentie.service.download.PermanentDownloadException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * Stops asking e-hentai for a while after it banned this IP or the image limit ran out: requests during a ban can
 * extend it, and every item would only fail the same way. In memory only: a restart is the user's way past it.
 */
final class EhentaiCooldown
{
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** One value, so a reader never sees the end of one cooldown with the reason of another. */
    private record Cooling(Instant until, String reason)
    {
    }

    private volatile Cooling cooling;

    /** @throws PermanentDownloadException while cooling down, saying why and until when */
    void check()
    {
        Cooling now = cooling;
        if (now != null && Instant.now().isBefore(now.until()))
        {
            throw new PermanentDownloadException(now.reason() + " - e-hentai is not asked again until "
                    + at(now.until()) + "; retry this item from the Failed list after that.");
        }
    }

    /** When the cooldown ends; empty while none runs. */
    Optional<Instant> until()
    {
        Cooling now = cooling;
        return now != null && Instant.now().isBefore(now.until()) ? Optional.of(now.until()) : Optional.empty();
    }

    /**
     * For a search, which is no item to retry from the Failed list; empty while none runs.
     *
     * @return the message, and when asking again makes sense
     */
    Optional<Map.Entry<String, Instant>> searchRefusal()
    {
        Cooling now = cooling;
        if (now == null || !Instant.now().isBefore(now.until()))
        {
            return Optional.empty();
        }
        return Optional.of(Map.entry(now.reason() + " - e-hentai is not searched again until " + at(now.until())
                + ".", now.until()));
    }

    /** @return the message for the item that found out */
    String start(String why, Duration length)
    {
        Instant end = Instant.now().plus(length);
        cooling = new Cooling(end, why);
        return why + " - e-hentai items are not attempted until " + at(end)
                + "; retry them from the Failed list after that.";
    }

    void clear()
    {
        cooling = null;
    }

    private static String at(Instant instant)
    {
        return LocalTime.ofInstant(instant, ZoneId.systemDefault()).format(TIME);
    }
}

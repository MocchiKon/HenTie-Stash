package io.github.mocchikon.hentie.service.subscription;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sites whose search asked the runner to wait ({@code RetryLaterException}), and until when. In memory, since a restart
 * is the user's way past it. A bean of its own because the page needs it too: a subscription whose site waits holds
 * back no other ({@link SubscriptionService.Turn}). A source sitting out a ban needs no entry:
 * {@code SearchSource#refusingUntil} holds all its sites.
 */
@Component
public class ParkedSites
{
    private final Map<String, Instant> until = new ConcurrentHashMap<>();

    public void park(String site, Instant until)
    {
        this.until.put(site, until);
    }

    public boolean isParked(String site)
    {
        Instant end = until.get(site);
        return end != null && Instant.now().isBefore(end);
    }

    public void clear()
    {
        until.clear();
    }
}

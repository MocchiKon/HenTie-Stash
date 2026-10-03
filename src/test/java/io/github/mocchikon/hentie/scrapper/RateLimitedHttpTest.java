package io.github.mocchikon.hentie.scrapper;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** How long a 429 asks to wait. Sites say it in several ways, and a wrong reading either hammers or stalls. */
class RateLimitedHttpTest
{
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    @Test
    void shouldReadRetryAfterInSecondsOrAsADate()
    {
        assertThat(RateLimitedHttp.retryAfter(headers("Retry-After", "17"), NOW)).contains(Duration.ofSeconds(17));
        assertThat(RateLimitedHttp.retryAfter(headers("Retry-After", "Fri, 02 Oct 2026 12:00:30 GMT"), NOW))
                .contains(Duration.ofSeconds(30));
        // A date already past means "now", never a negative wait.
        assertThat(RateLimitedHttp.retryAfter(headers("Retry-After", "Fri, 02 Oct 2026 11:00:00 GMT"), NOW))
                .contains(Duration.ZERO);
    }

    @Test
    void shouldReadARateLimitResetAsSecondsToWaitOrAsAnEpochTime()
    {
        assertThat(RateLimitedHttp.retryAfter(headers("X-RateLimit-Reset", "42"), NOW)).contains(Duration.ofSeconds(42));
        assertThat(RateLimitedHttp.retryAfter(headers("X-RateLimit-Reset", Long.toString(NOW.getEpochSecond() + 20)), NOW))
                .contains(Duration.ofSeconds(20));
        assertThat(RateLimitedHttp.retryAfter(headers("RateLimit-Reset", Long.toString(NOW.toEpochMilli() + 5_000)), NOW))
                .contains(Duration.ofSeconds(5));
    }

    @Test
    void shouldSayNothingWhenNoHeaderTellsHowLongToWait()
    {
        assertThat(RateLimitedHttp.retryAfter(headers("Content-Type", "application/json"), NOW)).isEmpty();
        assertThat(RateLimitedHttp.retryAfter(headers("Retry-After", "soon"), NOW)).isEmpty();
    }

    private static HttpHeaders headers(String name, String value)
    {
        return HttpHeaders.of(Map.of(name, List.of(value)), (n, v) -> true);
    }
}

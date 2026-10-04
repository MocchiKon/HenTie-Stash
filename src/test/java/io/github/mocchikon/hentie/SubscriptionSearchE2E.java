package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.scrapper.SearchSource.SearchPage;
import io.github.mocchikon.hentie.scrapper.ehentai.EhentaiDownloader;
import io.github.mocchikon.hentie.scrapper.ehentai.EhentaiProperties;
import io.github.mocchikon.hentie.scrapper.nhentai.NhentaiDownloader;
import io.github.mocchikon.hentie.scrapper.nhentai.NhentaiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subscriptions' searches against the real sites, with their real rate limits: what {@code SubscriptionRunnerIT} checks
 * against fakes, checked against the sites themselves - above all what the walk relies on: nhentai's
 * {@code uploaded:>Nh} filter and its upload times, and e-hentai's {@code next=<gid>}. Needs the internet, so it runs
 * only in the {@code e2e} profile:
 * <pre>
 * ./mvnw test -Pe2e
 * </pre>
 */
@SpringBootTest
class SubscriptionSearchE2E
{
    @Autowired NhentaiDownloader nhentai;
    @Autowired EhentaiDownloader ehentai;
    @Autowired NhentaiProperties nhentaiProperties;
    @Autowired EhentaiProperties ehentaiProperties;

    /** The test profile points the sources nowhere and lifts their limits; this suite wants the real thing. */
    private final NhentaiProperties nhentaiTestProfile = new NhentaiProperties();
    private final EhentaiProperties ehentaiTestProfile = new EhentaiProperties();

    @BeforeEach
    void useTheRealSites()
    {
        copy(nhentaiProperties, nhentaiTestProfile);
        copy(new NhentaiProperties(), nhentaiProperties);
        copy(ehentaiProperties, ehentaiTestProfile);
        copy(new EhentaiProperties(), ehentaiProperties);
    }

    @AfterEach
    void restore()
    {
        copy(nhentaiTestProfile, nhentaiProperties);
        copy(ehentaiTestProfile, ehentaiProperties);
        ehentai.clearCooldown();
    }

    /**
     * A cursor years old is counted in days, which keep up to a day of galleries above it: the page after it must
     * still start exactly where the list goes on.
     */
    @Test
    void shouldContinueNhentaisSearchExactlyBelowAPageYearsOld()
    {
        // GIVEN English galleries of about three years ago
        String query = nhentai.normalizedQuery("nhentai", "language:english");
        SearchPage old = nhentai.search("nhentai", query + " uploaded:>1000d", null);
        List<String> allButLast = old.resourceIds().subList(0, old.resourceIds().size() - 1);

        // WHEN the page after them, and the page after all of them but the last
        String cursor = nhentai.cursorAfter("nhentai", null, old.resourceIds());
        SearchPage next = nhentai.search("nhentai", query, cursor);
        SearchPage overlapping = nhentai.search("nhentai", query, nhentai.cursorAfter("nhentai", null, allButLast));

        // THEN the second starts with the last of them, then goes on like the first, as far as both reach
        var expected = new ArrayList<>(ids(next));
        expected.add(Long.parseLong(old.resourceIds().getLast()));
        expected.sort(Comparator.reverseOrder());
        int compared = Math.min(expected.size(), overlapping.resourceIds().size());
        assertThat(old.resourceIds()).hasSize(25);
        assertThat(ids(next)).isNotEmpty().isSortedAccordingTo(Comparator.reverseOrder()).doesNotHaveDuplicates()
                .doesNotContainAnyElementsOf(ids(old)).allMatch(id -> id < cut(cursor));
        assertThat(ids(overlapping).subList(0, compared)).isEqualTo(expected.subList(0, compared));
    }

    /**
     * Galleries 5783 to 5786 were uploaded in the same second and nhentai lists them in no order, so a page ending
     * with 5786 and 5785 may have 5784 and 5783 on the next: the cursor stands above the second, not below 5785.
     */
    @Test
    void shouldPutTheCursorAboveTheSecondItsPageEndedIn()
    {
        // WHEN
        String cursor = nhentai.cursorAfter("nhentai", null, List.of("5787", "5786", "5785"));

        // THEN below 5787, uploaded at 17:01:05, leaving out only the two of 17:01:04 the page had
        assertThat(cursor).isEqualTo("5787@1403974864:5785,5786");
    }

    @Test
    void shouldWalkNhentaisNewestPagesByCursor()
    {
        // GIVEN
        String query = nhentai.normalizedQuery("nhentai", "language:english");

        // WHEN
        SearchPage first = nhentai.search("nhentai", query, null);
        String cursor = nhentai.cursorAfter("nhentai", null, first.resourceIds());
        SearchPage second = nhentai.search("nhentai", query, cursor);

        // THEN
        assertThat(first.resourceIds()).hasSize(25);
        assertThat(first.more()).isTrue();
        assertThat(first.total()).isGreaterThan(1000L);
        assertThat(ids(first)).isSortedAccordingTo(Comparator.reverseOrder()).doesNotHaveDuplicates();
        assertThat(second.resourceIds()).isNotEmpty();
        assertThat(ids(second)).isSortedAccordingTo(Comparator.reverseOrder()).doesNotContainAnyElementsOf(ids(first))
                .allMatch(id -> id < cut(cursor));
    }

    @Test
    void shouldWalkEhentaisSearchExactlyBelowItsCursor()
    {
        // GIVEN
        String query = ehentai.normalizedQuery("e-hentai", "f_search=language:english$");

        // WHEN the first page, the page after its last gallery, and the page after its second-to-last
        SearchPage first = ehentai.search("e-hentai", query, null);
        SearchPage second = ehentai.search("e-hentai", query,
                ehentai.cursorAfter("e-hentai", null, first.resourceIds()));
        SearchPage overlapping = ehentai.search("e-hentai", query,
                ehentai.cursorAfter("e-hentai", null, first.resourceIds().subList(0, first.resourceIds().size() - 1)));

        // THEN the cursor names exactly where the list goes on, whatever page sizes the site uses
        assertThat(first.resourceIds()).hasSizeGreaterThanOrEqualTo(25);
        assertThat(first.more()).isTrue();
        assertThat(first.total()).isGreaterThan(1000L);
        assertThat(positions(first)).isSortedAccordingTo(Comparator.reverseOrder()).doesNotHaveDuplicates();
        assertThat(overlapping.resourceIds().getFirst()).isEqualTo(first.resourceIds().getLast());
        assertThat(overlapping.resourceIds().get(1)).isEqualTo(second.resourceIds().getFirst());
    }

    /** The id an nhentai cursor lists below. */
    private static long cut(String cursor)
    {
        return Long.parseLong(cursor.substring(0, cursor.indexOf('@')));
    }

    private static List<Long> ids(SearchPage page)
    {
        return page.resourceIds().stream().map(Long::parseLong).toList();
    }

    private List<Long> positions(SearchPage page)
    {
        return page.resourceIds().stream().map(ehentai::position).toList();
    }

    private static void copy(NhentaiProperties from, NhentaiProperties to)
    {
        to.setBaseUrl(from.getBaseUrl());
        to.setUserAgent(from.getUserAgent());
        to.setGalleryRequestsPerMinute(from.getGalleryRequestsPerMinute());
        to.setGalleryRequestsPerMinuteWithKey(from.getGalleryRequestsPerMinuteWithKey());
        to.setFavouritesRequestsPerMinute(from.getFavouritesRequestsPerMinute());
        to.setSearchRequestsPerMinute(from.getSearchRequestsPerMinute());
        to.setSearchRequestsPerMinuteWithKey(from.getSearchRequestsPerMinuteWithKey());
        to.setImageRequestIntervalMillis(from.getImageRequestIntervalMillis());
        to.setRequestTimeoutSeconds(from.getRequestTimeoutSeconds());
    }

    private static void copy(EhentaiProperties from, EhentaiProperties to)
    {
        to.setApiUrl(from.getApiUrl());
        to.setApiRequestIntervalMillis(from.getApiRequestIntervalMillis());
        to.setEhentaiUrl(from.getEhentaiUrl());
        to.setExhentaiUrl(from.getExhentaiUrl());
        to.setSearchRequestIntervalMillis(from.getSearchRequestIntervalMillis());
        to.setBrowserUserAgent(from.getBrowserUserAgent());
        to.setRequestTimeoutSeconds(from.getRequestTimeoutSeconds());
        to.setCooldownMinutes(from.getCooldownMinutes());
    }
}

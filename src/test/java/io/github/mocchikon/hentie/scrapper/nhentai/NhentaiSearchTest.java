package io.github.mocchikon.hentie.scrapper.nhentai;

import io.github.mocchikon.hentie.FakeNhentai;
import io.github.mocchikon.hentie.scrapper.SearchSource.SearchPage;
import io.github.mocchikon.hentie.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A subscription's walk through nhentai's search, against {@link FakeNhentai}: the walk must continue below its cursor
 * exactly, however the search changed between two requests, because nhentai offers page numbers only.
 */
class NhentaiSearchTest
{
    private final NhentaiProperties properties = new NhentaiProperties();
    private final SettingsService settingsService = mock(SettingsService.class);
    private FakeNhentai site;
    private NhentaiDownloader downloader;
    /** The fake's clock is the real one: the JDK's server sends its own {@code Date} header. */
    private Instant now;

    @BeforeEach
    void setUp() throws IOException
    {
        now = Instant.now();
        site = FakeNhentai.start();
        properties.setBaseUrl(site.baseUrl());
        properties.setGalleryRequestsPerMinute(0);
        properties.setGalleryRequestsPerMinuteWithKey(0);
        properties.setSearchRequestsPerMinute(0);
        properties.setSearchRequestsPerMinuteWithKey(0);
        when(settingsService.getNhentaiApiKey()).thenReturn("");
        downloader = new NhentaiDownloader(properties, settingsService);
    }

    @AfterEach
    void tearDown()
    {
        downloader.close();
        site.close();
    }

    /**
     * {@code count} galleries from {@code firstId} up, the newest uploaded {@code newestAge} and half an hour ago, an
     * hour apart: half an hour off the whole hours nhentai's filter counts in, so no gallery sits on its edge.
     */
    private void galleries(long firstId, int count, Duration newestAge)
    {
        Instant newest = now.minus(newestAge).minus(Duration.ofMinutes(30));
        for (int i = 0; i < count; i++)
        {
            site.searchable(firstId + i, newest.minus(Duration.ofHours(count - 1 - i)).getEpochSecond());
        }
    }

    @Test
    void shouldListTheNewestPageFirst()
    {
        // GIVEN
        galleries(1000, 60, Duration.ofHours(5));

        // WHEN
        SearchPage page = downloader.search("nhentai", "language:english", null);

        // THEN
        assertThat(page.resourceIds()).hasSize(25).first().isEqualTo("1059");
        assertThat(page.resourceIds().getLast()).isEqualTo("1035");
        assertThat(page.more()).isTrue();
        assertThat(page.total()).isEqualTo(60L);
        assertThat(site.searches()).containsExactly("language:english page=1");
    }

    /**
     * The cursor carries the gallery's upload time, and the search narrowed to what is older lists the galleries after
     * it on its first page.
     */
    @Test
    void shouldContinueBelowTheCursorWithOneSearch()
    {
        // GIVEN
        galleries(1000, 60, Duration.ofHours(5));
        SearchPage first = downloader.search("nhentai", "language:english", null);

        // WHEN
        String cursor = downloader.cursorAfter("nhentai", null, first.resourceIds());
        SearchPage next = downloader.search("nhentai", "language:english", cursor);

        // THEN the cursor stands above the second gallery 1035 was uploaded in, 29.5 hours ago, and names 1035 as had;
        // the search narrowed to that second and older starts with 1035, then come the 24 after it
        long uploaded = now.minus(Duration.ofHours(29)).minus(Duration.ofMinutes(30)).getEpochSecond();
        assertThat(cursor).isEqualTo("1036@" + uploaded + ":1035");
        assertThat(next.resourceIds()).hasSize(24).first().isEqualTo("1034");
        assertThat(site.searches()).containsExactly("language:english page=1", "language:english uploaded:>29h page=1");
        assertThat(next.total()).as("a narrowed search does not count the whole one").isNull();
    }

    @Test
    void shouldWalkEveryGalleryExactlyOnceWhileTheSearchChangesBetweenEveryTwoRequests()
    {
        // GIVEN 500 galleries, and a site that uploads, deletes and newly tags galleries before every search
        galleries(10_000, 500, Duration.ofHours(3));
        Set<Long> deleted = new HashSet<>();
        var random = new Random(42);
        long[] nextUpload = {20_000};
        site.beforeEachSearch(() ->
        {
            for (int i = 0; i < 3; i++)
            {
                site.searchable(nextUpload[0]++, Instant.now().getEpochSecond());
            }
            List<Long> present = site.searchableIds().stream().filter(id -> id < 20_000).toList();
            long victim = present.get(random.nextInt(present.size()));
            deleted.add(victim);
            site.unlist(victim);
            // An old gallery that only now matches: never a duty of the walk, but it must not upset it.
            site.searchable(5_000 + random.nextInt(4_000), now.minus(Duration.ofDays(400)).getEpochSecond());
        });

        // WHEN
        List<Long> walked = walk();

        // THEN
        var presentThroughout = new ArrayList<Long>();
        for (long id = 10_000; id < 10_500; id++)
        {
            if (!deleted.contains(id))
            {
                presentThroughout.add(id);
            }
        }
        assertThat(walked).doesNotHaveDuplicates();
        assertThat(walked).containsAll(presentThroughout);
        assertThat(walked.stream().filter(id -> id >= 10_000).toList()).isSortedAccordingTo(Comparator.reverseOrder());
    }

    /**
     * Galleries uploaded in the same second come in any order, so a page can end with the lowest ids of a second
     * whose higher ones are on the next page: below the page's lowest id they would be passed over for good.
     */
    @Test
    void shouldGoOnWithTheRestOfTheSecondItsPageEndedIn()
    {
        // GIVEN 22 galleries an hour apart, then six uploaded in one second, listed lowest id first, then 20 older
        galleries(2000, 22, Duration.ofHours(5));
        long second = now.minus(Duration.ofHours(30)).getEpochSecond();
        for (long id = 1990; id < 1996; id++)
        {
            site.searchable(id, second);
        }
        galleries(1900, 20, Duration.ofHours(40));
        site.listTiesLowestFirst();
        SearchPage first = downloader.search("nhentai", "q", null);

        // WHEN
        String cursor = downloader.cursorAfter("nhentai", null, first.resourceIds());
        SearchPage next = downloader.search("nhentai", "q", cursor);

        // THEN the first page ends with 1992, 1991 and 1990; the next one starts with the rest of their second
        assertThat(first.resourceIds()).endsWith("1992", "1991", "1990");
        assertThat(cursor).isEqualTo("2000@" + second + ":1990,1991,1992");
        assertThat(next.resourceIds()).startsWith("1995", "1994", "1993", "1919");
    }

    /** A page holding nothing but one second leaves the cursor above it, adding the page to what it had. */
    @Test
    void shouldWalkThroughASecondLongerThanAPage()
    {
        // GIVEN five galleries an hour apart, 40 uploaded in one second, in no order of their ids, and ten older ones
        galleries(3040, 5, Duration.ofHours(5));
        long second = now.minus(Duration.ofHours(20)).getEpochSecond();
        for (long id = 3000; id < 3040; id++)
        {
            site.searchable(id, second);
        }
        galleries(2990, 10, Duration.ofHours(30));

        // WHEN
        List<Long> walked = walk();

        // THEN
        assertThat(walked).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(site.searchableIds());
    }

    @Test
    void shouldRefuseToGoOnWhenIdsGoAgainstTheOrderOfUploads()
    {
        // GIVEN gallery 501 uploaded before gallery 500
        site.searchable(501, now.minus(Duration.ofHours(10)).getEpochSecond());
        site.searchable(500, now.minus(Duration.ofHours(5)).getEpochSecond());

        // WHEN + THEN
        assertThatThrownBy(() -> downloader.cursorAfter("nhentai", null, List.of("501", "500")))
                .isInstanceOf(UncheckedIOException.class).hasMessageContaining("501")
                .hasMessageContaining("against the order of their numbers");
    }

    @Test
    void shouldResumeAfterAYearWithoutSeeking()
    {
        // GIVEN a walk paused at gallery 1035 a year ago, and a year of uploads since
        galleries(1000, 60, Duration.ofDays(400));
        String cursor = downloader.cursorAfter("nhentai", null, List.of("1035"));
        for (long id = 5000; id < 9000; id++)
        {
            site.searchable(id, now.minus(Duration.ofDays(365)).plusSeconds((id - 5000) * 7800).getEpochSecond());
        }
        int searchesBefore = site.searches().size();

        // WHEN
        SearchPage page = downloader.search("nhentai", "q", cursor);

        // THEN counted in days, which keep up to a day above the cursor: here the page of galleries an hour apart
        assertThat(page.resourceIds()).first().isEqualTo("1034");
        assertThat(site.searches().subList(searchesBefore, site.searches().size()))
                .hasSizeLessThanOrEqualTo(2).allMatch(search -> search.startsWith("q uploaded:>401d"));
    }

    /** Too recent for the filter (nhentai cannot say "more than 1 hour ago"): read from the newest page down. */
    @Test
    void shouldFindAYoungCursorFromTheNewestPage()
    {
        // GIVEN 40 galleries uploaded in the last minutes
        for (long id = 1; id <= 40; id++)
        {
            site.searchable(id, now.minusSeconds(60 * (41 - id)).getEpochSecond());
        }
        String cursor = downloader.cursorAfter("nhentai", null, List.of("30"));

        // WHEN
        SearchPage page = downloader.search("nhentai", "q", cursor);

        // THEN
        assertThat(page.resourceIds()).first().isEqualTo("29");
        assertThat(page.resourceIds()).last().isEqualTo("16");
        assertThat(site.searches()).containsExactly("q page=1");
    }

    @Test
    void shouldPassOverAGalleryGoneSinceItWasListed()
    {
        // GIVEN
        galleries(1000, 30, Duration.ofHours(5));
        site.unlist(1005);

        // WHEN
        String cursor = downloader.cursorAfter("nhentai", null, List.of("1007", "1006", "1005"));

        // THEN above gallery 1006's second, which the gone gallery was uploaded no later than
        long uploaded = now.minus(Duration.ofHours(5 + 23)).minus(Duration.ofMinutes(30)).getEpochSecond();
        assertThat(cursor).isEqualTo("1007@" + uploaded + ":1005,1006");
    }

    /** Every page from the newest down, each from the cursor the page before it left. */
    private List<Long> walk()
    {
        var walked = new ArrayList<Long>();
        String cursor = null;
        SearchPage page = downloader.search("nhentai", "q", null);
        page.resourceIds().forEach(id -> walked.add(Long.parseLong(id)));
        while (page.more())
        {
            cursor = downloader.cursorAfter("nhentai", cursor, page.resourceIds());
            page = downloader.search("nhentai", "q", cursor);
            page.resourceIds().forEach(id -> walked.add(Long.parseLong(id)));
        }
        return walked;
    }

    @Test
    void shouldSkipWhatNhentaiMarksBlacklisted()
    {
        // GIVEN
        site.searchable(2, now.minusSeconds(10).getEpochSecond(), true);
        site.searchable(1, now.minusSeconds(20).getEpochSecond());

        // WHEN
        SearchPage page = downloader.search("nhentai", "q", null);

        // THEN
        assertThat(page.resourceIds()).containsExactly("2", "1");
        assertThat(page.blacklisted()).containsExactly("2");
    }

    @Test
    void shouldFailRatherThanEndOnAnAnswerItCannotRead()
    {
        // GIVEN
        site.failNext("/api/v2/search", 500);

        // WHEN + THEN
        assertThatThrownBy(() -> downloader.search("nhentai", "q", null)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void shouldTakeTheQueryOfAPastedSearchAndRefuseItsOwnFilter()
    {
        // WHEN + THEN
        assertThat(downloader.normalizedQuery("nhentai",
                "https://nhentai.net/search/?q=tag%3A%22big+breasts%22+language%3Aenglish&sort=popular"))
                .isEqualTo("tag:\"big breasts\" language:english");
        assertThat(downloader.normalizedQuery("nhentai", "  artist:someone   language:english "))
                .isEqualTo("artist:someone language:english");
        assertThatThrownBy(() -> downloader.normalizedQuery("nhentai", "artist:x uploaded:<7d"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("uploaded:");
        assertThatThrownBy(() -> downloader.normalizedQuery("nhentai", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** nhentai would search for the address itself, and the subscription would never find anything. */
    @Test
    void shouldRefuseTheAddressOfAPageThatIsNoSearch()
    {
        // WHEN + THEN
        assertThatThrownBy(() -> downloader.normalizedQuery("nhentai", "https://nhentai.net/artist/shindol/"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("address of a search");
        assertThatThrownBy(() -> downloader.normalizedQuery("nhentai", "nhentai.net/tag/big-breasts/?page=2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("address of a search");
    }
}

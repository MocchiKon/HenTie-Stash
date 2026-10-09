package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.SubscriptionForm;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.DownloadedGalleryRepository;
import io.github.mocchikon.hentie.repository.SubscriptionCheckpointRepository;
import io.github.mocchikon.hentie.repository.SubscriptionRepository;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.ehentai.EhentaiDownloader;
import io.github.mocchikon.hentie.scrapper.ehentai.EhentaiProperties;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.scrapper.nhentai.NhentaiProperties;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.download.DownloadChoices;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.subscription.SubscriptionRunner;
import io.github.mocchikon.hentie.service.subscription.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

/**
 * Subscriptions end to end against {@link FakeNhentai} and {@link FakeEhentai}, stepped by hand
 * ({@link SubscriptionRunner#runNext()}) as the download suites step the worker.
 * <p>
 * Not {@code @Transactional}: every step commits on its own, which is what is tested, so each test removes what it
 * made.
 */
@SpringBootTest
class SubscriptionRunnerIT
{
    private static final String PLAIN = BuiltInCompressionMode.NONE.getKey();

    @Autowired SubscriptionRunner runner;
    @Autowired SubscriptionService subscriptionService;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired SubscriptionCheckpointRepository checkpointRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired DownloadQueueService queueService;
    @Autowired DownloadedGalleryRepository downloadedGalleryRepository;
    @Autowired ChapterService chapterService;
    @Autowired NhentaiProperties nhentaiProperties;
    @Autowired EhentaiProperties ehentaiProperties;
    @Autowired EhentaiDownloader ehentaiDownloader;
    @Autowired SettingsService settingsService;
    @Autowired AppProperties appProperties;
    @Autowired DataDownloaderRegistry registry;
    @Autowired PlatformTransactionManager transactionManager;

    private FakeNhentai nhentai;
    private FakeEhentai ehentai;
    private String nhentaiUrlBefore;
    private String ehentaiUrlBefore;
    private String exhentaiUrlBefore;
    private int queueAheadBefore;
    private int failedLimitBefore;
    private final List<Integer> chapters = new ArrayList<>();
    private final List<String> downloaded = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException
    {
        nhentai = FakeNhentai.start();
        ehentai = FakeEhentai.start();
        nhentaiUrlBefore = nhentaiProperties.getBaseUrl();
        ehentaiUrlBefore = ehentaiProperties.getEhentaiUrl();
        exhentaiUrlBefore = ehentaiProperties.getExhentaiUrl();
        queueAheadBefore = appProperties.getSubscriptions().getQueueAhead();
        failedLimitBefore = appProperties.getSubscriptions().getFailedLimit();
        nhentaiProperties.setBaseUrl(nhentai.baseUrl());
        ehentaiProperties.setEhentaiUrl(ehentai.ehentaiUrl());
        ehentaiProperties.setExhentaiUrl(ehentai.exhentaiUrl());
        queueRepository.deleteAll();
        subscriptionRepository.deleteAll();
    }

    @AfterEach
    void tearDown()
    {
        subscriptionRepository.deleteAll();
        queueRepository.deleteAll();
        chapters.forEach(chapterService::delete);
        downloaded.forEach(downloadedGalleryRepository::deleteById);
        nhentaiProperties.setBaseUrl(nhentaiUrlBefore);
        ehentaiProperties.setEhentaiUrl(ehentaiUrlBefore);
        ehentaiProperties.setExhentaiUrl(exhentaiUrlBefore);
        appProperties.getSubscriptions().setQueueAhead(queueAheadBefore);
        appProperties.getSubscriptions().setFailedLimit(failedLimitBefore);
        settingsService.setEhentaiMemberId("");
        settingsService.setEhentaiPassHash("");
        settingsService.setEhentaiIgneous("");
        ehentaiDownloader.clearCooldown();
        nhentai.close();
        ehentai.close();
        runner.kick();
    }

    // ---- nhentai ----------------------------------------------------------------------------------------------

    @Test
    void shouldQueueTheWholeSearchThenOnlyWhatIsNew()
    {
        // GIVEN 60 galleries, the newest uploaded five hours ago
        searchable(1000, 60, Duration.ofHours(5));
        int id = subscribe("nhentai", "language:english");

        // WHEN
        int steps = runAll();

        // THEN a check and two pages below it, each gallery queued once behind what the user asks for
        assertThat(steps).isEqualTo(3);
        assertThat(rowsOf(id)).hasSize(60).allSatisfy(row ->
        {
            assertThat(row.getPriority()).isEqualTo(DownloadQueueItem.FROM_SUBSCRIPTION);
            assertThat(row.getLink()).isEqualTo("https://nhentai.net/g/" + row.getGalleryId().substring(8) + "/");
        });
        Subscription walked = reload(id);
        assertThat(walked.getNewestGalleryId()).isEqualTo("nhentai:1059");
        assertThat(walked.getOldestGalleryId()).isEqualTo("nhentai:1000");
        assertThat(walked.isReachedEnd()).isTrue();
        assertThat(walked.getQueuedCount()).isEqualTo(60);
        assertThat(walked.getResultTotal()).isEqualTo(60);
        assertThat(nhentai.searches().getFirst()).isEqualTo("language:english page=1");
        assertThat(nhentai.searches().subList(1, 3)).allMatch(search -> search.contains(" uploaded:>"));
        assertThat(checkpointRepository.findFirstBySubscriptionIdOrderByRecordedAtDesc(id)).get()
                .extracting(SubscriptionCheckpoint::getGalleryId).isEqualTo("nhentai:1059");

        // WHEN nothing is due, and later three new galleries have come
        assertThat(runner.runNext()).isFalse();
        nhentai.searchable(1060, Instant.now().getEpochSecond());
        nhentai.searchable(1061, Instant.now().getEpochSecond());
        nhentai.searchable(1062, Instant.now().getEpochSecond());
        assertThat(runner.runNext()).as("the polling interval has not passed").isFalse();
        edit(id, s -> s.setLastCheckedAt(LocalDateTime.now().minusMinutes(61)));
        assertThat(runAll()).isEqualTo(1);

        // THEN
        assertThat(rowsOf(id)).hasSize(63);
        assertThat(reload(id).getNewestGalleryId()).isEqualTo("nhentai:1062");
    }

    /**
     * What the library has, what was downloaded before and deleted since (downloaded_gallery), and a download never
     * finished are never queued; a link the user pasted keeps its row and its choices.
     */
    @Test
    void shouldPassOverWhatTheLibraryHasAndNeverTouchAPastedRow()
    {
        // GIVEN
        searchable(1, 5, Duration.ofHours(5));
        chapter("nhentai:5", DownloadStatus.SUCCESSFUL);
        downloadedAndDeleted("nhentai:4");
        chapter("nhentai:3", DownloadStatus.PENDING);
        queueService.enqueue(List.of("nhentai:2"), new DownloadChoices("LOSSLESS", true, TestDownloads.PLAIN_GALLERY_DL));
        int id = subscribe("nhentai", "q");

        // WHEN
        runAll();

        // THEN
        assertThat(queueRepository.findAll())
                .extracting(DownloadQueueItem::getGalleryId, DownloadQueueItem::getPriority,
                        DownloadQueueItem::getSubscriptionId, DownloadQueueItem::getCompressionMode)
                .containsExactlyInAnyOrder(tuple("nhentai:2", DownloadQueueItem.ASKED_FOR, null, "LOSSLESS"),
                        tuple("nhentai:1", DownloadQueueItem.FROM_SUBSCRIPTION, id, PLAIN));
        Subscription walked = reload(id);
        assertThat(walked.getQueuedCount()).isEqualTo(1);
        assertThat(walked.getInLibraryCount()).isEqualTo(2);
        assertThat(walked.getDeletedSinceCount()).isEqualTo(1);
        assertThat(walked.getAlreadyQueuedCount()).isEqualTo(1);
    }

    @Test
    void shouldListNoFurtherAheadThanItsDownloadsAllow()
    {
        // GIVEN
        appProperties.getSubscriptions().setQueueAhead(30);
        searchable(1, 100, Duration.ofHours(5));
        int id = subscribe("nhentai", "q");

        // WHEN
        runAll();

        // THEN a check and one page below it (whose first gallery is the cursor's own), then it waits
        assertThat(rowsOf(id)).hasSize(49);
        assertThat(subscriptionService.overview().getFirst().status()).startsWith("Waiting for 49 of its downloads");

        // WHEN 40 of them have downloaded
        rowsOf(id).stream().limit(40).forEach(queueRepository::delete);
        runAll();

        // THEN it lists on until it is ahead again
        assertThat(rowsOf(id)).hasSize(33);
        assertThat(reload(id).getQueuedCount()).isEqualTo(73);
    }

    /** Downloads that fail at once would otherwise page the whole site into the Failed list. */
    @Test
    void shouldStopListingWhileManyOfItsDownloadsFailed()
    {
        // GIVEN
        appProperties.getSubscriptions().setFailedLimit(5);
        searchable(1, 100, Duration.ofHours(5));
        int id = subscribe("nhentai", "q");
        assertThat(runner.runNext()).isTrue();
        rowsOf(id).stream().limit(5).forEach(row ->
        {
            row.setError("failed");
            queueRepository.save(row);
        });

        // WHEN
        boolean listed = runner.runNext();

        // THEN
        assertThat(listed).isFalse();
        assertThat(subscriptionService.overview().getFirst().status()).startsWith("Stopped listing: 5");
    }

    /** More than a page of new galleries is carried down in steps; a failure part-way loses nothing. */
    @Test
    void shouldCarryANewBatchDownAcrossPagesAndAFailure()
    {
        // GIVEN a walked search, then 60 galleries newer than its head
        searchable(1, 10, Duration.ofDays(3));
        int id = subscribe("nhentai", "q");
        runAll();
        searchable(100, 60, Duration.ofHours(5));
        edit(id, s -> s.setLastCheckedAt(LocalDateTime.now().minusDays(1)));

        // WHEN the check finds a page of new ones, and the next page fails
        assertThat(runner.runNext()).isTrue();
        Subscription catchingUp = reload(id);
        nhentai.failNext("/api/v2/search", 500);
        assertThat(runner.runNext()).isTrue();
        Subscription failed = reload(id);
        runAll();

        // THEN the head moved only once all of them were in, and none was queued twice
        assertThat(catchingUp.getCatchUpTop()).isEqualTo("nhentai:159");
        assertThat(catchingUp.getNewestGalleryId()).isEqualTo("nhentai:10");
        assertThat(failed.getLastError()).contains("HTTP 500");
        assertThat(failed.getCatchUpCursor()).isEqualTo(catchingUp.getCatchUpCursor());
        Subscription done = reload(id);
        assertThat(done.getNewestGalleryId()).isEqualTo("nhentai:159");
        assertThat(done.getCatchUpTop()).isNull();
        assertThat(done.getLastError()).isNull();
        assertThat(rowsOf(id)).hasSize(70).extracting(DownloadQueueItem::getGalleryId).doesNotHaveDuplicates();
    }

    /** Starting the waiting catch-up over would list its pages again and count every gallery on them twice. */
    @Test
    void shouldCheckAboveACatchUpThatWaitsForItsDownloads()
    {
        // GIVEN a walked search, then 60 galleries newer than its head, whose catch-up waits after its first page
        searchable(1, 10, Duration.ofDays(3));
        int id = subscribe("nhentai", "q");
        runAll();
        searchable(100, 60, Duration.ofHours(5));
        appProperties.getSubscriptions().setQueueAhead(20);
        edit(id, s -> s.setLastCheckedAt(LocalDateTime.now().minusDays(1)));
        runAll();
        assertThat(reload(id).getCatchUpTop()).isEqualTo("nhentai:159");

        // WHEN three more come, a check finds them, and then the catch-up may go on
        for (long gallery = 160; gallery <= 162; gallery++)
        {
            nhentai.searchable(gallery, Instant.now().getEpochSecond());
        }
        edit(id, s -> s.setLastCheckedAt(LocalDateTime.now().minusDays(1)));
        assertThat(runner.runNext()).isTrue();
        Subscription checked = reload(id);
        appProperties.getSubscriptions().setQueueAhead(1000);
        runAll();

        // THEN the check raised the catch-up's top, and every gallery was listed and counted once
        assertThat(checked.getCatchUpTop()).isEqualTo("nhentai:162");
        assertThat(checked.getCatchUpCursor()).isNotNull();
        Subscription done = reload(id);
        assertThat(done.getNewestGalleryId()).isEqualTo("nhentai:162");
        assertThat(done.getCatchUpTop()).isNull();
        assertThat(done.getQueuedCount()).isEqualTo(73);
        assertThat(done.getAlreadyQueuedCount()).isZero();
        assertThat(rowsOf(id)).hasSize(73).extracting(DownloadQueueItem::getGalleryId).doesNotHaveDuplicates();
    }

    /** A gallery that matches only since a tag was added after it was first passed. */
    @Test
    void shouldQueueAGalleryTaggedSinceOnTheRecheck()
    {
        // GIVEN a subscription whose head was at gallery 104 two days ago
        for (long gallery = 100; gallery <= 104; gallery++)
        {
            nhentai.searchable(gallery, Instant.now().minus(Duration.ofHours(200 - gallery).plusMinutes(30))
                    .getEpochSecond());
        }
        int id = subscribe("nhentai", "q");
        runAll();
        editCheckpoints(id, checkpoint -> checkpoint.setRecordedAt(LocalDateTime.now().minusHours(49)));
        for (long gallery = 106; gallery <= 109; gallery++)
        {
            nhentai.searchable(gallery, Instant.now().minus(Duration.ofHours(200 - gallery).plusMinutes(30))
                    .getEpochSecond());
        }
        edit(id, s -> s.setLastCheckedAt(LocalDateTime.now().minusDays(1)));
        runAll();
        assertThat(reload(id).getNewestGalleryId()).isEqualTo("nhentai:109");

        // WHEN gallery 105 is tagged so it matches, and the daily re-check is due
        nhentai.searchable(105, Instant.now().minus(Duration.ofHours(95).plusMinutes(30)).getEpochSecond());
        edit(id, s -> s.setLastRecheckedAt(LocalDateTime.now().minusHours(25)));
        runAll();

        // THEN
        assertThat(rowsOf(id)).extracting(DownloadQueueItem::getGalleryId).contains("nhentai:105");
        Subscription rechecked = reload(id);
        assertThat(rechecked.getLastRecheckedAt()).isAfter(LocalDateTime.now().minusMinutes(5));
        assertThat(rechecked.getQueuedCount()).isEqualTo(10);
    }

    /**
     * After a year off, what was uploaded shortly before the app stopped and tagged while it was off is listed again,
     * and the year, listed in one go, is not listed again by the re-checks after it.
     */
    @Test
    void shouldRecheckAroundAYearTheAppWasOffWithoutListingTheYearAgain()
    {
        // GIVEN a walked search whose head was 105 when the app stopped a year ago, its head 40 hours before that at
        // 101, and its last re-check 12 hours before it stopped
        Instant stoppedAt = Instant.now().minus(Duration.ofDays(365));
        long[] galleries = {100, 101, 102, 103, 105};
        long[] hoursBeforeStop = {50, 45, 30, 20, 1};
        for (int i = 0; i < galleries.length; i++)
        {
            nhentai.searchable(galleries[i], stoppedAt.minus(Duration.ofHours(hoursBeforeStop[i]).plusMinutes(30))
                    .getEpochSecond());
        }
        int id = subscribe("nhentai", "q");
        runAll();
        LocalDateTime stopped = LocalDateTime.now().minusDays(365);
        editCheckpoints(id, checkpoint -> checkpoint.setRecordedAt(stopped));
        checkpoint(id, "nhentai:101", stopped.minusHours(40));
        edit(id, s ->
        {
            s.setTopSeenAt(stopped);
            s.setLastCheckedAt(stopped);
            s.setLastRecheckedAt(stopped.minusHours(12));
        });

        // WHEN gallery 104, uploaded 10 hours before the app stopped, was tagged to match while it was off, 60
        // galleries came over the year, and the app runs again; its catch-up takes 30 hours
        nhentai.searchable(104, stoppedAt.minus(Duration.ofHours(10).plusMinutes(30)).getEpochSecond());
        for (int i = 0; i < 60; i++)
        {
            nhentai.searchable(200 + i, stoppedAt.plus(Duration.ofDays(1 + i * 6L).plusMinutes(30)).getEpochSecond());
        }
        assertThat(runner.runNext()).isTrue();
        assertThat(reload(id).getCatchUpTop()).isEqualTo("nhentai:259");
        LocalDateTime topSeen = LocalDateTime.now().minusHours(30);
        edit(id, s -> s.setTopSeenAt(topSeen));
        runAll();

        // THEN 104 is queued, and the only checkpoint left is the new head, at the time its catch-up read it
        assertThat(rowsOf(id)).extracting(DownloadQueueItem::getGalleryId).contains("nhentai:104");
        Subscription back = reload(id);
        assertThat(back.getNewestGalleryId()).isEqualTo("nhentai:259");
        assertThat(back.getCatchUpTop()).isNull();
        assertThat(checkpointRepository.findAll()).filteredOn(c -> c.getSubscriptionId() == id).singleElement()
                .satisfies(checkpoint ->
                {
                    assertThat(checkpoint.getGalleryId()).isEqualTo("nhentai:259");
                    assertThat(checkpoint.getRecordedAt()).isCloseTo(topSeen, within(1, ChronoUnit.SECONDS));
                });

        // WHEN the next re-check is due
        edit(id, s ->
        {
            s.setLastCheckedAt(LocalDateTime.now().minusHours(25));
            s.setLastRecheckedAt(LocalDateTime.now().minusHours(25));
        });
        int searchesBefore = nhentai.searches().size();
        int steps = runAll();

        // THEN it reads the newest page only: the year below the head is not listed again
        assertThat(steps).isEqualTo(1);
        assertThat(nhentai.searches().subList(searchesBefore, nhentai.searches().size()))
                .containsExactly("q page=1");
        assertThat(reload(id).getLastRecheckedAt()).isAfter(LocalDateTime.now().minusMinutes(5));
    }

    /** A page fetched for a walk that changed meanwhile must not move the new one. */
    @Test
    void shouldDropAPageFetchedForASearchThatChangedMeanwhile()
    {
        // GIVEN a subscription whose search is edited while its first page is on the way
        searchable(1, 5, Duration.ofHours(5));
        int id = subscribe("nhentai", "old search");
        nhentai.beforeEachSearch(() ->
        {
            SubscriptionForm form = subscriptionService.form(id).orElseThrow();
            form.setQuery("new search");
            subscriptionService.save(form, TestDownloads.PLAIN_GALLERY_DL);
            nhentai.beforeEachSearch(() -> { });
        });

        // WHEN
        assertThat(runner.runNext()).isTrue();

        // THEN nothing of the old search was queued, and the new one starts from the top
        assertThat(rowsOf(id)).isEmpty();
        assertThat(reload(id).getNewestGalleryId()).isNull();
        assertThat(reload(id).getRevision()).isEqualTo(1);
        runAll();
        assertThat(rowsOf(id)).hasSize(5);
        assertThat(nhentai.searches()).containsExactly("old search page=1", "new search page=1");
    }

    @Test
    void shouldLeaveItsDownloadsQueuedWhenDeleted()
    {
        // GIVEN
        searchable(1, 5, Duration.ofHours(5));
        int id = subscribe("nhentai", "q");
        runAll();

        // WHEN
        assertThat(subscriptionService.delete(id)).isPresent();

        // THEN
        assertThat(queueRepository.findAll()).hasSize(5).allSatisfy(row ->
        {
            assertThat(row.getSubscriptionId()).isNull();
            assertThat(row.getPriority()).isEqualTo(DownloadQueueItem.FROM_SUBSCRIPTION);
        });
        assertThat(checkpointRepository.findAll()).isEmpty();
    }

    // ---- e-hentai ---------------------------------------------------------------------------------------------

    @Test
    void shouldWalkEhentaiBelowItsCursorAndWaitOutABan()
    {
        // GIVEN 30 galleries
        ehentai.galleries(5000, 30);
        int id = subscribe("e-hentai", "f_search=female:x$");

        // WHEN
        assertThat(runner.runNext()).isTrue();

        // THEN the first page, under e-hentai's own gallery ids
        assertThat(rowsOf(id)).hasSize(25).extracting(DownloadQueueItem::getGalleryId)
                .contains(FakeEhentai.galleryId(5029), FakeEhentai.galleryId(5005));
        assertThat(reload(id).getOldestCursor()).isEqualTo("5005");

        // WHEN the site bans this IP before the next page
        ehentai.banned(true);
        assertThat(runner.runNext()).isTrue();

        // THEN the subscription waits without a failure counted, and so do its downloads
        Subscription waiting = reload(id);
        assertThat(waiting.getLastError()).contains("temporarily banned");
        assertThat(waiting.getFailedSteps()).isZero();
        assertThat(registry.refusals()).contains("ehentai:");
        assertThat(queueService.nextPending()).isEmpty();
        queueService.enqueue(List.of("mock:900"), TestDownloads.choices(PLAIN, false));
        assertThat(queueService.nextPending()).get().extracting(DownloadQueueItem::getGalleryId).isEqualTo("mock:900");
        assertThat(runner.runNext()).isFalse();

        // WHEN the ban is over
        ehentai.banned(false);
        ehentaiDownloader.clearCooldown();
        runner.kick();
        runAll();

        // THEN
        assertThat(rowsOf(id)).hasSize(30);
        assertThat(ehentai.requests()).extracting(request -> request.query().get("next"))
                .containsExactly(null, "5005", "5005");
        assertThat(ehentai.requests()).allSatisfy(request -> assertThat(request.cookie()).isEqualTo("nw=1"));
    }

    @Test
    void shouldSearchExhentaiWithTheAccountsCookies()
    {
        // GIVEN
        settingsService.setEhentaiMemberId("123");
        settingsService.setEhentaiPassHash("0123456789abcdef0123456789abcdef");
        ehentai.galleries(7000, 3).exhentaiRequires("ipb_member_id=123");
        int id = subscribe("exhentai", "f_search=x", new GalleryDlOptions("firefox", false, "0.5"));

        // WHEN
        runAll();

        // THEN
        assertThat(rowsOf(id)).hasSize(3).allSatisfy(row -> assertThat(row.getCookiesBrowser()).isEqualTo("firefox"));
        assertThat(ehentai.requests()).singleElement().satisfies(request ->
        {
            assertThat(request.path()).isEqualTo("/ex/");
            assertThat(request.cookie()).contains("ipb_member_id=123",
                    "ipb_pass_hash=0123456789abcdef0123456789abcdef", "nw=1");
            assertThat(request.userAgent()).startsWith("Mozilla/5.0");
        });

        // WHEN the cookies are gone from Settings
        settingsService.setEhentaiPassHash("");
        edit(id, s -> s.setLastCheckedAt(LocalDateTime.now().minusDays(1)));
        assertThat(runner.runNext()).isFalse();

        // THEN it says why, without asking the site
        assertThat(reload(id).getLastError()).contains("Settings");
        assertThat(ehentai.requests()).hasSize(1);

        // WHEN the cookies are back while no check is due
        settingsService.setEhentaiPassHash("0123456789abcdef0123456789abcdef");
        edit(id, s -> s.setLastCheckedAt(LocalDateTime.now()));
        assertThat(runner.runNext()).isFalse();

        // THEN the page no longer asks for them, still without asking the site
        assertThat(reload(id).getLastError()).isNull();
        assertThat(ehentai.requests()).hasSize(1);
    }

    @Test
    void shouldReportARefusedExhentaiAccount()
    {
        // GIVEN cookies exhentai does not accept
        settingsService.setEhentaiMemberId("999");
        settingsService.setEhentaiPassHash("0123456789abcdef0123456789abcdef");
        ehentai.galleries(7000, 3).exhentaiRequires("ipb_member_id=123");
        int id = subscribe("exhentai", "f_search=x", new GalleryDlOptions("firefox", false, "0.5"));

        // WHEN
        assertThat(runner.runNext()).isTrue();

        // THEN
        Subscription refused = reload(id);
        assertThat(refused.getLastError()).contains("did not accept the account");
        assertThat(refused.getFailedSteps()).isOne();
        assertThat(rowsOf(id)).isEmpty();
    }

    // ---- fixture ----------------------------------------------------------------------------------------------

    /**
     * {@code count} galleries from {@code firstId} up, the newest uploaded {@code newestAge} and half an hour ago, an
     * hour apart: half an hour off the whole hours nhentai's filter counts in, so no gallery sits on its edge.
     */
    private void searchable(long firstId, int count, Duration newestAge)
    {
        Instant newest = Instant.now().minus(newestAge).minus(Duration.ofMinutes(30));
        for (int i = 0; i < count; i++)
        {
            nhentai.searchable(firstId + i, newest.minus(Duration.ofHours(count - 1 - i)).getEpochSecond());
        }
    }

    private int subscribe(String site, String query)
    {
        return subscribe(site, query, TestDownloads.PLAIN_GALLERY_DL);
    }

    private int subscribe(String site, String query, GalleryDlOptions galleryDl)
    {
        SubscriptionForm form = subscriptionService.newForm();
        form.setSource(site);
        form.setQuery(query);
        form.setCompressionMode(PLAIN);
        return subscriptionService.save(form, galleryDl);
    }

    /** @return the steps taken until nothing was due */
    private int runAll()
    {
        int steps = 0;
        while (runner.runNext())
        {
            steps++;
            assertThat(steps).as("a walk that never ends").isLessThan(500);
        }
        return steps;
    }

    private Subscription reload(int id)
    {
        return subscriptionRepository.findById(id).orElseThrow();
    }

    private List<DownloadQueueItem> rowsOf(int id)
    {
        return queueRepository.findAll().stream().filter(row -> Integer.valueOf(id).equals(row.getSubscriptionId()))
                .toList();
    }

    private void edit(int id, Consumer<Subscription> change)
    {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                change.accept(subscriptionRepository.findById(id).orElseThrow()));
    }

    private void editCheckpoints(int id, Consumer<SubscriptionCheckpoint> change)
    {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                checkpointRepository.findAll().stream().filter(c -> c.getSubscriptionId() == id).forEach(change));
    }

    private void checkpoint(int id, String galleryId, LocalDateTime recordedAt)
    {
        var checkpoint = new SubscriptionCheckpoint();
        checkpoint.setSubscriptionId(id);
        checkpoint.setGalleryId(galleryId);
        checkpoint.setRecordedAt(recordedAt);
        checkpointRepository.save(checkpoint);
    }

    private void chapter(String galleryId, DownloadStatus status)
    {
        var form = new ChapterForm();
        form.setTitleFull("Subscribed " + galleryId);
        form.setLanguage("English");
        form.setGalleryId(galleryId);
        int chapterId = chapterService.create(form);
        chapterService.setDownloadStatus(chapterId, status);
        chapters.add(chapterId);
    }

    private void downloadedAndDeleted(String galleryId)
    {
        var record = new DownloadedGallery();
        record.setGalleryId(galleryId);
        record.setChapterId(Integer.MAX_VALUE);
        record.setDownloadedAt(LocalDateTime.now());
        downloadedGalleryRepository.save(record);
        downloaded.add(galleryId);
    }
}

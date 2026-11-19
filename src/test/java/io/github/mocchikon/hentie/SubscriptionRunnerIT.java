package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.CompressionRuleForm;
import io.github.mocchikon.hentie.dto.SubscriptionForm;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.DownloadedGalleryRepository;
import io.github.mocchikon.hentie.repository.ImageCompressionRuleRepository;
import io.github.mocchikon.hentie.repository.SubscriptionCheckpointRepository;
import io.github.mocchikon.hentie.repository.SubscriptionRepository;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.scrapper.ehentai.EhentaiDownloader;
import io.github.mocchikon.hentie.scrapper.ehentai.EhentaiProperties;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.scrapper.nhentai.NhentaiProperties;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionRuleService;
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
    @Autowired ImageCompressionRuleService ruleService;
    @Autowired ImageCompressionRuleRepository ruleRepository;

    private FakeNhentai nhentai;
    private FakeEhentai ehentai;
    private String nhentaiUrlBefore;
    private String ehentaiUrlBefore;
    private String exhentaiUrlBefore;
    private int queueAheadBefore;
    private int failedLimitBefore;
    private int retryAfterFailureBefore;
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
        retryAfterFailureBefore = appProperties.getSubscriptions().getRetryAfterFailureSeconds();
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
        ruleRepository.deleteAll();
        chapters.forEach(chapterService::delete);
        downloaded.forEach(downloadedGalleryRepository::deleteById);
        nhentaiProperties.setBaseUrl(nhentaiUrlBefore);
        ehentaiProperties.setEhentaiUrl(ehentaiUrlBefore);
        ehentaiProperties.setExhentaiUrl(exhentaiUrlBefore);
        appProperties.getSubscriptions().setQueueAhead(queueAheadBefore);
        appProperties.getSubscriptions().setFailedLimit(failedLimitBefore);
        appProperties.getSubscriptions().setRetryAfterFailureSeconds(retryAfterFailureBefore);
        settingsService.setEhentaiMemberId("");
        settingsService.setEhentaiPassHash("");
        settingsService.setEhentaiIgneous("");
        settingsService.setSubscriptionsOff(false);
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

    @Test
    void shouldListNothingWhileSubscriptionsAreTurnedOffAndKeepEachOnesPause()
    {
        // GIVEN one subscription running and one paused
        searchable(1000, 5, Duration.ofHours(5));
        int running = subscribe("nhentai", "running");
        int paused = subscribe("nhentai", "paused");
        assertThat(subscriptionService.setEnabled(paused, false)).isTrue();

        // WHEN
        settingsService.setSubscriptionsOff(true);

        // THEN
        assertThat(runner.runNext()).isFalse();
        assertThat(nhentai.searches()).isEmpty();
        assertThat(reload(running).isEnabled()).isTrue();
        assertThat(reload(paused).isEnabled()).isFalse();

        // WHEN turned on again, only the one not paused lists
        settingsService.setSubscriptionsOff(false);
        runAll();

        // THEN
        assertThat(rowsOf(running)).hasSize(5);
        assertThat(rowsOf(paused)).isEmpty();
        assertThat(reload(paused).isEnabled()).isFalse();
    }

    /**
     * Every subscription looks for new galleries before any lists older ones. Within a phase the lower priority number
     * goes first, however the rows were created, and the lower priority lists older galleries only once the higher one
     * has none left.
     */
    @Test
    void shouldCheckEverySubscriptionBeforeAnyBackfillsAndBackfillByPriority()
    {
        // GIVEN two subscriptions due at once, the lower priority one created first
        appProperties.getSubscriptions().setQueueAhead(1000);
        searchable(1, 60, Duration.ofHours(5));
        int low = subscribe("nhentai", "low", 5);
        int high = subscribe("nhentai", "high", 0);

        // WHEN
        int steps = runAll();

        // THEN
        assertThat(steps).isEqualTo(6);
        assertThat(searchKinds(0)).containsExactly("high newest", "low newest", "high below", "high below",
                "low below", "low below");
        assertThat(rowsOf(high)).hasSize(60);
        assertThat(rowsOf(low)).isEmpty();
    }

    /** The user's example: a lower priority does not go on listing older galleries while a higher one checks. */
    @Test
    void shouldNotBackfillWhileAHigherPriorityListsNewGalleriesAlsoWhileItWaitsForItsDownloads()
    {
        // GIVEN a higher priority walked to the end, and a lower one part-way down its older galleries
        appProperties.getSubscriptions().setQueueAhead(1000);
        searchable(1, 100, Duration.ofDays(5));
        int high = subscribe("nhentai", "high", 0);
        int low = subscribe("nhentai", "low", 1);
        while (!reload(high).isReachedEnd())
        {
            assertThat(runner.runNext()).isTrue();
        }
        assertThat(runner.runNext()).isTrue();
        assertThat(searchKinds(0)).endsWith("low below");
        assertThat(reload(low).isReachedEnd()).isFalse();

        // WHEN 60 new galleries come, the higher priority's check is due, and its downloads are ahead of the limit
        searchable(200, 60, Duration.ofHours(5));
        appProperties.getSubscriptions().setQueueAhead(20);
        edit(high, s -> s.setLastCheckedAt(LocalDateTime.now().minusDays(1)));
        int searchesBefore = nhentai.searches().size();
        assertThat(runner.runNext()).isTrue();
        boolean listedMore = runner.runNext();

        // THEN its check leaves a catch-up that waits for its downloads, and the lower priority waits with it
        assertThat(listedMore).isFalse();
        assertThat(searchKinds(searchesBefore)).containsExactly("high newest");
        assertThat(reload(high).getCatchUpTop()).isEqualTo("nhentai:259");
        assertThat(status(low)).startsWith("Waiting for \"high\" (priority 0) before listing older galleries; ");

        // WHEN its downloads catch up
        appProperties.getSubscriptions().setQueueAhead(1000);
        runAll();

        // THEN it lists every new gallery before the lower priority goes on
        assertThat(searchKinds(searchesBefore)).startsWith("high newest", "high below", "high below")
                .contains("low below").doesNotContain("low newest");
        assertThat(searchKinds(searchesBefore + 3)).allMatch(kind -> kind.equals("low below"));
        assertThat(reload(high).getNewestGalleryId()).isEqualTo("nhentai:259");
        assertThat(reload(low).isReachedEnd()).isTrue();
    }

    /**
     * After days off, every subscription has new galleries: a higher priority lists all of its own, on any site, also
     * while it waits for its downloads, before a lower one looks for its own.
     */
    @Test
    void shouldListTheNewGalleriesOfAHigherPriorityOnAnotherSiteFirst()
    {
        // GIVEN an e-hentai subscription and a lower priority nhentai one, both walked, their downloads done
        ehentai.galleries(5000, 10);
        searchable(1, 10, Duration.ofDays(3));
        int high = subscribe("e-hentai", "f_search=x", 0);
        int low = subscribe("nhentai", "q", 1);
        runAll();
        queueRepository.deleteAll();

        // WHEN 60 new galleries came on each site while the app was off, more than its downloads allow ahead
        ehentai.galleries(6000, 60);
        searchable(100, 60, Duration.ofHours(5));
        appProperties.getSubscriptions().setQueueAhead(20);
        edit(high, s -> s.setLastCheckedAt(LocalDateTime.now().minusDays(3)));
        edit(low, s -> s.setLastCheckedAt(LocalDateTime.now().minusDays(3)));
        int nhentaiSearches = nhentai.searches().size();
        int steps = runAll();

        // THEN the higher priority's first page, and its catch-up waits for its downloads, and the lower one with it
        assertThat(steps).isOne();
        assertThat(reload(high).getCatchUpTop()).isEqualTo(FakeEhentai.galleryId(6059));
        assertThat(nhentai.searches()).hasSize(nhentaiSearches);
        assertThat(status(low)).isEqualTo("Waiting for \"f_search=x\" (priority 0) before looking for new galleries.");
        assertThat(status(high)).isEqualTo("Waiting for 25 of its downloads before listing more new galleries.");

        // WHEN they download, twice
        rowsOf(high).forEach(queueRepository::delete);
        int stepsAfterFirst = runAll();
        rowsOf(high).forEach(queueRepository::delete);
        int stepsAfterSecond = runAll();

        // THEN the catch-up goes on a page each time, and the lower priority checks only once it is done
        assertThat(stepsAfterFirst).isOne();
        assertThat(stepsAfterSecond).isEqualTo(2);
        Subscription done = reload(high);
        assertThat(done.getNewestGalleryId()).isEqualTo(FakeEhentai.galleryId(6059));
        assertThat(done.getCatchUpTop()).isNull();
        assertThat(nhentai.searches().subList(nhentaiSearches, nhentai.searches().size()))
                .containsExactly("q page=1");
        assertThat(reload(low).getCatchUpTop()).isEqualTo("nhentai:159");
    }

    /** New galleries of any subscription come before the recent past again of a higher priority. */
    @Test
    void shouldLookForNewGalleriesBeforeRecheckingAHigherPriority()
    {
        // GIVEN a higher priority with a re-check due, and a lower one with a check due
        searchable(1, 10, Duration.ofDays(3));
        int high = subscribe("nhentai", "high", 0);
        int low = subscribe("nhentai", "low", 1);
        runAll();
        edit(high, s -> s.setLastRecheckedAt(LocalDateTime.now().minusHours(25)));
        edit(low, s -> s.setLastCheckedAt(LocalDateTime.now().minusDays(1)));
        int searchesBefore = nhentai.searches().size();

        // WHEN
        int steps = runAll();

        // THEN
        assertThat(steps).isEqualTo(2);
        assertThat(searchKinds(searchesBefore)).containsExactly("low newest", "high newest");
        assertThat(reload(high).getLastRecheckedAt()).isAfter(LocalDateTime.now().minusMinutes(5));
    }

    /** One stuck subscription must not stop every one below it: here its site bans this IP. */
    @Test
    void shouldNotWaitForAHigherPriorityWhoseSiteIsNotAsked()
    {
        // GIVEN an e-hentai subscription, whose site bans this IP, and a lower priority nhentai one
        ehentai.galleries(5000, 10).banned(true);
        searchable(1, 10, Duration.ofDays(3));
        int high = subscribe("e-hentai", "f_search=x", 0);
        int low = subscribe("nhentai", "q", 1);

        // WHEN
        runAll();

        // THEN
        assertThat(reload(high).getLastError()).contains("temporarily banned");
        assertThat(rowsOf(high)).isEmpty();
        assertThat(rowsOf(low)).hasSize(10);
    }

    /** A failing subscription holds back nothing while it waits to try again. */
    @Test
    void shouldNotWaitForAHigherPriorityThatFailed()
    {
        // GIVEN
        appProperties.getSubscriptions().setRetryAfterFailureSeconds(3600);
        searchable(1, 10, Duration.ofDays(3));
        int high = subscribe("nhentai", "high", 0);
        int low = subscribe("nhentai", "low", 1);
        nhentai.failNext("/api/v2/search", 500);

        // WHEN
        assertThat(runner.runNext()).isTrue();
        assertThat(runner.runNext()).isTrue();

        // THEN
        assertThat(nhentai.requestsFor("/api/v2/search")).isEqualTo(2);
        assertThat(nhentai.searches()).containsExactly("low page=1");
        assertThat(reload(high).getFailedSteps()).isOne();
        assertThat(rowsOf(low)).hasSize(10);
        assertThat(runner.runNext()).isFalse();
    }

    /**
     * What the library has and what was downloaded before and deleted since (downloaded_gallery) are never queued; a
     * download never finished is, to fetch what it misses; a link the user pasted keeps its row and its choices.
     */
    @Test
    void shouldPassOverWhatTheLibraryHasAndNeverTouchAPastedRow()
    {
        // GIVEN
        searchable(1, 5, Duration.ofHours(5));
        chapter("nhentai:5", DownloadStatus.SUCCESSFUL);
        downloadedAndDeleted("nhentai:4");
        chapter("nhentai:3", DownloadStatus.PENDING);
        queueService.enqueue(List.of("nhentai:2"), new DownloadChoices("LOSSLESS", false, true, TestDownloads.PLAIN_GALLERY_DL));
        int id = subscribe("nhentai", "q");

        // WHEN
        runAll();

        // THEN
        assertThat(queueRepository.findAll())
                .extracting(DownloadQueueItem::getGalleryId, DownloadQueueItem::getPriority,
                        DownloadQueueItem::getSubscriptionId, DownloadQueueItem::getCompressionMode)
                .containsExactlyInAnyOrder(tuple("nhentai:2", DownloadQueueItem.ASKED_FOR, null, "LOSSLESS"),
                        tuple("nhentai:3", DownloadQueueItem.FROM_SUBSCRIPTION, id, BuiltInCompressionMode.RULES_KEY),
                        tuple("nhentai:1", DownloadQueueItem.FROM_SUBSCRIPTION, id, BuiltInCompressionMode.RULES_KEY));
        Subscription walked = reload(id);
        assertThat(walked.getQueuedCount()).isEqualTo(2);
        assertThat(walked.getInLibraryCount()).isEqualTo(1);
        assertThat(walked.getDeletedSinceCount()).isEqualTo(1);
        assertThat(walked.getAlreadyQueuedCount()).isEqualTo(1);
    }

    /** A failed row waits for the user's decision, even for a download never finished. */
    @Test
    void shouldLeaveTheFailedRowOfAnUnfinishedDownloadAlone()
    {
        // GIVEN an unfinished chapter whose pasted download failed
        searchable(1, 1, Duration.ofHours(5));
        chapter("nhentai:1", DownloadStatus.PENDING);
        queueService.enqueue(List.of("nhentai:1"), new DownloadChoices("LOSSLESS", false, false, TestDownloads.PLAIN_GALLERY_DL));
        DownloadQueueItem failed = queueRepository.findFirstByGalleryIdOrderByIdAsc("nhentai:1").orElseThrow();
        failed.setError("failed");
        queueRepository.save(failed);
        int id = subscribe("nhentai", "q");

        // WHEN
        runAll();

        // THEN
        assertThat(queueRepository.findAll())
                .extracting(DownloadQueueItem::getGalleryId, DownloadQueueItem::getError, DownloadQueueItem::getSubscriptionId)
                .containsExactly(tuple("nhentai:1", "failed", null));
        assertThat(reload(id).getAlreadyQueuedCount()).isOne();
    }

    /**
     * A gallery in the library is passed over, unless a rule that allows upgrades gives it a better mode than its pages
     * have: then it is queued By rules, and counted apart from the new ones.
     */
    @Test
    void shouldQueueAGalleryInTheLibraryAgainOnlyForAnUpgradeARuleAllows()
    {
        // GIVEN three galleries in the library - compressed with Very high reduction, with Lossless, and never - and
        // a rule allowing every gallery an upgrade to Lossless
        searchable(1, 3, Duration.ofHours(5));
        int worse = chapter("nhentai:3", DownloadStatus.SUCCESSFUL);
        int equal = chapter("nhentai:2", DownloadStatus.SUCCESSFUL);
        chapter("nhentai:1", DownloadStatus.SUCCESSFUL);
        chapterService.setCompressionMode(worse, BuiltInCompressionMode.VERY_HIGH_REDUCTION.getKey());
        chapterService.setCompressionMode(equal, BuiltInCompressionMode.LOSSLESS.getKey());
        everyGallery(BuiltInCompressionMode.LOSSLESS.getKey(), true);
        int id = subscribe("nhentai", "q");

        // WHEN
        runAll();

        // THEN
        assertThat(rowsOf(id)).extracting(DownloadQueueItem::getGalleryId, DownloadQueueItem::getCompressionMode)
                .containsExactly(tuple("nhentai:3", BuiltInCompressionMode.RULES_KEY));
        Subscription walked = reload(id);
        assertThat(walked.getUpgradeCount()).isOne();
        assertThat(walked.getQueuedCount()).isZero();
        assertThat(walked.getInLibraryCount()).isEqualTo(2);
    }

    @Test
    void shouldPassOverACompressedGalleryWhenNoRuleAllowsAnUpgrade()
    {
        // GIVEN
        searchable(1, 1, Duration.ofHours(5));
        int compressed = chapter("nhentai:1", DownloadStatus.SUCCESSFUL);
        chapterService.setCompressionMode(compressed, BuiltInCompressionMode.VERY_HIGH_REDUCTION.getKey());
        everyGallery(BuiltInCompressionMode.LOSSLESS.getKey(), false);
        int id = subscribe("nhentai", "q");

        // WHEN
        runAll();

        // THEN
        assertThat(rowsOf(id)).isEmpty();
        assertThat(reload(id).getUpgradeCount()).isZero();
        assertThat(reload(id).getInLibraryCount()).isOne();
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
        assertThat(subscriptionService.overview(null, false).getFirst().status()).startsWith("Waiting for 49 of its downloads");

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
        assertThat(subscriptionService.overview(null, false).getFirst().status()).startsWith("Stopped listing: 5");
    }

    /** More than a page of new galleries is carried down in steps; a failure part-way loses nothing. */
    @Test
    void shouldCarryANewBatchDownAcrossPagesAndAFailure()
    {
        // GIVEN a walked search, then 60 galleries newer than its head, with room in the queue for all of them
        appProperties.getSubscriptions().setQueueAhead(1000);
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
        // 101, and its last re-check 12 hours before it stopped, with room in the queue for everything listed after it
        appProperties.getSubscriptions().setQueueAhead(1000);
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
        int id = subscribe("exhentai", "f_search=x", new GalleryDlOptions(false, "0.5"));

        // WHEN
        runAll();

        // THEN
        assertThat(rowsOf(id)).hasSize(3);
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
        int id = subscribe("exhentai", "f_search=x", new GalleryDlOptions(false, "0.5"));

        // WHEN
        assertThat(runner.runNext()).isTrue();

        // THEN
        Subscription refused = reload(id);
        assertThat(refused.getLastError()).contains("did not accept the account");
        assertThat(refused.getFailedSteps()).isOne();
        assertThat(rowsOf(id)).isEmpty();
    }

    /** A download that failed for want of the account, as the worker records one. */
    private int failedForLogin(String galleryId)
    {
        var row = new DownloadQueueItem();
        row.setLink(galleryId);
        row.setGalleryId(galleryId);
        row.setRequestDelay("0");
        row.setAttempts(1);
        row.setError("e-hentai shows this gallery only to logged-in users.");
        row.setNeedsLogin(true);
        return queueRepository.save(row).getId();
    }

    @Test
    void shouldRetryDownloadsThatNeededTheAccountOnceAnExhentaiSearchAcceptsIt()
    {
        // GIVEN a download of each source that failed for want of a login
        int ehentaiRow = failedForLogin(FakeEhentai.galleryId(5));
        int otherRow = failedForLogin("hitomi:5");
        settingsService.setEhentaiMemberId("125");
        settingsService.setEhentaiPassHash("0123456789abcdef0123456789abcdef");
        ehentai.galleries(7000, 3).exhentaiRequires("ipb_member_id=125");
        subscribe("exhentai", "f_search=x");

        // WHEN
        runAll();

        // THEN only e-hentai's waits again.
        DownloadQueueItem revived = queueRepository.findById(ehentaiRow).orElseThrow();
        assertThat(revived.getError()).isNull();
        assertThat(revived.getAttempts()).isZero();
        assertThat(revived.isNeedsLogin()).isFalse();
        assertThat(queueRepository.findById(otherRow).orElseThrow().getError()).isNotNull();
    }

    /** Saving the account in Settings asks exhentai once; nothing else might ask it while no subscription runs. */
    @Test
    void shouldRetryDownloadsThatNeededTheAccountOnceACheckFindsItAccepted() throws InterruptedException
    {
        // GIVEN
        int row = failedForLogin(FakeEhentai.galleryId(6));
        settingsService.setEhentaiMemberId("126");
        settingsService.setEhentaiPassHash("0123456789abcdef0123456789abcdef");
        ehentai.exhentaiRequires("ipb_member_id=126");

        // WHEN
        registry.checkLogins();

        // THEN one request for exhentai's front page, and the row waits again
        long until = System.currentTimeMillis() + 10_000;
        while (queueRepository.findById(row).orElseThrow().getError() != null && System.currentTimeMillis() < until)
        {
            Thread.sleep(50);
        }
        assertThat(queueRepository.findById(row).orElseThrow().getError()).isNull();
        assertThat(ehentai.requests()).singleElement().satisfies(request ->
        {
            assertThat(request.path()).isEqualTo("/ex/");
            assertThat(request.cookie()).contains("ipb_member_id=126");
        });

        // WHEN asked again with the same account
        registry.checkLogins();

        // THEN it is known to be accepted, so the site is not asked.
        assertThat(ehentai.requests()).hasSize(1);
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
        return subscribe(site, query, Subscription.DEFAULT_PRIORITY);
    }

    private int subscribe(String site, String query, int priority)
    {
        SubscriptionForm form = subscriptionService.newForm();
        form.setSource(site);
        form.setQuery(query);
        form.setPriority(priority);
        return subscriptionService.save(form, TestDownloads.PLAIN_GALLERY_DL);
    }

    private int subscribe(String site, String query, GalleryDlOptions galleryDl)
    {
        SubscriptionForm form = subscriptionService.newForm();
        form.setSource(site);
        form.setQuery(query);
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

    /**
     * nhentai's searches from the given one on, by the query's first word: "newest" for the search's newest page,
     * "below" for a page below a cursor (a backfill or a catch-up).
     */
    private List<String> searchKinds(int from)
    {
        List<String> searches = nhentai.searches();
        return searches.subList(from, searches.size()).stream()
                .map(search -> search.substring(0, search.indexOf(' '))
                        + (search.contains(" uploaded:>") ? " below" : " newest"))
                .toList();
    }

    private String status(int id)
    {
        return subscriptionService.overview(null, false).stream().filter(view -> view.id() == id).findFirst()
                .orElseThrow().status();
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

    private int chapter(String galleryId, DownloadStatus status)
    {
        var form = new ChapterForm();
        form.setTitleFull("Subscribed " + galleryId);
        form.setLanguage("English");
        form.setGalleryId(galleryId);
        int chapterId = chapterService.create(form);
        chapterService.setDownloadStatus(chapterId, status);
        chapters.add(chapterId);
        return chapterId;
    }

    /** A rule without conditions: it decides every gallery. */
    private void everyGallery(String mode, boolean allowUpgrade)
    {
        var form = new CompressionRuleForm();
        form.setCompressionMode(mode);
        form.setAllowUpgrade(allowUpgrade);
        ruleService.save(form);
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

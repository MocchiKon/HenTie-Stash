package io.github.mocchikon.hentie;

import com.sun.net.httpserver.HttpServer;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.entity.Subscription;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.SubscriptionRepository;
import io.github.mocchikon.hentie.scrapper.ehentai.EhentaiDownloader;
import io.github.mocchikon.hentie.scrapper.ehentai.EhentaiProperties;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.DownloadService;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.download.DownloadChoices;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * hitomi and e-hentai links through the whole pipeline, with {@link FakeGalleryDl} in gallery-dl's place and a fake
 * e-hentai API: what gallery-dl is asked for, what its pages and failures become.
 */
@SpringBootTest
@Transactional
class GalleryDlDownloadIT
{
    private static final String NO_COMPRESSION = BuiltInCompressionMode.NONE.getKey();

    private static final String HITOMI_JSON = """
            [[2, {"category": "hitomi", "count": 3, "gallery_id": 9001, "title": "Hitomi Gallery 9001",
              "title_jpn": "", "type": "Doujinshi", "language": "English",
              "tags": ["Big Breasts \\u2640", "Big Penis \\u2642", "Full Color"], "artist": ["hitomi artist"],
              "group": ["hitomi group"], "parody": ["original"], "characters": []}]]""";

    private static final String GDATA = """
            {"gmetadata":[{"gid":618395,"token":"0439fa3666","title":"[Circle] E-Hentai Gallery &amp; Co",
            "title_jpn":"","category":"Non-H","filecount":"2","tags":["artist:eh artist","female:big breasts",
            "other:full color","language:english","language:translated"]}]}""";

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired ChapterRepository chapterRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired ImageService imageService;
    @Autowired DownloadService downloadService;
    @Autowired AppProperties appProperties;
    @Autowired EhentaiProperties ehentaiProperties;
    @Autowired EhentaiDownloader ehentaiDownloader;
    @Autowired SubscriptionRepository subscriptionRepository;
    @PersistenceContext EntityManager em;

    private HttpServer api;
    private final AtomicInteger apiCalls = new AtomicInteger();
    private String apiUrlBefore;
    private int idleTimeoutBefore;
    private int metadataTimeoutBefore;
    private int cooldownBefore;

    @BeforeEach
    void setUp() throws IOException
    {
        FakeGalleryDl.reset();
        worker.setPaused(false);
        api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        api.createContext("/api.php", exchange ->
        {
            apiCalls.incrementAndGet();
            byte[] body = GDATA.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        // Shared singletons: restored afterwards.
        apiUrlBefore = ehentaiProperties.getApiUrl();
        ehentaiProperties.setApiUrl("http://127.0.0.1:" + api.getAddress().getPort() + "/api.php");
        cooldownBefore = ehentaiProperties.getCooldownMinutes();
        idleTimeoutBefore = appProperties.getGalleryDl().getIdleTimeoutSeconds();
        metadataTimeoutBefore = appProperties.getGalleryDl().getMetadataTimeoutSeconds();
    }

    @AfterEach
    void restore()
    {
        ehentaiProperties.setApiUrl(apiUrlBefore);
        ehentaiProperties.setCooldownMinutes(cooldownBefore);
        ehentaiDownloader.clearCooldown();
        appProperties.getGalleryDl().setIdleTimeoutSeconds(idleTimeoutBefore);
        appProperties.getGalleryDl().setMetadataTimeoutSeconds(metadataTimeoutBefore);
        api.stop(0);
    }

    private static DownloadChoices choices(String browser, boolean originals, String delay)
    {
        return new DownloadChoices(NO_COMPRESSION, false, new GalleryDlOptions(browser, originals, delay));
    }

    // ---- hitomi --------------------------------------------------------------------------------------------

    @Test
    void shouldFetchMetadataOnceAndThePagesInOneRunWhenDownloadingAHitomiGallery() throws IOException
    {
        // GIVEN a gallery of three pages; the paste asked for cookies and originals, which hitomi ignores.
        FakeGalleryDl.set("json", HITOMI_JSON);
        FakeGalleryDl.set("pages", "3");
        queueService.enqueue(List.of("https://hitomi.la/doujinshi/hitomi-gallery-english-9001.html"),
                choices("firefox", true, "0.5"));
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN the chapter holds what gallery-dl read, tags in their canonical spelling.
            Chapter chapter = chapterRepository.findByGalleryId("hitomi:9001").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getTitleFull()).isEqualTo("Hitomi Gallery 9001");
            assertThat(chapter.getNativeTitle()).isNull();
            assertThat(chapter.getLanguage()).isEqualTo("English");
            assertThat(chapter.getTags()).extracting("name")
                    .containsExactlyInAnyOrder("big breasts ♀", "big breasts", "big penis ♂", "big penis", "full color");
            assertThat(chapter.getArtists()).extracting("name").containsExactly("hitomi artist");
            assertThat(chapter.getCategories()).extracting("name").containsExactly("doujinshi");
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(imageService.pageUrls(chapterId)).hasSize(3);
            assertThat(chapter.getPageNum()).isEqualTo(3);

            // AND gallery-dl was asked twice: once for the metadata, once for every page.
            List<List<String>> calls = FakeGalleryDl.calls();
            assertThat(calls).hasSize(2);
            List<String> metadata = calls.get(0);
            assertThat(metadata).contains("--config-ignore", "--no-input", "-j")
                    .endsWith("https://hitomi.la/galleries/9001.html");
            assertThat(FakeGalleryDl.option(metadata, "--range")).isEqualTo("1");
            List<String> pages = calls.get(1);
            assertThat(FakeGalleryDl.option(pages, "--range")).isEqualTo("1-3");
            assertThat(FakeGalleryDl.option(pages, "--sleep")).isEqualTo("0.5");
            assertThat(FakeGalleryDl.option(pages, "-f")).isEqualTo("{num}.{extension}");
            assertThat(pages).contains("format=webp").doesNotContain("--cookies-from-browser", "--sleep-request")
                    .noneMatch(arg -> arg.startsWith("original="));
            // AND its folder inside staging was removed with the rest of staging.
            assertThat(FakeGalleryDl.option(pages, "-D")).contains(".gallery-dl-");
            assertThat(java.nio.file.Path.of(FakeGalleryDl.option(pages, "-D"))).doesNotExist();
            assertThat(queueRepository.findAll()).noneMatch(item -> "hitomi:9001".equals(item.getGalleryId()));
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldAskOnlyForTheMissingPagesWhenAnAttemptFailedPartWay() throws IOException
    {
        // GIVEN page 2 fails the first time.
        FakeGalleryDl.set("json", HITOMI_JSON);
        FakeGalleryDl.set("pages", "3");
        FakeGalleryDl.set("fail", "2");
        queueService.enqueue(List.of("hitomi:9001"), choices(null, false, "0"));
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the attempt failed, nothing is published, and gallery-dl's own words say why.
            DownloadQueueItem item = queueRepository.findByLink("hitomi:9001").orElseThrow();
            assertThat(item.getAttempts()).isEqualTo(1);
            assertThat(item.getError()).isNull();
            Chapter chapter = chapterRepository.findByGalleryId("hitomi:9001").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.PENDING);
            assertThat(imageService.pageUrls(chapterId)).isEmpty();

            // WHEN the next attempt runs and the page arrives
            FakeGalleryDl.set("fail", "");
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN only page 2 was asked for: 1 and 3 came from staging.
            assertThat(FakeGalleryDl.option(FakeGalleryDl.calls().getLast(), "--range")).isEqualTo("2");
            assertThat(chapterRepository.findById(chapterId).orElseThrow().getDownloadStatus())
                    .isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(imageService.pageUrls(chapterId)).hasSize(3);
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldLeaveAGapWhenRetryingWhileIgnoringImageErrors() throws IOException
    {
        // GIVEN a page that never arrives, and an item retried in lenient mode
        FakeGalleryDl.set("json", HITOMI_JSON);
        FakeGalleryDl.set("pages", "3");
        FakeGalleryDl.set("fail", "2");
        queueService.enqueue(List.of("hitomi:9001"), choices(null, false, "0"));
        DownloadQueueItem item = queueRepository.findByLink("hitomi:9001").orElseThrow();
        item.setIgnoreImageErrors(true);
        queueRepository.save(item);
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN the chapter is finished with a gap where page 2 belongs.
            Chapter chapter = chapterRepository.findByGalleryId("hitomi:9001").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(imageService.pageNumbersOnDisk(chapterId)).containsExactlyInAnyOrder(1, 3);
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldNotFinishAChapterWithoutPagesEvenWhenIgnoringImageErrors() throws IOException
    {
        // GIVEN every page fails, in lenient mode
        FakeGalleryDl.set("json", HITOMI_JSON);
        FakeGalleryDl.set("pages", "0");
        queueService.enqueue(List.of("hitomi:9001"), choices(null, false, "0"));
        DownloadQueueItem item = queueRepository.findByLink("hitomi:9001").orElseThrow();
        item.setIgnoreImageErrors(true);
        queueRepository.save(item);
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the attempt failed: a finished chapter with no pages could never be fixed by queueing it again.
            Chapter chapter = chapterRepository.findByGalleryId("hitomi:9001").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.PENDING);
            assertThat(queueRepository.findByLink("hitomi:9001").orElseThrow().getAttempts()).isEqualTo(1);
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldNeverSkipAPageGalleryDlCouldNotWrite() throws IOException
    {
        // GIVEN a page lost to a failed write (exit bit 128), in lenient mode
        FakeGalleryDl.set("json", HITOMI_JSON);
        FakeGalleryDl.set("pages", "3");
        FakeGalleryDl.set("fail", "3");
        FakeGalleryDl.set("exit", "128");
        FakeGalleryDl.set("stderr", "[download][error] Unable to download data:  OSError: [Errno 28] No space left");
        queueService.enqueue(List.of("hitomi:9001"), choices(null, false, "0"));
        DownloadQueueItem item = queueRepository.findByLink("hitomi:9001").orElseThrow();
        item.setIgnoreImageErrors(true);
        queueRepository.save(item);
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the attempt failed rather than finishing without page 3.
            Chapter chapter = chapterRepository.findByGalleryId("hitomi:9001").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.PENDING);
            assertThat(imageService.pageUrls(chapterId)).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldFailTheItemAtOnceWhenTheSiteHasNoSuchGallery() throws IOException
    {
        // GIVEN gallery-dl's answer for a gallery that does not exist
        FakeGalleryDl.set("json",
                "[[-1, {\"error\": \"NotFoundError\", \"message\": \"Requested gallery could not be found\"}]]");
        queueService.enqueue(List.of("hitomi:404"), choices(null, false, "0"));

        // WHEN
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN it lands in the Failed list with gallery-dl's reason, and no chapter was made.
        DownloadQueueItem item = queueRepository.findByLink("hitomi:404").orElseThrow();
        assertThat(item.getError()).contains("could not be found");
        assertThat(chapterRepository.findByGalleryId("hitomi:404")).isEmpty();
    }

    @Test
    void shouldStopAGalleryDlThatPrintsNothing() throws IOException
    {
        // GIVEN a gallery-dl that hangs, and a short idle timeout
        FakeGalleryDl.set("hang", "true");
        appProperties.getGalleryDl().setIdleTimeoutSeconds(2);
        appProperties.getGalleryDl().setMetadataTimeoutSeconds(60);
        queueService.enqueue(List.of("hitomi:9002"), choices(null, false, "0"));

        // WHEN
        long start = System.nanoTime();
        assertThat(worker.processNext()).isTrue();
        em.flush();

        // THEN the attempt failed after the timeout rather than holding the worker.
        assertThat(System.nanoTime() - start).isLessThan(java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
        DownloadQueueItem item = queueRepository.findByLink("hitomi:9002").orElseThrow();
        assertThat(item.getAttempts()).isEqualTo(1);
    }

    // ---- e-hentai ------------------------------------------------------------------------------------------

    @Test
    void shouldTakeMetadataFromTheApiAndPagesFromExhentaiWithCookies() throws IOException
    {
        // GIVEN
        FakeGalleryDl.set("pages", "2");
        queueService.enqueue(List.of("https://e-hentai.org/g/618395/0439fa3666/"), choices("firefox", true, "0.4-0.65"));
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();
            em.clear();

            // THEN the metadata came from the API, once...
            assertThat(apiCalls.get()).isEqualTo(1);
            Chapter chapter = chapterRepository.findByGalleryId("ehentai:618395/0439fa3666").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getTitleFull()).isEqualTo("[Circle] E-Hentai Gallery & Co");
            assertThat(chapter.getLanguage()).isEqualTo("English");
            assertThat(chapter.getTags()).extracting("name").containsExactlyInAnyOrder("big breasts ♀", "big breasts",
                    "full color");
            assertThat(chapter.getArtists()).extracting("name").containsExactly("eh artist");
            assertThat(chapter.getCategories()).extracting("name").containsExactly("non-h");
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(imageService.pageUrls(chapterId)).hasSize(2);

            // ...and the pages from exhentai, since cookies were chosen, with the paste's choices.
            List<List<String>> calls = FakeGalleryDl.calls();
            assertThat(calls).hasSize(1);
            List<String> run = calls.getFirst();
            assertThat(run.getLast()).isEqualTo("https://exhentai.org/g/618395/0439fa3666/");
            assertThat(FakeGalleryDl.option(run, "--cookies-from-browser")).isEqualTo("firefox");
            assertThat(FakeGalleryDl.option(run, "--sleep-request")).isEqualTo("0.4-0.65");
            assertThat(run).contains("original=true").doesNotContain("--sleep");
            assertThat(FakeGalleryDl.option(run, "--range")).isEqualTo("1-2");

            // ...so the chapter links to exhentai, which may have galleries e-hentai hides.
            assertThat(chapter.getSourceSite()).isEqualTo("exhentai");
            assertThat(downloadService.sourcePageLink(chapter)).contains("https://exhentai.org/g/618395/0439fa3666/");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldUseEhentaiWithoutCookies() throws IOException
    {
        // GIVEN
        FakeGalleryDl.set("pages", "2");
        queueService.enqueue(List.of("https://exhentai.org/g/618395/0439fa3666/"), choices(null, false, "1"));
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN e-hentai.org, whatever domain was pasted, and no cookies...
            Chapter chapter = chapterRepository.findByGalleryId("ehentai:618395/0439fa3666").orElseThrow();
            chapterId = chapter.getId();
            List<String> run = FakeGalleryDl.calls().getFirst();
            assertThat(run.getLast()).isEqualTo("https://e-hentai.org/g/618395/0439fa3666/");
            assertThat(run).contains("original=false").doesNotContain("--cookies-from-browser");
            // ...and the chapter links there.
            assertThat(chapter.getSourceSite()).isNull();
            assertThat(downloadService.sourcePageLink(chapter)).contains("https://e-hentai.org/g/618395/0439fa3666/");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldFallBackToEhentaiOnceWhenExhentaiRefusesTheAccount() throws IOException
    {
        // GIVEN exhentai shows this account nothing
        FakeGalleryDl.set("pages", "2");
        FakeGalleryDl.set("exhentai.org.exit", "16");
        FakeGalleryDl.set("exhentai.org.stderr", "[exhentai][error] AuthorizationError: Insufficient privileges");
        queueService.enqueue(List.of("ehentai:618395/0439fa3666"), choices("firefox", false, "0"));
        Integer chapterId = null;
        try
        {
            // WHEN
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the pages came from e-hentai.org on a second run.
            Chapter chapter = chapterRepository.findByGalleryId("ehentai:618395/0439fa3666").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getDownloadStatus()).isEqualTo(DownloadStatus.SUCCESSFUL);
            assertThat(FakeGalleryDl.calls()).extracting(List::getLast).containsExactly(
                    "https://exhentai.org/g/618395/0439fa3666/", "https://e-hentai.org/g/618395/0439fa3666/");
            // The account cannot see exhentai, so the chapter links to e-hentai.
            assertThat(downloadService.sourcePageLink(chapter)).contains("https://e-hentai.org/g/618395/0439fa3666/");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldStopAskingEhentaiForAWhileAfterABan() throws IOException
    {
        // GIVEN gallery-dl reports a ban
        ehentaiProperties.setCooldownMinutes(60);
        FakeGalleryDl.set("pages", "2");
        FakeGalleryDl.set("exit", "16");
        FakeGalleryDl.set("stderr", "[exhentai][error] AuthorizationError: Temporarily Banned");
        queueService.enqueue(List.of("ehentai:618395/0439fa3666", "ehentai:1/0123456789"), choices(null, false, "0"));
        Integer chapterId = null;
        try
        {
            // WHEN the first item runs into it
            assertThat(worker.processNext()).isTrue();
            em.flush();
            chapterId = chapterRepository.findByGalleryId("ehentai:618395/0439fa3666").map(Chapter::getId).orElse(null);

            // THEN it fails at once, saying until when
            assertThat(queueRepository.findByLink("ehentai:618395/0439fa3666").orElseThrow().getError())
                    .contains("Temporarily Banned").contains("not attempted until");

            // WHEN the next e-hentai item comes up
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN it fails too, without the API or gallery-dl being asked again.
            assertThat(queueRepository.findByLink("ehentai:1/0123456789").orElseThrow().getError())
                    .contains("not asked again until");
            assertThat(apiCalls.get()).isEqualTo(1);
            assertThat(FakeGalleryDl.calls()).hasSize(1);
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /**
     * A subscription's galleries wait out the ban in the queue instead of failing one after the other: their failure
     * would say nothing about them, and the subscription would only list more to fail the same way.
     */
    @Test
    void shouldKeepASubscriptionsDownloadsWaitingThroughABan() throws IOException
    {
        // GIVEN two galleries a subscription queued, and gallery-dl reporting a ban
        ehentaiProperties.setCooldownMinutes(60);
        FakeGalleryDl.set("pages", "2");
        FakeGalleryDl.set("exit", "16");
        FakeGalleryDl.set("stderr", "[exhentai][error] AuthorizationError: Temporarily Banned");
        var subscription = new Subscription();
        subscription.setSource("e-hentai");
        subscription.setQuery("f_search=x");
        subscription.setPollMinutes(60);
        subscription.setRecheckEveryHours(24);
        subscription.setRecheckDepthHours(48);
        subscription.setRequestDelay("0");
        int subscriptionId = subscriptionRepository.save(subscription).getId();
        queueService.queueForSubscription(subscriptionId, List.of("https://e-hentai.org/g/618395/0439fa3666/",
                "https://e-hentai.org/g/1/0123456789/"), choices(null, false, "0"));
        Integer chapterId = null;
        try
        {
            // WHEN the first runs into the ban, and the worker looks for more
            assertThat(worker.processNext()).isTrue();
            em.flush();
            chapterId = chapterRepository.findByGalleryId("ehentai:618395/0439fa3666").map(Chapter::getId).orElse(null);
            boolean ranAnother = worker.processNext();

            // THEN both still wait, nothing counted, and nothing more was asked of the site
            assertThat(ranAnother).isFalse();
            assertThat(queueRepository.findAll())
                    .filteredOn(row -> Integer.valueOf(subscriptionId).equals(row.getSubscriptionId()))
                    .hasSize(2)
                    .allSatisfy(row ->
                    {
                        assertThat(row.getError()).isNull();
                        assertThat(row.getAttempts()).isZero();
                    });
            assertThat(apiCalls.get()).isEqualTo(1);
            assertThat(FakeGalleryDl.calls()).hasSize(1);

            // WHEN the cooldown is over
            ehentaiDownloader.clearCooldown();

            // THEN the first is next again
            assertThat(queueService.nextPending()).get().extracting(DownloadQueueItem::getGalleryId)
                    .isEqualTo("ehentai:618395/0439fa3666");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    /** Files are not rolled back with the transaction. */
    private void cleanUp(Integer chapterId)
    {
        if (chapterId != null)
        {
            imageService.discardStagedPages(chapterId);
            imageService.deleteAll(chapterId);
        }
    }
}

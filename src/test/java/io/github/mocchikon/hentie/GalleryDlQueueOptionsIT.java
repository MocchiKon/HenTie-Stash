package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.scrapper.gallerydl.GalleryDlOptions;
import io.github.mocchikon.hentie.service.download.DownloadChoices;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.PermanentDownloadException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gallery-dl choices on a queue row: a paste sets them and a re-paste replaces them, a retry keeps them, and a
 * re-download in full quality takes the defaults it is given.
 */
@SpringBootTest
@Transactional
class GalleryDlQueueOptionsIT
{
    private static final String NONE = BuiltInCompressionMode.NONE.getKey();

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadQueueRepository queueRepository;

    private static DownloadChoices choices(String browser, boolean originals, String delay)
    {
        return new DownloadChoices(NONE, false, new GalleryDlOptions(browser, originals, delay));
    }

    @Test
    void shouldStoreThePastesChoicesOnTheRow()
    {
        // WHEN
        queueService.enqueue(List.of("hitomi:7101"), choices("firefox", true, "0.4-0.65"));

        // THEN
        DownloadQueueItem item = queueRepository.findByLink("hitomi:7101").orElseThrow();
        assertThat(item.getCookiesBrowser()).isEqualTo("firefox");
        assertThat(item.isDownloadOriginals()).isTrue();
        assertThat(item.getRequestDelay()).isEqualTo("0.4-0.65");
    }

    @Test
    void shouldReplaceTheChoicesWhenALinkIsPastedAgain()
    {
        // GIVEN one waiting and one failed row
        queueService.enqueue(List.of("hitomi:7102", "hitomi:7103"), choices("firefox", true, "2"));
        DownloadQueueItem failed = queueRepository.findByLink("hitomi:7103").orElseThrow();
        failed.setError("it failed");
        queueRepository.save(failed);

        // WHEN both are pasted again with other choices
        queueService.enqueue(List.of("hitomi:7102", "hitomi:7103"), choices(null, false, "0.5"));

        // THEN both take them.
        for (String link : List.of("hitomi:7102", "hitomi:7103"))
        {
            DownloadQueueItem item = queueRepository.findByLink(link).orElseThrow();
            assertThat(item.getCookiesBrowser()).isNull();
            assertThat(item.isDownloadOriginals()).isFalse();
            assertThat(item.getRequestDelay()).isEqualTo("0.5");
        }
    }

    @Test
    void shouldKeepTheChoicesWhenRetrying()
    {
        // GIVEN a failed row
        queueService.enqueue(List.of("hitomi:7104"), choices("librewolf", true, "3"));
        DownloadQueueItem item = queueRepository.findByLink("hitomi:7104").orElseThrow();
        item.setError("it failed");
        queueRepository.save(item);

        // WHEN it is retried, in lenient mode
        assertThat(queueService.retry(item.getId(), true, false)).isTrue();

        // THEN its choices are the paste's.
        DownloadQueueItem retried = queueRepository.findByLink("hitomi:7104").orElseThrow();
        assertThat(retried.getCookiesBrowser()).isEqualTo("librewolf");
        assertThat(retried.isDownloadOriginals()).isTrue();
        assertThat(retried.getRequestDelay()).isEqualTo("3");
    }

    @Test
    void shouldTakeTheGivenDefaultsForAFullQualityRedownload()
    {
        // GIVEN a waiting row with a paste's choices
        queueService.enqueue(List.of("hitomi:7105"), choices("firefox", true, "3"));

        // WHEN it is turned into a re-download with Settings' defaults
        queueService.enqueueFullQuality("hitomi:7105", "hitomi:7105", new GalleryDlOptions(null, false, "0.4-0.65"));

        // THEN
        DownloadQueueItem item = queueRepository.findByLink("hitomi:7105").orElseThrow();
        assertThat(item.isReplacePages()).isTrue();
        assertThat(item.getCookiesBrowser()).isNull();
        assertThat(item.isDownloadOriginals()).isFalse();
        assertThat(item.getRequestDelay()).isEqualTo("0.4-0.65");
    }

    @Test
    void shouldRefuseARowWhoseDelayGalleryDlCannotRead()
    {
        // GIVEN a hand-edited row
        var item = new DownloadQueueItem();
        item.setLink("hitomi:7106");
        item.setGalleryId("hitomi:7106");
        item.setRequestDelay("fast");

        // WHEN + THEN it is never fetched without a delay
        assertThatThrownBy(() -> DownloadQueueService.galleryDlOptions(item))
                .isInstanceOf(PermanentDownloadException.class)
                .hasMessageContaining("fast");
    }
}

package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.TestDownloads;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.service.download.DownloadChoices;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DownloadServiceTest
{
    private final DownloadQueueService queueService = mock(DownloadQueueService.class);
    private final DownloadWorker worker = mock(DownloadWorker.class);
    private final DataDownloaderRegistry registry = mock(DataDownloaderRegistry.class);
    private final SettingsService settingsService = mock(SettingsService.class);
    private final DownloadService service = new DownloadService(queueService, worker, registry, settingsService);

    @Test
    void shouldReturnEmptyWhenInputIsNull()
    {
        // WHEN
        List<String> result = service.parseLinks(null);

        // THEN
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenInputIsBlank()
    {
        // WHEN
        List<String> result = service.parseLinks("   \n  \t ");

        // THEN
        assertThat(result).isEmpty();
    }

    @Test
    void shouldTrimAndDropBlankLinesWhenParsingLinks()
    {
        // GIVEN
        String raw = "  https://a.example/1  \n\n   \n https://a.example/2 ";

        // WHEN
        List<String> result = service.parseLinks(raw);

        // THEN
        assertThat(result)
                .containsExactly("https://a.example/1", "https://a.example/2");
    }

    @Test
    void shouldDeDuplicatePreservingFirstOccurrenceOrderWhenParsingLinks()
    {
        // GIVEN
        String raw = "https://a/1\nhttps://a/2\nhttps://a/1\nhttps://a/3";

        // WHEN
        List<String> result = service.parseLinks(raw);

        // THEN
        assertThat(result)
                .containsExactly("https://a/1", "https://a/2", "https://a/3");
    }

    @Test
    void shouldHandleMixedLineBreaksWhenParsingLinks()
    {
        // GIVEN
        String raw = "https://a/1\r\nhttps://a/2\rhttps://a/3\nhttps://a/4";

        // WHEN
        List<String> result = service.parseLinks(raw);

        // THEN
        assertThat(result)
                .containsExactly("https://a/1", "https://a/2", "https://a/3", "https://a/4");
    }

    @Test
    void shouldNotTouchTheQueueWhenThereIsNothingToQueue()
    {
        // WHEN
        var result = service.queue(List.of(), TestDownloads.choices("NONE", false));

        // THEN
        assertThat(result.queued()).isZero();
        verifyNoInteractions(queueService);
        verify(worker, never()).kick();
    }

    @Test
    void shouldQueueTheLinksAndWakeTheWorker()
    {
        // GIVEN
        when(queueService.enqueue(any(), any())).thenReturn(new DownloadQueueService.EnqueueResult(2, 1, 0, 1));
        DownloadChoices choices = TestDownloads.choices("LOSSLESS", true);

        // WHEN
        var result = service.queue(List.of("mock:1", "mock:2", "mock:3", "junk"), choices);

        // THEN the links go to the queue verbatim, with the paste form's choices
        final var captor = ArgumentCaptor.forClass(List.class);
        verify(queueService).enqueue(captor.capture(), eq(choices));
        assertThat(captor.getValue()).containsExactly("mock:1", "mock:2", "mock:3", "junk");
        // ...the worker is woken rather than left to its idle poll...
        verify(worker).kick();
        // ...and unsupported links are counted too.
        assertThat(result.queued()).isEqualTo(3);
        assertThat(result.rejected()).isEqualTo(1);
    }
}

package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.service.ChapterService.StatsSync;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** The heal's answer for each resync outcome, which decides whether the detail page builds its view again. */
class ImageStatsServiceUnitTest
{
    private static final int CHAPTER_ID = 7;

    private final ChapterService chapterService = mock(ChapterService.class);
    private final ImageService imageService = mock(ImageService.class);
    private final ImageStatsService service = new ImageStatsService(mock(ChapterRepository.class), chapterService,
            imageService, new WriteGate(new AppProperties()));

    /** An unreadable folder keeps the stored numbers, so reading the view again would show the same ones. */
    @Test
    void shouldNotAskForAnotherReadWhenTheFolderCouldNotBeRead()
    {
        // GIVEN
        when(chapterService.syncImageStats(CHAPTER_ID)).thenReturn(StatsSync.UNREADABLE);

        // WHEN
        boolean readAgain = service.healIfDrifted(CHAPTER_ID, 2, 0);

        // THEN
        assertThat(readAgain).isFalse();
        verify(imageService, never()).invalidateListing(CHAPTER_ID);
    }

    @Test
    void shouldAskForAnotherReadWhenTheStatsWereRepaired()
    {
        // GIVEN
        when(chapterService.syncImageStats(CHAPTER_ID)).thenReturn(StatsSync.REPAIRED);

        // WHEN
        boolean readAgain = service.healIfDrifted(CHAPTER_ID, 0, 2);

        // THEN
        assertThat(readAgain).isTrue();
        verify(imageService).invalidateListing(CHAPTER_ID);
    }

    @Test
    void shouldNotResyncWhenTheStoredCountMatchesTheListing()
    {
        // WHEN
        boolean readAgain = service.healIfDrifted(CHAPTER_ID, 2, 2);

        // THEN
        assertThat(readAgain).isFalse();
        verifyNoInteractions(chapterService);
    }
}

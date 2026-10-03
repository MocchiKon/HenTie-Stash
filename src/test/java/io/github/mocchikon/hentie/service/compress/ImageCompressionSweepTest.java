package io.github.mocchikon.hentie.service.compress;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The order of the sweep's writes, which no integration test can stop halfway. Nothing rebuilds a chapter's
 * mode from its files, and a sweep run again finds no input left in a finished chapter, so a mode not yet
 * recorded when the walk stops is lost for good.
 */
class ImageCompressionSweepTest
{
    private static final CompressionProfile PROFILE = new CompressionProfile("custom:7", "Sweep JXL",
            ImageEncoder.JXL, List.of("-q", "40"), List.of(), 0, 0, Set.of());

    private final ChapterRepository chapterRepository = mock(ChapterRepository.class);
    private final ChapterService chapterService = mock(ChapterService.class);
    private final ImageCompressionService compressionService = mock(ImageCompressionService.class);
    private final SettingsService settingsService = mock(SettingsService.class);
    private final ImageCompressionSweep sweep = new ImageCompressionSweep(chapterRepository, chapterService,
            compressionService, mock(ImageCompressionModeService.class), settingsService,
            new WriteGate(new AppProperties()));

    @Test
    void shouldRecordAChaptersModeBeforeMovingOnToTheNextChapter()
    {
        // GIVEN one slice of two chapters, the second of which ends the walk, as a shutdown would.
        when(settingsService.getImageCompressionMode()).thenReturn(PROFILE.key());
        when(compressionService.profileFor(PROFILE.key())).thenReturn(Optional.of(PROFILE));
        when(compressionService.exclusively(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(chapterRepository.findIdsAfter(eq(0), any(Pageable.class))).thenReturn(List.of(11, 12));
        when(compressionService.compressChapter(11, PROFILE))
                .thenReturn(new ImageCompressionService.Summary(2, 2, 900, 400));
        when(compressionService.compressChapter(12, PROFILE)).thenThrow(new IllegalStateException("stopped"));

        // WHEN
        assertThatThrownBy(sweep::compressAll).hasMessage("stopped");

        // THEN the finished chapter already names its mode, and the unfinished one does not.
        verify(chapterService).setCompressionMode(11, PROFILE.key());
        verify(chapterService, never()).setCompressionMode(eq(12), any());
    }
}

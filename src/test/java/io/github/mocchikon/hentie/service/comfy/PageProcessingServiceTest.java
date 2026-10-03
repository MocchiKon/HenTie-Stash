package io.github.mocchikon.hentie.service.comfy;

import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Asker;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Progress;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.Progress.Estimate;
import io.github.mocchikon.hentie.service.comfy.PageProcessingService.RunTime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Built from numbers alone, so every case is pinned down here instead of timed in a suite. */
class PageProcessingServiceTest
{
    private static final String NODE = "Upscale Image (using Model)";

    // ---- the estimate ------------------------------------------------------

    /** 20 s into a run whose predecessors took 50 s (55 s the slow ones): 40%, and ComfyUI's own kind of range. */
    @Test
    void shouldCountDownFromWhatTheWorkflowsRecentRunsTook()
    {
        // GIVEN the node reporting a coarse 1 of 4 tiles
        var progress = Progress.running(NODE, 1, 4).withEstimate(new Estimate(20_000, 50_000, 55_000));

        // THEN the time decides, not the tile count
        assertThat(progress.percent()).isEqualTo(40);
        assertThat(progress.message()).isEqualTo(NODE + " 40%, ~30-35 s left");
    }

    @Test
    void shouldGiveOneNumberOnceTheRangeCloses()
    {
        // GIVEN
        var progress = Progress.running(NODE, 2, 4).withEstimate(new Estimate(46_400, 50_000, 50_000));

        // THEN
        assertThat(progress.percent()).isEqualTo(92);
        assertThat(progress.message()).isEqualTo(NODE + " 92%, ~4 s left");
    }

    /** Past the typical run but not the slow ones: never 100% before the result is here. */
    @Test
    void shouldNeverSayDoneBeforeTheResultIsHere()
    {
        // GIVEN
        var progress = Progress.running(NODE, 3, 4).withEstimate(new Estimate(52_000, 50_000, 55_000));

        // THEN
        assertThat(progress.percent()).isEqualTo(99);
        assertThat(progress.message()).isEqualTo(NODE + " 99%, ~1-3 s left");
    }

    /** Past even the slow runs the estimate says nothing more, so the node's own count is shown again. */
    @Test
    void shouldFallBackToTheNodesOwnCountOnceARunTakesLongerThanUsual()
    {
        // GIVEN
        var progress = Progress.running(NODE, 2, 4).withEstimate(new Estimate(60_000, 50_000, 55_000));

        // THEN
        assertThat(progress.percent()).isEqualTo(50);
        assertThat(progress.message()).isEqualTo(NODE + " 50%, taking longer than usual");
    }

    /** Until pages of another size have run, time is assumed to follow the pixels: four times them, four times it. */
    @Test
    void shouldExpectTimeToFollowThePixelsUntilPagesOfAnotherSizeHaveRun()
    {
        // GIVEN three runs on 500x500 pages, of 10, 11 and 12 s
        var runs = List.of(new RunTime(10_000, 250_000), new RunTime(11_000, 250_000), new RunTime(12_000, 250_000));

        // WHEN a 1000x1000 page has run for 4 s
        var estimate = Estimate.of(runs, 1_000_000, 4_000);

        // THEN the median and the 75th percentile of 40, 44 and 48 s
        assertThat(Estimate.sizeExponent(runs)).isEqualTo(1);
        assertThat(estimate).isEqualTo(new Estimate(4_000, 44_000, 48_000));
    }

    /** Each run is scaled from its own page's size, as far as the runs show the time follows it. */
    @Test
    void shouldScaleEachRunByItsOwnPage()
    {
        // GIVEN a 500x500 page done in 10 s and a 1000x1000 one in 40 s: the time follows the pixels
        var runs = List.of(new RunTime(10_000, 250_000), new RunTime(40_000, 1_000_000));

        // WHEN a 750x1000 page starts
        var estimate = Estimate.of(runs, 750_000, 0);

        // THEN
        assertThat(Estimate.sizeExponent(runs)).isEqualTo(1);
        assertThat(estimate).isEqualTo(new Estimate(0, 30_000, 30_000));
    }

    /** A workflow that scales every page to one size first takes as long on any page - and the runs show it. */
    @Test
    void shouldLearnThatAWorkflowTakesAsLongOnAnyPage()
    {
        // GIVEN 10-11 s on 500x500 and 1000x1000 pages alike
        var runs = List.of(new RunTime(10_000, 250_000), new RunTime(10_000, 1_000_000), new RunTime(11_000, 250_000));

        // WHEN a page twice as large starts
        var estimate = Estimate.of(runs, 2_000_000, 0);

        // THEN it is expected to take as long as the others
        assertThat(Estimate.sizeExponent(runs)).isZero();
        assertThat(estimate).isEqualTo(new Estimate(0, 10_000, 11_000));
    }

    /** A fixed part besides the pixels - overhead, a pass at a fixed size - makes the time grow slower than them. */
    @Test
    void shouldLearnHowFarTheTimeFollowsThePixels()
    {
        // GIVEN 10 s on a 1 megapixel page and 15 s on a 2 megapixel one
        var runs = List.of(new RunTime(10_000, 1_000_000), new RunTime(15_000, 2_000_000));

        // WHEN a 4 megapixel page starts
        var estimate = Estimate.of(runs, 4_000_000, 0);

        // THEN each doubling costs half as much again: 22.5 s
        assertThat(Estimate.sizeExponent(runs)).isCloseTo(Math.log(1.5) / Math.log(2), within(1e-9));
        assertThat(estimate).isEqualTo(new Estimate(0, 22_500, 22_500));
    }

    /**
     * The first run loads the model and is slow. A line fitted through the runs would lean towards it; the
     * median of what each pair says does not.
     */
    @Test
    void shouldNotLetOneSlowRunDecideHowTheTimeFollowsThePixels()
    {
        // GIVEN a slow first run, three runs of 10 s on the same size, and one of 20 s on twice the pixels
        var runs = List.of(new RunTime(20_000, 1_000_000), new RunTime(10_000, 1_000_000),
                new RunTime(10_000, 1_000_000), new RunTime(10_000, 1_000_000), new RunTime(20_000, 2_000_000));

        // WHEN a 4 megapixel page starts
        var estimate = Estimate.of(runs, 4_000_000, 0);

        // THEN
        assertThat(Estimate.sizeExponent(runs)).isEqualTo(1);
        assertThat(estimate).isEqualTo(new Estimate(0, 40_000, 40_000));
    }

    /** Pages 20% apart say more about noise than about their size, so they are not used. */
    @Test
    void shouldNotLearnFromPagesTooCloseInSize()
    {
        // GIVEN 10 s on 1 and on 1.2 megapixels, which would say the size does not matter
        var runs = List.of(new RunTime(10_000, 1_000_000), new RunTime(10_000, 1_200_000));

        // WHEN a 2 megapixel page starts
        var estimate = Estimate.of(runs, 2_000_000, 0);

        // THEN the time is still taken to follow the pixels
        assertThat(Estimate.sizeExponent(runs)).isEqualTo(1);
        assertThat(estimate).isEqualTo(new Estimate(0, 16_667, 20_000));
    }

    /**
     * The time comes from the latest runs, to follow a machine that became slower; the size exponent from all
     * of them, since pages of another size are seldom among the latest.
     */
    @Test
    void shouldTakeTheTimeFromTheLatestRunsAndTheSizeFromAllOfThem()
    {
        // GIVEN a 4 megapixel page as quick as a 1 megapixel one long ago, and then five runs of 12 s
        var runs = List.of(new RunTime(10_000, 1_000_000), new RunTime(10_000, 4_000_000),
                new RunTime(12_000, 1_000_000), new RunTime(12_000, 1_000_000), new RunTime(12_000, 1_000_000),
                new RunTime(12_000, 1_000_000), new RunTime(12_000, 1_000_000));

        // WHEN another 4 megapixel page starts
        var estimate = Estimate.of(runs, 4_000_000, 0);

        // THEN
        assertThat(Estimate.sizeExponent(runs)).isZero();
        assertThat(estimate).isEqualTo(new Estimate(0, 12_000, 12_000));
    }

    /** Six times the time for twice the pixels is noise, not a real cost, so it counts as the square at most. */
    @Test
    void shouldKeepTheLearntExponentToWhatAWorkflowCanCost()
    {
        var runs = List.of(new RunTime(10_000, 1_000_000), new RunTime(60_000, 2_000_000));

        assertThat(Estimate.sizeExponent(runs)).isEqualTo(Estimate.MAX_EXPONENT);
    }

    /** Without both sizes - a page or a run whose header said nothing - a run counts as it came. */
    @Test
    void shouldTakeARunAsItCameWithoutTheSizesToScaleIt()
    {
        // GIVEN
        var unknownPage = Estimate.of(List.of(new RunTime(10_000, 250_000)), 0, 1_000);
        var unknownRun = Estimate.of(List.of(new RunTime(10_000, 0)), 1_000_000, 1_000);

        // THEN - and a run of unknown size says nothing about how the time follows the size either
        assertThat(unknownPage).isEqualTo(new Estimate(1_000, 10_000, 10_000));
        assertThat(unknownRun).isEqualTo(new Estimate(1_000, 10_000, 10_000));
        assertThat(Estimate.sizeExponent(List.of(new RunTime(10_000, 0), new RunTime(90_000, 4_000_000))))
                .isEqualTo(1);
    }

    @Test
    void shouldCountInMinutesPastAMinuteAsComfyUiDoes()
    {
        // GIVEN
        var minutes = Progress.running(NODE, 0, 0).withEstimate(new Estimate(10_000, 190_000, 250_000));
        var aboutOne = Progress.running(NODE, 0, 0).withEstimate(new Estimate(0, 70_000, 80_000));
        var upToTwo = Progress.running(NODE, 0, 0).withEstimate(new Estimate(0, 40_000, 70_000));

        // THEN
        assertThat(minutes.message()).isEqualTo(NODE + " 5%, ~3-4 min left");
        assertThat(aboutOne.message()).isEqualTo(NODE + " 0%, ~1 min left");
        assertThat(upToTwo.message()).isEqualTo(NODE + " 0%, ~1-2 min left");
    }

    /** A workflow's first run has nothing to go by: the node's own count, as ComfyUI reports it. */
    @Test
    void shouldShowTheNodesOwnCountWithoutAnEstimate()
    {
        // GIVEN
        var counted = Progress.running(NODE, 1, 2);
        var started = Progress.running(null, 0, 0);

        // THEN
        assertThat(counted.percent()).isEqualTo(50);
        assertThat(counted.message()).isEqualTo(NODE + " 50%");
        assertThat(started.percent()).isNull();
        assertThat(started.message()).isEqualTo("Processing");
    }

    @Test
    void shouldSayWhereAPageThatIsNotRunningYetIs()
    {
        assertThat(Progress.queued(0).message()).isEqualTo("Queued");
        assertThat(Progress.queued(1).message()).isEqualTo("Queued - 1 page ahead");
        assertThat(Progress.queued(3).message()).isEqualTo("Queued - 3 pages ahead");
        assertThat(Progress.starting().message()).isEqualTo("Waiting for ComfyUI to start");
        assertThat(Progress.waiting(2).message()).isEqualTo("Waiting in ComfyUI's queue - 2 ahead");
        assertThat(Progress.queued(3).percent()).isNull();
    }

    // ---- who asks ----------------------------------------------------------

    @Test
    void shouldTakeTheViewerIdAViewerMakesUp()
    {
        assertThat(Asker.of("0f3a9c2e81d4b7a6", 7)).isEqualTo(new Asker("0f3a9c2e81d4b7a6", 7));
    }

    /** Anything else is asked anonymously, never refused: the page still comes, only nothing is superseded. */
    @Test
    void shouldAskAnonymouslyForAnIdNoViewerWouldMakeUp()
    {
        assertThat(Asker.of(null, 7)).isEqualTo(Asker.ANONYMOUS);
        assertThat(Asker.of("", 7)).isEqualTo(Asker.ANONYMOUS);
        assertThat(Asker.of("../x", 7)).isEqualTo(Asker.ANONYMOUS);
        assertThat(Asker.of("a".repeat(65), 7)).isEqualTo(Asker.ANONYMOUS);
        assertThat(Asker.of(null, 7, List.of("3.png"))).isEqualTo(Asker.ANONYMOUS);
    }

    @Test
    void shouldKeepThePlanAViewerSendsInItsOrder()
    {
        assertThat(Asker.of("0f3a9c2e81d4b7a6", 7, List.of("3.png", "4.png", "2.png")))
                .isEqualTo(new Asker("0f3a9c2e81d4b7a6", 7, List.of("3.png", "4.png", "2.png")));
    }

    /** The plan stays in memory while the viewer's interest lasts, so its size is capped. */
    @Test
    void shouldHoldThePlanToWhatAViewerCanSend()
    {
        // GIVEN a name no page has, and more pages than any viewer plans
        var names = new ArrayList<String>();
        names.add("x".repeat(Asker.MAX_PAGE_NAME) + ".png");
        for (int page = 1; page <= Asker.MAX_PLAN + 10; page++)
        {
            names.add(page + ".png");
        }

        // WHEN
        List<String> plan = Asker.of("0f3a9c2e81d4b7a6", 7, names).plan();

        // THEN
        assertThat(plan).hasSize(Asker.MAX_PLAN).startsWith("1.png").endsWith(Asker.MAX_PLAN + ".png");
    }
}

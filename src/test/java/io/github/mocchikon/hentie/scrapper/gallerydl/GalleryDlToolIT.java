package io.github.mocchikon.hentie.scrapper.gallerydl;

import io.github.mocchikon.hentie.FakeGalleryDl;
import io.github.mocchikon.hentie.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Finding and asking gallery-dl, with {@link FakeGalleryDl} in its place. */
@SpringBootTest
class GalleryDlToolIT
{
    @Autowired GalleryDlTool tool;
    @Autowired AppProperties appProperties;

    private List<String> commandBefore;
    private String binDirBefore;

    @BeforeEach
    void remember() throws IOException
    {
        FakeGalleryDl.reset();
        commandBefore = appProperties.getGalleryDl().getCommand();
        binDirBefore = appProperties.getGalleryDl().getBinDir();
    }

    @AfterEach
    void restore()
    {
        appProperties.getGalleryDl().setCommand(commandBefore);
        appProperties.getGalleryDl().setBinDir(binDirBefore);
        tool.refreshVersion();
    }

    @Test
    void shouldReportTheVersionOfTheGalleryDlInUse() throws IOException
    {
        // GIVEN
        FakeGalleryDl.set("version", "9.8.7");

        // WHEN
        tool.refreshVersion();
        GalleryDlTool.Status status = tool.status();

        // THEN
        assertThat(status.version()).isEqualTo("9.8.7");
        assertThat(status.problem()).isNull();
        assertThat(status.executable().origin()).isEqualTo(GalleryDlTool.Origin.CONFIGURED);
        assertThat(status.updatable()).isFalse();
    }

    @Test
    void shouldSayWhatIsMissingWhenTheBundledCopyIsNotThere()
    {
        // GIVEN no configured command, and a bin folder without gallery-dl (as on macOS)
        appProperties.getGalleryDl().setCommand(List.of());
        appProperties.getGalleryDl().setBinDir("./target/no-gallery-dl-here");

        // WHEN + THEN
        assertThatThrownBy(() -> tool.executable())
                .isInstanceOfSatisfying(GalleryDlException.class,
                        e -> assertThat(e.kind()).isEqualTo(GalleryDlException.Kind.NOT_RUNNABLE))
                .hasMessageContaining("Use the gallery-dl installed on this system");
        assertThat(tool.status().problem()).contains("is missing");
    }

    @Test
    void shouldUpdateOnlyTheBundledCopy()
    {
        // WHEN the copy in use is not the bundled one
        GalleryDlTool.UpdateOutcome outcome = tool.update();

        // THEN nothing ran.
        assertThat(outcome.updated()).isFalse();
        assertThat(outcome.message()).contains("Only the gallery-dl bundled with the app");
    }

    @Test
    void shouldPassStdoutLineByLineAndKeepStderr() throws IOException
    {
        // GIVEN
        FakeGalleryDl.set("stderr", "[x][error] one|[x][error] two");
        var lines = new java.util.ArrayList<String>();

        // WHEN
        GalleryDlTool.Result result = tool.run(List.of("--version"), lines::add, null, java.time.Duration.ofSeconds(30));

        // THEN
        assertThat(result.exitCode()).isZero();
        assertThat(lines).containsExactly("1.0.0-fake");
        assertThat(result.stderr()).containsExactly("[x][error] one", "[x][error] two");
        assertThat(tool.isRunning()).isFalse();
    }
}

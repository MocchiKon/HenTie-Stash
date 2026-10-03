package io.github.mocchikon.hentie.config;

import io.github.mocchikon.hentie.entity.ImageEncoder;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <b>The field initializers are the only place an {@code app.*} default is written.</b> The test profile's
 * {@code application.properties} replaces the main one wholesale, so a default kept only in the main file
 * would be silently zero, false or null in every test.
 */
@Component
@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties
{
    /** Relative to the working directory. */
    private String dataDir = "./data";

    private boolean openBrowser = true;

    /** The BCrypt hash. Deleting the file resets the password (login turns off), hence the name. */
    private String passwordFile = "password-delete-to-reset.txt";

    /** Only seeds the Settings toggle. A property so the test profile can pin it off. */
    private boolean matchAutoLinkDefault = true;

    private Download download = new Download();

    private ImageCompression imageCompression = new ImageCompression();

    private Storage storage = new Storage();

    private ComfyUi comfyui = new ComfyUi();

    private Search search = new Search();

    private Writes writes = new Writes();

    private GalleryDl galleryDl = new GalleryDl();

    /** See {@code scrapper.gallerydl}. */
    @Data
    public static class GalleryDl
    {
        /**
         * Next to the jar, one sub-folder per platform, like the image tools: {@code win/gallery-dl.exe},
         * {@code linux/gallery-dl.bin}. Ignored when Settings says to use the gallery-dl installed on the system.
         */
        private String binDir = "./bin";

        /**
         * When set, the command gallery-dl is started with, in place of the bundled or system copy: for a gallery-dl
         * run as a Python module ({@code python3,-m,gallery_dl}), and for the tests' fake.
         */
        private List<String> command = List.of();

        /** The delay a Settings default starts at: gallery-dl's own for e-hentai (3-6 s) is far slower. */
        private String defaultDelay = "0.4-0.65";

        /**
         * A run that prints nothing for this long is killed. Every HTTP request prints a line (gallery-dl runs
         * verbose), so only a hung process stays silent this long.
         */
        private int idleTimeoutSeconds = 300;

        /** A metadata run, whatever it prints. */
        private int metadataTimeoutSeconds = 180;

        /** {@code --version}: a one-file executable unpacks itself first, which takes a few seconds on Windows. */
        private int versionTimeoutSeconds = 60;

        /** {@code -U} downloads the new executable. */
        private int updateTimeoutSeconds = 600;
    }

    /** See {@code WriteGate}. */
    @Data
    public static class Writes
    {
        /**
         * How long a request's first write waits for its turn before the user is told the library is busy.
         * Longer than any slice of a background loop, so waiting behind one never shows; shorter than the few
         * single transactions that hold the lock for longer (a title index rebuild, merging a tag on much of the
         * library). A property so the tests can make it short.
         */
        private long requestWaitMillis = 10_000;
    }

    @Data
    public static class Search
    {
        /**
         * An included value with at least this many rows counts as broad (see {@code SearchService.plan}).
         * Around it, starting from the value and walking the sort index cost the same on 1.5M chapters. A
         * property so the tests can force either plan.
         */
        private int largeListRows = 30_000;
    }

    /** Not a source's own settings (the mock folder, nhentai's): a source's configuration belongs to the source. */
    @Data
    public static class Download
    {
        /** A permanent failure skips the remaining attempts. */
        private int maxAttempts = 3;

        /** Multiplied by the attempt number. */
        private long retryBackoffMillis = 2_000;

        /**
         * How often one page is fetched again before the attempt fails. Per page, so a page that fails now and
         * then does not cost the whole gallery an attempt. For every source alike, so a source never retries
         * on its own.
         */
        private int pageRetries = 5;

        /** Between two fetches of one page. Fixed, not growing: the item's own attempts back off further. */
        private long pageRetryBackoffMillis = 1_000;

        /** Starting the worker is also what resumes an interrupted queue. */
        private boolean workerEnabled = true;
    }

    /** Timeouts and limits only; what differs per install (address, folder, script) is set on the Settings page. */
    @Data
    public static class ComfyUi
    {
        /** Used while the Settings field is blank. A property so the tests can point it where nothing answers. */
        private String defaultUrl = "http://127.0.0.1:8188";

        /** Short, because the Settings page and the viewer's workflow list wait for it. */
        private int connectTimeoutSeconds = 3;

        /** One HTTP call. */
        private int requestTimeoutSeconds = 120;

        /** One run, queue time included. Generous: a diffusion workflow without a GPU takes minutes a page. */
        private int jobTimeoutSeconds = 600;

        /** For a ComfyUI the app launched, before it is reported as stuck. */
        private int startupTimeoutSeconds = 300;

        /** 0 = never prune. An automatic RAM disk caps it further, see {@code ScratchSpace}. */
        private long resultCacheMaxMb = 2048;

        /**
         * A page nobody asked about for this long is dropped before it starts. Must outlast the gap between two
         * long-polls; any longer only lets pages the reader left run ahead of the one on screen.
         */
        private long abandonAfterMillis = 10_000;
    }

    /**
     * Only steers the automatic choice. The folders have no property: a property fallback would make a cleared
     * Settings field mean "whatever the properties say" rather than "automatic".
     */
    @Data
    public static class Storage
    {
        /** {@code /dev/shm} first: always tmpfs, and never aged out by {@code systemd-tmpfiles}. Empty = no detection. */
        private List<String> ramDiskCandidates = List.of("/dev/shm", "/tmp");

        /** Below this the data folder is used: a gallery that outgrows a shared tmpfs fails part-way. */
        private long ramDiskMinFreeMb = 1024;
    }

    /** The built-in modes are fields, not a {@code Map}, because a map bound from properties has no order. */
    @Data
    public static class ImageCompression
    {
        /** Off: the dropdowns remain but nothing is ever encoded. */
        private boolean enabled = true;

        /**
         * Next to the jar, one sub-folder per platform ({@code win/}, {@code linux/}). Ignored when Settings
         * says to use the tools installed on the system.
         */
        private String binDir = "./bin";

        /** 0 = one per processor. */
        private int threads = 0;

        /** Per tool call; past it the tool is killed and the original kept. */
        private int timeoutSeconds = 300;

        /** 0 = never prune. An automatic RAM disk caps it further, see {@code ScratchSpace.transcodeCacheCapBytes}. */
        private long transcodeCacheMaxMb = 512;

        private Mode lossless = new Mode();
        private Mode highReduction = new Mode();
        private Mode veryHighReduction = new Mode();
    }

    /** Exactly the fields a user-defined mode has. */
    @Data
    public static class Mode
    {
        private ImageEncoder encoder = ImageEncoder.JXL;
        private String encoderArgs = "";
        private String magickArgs = "";
        private int minRelativeReduction;
        private int minAbsoluteReduction;
        private String formats = "";
    }
}

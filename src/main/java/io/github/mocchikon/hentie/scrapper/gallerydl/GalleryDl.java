package io.github.mocchikon.hentie.scrapper.gallerydl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.ImageDirectory;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two ways a source uses gallery-dl: one gallery's metadata, and some of its pages into a folder.
 * <p>
 * <b>{@code --config-ignore} on every run</b>: a user's own gallery-dl configuration could set an archive that
 * skips files already downloaded elsewhere, post-processors, other file names or credentials, and every one of
 * them would change what lands in the folder. Everything a run needs is on its command line.
 * <p>
 * <b>Verbose ({@code -v})</b>, because it prints a line for every HTTP request: the only sign of life during
 * e-hentai's walk through the pages before the first wanted one, which downloads nothing, so the idle timeout can
 * be short and still never stop a working run.
 */
@Component
@RequiredArgsConstructor
public class GalleryDl
{
    private static final Logger log = LoggerFactory.getLogger(GalleryDl.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A completed file is printed as its path, an existing one as {@code "# <path>"}; pages are named {@code <num>.<ext>}. */
    private static final Pattern PAGE_FILE = Pattern.compile("(\\d{1,9})\\.([A-Za-z0-9]{1,10})");

    /** gallery-dl's error and warning lines: {@code [logger][level] message}. */
    static final Pattern LOG_LINE = Pattern.compile("\\[([^]]*)]\\[(error|warning)] (.*)");

    /** Most of a message that ends in the queue's {@code error} column (2000 characters, with context around it). */
    private static final int MAX_PROBLEM_CHARS = 1200;

    /** Windows' "a DLL could not be loaded" codes: the Visual C++ runtime the one-file executable needs is missing. */
    private static final Set<Integer> MISSING_RUNTIME_CODES = Set.of(0xC0000135, 0xC000007B);

    /**
     * 128 + SIGABRT, SIGKILL, SIGSEGV or SIGTERM: how a run killed or crashed outside Windows ends. As status bits
     * they would be combinations gallery-dl never reports, so they are told apart even after an error line.
     */
    private static final Set<Integer> SIGNAL_EXIT_CODES = Set.of(128 + 6, 128 + 9, 128 + 11, 128 + 15);

    private final GalleryDlTool tool;
    private final AppProperties appProperties;

    /** Receives each page as gallery-dl completes it; it may move the file away. */
    @FunctionalInterface
    public interface PageSink
    {
        void accept(int page, Path file) throws IOException;
    }

    /**
     * @param problem gallery-dl's own words for what went wrong, or null when it reported nothing
     * @param writeFailed a file could not be written: never a page to skip
     */
    public record Outcome(Set<Integer> received, String problem, boolean writeFailed)
    {
    }

    /**
     * The gallery's metadata from one {@code -j --range 1} run: one request for the gallery, and the first page's
     * address, never a page's bytes.
     *
     * @return gallery-dl's Directory message, the gallery's fields (with {@code count}, its number of images)
     * @throws GalleryDlException classified ({@link GalleryDlException#kind})
     */
    public JsonNode metadata(String url, List<String> sourceArgs) throws IOException
    {
        var args = new ArrayList<>(commonArgs());
        args.addAll(sourceArgs);
        args.addAll(List.of("-j", "--range", "1", url));
        GalleryDlTool.Result result = tool.run(args, null, idleTimeout(),
                Duration.ofSeconds(Math.max(1, appProperties.getGalleryDl().getMetadataTimeoutSeconds())));
        return directoryIn(url, result);
    }

    /** Whether an update holds gallery-dl, so a run would be refused without starting. */
    public boolean isUpdating()
    {
        return tool.isUpdating();
    }

    static JsonNode directoryIn(String url, GalleryDlTool.Result result) throws GalleryDlException
    {
        JsonNode messages;
        try
        {
            messages = StringUtils.isBlank(result.stdout()) ? null : JSON.readTree(result.stdout());
        }
        catch (IOException e)
        {
            messages = null;
        }
        JsonNode directory = null;
        JsonNode error = null;
        if (messages != null && messages.isArray())
        {
            for (JsonNode message : messages)
            {
                int type = message.path(0).asInt(0);
                if (type == 2 && directory == null && message.path(1).isObject())
                {
                    directory = message.path(1);
                }
                else if (type == -1 && error == null)
                {
                    error = message.path(1);
                }
            }
        }
        if (directory != null)
        {
            return directory;
        }
        if (error != null)
        {
            String name = error.path("error").asText("");
            String text = StringUtils.defaultIfBlank(error.path("message").asText(""), name);
            throw new GalleryDlException(kindOfError(name, text), "gallery-dl could not read " + url + ": "
                    + StringUtils.abbreviate(text, MAX_PROBLEM_CHARS));
        }
        if (result.exitCode() != 0)
        {
            throw classify(result.exitCode(), result.stderr(), "gallery-dl could not read " + url);
        }
        throw new GalleryDlException(GalleryDlException.Kind.OTHER, "gallery-dl returned nothing for " + url
                + problemSuffix(result.stderr()));
    }

    private static GalleryDlException.Kind kindOfError(String name, String message)
    {
        GalleryDlException.Kind byWords = kindOfWords(message);
        if (byWords != null)
        {
            return byWords;
        }
        return switch (name)
        {
            case "NotFoundError" -> GalleryDlException.Kind.NOT_FOUND;
            case "AuthorizationError", "AuthenticationError", "AuthRequired" -> GalleryDlException.Kind.REFUSED;
            case "ChallengeError" -> GalleryDlException.Kind.CHALLENGE;
            case "NoExtractorError" -> GalleryDlException.Kind.UNSUPPORTED;
            default -> GalleryDlException.Kind.OTHER;
        };
    }

    /**
     * Downloads {@code pages} of the gallery at {@code url} into {@code folder}, handing each to {@code sink} as it
     * is complete. gallery-dl writes a file as {@code .part} and renames it when whole, and prints its path only
     * then, so a page reaches the sink whole. A page gallery-dl fails is left out: it goes on with the next one.
     * An error of the extractor's own (the gallery's page, a network outage) ends the run there, so the pages after
     * it were never tried: that is a failure of the whole run, never pages to skip.
     *
     * @param folder only written into, created when missing; the caller removes it
     * @throws GalleryDlException for a failure that is about the whole gallery (classified); a failed page is not
     *                            one, it is missing from {@link Outcome#received}
     */
    public Outcome download(String url, List<String> sourceArgs, SortedSet<Integer> pages, Path folder,
                            PageSink sink) throws IOException
    {
        Files.createDirectories(folder);
        var args = new ArrayList<>(commonArgs());
        args.addAll(sourceArgs);
        args.addAll(List.of("-D", folder.toString(), "-f", "{num}.{extension}", "--range", rangeSpec(pages), url));
        Set<Integer> received = new HashSet<>();
        Path runFolder = folder.toAbsolutePath().normalize();
        GalleryDlTool.Result result = tool.run(args, line -> onPageLine(line, runFolder, pages, received, sink),
                idleTimeout(), null);
        if (result.exitCode() == 0 || received.containsAll(pages))
        {
            return new Outcome(received, null, false);
        }
        GalleryDlException failure = classify(result.exitCode(), result.stderr(),
                "gallery-dl could not download " + url);
        if (failure.kind() == GalleryDlException.Kind.WRITE)
        {
            return new Outcome(received, failure.getMessage(), true);
        }
        // Bit 4 is gallery-dl's own status for a file it failed and went past. Without it the run ended some other
        // way (a kill exits with 1), so the pages after it were never tried, even if an earlier page failed.
        if (failure.kind() == GalleryDlException.Kind.OTHER && (result.exitCode() & 4) != 0
                && !stoppedByExtractor(result.stderr()))
        {
            return new Outcome(received, problem(result.stderr()), false);
        }
        throw failure;
    }

    private static void onPageLine(String line, Path runFolder, Set<Integer> wanted, Set<Integer> received,
                                   PageSink sink)
    {
        String path = line.startsWith("# ") ? line.substring(2) : line;
        Path file;
        try
        {
            file = Path.of(path.strip()).toAbsolutePath().normalize();
        }
        catch (RuntimeException e)
        {
            return;
        }
        Path name = file.getFileName();
        Matcher matcher = name == null ? null : PAGE_FILE.matcher(name.toString());
        if (matcher == null || !matcher.matches())
        {
            return;
        }
        if (!file.startsWith(runFolder))
        {
            // Never moved or deleted: the app touches files only inside folders it named itself.
            log.warn("Ignoring {} from gallery-dl: not in the folder it was given", file);
            return;
        }
        int page = Integer.parseInt(matcher.group(1));
        if (!wanted.contains(page) || !ImageDirectory.isImage(name.toString()) || !Files.isRegularFile(file))
        {
            // Not a page this run asked for, or not an image the app could show: never published.
            log.warn("Ignoring {} from gallery-dl: not one of the pages asked for", file);
            deleteQuietly(file);
            return;
        }
        try
        {
            if (Files.size(file) == 0)
            {
                log.warn("Ignoring page {} from gallery-dl: the file is empty", page);
                deleteQuietly(file);
                return;
            }
            sink.accept(page, file);
            received.add(page);
        }
        catch (IOException e)
        {
            // Stops the run: the next page would fail the same way.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * gallery-dl logs a failed file under {@code download} (or a {@code downloader.*} logger); an error under any
     * other logger comes from the extractor, which stops at it.
     */
    private static boolean stoppedByExtractor(List<String> stderr)
    {
        for (String line : stderr)
        {
            Matcher matcher = LOG_LINE.matcher(line.strip());
            if (matcher.matches() && matcher.group(2).equals("error"))
            {
                String logger = matcher.group(1);
                if (!logger.equals("download") && !logger.startsWith("downloader"))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static void deleteQuietly(Path file)
    {
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException e)
        {
            log.debug("Could not delete {}: {}", file, e.toString());
        }
    }

    private List<String> commonArgs()
    {
        return List.of("--config-ignore", "--no-input", "-v", "-o", "output.mode=pipe",
                "-R", Integer.toString(Math.max(0, appProperties.getDownload().getPageRetries())), "--no-mtime");
    }

    private Duration idleTimeout()
    {
        return Duration.ofSeconds(Math.max(1, appProperties.getGalleryDl().getIdleTimeoutSeconds()));
    }

    /** Sorted pages as gallery-dl's {@code --range}: runs of consecutive pages as {@code a-b}, the rest one by one. */
    public static String rangeSpec(Collection<Integer> pages)
    {
        var parts = new ArrayList<String>();
        Iterator<Integer> sorted = pages.stream().sorted().distinct().iterator();
        Integer start = null;
        Integer end = null;
        while (sorted.hasNext())
        {
            int page = sorted.next();
            if (end != null && page == end + 1)
            {
                end = page;
                continue;
            }
            if (start != null)
            {
                parts.add(start.equals(end) ? start.toString() : start + "-" + end);
            }
            start = page;
            end = page;
        }
        if (start != null)
        {
            parts.add(start.equals(end) ? start.toString() : start + "-" + end);
        }
        return String.join(",", parts);
    }

    // ---- what went wrong -----------------------------------------------------------------------------------

    /**
     * gallery-dl reports a failure only as its exit status (a set of bits) and its log, so both are read: the
     * wording first, for the cases that share a bit with others (a ban and a refused account are both "not
     * authorized").
     */
    static GalleryDlException classify(int exitCode, List<String> stderr, String context)
    {
        String problem = problem(stderr);
        String message = context + (problem == null ? " (" + describeExit(exitCode, stderr) + ")" : ": " + problem);
        String allErrors = String.join("\n", errorLines(stderr));
        GalleryDlException.Kind byWords = kindOfWords(allErrors);
        if (byWords != null)
        {
            return new GalleryDlException(byWords, message);
        }
        if (MISSING_RUNTIME_CODES.contains(exitCode))
        {
            return new GalleryDlException(GalleryDlException.Kind.NOT_RUNNABLE, "gallery-dl could not start: the "
                    + "Microsoft Visual C++ Redistributable it needs is missing. Install it from Microsoft and "
                    + "retry.");
        }
        // Not a status of gallery-dl's own: a crash (Windows' NTSTATUS codes), a kill by a signal (128 + its
        // number), or any status without an error line, since gallery-dl's own status bits always come with one
        // (a kill from Task Manager exits with 1).
        if (exitCode < 0 || exitCode > 255 || errorLines(stderr).isEmpty()
                || (!SystemUtils.IS_OS_WINDOWS && SIGNAL_EXIT_CODES.contains(exitCode)))
        {
            return new GalleryDlException(GalleryDlException.Kind.ABORTED, context + ": gallery-dl was stopped or "
                    + "crashed (" + describeExit(exitCode, stderr) + ")");
        }
        GalleryDlException.Kind kind;
        if ((exitCode & 16) != 0)
        {
            kind = GalleryDlException.Kind.REFUSED;
        }
        else if ((exitCode & 8) != 0)
        {
            kind = GalleryDlException.Kind.CHALLENGE;
        }
        else if ((exitCode & (32 | 64)) != 0)
        {
            kind = GalleryDlException.Kind.UNSUPPORTED;
        }
        else if ((exitCode & 128) != 0)
        {
            kind = GalleryDlException.Kind.WRITE;
        }
        else
        {
            kind = GalleryDlException.Kind.OTHER;
        }
        return new GalleryDlException(kind, message);
    }

    private static GalleryDlException.Kind kindOfWords(String text)
    {
        if (text == null)
        {
            return null;
        }
        if (text.contains("Temporarily Banned") || text.contains("temporarily banned"))
        {
            return GalleryDlException.Kind.BANNED;
        }
        if (text.contains("Image limit exceeded") || text.contains("image limit"))
        {
            return GalleryDlException.Kind.IMAGE_LIMIT;
        }
        if (text.contains("Not enough GP"))
        {
            return GalleryDlException.Kind.NO_GP;
        }
        if (text.contains("NotFoundError") || text.contains("could not be found"))
        {
            return GalleryDlException.Kind.NOT_FOUND;
        }
        if (text.contains("Unsupported URL") || text.contains("NoExtractorError"))
        {
            return GalleryDlException.Kind.UNSUPPORTED;
        }
        return null;
    }

    /** The error lines without their {@code [logger][error]} prefix, warnings when there are none; null for none. */
    static String problem(List<String> stderr)
    {
        List<String> lines = errorLines(stderr);
        if (lines.isEmpty())
        {
            lines = warningLines(stderr);
        }
        if (lines.isEmpty())
        {
            return null;
        }
        // The last ones say what finally failed; earlier ones are often the retries leading up to it.
        List<String> last = lines.size() > 3 ? lines.subList(lines.size() - 3, lines.size()) : lines;
        return StringUtils.abbreviate(String.join("; ", last), MAX_PROBLEM_CHARS);
    }

    private static List<String> errorLines(List<String> stderr)
    {
        return linesOfLevel(stderr, "error");
    }

    private static List<String> warningLines(List<String> stderr)
    {
        return linesOfLevel(stderr, "warning");
    }

    private static List<String> linesOfLevel(List<String> stderr, String level)
    {
        var lines = new ArrayList<String>();
        for (String line : stderr)
        {
            Matcher matcher = LOG_LINE.matcher(line.strip());
            if (matcher.matches() && matcher.group(2).equals(level))
            {
                lines.add(matcher.group(3).strip());
            }
        }
        return lines;
    }

    /** For a failure without gallery-dl's words: the exit status and the last line it printed. */
    static String describeExit(int exitCode, List<String> stderr)
    {
        String problem = problem(stderr);
        if (problem != null)
        {
            return problem;
        }
        String last = stderr.isEmpty() ? "" : StringUtils.abbreviate(stderr.getLast().strip(), 300);
        return "exit code " + exitCode + (last.isEmpty() ? "" : ": " + last);
    }

    private static String problemSuffix(List<String> stderr)
    {
        String problem = problem(stderr);
        return problem == null ? "" : ": " + problem;
    }
}

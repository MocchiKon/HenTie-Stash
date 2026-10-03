package io.github.mocchikon.hentie;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * Stands in for gallery-dl in every suite (the test profile's {@code app.gallery-dl.command}): a separate process,
 * started the way the real one is, that behaves as the fixture a suite writes says. Only the JDK, so it runs from the
 * test classes alone. Every call's arguments are recorded, so a suite can check what the app asked for.
 * <p>
 * Fixture keys ({@code <host>.<key>} wins over {@code <key>}, so one domain can behave differently):
 * <ul>
 *     <li>{@code json} - what {@code -j} prints;</li>
 *     <li>{@code pages} - how many pages the gallery has; {@code fail} - pages that fail (comma list);</li>
 *     <li>{@code ext} - the extension pages are written with; {@code image} - a file copied as every page;</li>
 *     <li>{@code exit} - an exit status; with bit 8, 16, 32 or 64 set nothing is delivered;</li>
 *     <li>{@code stderr} - lines printed to stderr ({@code |} separates them);</li>
 *     <li>{@code delayMillis} - a wait before each page; {@code hang} - print nothing and never finish;</li>
 *     <li>{@code version} - what {@code --version} prints.</li>
 * </ul>
 */
public final class FakeGalleryDl
{
    public static final Path DIR = Path.of("./target/fake-gallery-dl");
    private static final String FIXTURE = "fixture.properties";

    private FakeGalleryDl()
    {
    }

    // ---- the test side -------------------------------------------------------------------------------------

    /** Forgets every fixture value and recorded call. */
    public static void reset() throws IOException
    {
        if (Files.isDirectory(DIR))
        {
            try (Stream<Path> files = Files.walk(DIR))
            {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                {
                    Files.delete(file);
                }
            }
        }
        Files.createDirectories(DIR);
    }

    public static void set(String key, String value) throws IOException
    {
        Files.createDirectories(DIR);
        Properties fixture = fixture(DIR);
        fixture.setProperty(key, value);
        try (var out = Files.newBufferedWriter(DIR.resolve(FIXTURE), StandardCharsets.UTF_8))
        {
            fixture.store(out, null);
        }
    }

    /** The arguments of every call so far, oldest first, without the command itself. */
    public static List<List<String>> calls() throws IOException
    {
        var calls = new ArrayList<List<String>>();
        if (!Files.isDirectory(DIR))
        {
            return calls;
        }
        try (Stream<Path> files = Files.list(DIR))
        {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("call-"))
                    .sorted(Comparator.comparingInt(FakeGalleryDl::callNumber)).toList())
            {
                calls.add(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        }
        return calls;
    }

    /** The value following {@code option} in a call, or null. */
    public static String option(List<String> call, String option)
    {
        int at = call.indexOf(option);
        return at < 0 || at + 1 >= call.size() ? null : call.get(at + 1);
    }

    private static int callNumber(Path file)
    {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring("call-".length(), name.indexOf('.')));
    }

    private static Properties fixture(Path dir) throws IOException
    {
        var fixture = new Properties();
        Path file = dir.resolve(FIXTURE);
        if (Files.isRegularFile(file))
        {
            try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8))
            {
                fixture.load(in);
            }
        }
        return fixture;
    }

    // ---- the process ---------------------------------------------------------------------------------------

    public static void main(String[] argv) throws Exception
    {
        Path dir = Path.of(argv[0]);
        List<String> args = Arrays.asList(argv).subList(1, argv.length);
        Files.createDirectories(dir);
        record(dir, args);
        Properties fixture = fixture(dir);
        String url = args.isEmpty() ? "" : args.getLast();
        String host = url.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        var lookup = new Lookup(fixture, host);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);

        if (lookup.flag("hang"))
        {
            Thread.sleep(Long.MAX_VALUE);
        }
        lookup.lines("stderr").forEach(err::println);
        int exit = lookup.integer("exit", 0);
        if (args.contains("--version"))
        {
            out.println(lookup.get("version", "1.0.0-fake"));
            System.exit(exit);
        }
        if (args.contains("-U"))
        {
            out.println("Updated to " + lookup.get("version", "1.0.0-fake"));
            System.exit(exit);
        }
        if (args.contains("-j"))
        {
            err.println("[urllib3.connectionpool][debug] GET " + url);
            out.println(lookup.get("json", "[]"));
            System.exit(exit);
        }
        String folder = option(args, "-D");
        if (folder == null)
        {
            err.println("[fake][error] nothing to do");
            System.exit(2);
        }
        if ((exit & (8 | 16 | 32 | 64)) != 0)
        {
            System.exit(exit);
        }
        int pages = lookup.integer("pages", 0);
        var fail = new TreeSet<Integer>();
        for (String page : lookup.get("fail", "").split(","))
        {
            if (!page.isBlank())
            {
                fail.add(Integer.parseInt(page.strip()));
            }
        }
        String ext = lookup.get("ext", "webp");
        String image = lookup.get("image", null);
        long delay = lookup.integer("delayMillis", 0);
        Files.createDirectories(Path.of(folder));
        for (int page : range(option(args, "--range")))
        {
            if (delay > 0)
            {
                Thread.sleep(delay);
            }
            err.println("[urllib3.connectionpool][debug] GET page " + page);
            if (page > pages || fail.contains(page))
            {
                err.println("[downloader.http][warning] HTTP Error 404 for page " + page);
                err.println("[download][error] Failed to download " + page + "." + ext);
                exit |= 4;
                continue;
            }
            Path part = Path.of(folder, page + "." + ext + ".part");
            if (image != null)
            {
                Files.copy(Path.of(image), part, StandardCopyOption.REPLACE_EXISTING);
            }
            else
            {
                Files.writeString(part, "fake page " + page);
            }
            Path done = Path.of(folder, page + "." + ext);
            Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
            out.println(done);
        }
        System.exit(exit);
    }

    private static void record(Path dir, List<String> args) throws IOException
    {
        // The pid keeps two calls apart even when they start in the same millisecond.
        long count;
        try (Stream<Path> files = Files.list(dir))
        {
            count = files.filter(f -> f.getFileName().toString().startsWith("call-")).count();
        }
        Path file = dir.resolve("call-" + (count + 1) + "." + ProcessHandle.current().pid() + ".args");
        Files.write(file, args, StandardCharsets.UTF_8);
    }

    private static List<Integer> range(String spec)
    {
        var pages = new ArrayList<Integer>();
        if (spec == null)
        {
            return pages;
        }
        for (String part : spec.split(","))
        {
            String[] bounds = part.strip().split("-");
            int from = Integer.parseInt(bounds[0]);
            int to = bounds.length > 1 ? Integer.parseInt(bounds[1]) : from;
            for (int page = from; page <= to; page++)
            {
                pages.add(page);
            }
        }
        return pages;
    }

    private record Lookup(Properties fixture, String host)
    {
        String get(String key, String fallback)
        {
            String value = fixture.getProperty(host + "." + key);
            return value != null ? value : fixture.getProperty(key, fallback);
        }

        int integer(String key, int fallback)
        {
            String value = get(key, null);
            return value == null ? fallback : Integer.parseInt(value.strip());
        }

        boolean flag(String key)
        {
            return Boolean.parseBoolean(get(key, "false"));
        }

        List<String> lines(String key)
        {
            String value = get(key, "");
            return value.isEmpty() ? List.of() : Arrays.asList(value.split("\\|"));
        }
    }
}

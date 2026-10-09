package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.dto.CandidateScope;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.match.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in: how close "Match chapters" comes to a library whose series and chapter numbers were set by hand.
 * <pre>
 * ./mvnw test -Dtest=MatchingAccuracyIT -Dmatching.truth=path/to/hand-matched.db
 *     [-Dmatching.compare=path/to/auto-matched.db]   also judge a library matched earlier, as it is
 * </pre>
 * The databases are only read, from copies removed once read. The hand-matched library's chapters and artists are
 * copied into this suite's own database in no series, the real sweep matches them from scratch, and every decision
 * it made is judged against the hand-made series, in the order the sweep made it:
 * <ul>
 *   <li>a chapter that <b>joined</b> a series is right when a chapter of its own hand-made series started that
 *       series, else it went to a wrong series;</li>
 *   <li>a chapter that <b>started</b> a series is right when no series started by a chapter of its hand-made
 *       series existed yet, else it should have joined that one.</li>
 * </ul>
 * Judging decisions rather than the final partition keeps one early mistake from being counted again for
 * every chapter that follows it. Chapter numbers are compared as they are.
 * <p>
 * The report goes to the log and to {@code target/matching-accuracy/report.txt}, one line per chapter to
 * {@code chapters.tsv} beside it. Nothing is asserted beyond the copy being complete: the numbers are the
 * deliverable.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "matching.truth", matches = ".+",
        disabledReason = "Needs a hand-matched library; enable with -Dmatching.truth=<database file>")
class MatchingAccuracyIT
{
    private static final Logger log = LoggerFactory.getLogger(MatchingAccuracyIT.class);

    private static final Path WORK = Path.of("target", "matching-accuracy");

    private static final float NUMBER_TOLERANCE = 0.001f;

    @Autowired JdbcTemplate jdbc;
    @Autowired ChapterMatchingSweep sweep;
    @Autowired SeriesCandidateFinder seriesCandidateFinder;
    @Autowired ChapterCandidateFinder chapterCandidateFinder;
    @Autowired ChapterRepository chapterRepository;
    @Autowired SeriesRepository seriesRepository;
    @Autowired SettingsService settingsService;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void ownDatabase(DynamicPropertyRegistry registry)
    {
        // The xerial driver does not create missing parent folders.
        WORK.toFile().mkdirs();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + WORK.resolve("eval.db")
                + "?journal_mode=WAL&synchronous=OFF&foreign_keys=ON&busy_timeout=60000&temp_store=MEMORY");
        registry.add("app.data-dir", () -> WORK.resolve("data").toString());
    }

    @Test
    void shouldReportHowCloseMatchChaptersComesToTheHandMatchedLibrary() throws Exception
    {
        // GIVEN the hand-matched library's chapters, in no series
        Library truth = readCopy(System.getProperty("matching.truth"), "truth.db");
        load(truth);

        // WHEN
        sweep.matchUnlinked();

        // THEN
        Library swept = readBack(truth);
        assertThat(swept.chapters()).hasSameSizeAs(truth.chapters());

        var report = new StringBuilder()
                .append("Matching accuracy against ").append(System.getProperty("matching.truth")).append('\n')
                .append("  ").append(truth.chapters().size()).append(" chapters in ")
                .append(truth.seriesCount()).append(" hand-made series, threshold ")
                .append(settingsService.getMatchThreshold()).append("\n\n");
        var sweptEvaluation = new Evaluation(truth, swept);
        report.append(sweptEvaluation.render("Match chapters on this build"));
        report.append(rematchDisagreements(truth, swept));
        report.append(linkChaptersOffers(truth, swept));

        String compare = System.getProperty("matching.compare");
        if (compare != null && !compare.isBlank())
        {
            Library earlier = readCopy(compare, "compare.db");
            report.append('\n').append(new Evaluation(truth, earlier).render("As matched in " + compare));
        }

        Files.writeString(WORK.resolve("report.txt"), report);
        Files.writeString(WORK.resolve("chapters.tsv"), sweptEvaluation.tsv());
        log.info("\n{}", report);
    }

    /**
     * Read from a copy, removed again once read: SQLite may write to a database (or its WAL) it only reads, and a
     * library is nothing to leave lying in {@code target}.
     */
    private static Library readCopy(String file, String name) throws Exception
    {
        Path copy = WORK.resolve(name);
        var copies = new ArrayList<>(List.of(copy));
        Files.copy(Path.of(file), copy, StandardCopyOption.REPLACE_EXISTING);
        for (String suffix : List.of("-wal", "-shm"))
        {
            Path sidecar = Path.of(file + suffix);
            Path target = Path.of(copy + suffix);
            copies.add(target);
            if (Files.exists(sidecar))
            {
                Files.copy(sidecar, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try
        {
            return Library.read(copy);
        }
        finally
        {
            for (Path path : copies)
            {
                Files.deleteIfExists(path);
            }
        }
    }

    /** As an import leaves a library: no series, no numbers, no matching keys. */
    private void load(Library truth)
    {
        jdbc.update("delete from chapter_artists");
        jdbc.update("update chapter set series_id = null");
        jdbc.update("delete from series");
        jdbc.update("delete from chapter");
        jdbc.update("delete from artist");

        jdbc.batchUpdate("insert into artist (id, name) values (?, ?)",
                truth.artists().entrySet().stream().map(a -> new Object[]{a.getKey(), a.getValue()}).toList());
        jdbc.batchUpdate("""
                        insert into chapter (id, title_full, title_pretty, native_title, language, status, upload_date,
                                             gallery_id, page_num, disk_size, download_status, match_key)
                        values (?, ?, ?, ?, ?, 0, ?, ?, 0, 0, 0, '')
                        """,
                truth.chapters().values().stream().map(c -> new Object[]{c.id(), c.titleFull(), c.titlePretty(),
                        c.nativeTitle(), c.language(), c.uploadDate(), c.galleryId()}).toList());
        jdbc.batchUpdate("insert into chapter_artists (chapter_id, artist_id) values (?, ?)",
                truth.chapterArtists().stream().map(link -> new Object[]{link[0], link[1]}).toList());
    }

    private Library readBack(Library truth)
    {
        var chapters = new LinkedHashMap<Integer, Row>();
        jdbc.query("select id, series_id, chapter_num, match_key from chapter order by id", rs ->
        {
            Row original = truth.chapters().get(rs.getInt(1));
            chapters.put(original.id(), original.placed(nullableInt(rs, 2), nullableFloat(rs, 3), rs.getString(4)));
        });
        var seriesTitles = new HashMap<Integer, String>();
        jdbc.query("select id, title_full from series", rs ->
        {
            seriesTitles.put(rs.getInt(1), rs.getString(2));
        });
        return new Library(chapters, truth.artists(), truth.chapterArtists(), seriesTitles);
    }

    /**
     * What "Add to series" (and so "Link chapters", which scores the same way) says once every series exists:
     * chapters for which another series now ranks first, above the threshold. The sweep decided each chapter
     * before later series existed, so these are the decisions a re-match would change.
     */
    private String rematchDisagreements(Library truth, Library swept)
    {
        double threshold = settingsService.getMatchThresholdScore();
        var founders = swept.founderTruth(truth);
        var transactions = new TransactionTemplate(transactionManager);
        transactions.setReadOnly(true);

        int moves = 0;
        int movesRight = 0;
        var lines = new StringBuilder();
        for (Row chapter : swept.inSweepOrder())
        {
            List<ScoredSeries> ranked = transactions.execute(status -> seriesCandidateFinder.rank(
                    chapterRepository.findById(chapter.id()).orElseThrow(), null));
            if (ranked == null || ranked.isEmpty())
            {
                continue;
            }
            ScoredSeries best = ranked.getFirst();
            if (best.score() < threshold || best.series().getId().equals(chapter.seriesId()))
            {
                continue;
            }
            moves++;
            int truthSeries = truth.chapters().get(chapter.id()).seriesId();
            boolean right = Objects.equals(founders.get(best.series().getId()), truthSeries);
            movesRight += right ? 1 : 0;
            lines.append(String.format("    c%d %s -> %.2f '%s' (%s)%n", chapter.id(), chapter.titleFull(),
                    best.score(), best.series().getTitleFull(), right ? "right" : "wrong"));
        }
        return String.format("  After the sweep, Add to series ranks another series first for %d chapter(s), "
                + "%d of them the right one%n%s", moves, movesRight, lines);
    }

    /**
     * What every series page's "Link chapters" (scope: all chapters) offers above the threshold once the sweep is
     * done: chapters of other series. An offer is right when the chapter belongs to the series' hand-made series.
     */
    private String linkChaptersOffers(Library truth, Library swept)
    {
        double threshold = settingsService.getMatchThresholdScore();
        var founders = swept.founderTruth(truth);
        var transactions = new TransactionTemplate(transactionManager);
        transactions.setReadOnly(true);

        var offered = new TreeMap<Integer, String>();
        int right = 0;
        for (int seriesId : new TreeSet<>(founders.keySet()))
        {
            List<ScoredChapter> ranked = transactions.execute(status -> chapterCandidateFinder.rank(
                    seriesRepository.findById(seriesId).orElseThrow(), CandidateScope.ALL));
            for (ScoredChapter candidate : ranked == null ? List.<ScoredChapter>of() : ranked)
            {
                if (candidate.score() < threshold)
                {
                    break;
                }
                Row chapter = swept.chapters().get(candidate.chapterId());
                boolean isRight = Objects.equals(founders.get(seriesId),
                        truth.chapters().get(chapter.id()).seriesId());
                right += isRight ? 1 : 0;
                offered.merge(chapter.id(), String.format("    c%d %s -> %.2f S%d '%s' (%s)%n", chapter.id(),
                        chapter.titleFull(), candidate.score(), seriesId, swept.seriesTitles().get(seriesId),
                        isRight ? "right" : "wrong"), String::concat);
            }
        }
        return String.format("  After the sweep, Link chapters offers %d chapter(s) of other series above the "
                + "threshold, %d offer(s) right%n%s", offered.size(), right, String.join("", offered.values()));
    }

    private static Integer nullableInt(ResultSet rs, int column) throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Float nullableFloat(ResultSet rs, int column) throws SQLException
    {
        float value = rs.getFloat(column);
        return rs.wasNull() ? null : value;
    }

    private record Row(int id, String titleFull, String titlePretty, String nativeTitle, String language,
                       Object uploadDate, String galleryId, Integer seriesId, Float chapterNum, String matchKey)
    {
        Row placed(Integer series, Float number, String key)
        {
            return new Row(id, titleFull, titlePretty, nativeTitle, language, uploadDate, galleryId, series, number,
                    key);
        }
    }

    private record Library(Map<Integer, Row> chapters, Map<Integer, String> artists, List<int[]> chapterArtists,
                           Map<Integer, String> seriesTitles)
    {
        static Library read(Path file) throws SQLException
        {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file))
            {
                var chapters = new LinkedHashMap<Integer, Row>();
                try (ResultSet rs = connection.createStatement().executeQuery("""
                        select id, title_full, title_pretty, native_title, language, upload_date, gallery_id,
                               series_id, chapter_num, match_key
                        from chapter order by id"""))
                {
                    while (rs.next())
                    {
                        chapters.put(rs.getInt(1), new Row(rs.getInt(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getObject(6), rs.getString(7),
                                nullableInt(rs, 8), nullableFloat(rs, 9), rs.getString(10)));
                    }
                }
                var artists = new LinkedHashMap<Integer, String>();
                try (ResultSet rs = connection.createStatement().executeQuery("select id, name from artist"))
                {
                    while (rs.next())
                    {
                        artists.put(rs.getInt(1), rs.getString(2));
                    }
                }
                var links = new ArrayList<int[]>();
                try (ResultSet rs = connection.createStatement().executeQuery(
                        "select chapter_id, artist_id from chapter_artists"))
                {
                    while (rs.next())
                    {
                        links.add(new int[]{rs.getInt(1), rs.getInt(2)});
                    }
                }
                var seriesTitles = new HashMap<Integer, String>();
                try (ResultSet rs = connection.createStatement().executeQuery("select id, title_full from series"))
                {
                    while (rs.next())
                    {
                        seriesTitles.put(rs.getInt(1), rs.getString(2));
                    }
                }
                return new Library(chapters, artists, links, seriesTitles);
            }
        }

        long seriesCount()
        {
            return chapters.values().stream().map(Row::seriesId).filter(Objects::nonNull).distinct().count();
        }

        /** The sweep walks unlinked chapters by {@code (match_key, id)}. */
        List<Row> inSweepOrder()
        {
            return chapters.values().stream()
                    .sorted(Comparator.comparing((Row row) -> Objects.toString(row.matchKey(), ""))
                            .thenComparingInt(Row::id))
                    .toList();
        }

        /** Each series' hand-made series, as the series of the chapter that started it. */
        Map<Integer, Integer> founderTruth(Library truth)
        {
            var founders = new HashMap<Integer, Integer>();
            for (Row row : inSweepOrder())
            {
                if (row.seriesId() != null)
                {
                    founders.putIfAbsent(row.seriesId(), truth.chapters().get(row.id()).seriesId());
                }
            }
            return founders;
        }
    }

    private enum Verdict
    {
        JOINED_RIGHT("joined the right series"),
        STARTED_RIGHT("started a series, rightly"),
        JOINED_WRONG("joined a WRONG series"),
        STARTED_WRONG("started a NEW series instead of joining one");

        private final String text;

        Verdict(String text)
        {
            this.text = text;
        }
    }

    private static final class Evaluation
    {
        private final Library truth;
        private final Library result;
        private final Map<Integer, Verdict> verdicts = new LinkedHashMap<>();
        private final Map<Integer, Integer> founders;

        Evaluation(Library truth, Library result)
        {
            this.truth = truth;
            this.result = result;
            this.founders = result.founderTruth(truth);
            var startedFor = new HashSet<Integer>();
            var started = new HashSet<Integer>();
            for (Row row : result.inSweepOrder())
            {
                int truthSeries = truth.chapters().get(row.id()).seriesId();
                if (started.add(row.seriesId()))
                {
                    verdicts.put(row.id(), startedFor.add(truthSeries) ? Verdict.STARTED_RIGHT : Verdict.STARTED_WRONG);
                }
                else
                {
                    verdicts.put(row.id(), Objects.equals(founders.get(row.seriesId()), truthSeries)
                            ? Verdict.JOINED_RIGHT : Verdict.JOINED_WRONG);
                }
            }
        }

        private long count(Verdict... wanted)
        {
            var set = EnumSet.copyOf(List.of(wanted));
            return verdicts.values().stream().filter(set::contains).count();
        }

        private boolean numberRight(int id)
        {
            Float expected = truth.chapters().get(id).chapterNum();
            Float actual = result.chapters().get(id).chapterNum();
            if (expected == null || actual == null)
            {
                return expected == actual;
            }
            return Math.abs(expected - actual) < NUMBER_TOLERANCE;
        }

        String render(String label)
        {
            int total = verdicts.size();
            var out = new StringBuilder("  ").append(label).append('\n');
            out.append(String.format("    series made: %d (hand-made: %d), hand-made series reproduced exactly: %d%n",
                    result.seriesCount(), truth.seriesCount(), exactSeries()));
            long right = count(Verdict.JOINED_RIGHT, Verdict.STARTED_RIGHT);
            out.append(line("correctly matched", right, total,
                    String.format("joined the right series %d, rightly started one %d",
                            count(Verdict.JOINED_RIGHT), count(Verdict.STARTED_RIGHT))));
            out.append(line("matched to a wrong series", count(Verdict.JOINED_WRONG), total, ""));
            out.append(line("not matched, new series created", count(Verdict.STARTED_WRONG), total, ""));
            out.append(pairwise());

            long numbered = verdicts.keySet().stream().filter(this::numberRight).count();
            var inFamilies = verdicts.keySet().stream().filter(id -> familySize(id) > 1).toList();
            long numberedInFamilies = inFamilies.stream().filter(this::numberRight).count();
            out.append(line("chapter number right", numbered, total, ""));
            out.append(line("  in series of 2+ chapters", numberedInFamilies, inFamilies.size(), ""));
            out.append(mistakes());
            return out.toString();
        }

        private static String line(String what, long count, long total, String detail)
        {
            return String.format("    %-34s %4d of %d = %5.1f%%%s%n", what + ":", count, total,
                    total == 0 ? 0.0 : 100.0 * count / total, detail.isEmpty() ? "" : "  (" + detail + ")");
        }

        private long familySize(int id)
        {
            Integer series = truth.chapters().get(id).seriesId();
            return truth.chapters().values().stream().filter(row -> Objects.equals(row.seriesId(), series)).count();
        }

        private long exactSeries()
        {
            Map<Integer, Set<Integer>> truthSets = members(truth);
            var resultSets = new HashSet<>(members(result).values());
            return truthSets.values().stream().filter(resultSets::contains).count();
        }

        private static Map<Integer, Set<Integer>> members(Library library)
        {
            var members = new HashMap<Integer, Set<Integer>>();
            library.chapters().values().forEach(row ->
                    members.computeIfAbsent(row.seriesId(), id -> new TreeSet<>()).add(row.id()));
            return members;
        }

        /** Over chapter pairs: precision is how many put together belong together, recall the reverse. */
        private String pairwise()
        {
            var ids = new ArrayList<>(verdicts.keySet());
            long together = 0;
            long both = 0;
            long belong = 0;
            for (int i = 0; i < ids.size(); i++)
            {
                for (int j = i + 1; j < ids.size(); j++)
                {
                    boolean sameResult = Objects.equals(result.chapters().get(ids.get(i)).seriesId(),
                            result.chapters().get(ids.get(j)).seriesId());
                    boolean sameTruth = Objects.equals(truth.chapters().get(ids.get(i)).seriesId(),
                            truth.chapters().get(ids.get(j)).seriesId());
                    together += sameResult ? 1 : 0;
                    belong += sameTruth ? 1 : 0;
                    both += sameResult && sameTruth ? 1 : 0;
                }
            }
            double precision = together == 0 ? 1.0 : (double) both / together;
            double recall = belong == 0 ? 1.0 : (double) both / belong;
            return String.format("    pairs: precision %.3f, recall %.3f, F1 %.3f%n", precision, recall,
                    precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall));
        }

        /** Every hand-made series with a wrong decision or number, chapters in sweep order. */
        private String mistakes()
        {
            var out = new StringBuilder("    mistakes, by hand-made series:\n");
            var bySeries = new LinkedHashMap<Integer, List<Row>>();
            truth.chapters().values().stream()
                    .sorted(Comparator.comparing((Row row) -> truth.seriesTitles().getOrDefault(row.seriesId(), ""),
                            String.CASE_INSENSITIVE_ORDER).thenComparingInt(Row::id))
                    .forEach(row -> bySeries.computeIfAbsent(row.seriesId(), id -> new ArrayList<>()).add(row));
            var order = new HashMap<Integer, Integer>();
            var sweepOrder = result.inSweepOrder();
            for (int i = 0; i < sweepOrder.size(); i++)
            {
                order.put(sweepOrder.get(i).id(), i);
            }
            for (var entry : bySeries.entrySet())
            {
                List<Row> rows = new ArrayList<>(entry.getValue());
                boolean wrong = rows.stream().anyMatch(row -> !numberRight(row.id())
                        || verdicts.get(row.id()) == Verdict.JOINED_WRONG
                        || verdicts.get(row.id()) == Verdict.STARTED_WRONG);
                if (!wrong)
                {
                    continue;
                }
                rows.sort(Comparator.comparingInt(row -> order.get(row.id())));
                out.append(String.format("      S%d '%s'%n", entry.getKey(),
                        truth.seriesTitles().getOrDefault(entry.getKey(), "?")));
                for (Row row : rows)
                {
                    Row placed = result.chapters().get(row.id());
                    out.append(String.format("        c%-6d %-46s #%s%s  %s%n", row.id(),
                            verdicts.get(row.id()).text + " S" + placed.seriesId(),
                            number(placed.chapterNum()),
                            numberRight(row.id()) ? "" : " (want " + number(row.chapterNum()) + ")",
                            row.titleFull()));
                }
            }
            return out.toString();
        }

        private static String number(Float value)
        {
            return value == null ? "-" : String.format(Locale.ROOT, "%.2f", value);
        }

        String tsv()
        {
            var out = new StringBuilder("id\ttruth_series\tseries\tverdict\ttruth_num\tnum\tmatch_key\ttitle_full\n");
            for (Row row : result.inSweepOrder())
            {
                Row expected = truth.chapters().get(row.id());
                out.append(row.id()).append('\t').append(expected.seriesId()).append('\t').append(row.seriesId())
                        .append('\t').append(verdicts.get(row.id())).append('\t')
                        .append(number(expected.chapterNum())).append('\t').append(number(row.chapterNum()))
                        .append('\t').append(row.matchKey()).append('\t').append(row.titleFull()).append('\n');
            }
            return out.toString();
        }
    }
}

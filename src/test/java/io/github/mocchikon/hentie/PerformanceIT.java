package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.*;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.entity.Character;
import io.github.mocchikon.hentie.repository.*;
import io.github.mocchikon.hentie.service.*;
import io.github.mocchikon.hentie.service.match.ScoredSeries;
import io.github.mocchikon.hentie.service.match.SeriesCandidateFinder;
import io.github.mocchikon.hentie.service.match.TitleKey;
import org.apache.commons.lang3.StringUtils;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in benchmark of the user-facing latency paths at scale ({@code -Dperf.chapters}, default 30k; push it
 * to 1.5M or toward the ~5M target), against on-disk SQLite under {@code ./target/perf-sqlite/}, the engine
 * the app ships with. Search goes through the real {@link SearchService#search}, thumbnails included, so the
 * numbers are what a user waits for.
 * <ul>
 *   <li>{@link #benchmarkChapterSearches()} / {@link #benchmarkSeriesSearches()} - every facet side by side,
 *       metadata against the cheap scalar ones, each paged.</li>
 *   <li>{@code benchmarkPaged*} - one test per search shape (so each runs alone), first / middle / last page
 *       for both entities: plain facets, sorts, the combined-filter shapes users build (including those that
 *       push the count onto the Criteria path) and exclusions. See {@link #pagedPattern}.</li>
 *   <li>{@link #benchmarkMatchChapterToSeries()} / {@link #benchmarkLinkChapters()} - matching in both
 *       directions, over realistic families (below).</li>
 *   <li>{@link #benchmarkMetadataAutocomplete()}, {@link #benchmarkReadViews()} - autocomplete cold and warm,
 *       and the detail views.</li>
 *   <li>{@link #benchmarkChapterCrud()} / {@link #benchmarkSeriesCrud()} / {@link #benchmarkMetadataCrud()} -
 *       the write paths, on throwaway rows removed afterwards. Chapter create runs <b>without</b> matching
 *       (the test profile pins auto-linking off), or its numbers would not be comparable.</li>
 * </ul>
 * <p>
 * The data has a real collection's shape: status 80% {@code NEW} / 18% {@code REVIEWED} / 2% favourite and
 * scores skewed toward 7-9 (see {@link #statusOrdinalFor}/{@link #humanScore}), so status/score filters
 * and sorts behave as in production.
 * <p>
 * <b>Realistic families.</b> Every series is a family with its own name and artist, and every chapter carries
 * it: {@code [Perf Chapter 12 ZMKAc] Kamina Rotasu Beniko 2}. Search terms match only in the bracket group,
 * which matching ignores, so the match key is the family name alone. Without this every title would reduce to
 * the key {@code perf} and a chapter would "match" a large slice of all series, which no user could meet.
 * Families are sized as a library's series are, most of one chapter and a few of hundreds (see
 * {@link #familySize}), and upload dates follow ids, as downloads arrive, so a date range is a run of ids.
 * <p>
 * Run it with
 * <pre>
 *   ./mvnw test -Dtest=PerformanceIT#benchmarkPagedTerm5AndHalfTag -Dperf.enabled=true
 *   ./mvnw test -Dtest=PerformanceIT -Dperf.enabled=true -Dperf.chapters=500000
 *   ./mvnw test -Dtest=PerformanceIT -Dperf.enabled=true -Dperf.chapters=400000 -Dperf.reuse=true    # keep the seeded DB across runs
 *   ./mvnw test -Dtest=PerformanceIT -Dperf.enabled=true -Dperf.sql=true                             # + trace SQL
 *   ./mvnw test -Dtest=PerformanceIT -Dperf.enabled=true -Dperf.onlyTables=true                      # + only tables for easier human comparision
 * </pre>
 * {@code -Dperf.reuse=true} keeps the (multi-GB) dataset across runs: {@link #seed()} then rebuilds its
 * bookkeeping from the DB instead of seeding, and reseeds only a dataset of another size. Delete
 * {@code ./target/perf-sqlite} to force a fresh seed.
 * <p>
 * Each facet is sampled {@code RUNS} times with <b>a different value each run</b> (from a "probe pool" of
 * comparable values), so no timing is a cache hit. The statistics pass of a start and a warmup run first, so
 * samples show steady-state cost with the statistics the app would have (the run's first search is
 * {@link #measureColdStartPenalty()}). Assertions only check correctness; the timings are the deliverable, and
 * a {@code @Timeout} guards against a hang.
 * <p>
 * {@code @DirtiesContext(AFTER_CLASS)} keeps the heavy dataset and its connections out of later suites.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "perf.enabled", matches = "true",
        disabledReason = "Heavy dataset benchmark; enable with -Dperf.enabled=true")
class PerformanceIT
{
    private static final Logger log = LoggerFactory.getLogger(PerformanceIT.class);

    /** Samples per facet, each on a different value, so no run is a cache hit. */
    private static final int RUNS = 4;

    /** The SearchService default page size. */
    private static final int PAGE_SIZE = 36;

    private static final int TAG_COUNT = 400;
    private static final int ARTIST_COUNT = 800;
    private static final int CHARACTER_COUNT = 300;
    private static final int PARODY_COUNT = 300;
    private static final int GROUP_COUNT = 150;
    private static final int JDBC_CHUNK = 5_000;
    // A throwaway series for the CRUD benchmark.
    private static final int SERIES_CRUD_CHAPTERS = 2;

    // Series sizes as a library has them: P(size >= s) = s^-SERIES_SIZE_TAIL, capped. About 71% of series hold
    // one chapter, 1.6% ten or more, and ~200 per 1.5M chapters a hundred or more.
    private static final double SERIES_SIZE_TAIL = 1.8;
    private static final int MAX_SERIES_SIZE = 1_000;
    // Upload dates spread over ~3 years (2020-2022) in id order.
    private static final int DATE_SPAN_DAYS = 1_096;
    private static final LocalDate FIRST_DAY = LocalDate.of(2020, 1, 1);
    private static final LocalDate LAST_DAY = FIRST_DAY.plusDays(DATE_SPAN_DAYS - 1);

    // Title tokens that match an exact fraction of rows (index i carries one when i % mod is a run residue).
    // RUNS tokens per fraction (see #termToken), so each run searches a different string. Letters only, and
    // none a substring of another, so each matches only its intended fraction.
    private static final int PCT5_MOD = 20;
    private static final int PCT10_MOD = 10;
    private static final int PCT50_MOD = 2;

    // So the CRUD benchmarks pay a realistic link-building cost.
    private static final int TAGS_PER_CRUD = 6;

    // Throwaway tags for the rename/merge/remove benchmark, each on ~1/MCRUD_LINK_MOD of the chapters (a
    // broad tag), so merge/remove move a realistic number of join rows.
    private static final String MCRUD_TAG_PREFIX = "perf-mcrud-tag-";
    private static final int MCRUD_LINK_MOD = 10;

    // Throwaway CRUD rows are found by these prefixes, so a crashed run's leftovers can be cleared.
    private static final String CRUD_CHAPTER_PREFIX = "Perf CRUD Chapter ";
    private static final String CRUD_SERIES_PREFIX = "Perf CRUD Series ";
    private static final String SCRUD_CHAPTER_PREFIX = "Perf SCRUD Chapter ";   // chapters attached to CRUD series

    // Per type, ids 0..PROBE-1 are assigned by i % PROBE, so all have comparable, high cardinality and
    // searches can rotate over them. Must be >= RUNS+1 and <= each *_COUNT.
    private static final int PROBE = 40;

    private static final List<String> LANGUAGES = List.of("English", "Japanese", "Chinese");

    // Different terms that all match every row, so the "matches all" search varies its value each run.
    private static final List<String> CHAPTER_TERMS =
            List.of("perf", "chapter", "perf chapter", "erf cha", "rf chapt", "f chapte");
    private static final List<String> SERIES_TERMS =
            List.of("series", "perf series", "erf seri", "rf serie", "f series", " serie");

    // Family names: a first word, then two words spelling the family index in base WORDS, so every key is
    // unique. Consonant-vowel syllables never form a stopword, marker, numeral or search term, so TitleKey
    // strips no family word and search terms match only in the bracket group.
    private static final String[] SYLLABLES = {
            "ka", "ki", "ku", "ke", "ko", "sa", "shi", "su", "se", "so", "ta", "chi", "tsu", "te", "to",
            "na", "ni", "nu", "ne", "no", "ha", "hi", "fu", "he", "ho", "ma", "mi", "mu", "me", "mo",
            "ya", "yu", "yo", "ra", "ri", "ru", "re", "ro", "wa", "ga", "gi", "gu", "ge", "go",
            "za", "ji", "zu", "ze", "zo", "da", "de", "do", "ba", "bi", "bu", "be", "bo",
            "pa", "pi", "pu", "pe", "po"};
    private static final int FIRST_WORDS = SYLLABLES.length * SYLLABLES.length;
    private static final int WORDS = 3_000;
    // The first word decides the matching block. Skewed so the commonest starts ~1% of titles (a "Boku no...")
    // while a typical series shares its block with a few hundred others at 750k series.
    private static final double FIRST_WORD_SKEW = 1.8;
    // Every SEQUEL_EVERY-th family extends the previous one's name by a word, as sequels do.
    private static final int SEQUEL_EVERY = 10;
    // Fractional parts of k * these give independent, evenly spread sequences (first word, artist, size).
    private static final double GOLDEN_FRACTION = 0.6180339887498949;
    private static final double SILVER_FRACTION = 0.4142135623730951;
    private static final double BRONZE_FRACTION = 0.3027756377319946;

    // One artist per family (a sequel shares its base's), skewed so a typical artist has about a dozen series
    // and the most prolific stays under the fanout past which matching ignores an artist. Only ARTIST_PROBES
    // probe artists are seeded: each sits on 1/PROBE of the library, and one on every family would push
    // every matching artist seek past the fanout.
    private static final int SERIES_PER_ARTIST = 15;
    private static final double ARTIST_SKEW = 1.5;
    private static final int ARTIST_PROBES = 8;
    private static final String FAMILY_ARTIST_PREFIX = "perf-fartist-";

    // A bracket group matching ignores; series matching creates keep it, so one prefix cleans up both.
    private static final String MATCH_PREFIX = "[Perf Match ";
    // SeriesCandidateFinder's caps, package-private there.
    private static final int BLOCK_CANDIDATE_LIMIT = 200;
    private static final int ARTIST_FANOUT_LIMIT = 2_000;
    // The controllers' limits (MAX_MATCHES, AutocompleteController.LIMIT, ManageController.RECENT_LIMIT).
    private static final int ADD_TO_SERIES_MATCHES = 5;
    private static final int LINK_CHAPTERS_MATCHES = 30;
    private static final int AUTOCOMPLETE_LIMIT = 10;
    private static final int MANAGE_FILTER_LIMIT = 3;

    // Beside the database, so a reused dataset keeps the page files the read-view benchmark needs.
    private static final String PERF_DATA_DIR = "./target/perf-sqlite/data";

    private final int chapterCount = Integer.getInteger("perf.chapters", 30_000);
    // familyStart[f] is family f's first chapter, families counting from 1; the last entry is chapterCount + 1.
    private final int[] familyStart = familyStarts(chapterCount);
    private final int[] familyOfChapter = familyOfChapter(familyStart, chapterCount);
    // One series per family, so every series has chapters (an empty one could derive no tag). perf.series
    // leaves the chapters of the later families in no series.
    private final int seriesCount =
            Math.min(Integer.getInteger("perf.series", Integer.MAX_VALUE), familyStart.length - 2);
    // The RUNS longest families with a series, longest first: the worst case of the pages reading a whole series.
    private final int[] longFamilies = longestFamilies(familyStart, seriesCount, RUNS);

    @Autowired SearchService searchService;
    @Autowired ChapterService chapterService;   // CRUD + read-view (buildView) benchmarks
    @Autowired SeriesService seriesService;      // CRUD + read-view (buildView) benchmarks
    @Autowired MetadataService metadataService;  // rename/merge/remove + autocomplete benchmarks
    @Autowired MetadataCatalog metadataCatalog;  // autocomplete benchmark (its cache fill)
    @Autowired MatchingService matchingService;  // Add-to-series / Link chapters pages
    @Autowired SeriesCandidateFinder seriesCandidateFinder;   // what auto-linking ranks
    @Autowired SettingsService settingsService;  // auto-linking on for the create-and-match benchmark
    @Autowired AppProperties appProperties;
    @Autowired CacheManager cacheManager;
    @Autowired SeriesRepository seriesRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired CharacterRepository characterRepository;
    @Autowired ParodyRepository parodyRepository;
    @Autowired GroupRepository groupRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;   // the statistics pass a start runs

    private List<Integer> tagIds;
    private List<Integer> artistIds;
    private List<Integer> characterIds;
    private List<Integer> parodyIds;
    private List<Integer> groupIds;
    private List<Integer> seriesIds;
    private List<Integer> familyArtistIds;

    // Indices past every seeded family, so each "new work" a matching benchmark names is a key no series has.
    private int freshFamilies;
    private int matchRows;   // numbers the throwaway matching chapters, so titles and gallery ids stay unique

    // Broad tags outside the probe pool, the worst case for the count: halfTagId on 50% of chapters,
    // allTagId on 100%. halfTagId reaches every series holding an even chapter, far more than half, so
    // halfSeriesTagId sits on the chapters of exactly half the series.
    private int halfTagId;
    private int allTagId;
    private int halfSeriesTagId;

    // Recorded while seeding, so the assertions stay exact while searches rotate over probe values.
    private final Map<Integer, Long> tagCounts = new HashMap<>();
    private final Map<Integer, Long> artistCounts = new HashMap<>();
    private final Map<Integer, Long> characterCounts = new HashMap<>();
    private final Map<Integer, Long> parodyCounts = new HashMap<>();
    private final Map<Integer, Long> groupCounts = new HashMap<>();

    /** Without reuse, every run wipes and re-migrates (see {@code TestSchemaConfig}). */
    @DynamicPropertySource
    static void perfDatasource(DynamicPropertyRegistry registry)
    {
        boolean reuse = Boolean.getBoolean("perf.reuse");
        boolean freshSchema = !reuse;

        enableSqlTracing(registry);

        // The xerial driver does not create missing parent folders.
        new File("./target/perf-sqlite").mkdirs();
        // Per-connection pragmas for a fast bulk load; fsync per commit would make seeding crawl.
        // synchronous=OFF is safe because the dataset is a throwaway fixture. cache_size is in KiB (512MB).
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:./target/perf-sqlite/perfdb.db"
                + "?journal_mode=WAL&synchronous=OFF&cache_size=-524288&temp_store=MEMORY&busy_timeout=60000");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        // Reuse only skips the wipe; the schema comes from Flyway either way.
        registry.add("test.flyway.clean", () -> String.valueOf(freshSchema));
        registry.add("app.data-dir", () -> PERF_DATA_DIR);
    }

    /**
     * {@code -Dperf.sql=true}: logs the statements, bound parameters and session statistics, to inspect the
     * query shapes. Off by default because seeding would flood the log.
     */
    private static void enableSqlTracing(DynamicPropertyRegistry registry)
    {
        if (!Boolean.getBoolean("perf.sql"))
        {
            return;
        }
        registry.add("spring.jpa.properties.hibernate.format_sql", () -> "true");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        registry.add("logging.level.org.hibernate.SQL", () -> "DEBUG");          // the statements
        registry.add("logging.level.org.hibernate.orm.jdbc.bind", () -> "TRACE"); // bound parameters (Hibernate 6+)
        registry.add("logging.level.org.hibernate.stat", () -> "DEBUG");         // per-session statistics summary
    }

    private boolean reuseEnabled()
    {
        return Boolean.getBoolean("perf.reuse");
    }

    @BeforeAll
    void seed()
    {
        // Family names spell the family index (and the "new work" ones past it) in two base-WORDS digits.
        assertThat(chapterCount + 10_000L).isLessThan((long) WORDS * WORDS);
        // A run killed inside benchmarkMatchChapterToSeries would leave auto-linking on in a reused database.
        settingsService.setMatchAutoLinkEnabled(appProperties.isMatchAutoLinkDefault());

        if (reuseEnabled() && isAlreadySeeded())
        {
            log.info("[perf] reusing existing {}-chapter dataset (perf.reuse=true) - skipping seed",
                    chapterCount);
            loadSeededState();
            writePageFiles();
            measureColdStartPenalty();
            settleAndWarmup();
            return;
        }
        if (reuseEnabled())
        {
            log.info("[perf] perf.reuse=true but no matching dataset - wiping any stale data and reseeding");
            wipeAll();
        }

        long t0 = System.nanoTime();
        log.info("[perf] seeding {} chapters, {} series ({}) ...", chapterCount, seriesCount, seriesSizes());

        tagIds = insertMetadata(TAG_COUNT, "tag", tagRepository::saveAll, i -> newTag("perf-tag-" + i));
        artistIds = insertMetadata(ARTIST_COUNT, "artist", artistRepository::saveAll, i -> newArtist("perf-artist-" + i));
        familyArtistIds = insertFamilyArtists();
        characterIds = insertMetadata(CHARACTER_COUNT, "character", characterRepository::saveAll, i -> newCharacter("perf-char-" + i));
        parodyIds = insertMetadata(PARODY_COUNT, "parody", parodyRepository::saveAll, i -> newParody("perf-parody-" + i));
        groupIds = insertMetadata(GROUP_COUNT, "group", groupRepository::saveAll, i -> newGroup("perf-group-" + i));

        // The broad tags sit outside the probe pool.
        halfTagId = tagIds.get(PROBE);
        allTagId = tagIds.get(PROBE + 1);
        halfSeriesTagId = tagIds.get(PROBE + 2);

        seedSeries();
        seedChapters();
        seedJoinTable("chapter_tags", "tag_id", this::tagsFor, tagCounts);
        seedJoinTable("chapter_artists", "artist_id", this::artistsFor, artistCounts);
        seedJoinTable("chapter_characters", "character_id", this::charactersFor, characterCounts);
        seedJoinTable("chapter_parodies", "parody_id", this::parodiesFor, parodyCounts);
        seedJoinTable("chapter_groups", "group_id", this::groupsFor, groupCounts);
        seedSeriesEffectiveMetadata();
        seedSeriesPageDiskTotals();

        long ms = (System.nanoTime() - t0) / 1_000_000;
        Long chapters = jdbc.queryForObject("select count(*) from chapter", Long.class);
        Long tagLinks = jdbc.queryForObject("select count(*) from chapter_tags", Long.class);
        log.info("[perf] seeded {} chapters, {} chapter_tag links in {} ms", chapters, tagLinks, ms);

        writePageFiles();
        measureColdStartPenalty();
        settleAndWarmup();
    }

    /**
     * Opening a chapter heals its page count from disk, so without files the benchmarked chapters would drop
     * to 0 pages and out of the page-count filters, for good in a reused dataset. Empty files suffice: the
     * detail page only lists names.
     */
    private void writePageFiles()
    {
        for (int chapterId : IntStream.range(0, RUNS)
                .flatMap(r -> IntStream.of(seededChapterId(r), longSeriesChapterId(r))).toArray())
        {
            Integer pages = jdbc.queryForObject("select page_num from chapter where id = ?", Integer.class, chapterId);
            Path folder = Path.of(PERF_DATA_DIR, String.valueOf(chapterId));
            try
            {
                Files.createDirectories(folder);
                for (int page = 1; pages != null && page <= pages; page++)
                {
                    Path file = folder.resolve(page + ".jpg");
                    if (Files.notExists(file))
                    {
                        Files.createFile(file);
                    }
                }
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reuse of the on-disk SQLite dataset
    // ---------------------------------------------------------------------------------------------
    //
    // No id reset is needed after the seed: AUTOINCREMENT moves past every id inserted, explicit ones too,
    // so a later create takes max(id)+1.

    /** Seeded means the requested row counts <b>and</b> the current data shape; anything else is reseeded. */
    private boolean isAlreadySeeded()
    {
        if (rowCount("chapter") != chapterCount || rowCount("series") != seriesCount)
        {
            return false;
        }
        Long newChapters = jdbc.queryForObject(
                "select count(*) from chapter where status = " + Status.NEW.ordinal(), Long.class);
        long expectedNew = Math.round(chapterCount * 0.80);   // NEW = 80% of chapters (see statusOrdinalFor)
        long tolerance = Math.max(1, chapterCount / 100);     // ~1%, covers the not-divisible-by-100 remainder
        if (newChapters == null || Math.abs(newChapters - expectedNew) > tolerance)
        {
            return false;
        }
        // Score (i % 97) and status (i % 100) are independent, so REVIEWED rows span every score.
        Long reviewedHighScore = jdbc.queryForObject("select count(*) from chapter where status = "
                + Status.REVIEWED.ordinal() + " and score >= 6", Long.class);
        if (reviewedHighScore == null || reviewedHighScore == 0)
        {
            return false;
        }
        // Every seeded chapter has 5..54 pages; one at 0 would break the page-count benchmarks' exact counts.
        Long pagedChapters = jdbc.queryForObject("select count(*) from chapter where page_num > 0", Long.class);
        if (pagedChapters == null || pagedChapters != chapterCount)
        {
            return false;
        }
        // The series sizes and the dates in id order, which the series-page and date-range timings depend on.
        Long longest = jdbc.queryForObject("select max(n) from (select count(*) n from chapter "
                + "where series_id is not null group by series_id)", Long.class);
        if (longest == null || longest != longestSeriesSize())
        {
            return false;
        }
        for (int id : new int[] {1, chapterCount})
        {
            Long stored = jdbc.queryForObject("select upload_date from chapter where id = ?", Long.class, id);
            if (stored == null || stored != Date.valueOf(dateFor(id)).getTime())
            {
                return false;
            }
        }
        // Without family titles and artists every matching benchmark would degenerate.
        return rowCount("series", "match_key = ''") == 0 && rowCount("chapter", "match_key = ''") == 0
                && rowCount("artist", "name like '" + FAMILY_ARTIST_PREFIX + "%'") == familyArtistCount();
    }

    /** 0 for a table that does not exist yet. */
    private long rowCount(String table)
    {
        try
        {
            Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
            return n == null ? 0 : n;
        }
        catch (org.springframework.dao.DataAccessException tableMissingOrEmpty)
        {
            return 0;
        }
    }

    /** In FK-safe order: join tables, chapter, series, then metadata. */
    private void wipeAll()
    {
        for (String t : List.of(
                "chapter_tags", "chapter_artists", "chapter_characters", "chapter_parodies", "chapter_groups",
                "chapter_categories",
                "series_effective_tags", "series_effective_artists", "series_effective_characters",
                "series_effective_parodies", "series_effective_groups", "series_effective_categories",
                "series_effective_languages",
                "series_tags", "series_artists", "series_characters", "series_parodies", "series_groups",
                "series_categories", "series_languages",
                "chapter", "series",
                "tag", "artist", "character", "parody", "group_artists", "category"))
        {
            try
            {
                jdbc.update("delete from " + t);
            }
            catch (org.springframework.dao.DataAccessException notPresentYet)
            {
                // Not created yet, or an optional table.
            }
        }
    }

    /** Ids read in id order match the seed's insertion order, so the bookkeeping comes out identical. */
    private void loadSeededState()
    {
        // Parody stores its name in `title`.
        tagIds = idsFor("tag", "name", "perf-tag-%");
        artistIds = idsFor("artist", "name", "perf-artist-%");
        characterIds = idsFor("character", "name", "perf-char-%");
        parodyIds = idsFor("parody", "title", "perf-parody-%");
        groupIds = idsFor("group_artists", "name", "perf-group-%");
        familyArtistIds = idsFor("artist", "name", FAMILY_ARTIST_PREFIX + "%");
        seriesIds = jdbc.queryForList(
                "select id from series where title_full like '[Perf Series %' order by id", Integer.class);

        halfTagId = tagIds.get(PROBE);
        allTagId = tagIds.get(PROBE + 1);
        halfSeriesTagId = tagIds.get(PROBE + 2);

        loadCounts("chapter_tags", "tag_id", tagCounts);
        loadCounts("chapter_artists", "artist_id", artistCounts);
        loadCounts("chapter_characters", "character_id", characterCounts);
        loadCounts("chapter_parodies", "parody_id", parodyCounts);
        loadCounts("chapter_groups", "group_id", groupCounts);
    }

    private List<Integer> idsFor(String table, String nameColumn, String nameLike)
    {
        return jdbc.queryForList(
                "select id from " + table + " where " + nameColumn + " like ? order by id",
                Integer.class, nameLike);
    }

    private void loadCounts(String table, String column, Map<Integer, Long> counts)
    {
        jdbc.query("select " + column + ", count(*) from " + table + " group by " + column,
                rs -> { counts.put(rs.getInt(1), rs.getLong(2)); });
    }

    /**
     * The run's first tag search, before the statistics pass and any warmup. A fresh seed has no statistics
     * yet, and without them the planner may pick a nested loop and take seconds; a reused dataset has the ones
     * gathered on an earlier run. Logged, not asserted, since the size depends on the machine and scale.
     */
    private void measureColdStartPenalty()
    {
        boolean analyzed = rowCount("sqlite_master", "name = 'sqlite_stat1'") > 0
                && rowCount("sqlite_stat1", "tbl = 'chapter'") > 0;
        long start = System.nanoTime();
        long matches = search(SearchType.CHAPTER, c -> c.setTagIds(List.of(tagIds.get(0))), 0).getTotalElements();
        long coldMs = (System.nanoTime() - start) / 1_000_000;
        log.warn("[perf] COLD-START tag search ({} statistics, no warmup): {} ms over {} matches",
                analyzed ? "with" : "without", coldMs, matches);
    }

    /**
     * The statistics pass a start runs ({@code flyway.migrate()}), then every query shape once, so measurements
     * show steady-state cost with the statistics the app would have, not JIT, first-scan or bad-plan noise.
     * Values sit outside the probe pool, so nothing measured is pre-cached.
     */
    private void settleAndWarmup()
    {
        flyway.migrate();

        int tag = tagIds.get(TAG_COUNT / 2);
        int artist = artistIds.get(ARTIST_COUNT / 2);
        int character = characterIds.get(CHARACTER_COUNT / 2);
        int parody = parodyIds.get(PARODY_COUNT / 2);
        int group = groupIds.get(GROUP_COUNT / 2);

        warm(SearchType.CHAPTER, c -> c.setTitle("perf"));
        warm(SearchType.CHAPTER, c -> c.setStatuses(List.of(Status.REVIEWED)));
        warm(SearchType.CHAPTER, c -> c.setLanguages(List.of(LANGUAGES.get(1))));
        warm(SearchType.CHAPTER, c -> c.setMinScore(5));
        warm(SearchType.CHAPTER, c -> c.setMinPages(20));
        warm(SearchType.CHAPTER, c -> { c.setMinPages(10); c.setMaxPages(40); });
        warm(SearchType.CHAPTER, c -> { c.setTagIds(List.of(tag)); c.setSortBy(SortBy.PAGE_NUM); });
        warm(SearchType.CHAPTER, c -> { c.setTagIds(List.of(tag)); c.setSortBy(SortBy.DISK_SIZE); });
        warm(SearchType.CHAPTER, c -> { c.setSortBy(SortBy.PAGE_NUM); c.setSortDir(Sort.Direction.DESC); });
        warm(SearchType.CHAPTER, c -> { c.setSortBy(SortBy.DISK_SIZE); c.setSortDir(Sort.Direction.DESC); });
        warm(SearchType.CHAPTER, c -> c.setUploadFrom(LocalDate.of(2021, 1, 1)));
        warm(SearchType.CHAPTER, c -> c.setGalleryId("perf-g-2"));
        warm(SearchType.CHAPTER, c -> c.setTagIds(List.of(tag)));
        warm(SearchType.CHAPTER, c -> c.setArtistIds(List.of(artist)));
        warm(SearchType.CHAPTER, c -> c.setCharacterIds(List.of(character)));
        warm(SearchType.CHAPTER, c -> c.setParodyIds(List.of(parody)));
        warm(SearchType.CHAPTER, c -> c.setGroupIds(List.of(group)));

        // The tag + SCORE-sort shapes, with and without a language filter.
        warm(SearchType.CHAPTER, c ->
        {
            c.setTagIds(List.of(tag));
            c.setSortBy(SortBy.SCORE);
        });
        warm(SearchType.CHAPTER, c ->
        {
            c.setTagIds(List.of(tag));
            c.setLanguages(List.of(LANGUAGES.get(1)));
            c.setSortBy(SortBy.SCORE);
        });

        warm(SearchType.SERIES, c -> c.setTitle("series"));
        warm(SearchType.SERIES, c -> c.setStatuses(List.of(Status.REVIEWED)));
        warm(SearchType.SERIES, c -> c.setLanguages(List.of(LANGUAGES.get(1))));
        warm(SearchType.SERIES, c -> c.setMinScore(5));
        warm(SearchType.SERIES, c -> c.setMinPages(10));
        warm(SearchType.SERIES, c -> { c.setTagIds(List.of(tag)); c.setSortBy(SortBy.DISK_SIZE); });
        warm(SearchType.SERIES, c -> { c.setSortBy(SortBy.PAGE_NUM); c.setSortDir(Sort.Direction.DESC); });
        warm(SearchType.SERIES, c -> { c.setSortBy(SortBy.DISK_SIZE); c.setSortDir(Sort.Direction.DESC); });
        warm(SearchType.SERIES, c -> c.setTagIds(List.of(tag)));
        warm(SearchType.SERIES, c -> c.setArtistIds(List.of(artist)));

        // The same for series.
        warm(SearchType.SERIES, c ->
        {
            c.setTagIds(List.of(tag));
            c.setSortBy(SortBy.SCORE);
        });
        warm(SearchType.SERIES, c ->
        {
            c.setTagIds(List.of(tag));
            c.setLanguages(List.of(LANGUAGES.get(1)));
            c.setSortBy(SortBy.SCORE);
        });

        // The combined-filter shapes, for both entity types.
        int tag2 = tagIds.get(TAG_COUNT / 2 + 1);
        int tag3 = tagIds.get(TAG_COUNT / 2 + 2);
        int tag4 = tagIds.get(TAG_COUNT / 2 + 3);
        for (SearchType t : List.of(SearchType.CHAPTER, SearchType.SERIES))
        {
            // The exclusion shapes (one and two excluded values bind differently).
            warm(t, c ->
            {
                c.setTagIds(List.of(tag, tag2));
                c.setExcludedTagIds(List.of(tag3, tag4));
            });
            warm(t, c ->
            {
                c.setTagIds(List.of(tag));
                c.setExcludedTagIds(List.of(tag3, tag4));
            });
            warm(t, c ->
            {
                c.setTagIds(List.of(tag));
                c.setExcludedTagIds(List.of(tag3));
            });
            warm(t, c -> c.setExcludedTagIds(List.of(tag3, tag4)));
            warm(t, c ->
            {
                c.setTagIds(List.of(tag));
                c.setStatuses(List.of(Status.NEW));
                c.setExcludedTagIds(List.of(tag3, tag4));
            });
            warm(t, c ->
            {
                c.setTagIds(List.of(tag));
                c.setStatuses(List.of(Status.NEW));
                c.setMinScore(6);
                c.setSortBy(SortBy.SCORE);
                c.setSortDir(Sort.Direction.DESC);
            });
            warm(t, c ->
            {
                c.setTagIds(List.of(tag));
                c.setStatuses(List.of(Status.NEW));
                c.setSortBy(SortBy.DATE);
                c.setSortDir(Sort.Direction.ASC);
            });
            warm(t, c -> c.setTagIds(List.of(tag, tag2)));                        // multi-tag AND
            warm(t, c -> { c.setTagIds(List.of(tag, tag2)); c.setMinPages(20); }); // 2 tags + page-count filter
            warm(t, c ->                                                          // 2 tags + disk_size sort
            {
                c.setTagIds(List.of(tag, tag2));
                c.setSortBy(SortBy.DISK_SIZE);
                c.setSortDir(Sort.Direction.DESC);
            });
            warm(t, c ->
            {
                c.setTagIds(List.of(tag));
                c.setStatuses(List.of(Status.NEW));
                c.setLanguages(List.of(LANGUAGES.get(1)));
            });
            warm(t, c -> { c.setTagIds(List.of(tag)); date30(c); });             // tag + date range
            warm(t, c -> c.setTitle(termToken('A', RUNS)));                       // term shape (LIKE scan; any fraction)
        }

        // The detail views.
        chapterService.buildView(seededChapterId(0));
        seriesService.buildView(seededSeriesId(0), null);
    }

    private void warm(SearchType type, Consumer<SearchCriteria> setup)
    {
        search(type, setup, 0);
    }

    // ---------------------------------------------------------------------------------------------
    // Benchmarks
    // ---------------------------------------------------------------------------------------------

    /** First / middle / last page, so a sweep also measures the deep-paging (OFFSET) cost. */
    private void sweepFacet(List<Result> out, String label, SearchType type, ObjIntConsumer<SearchCriteria> setup)
    {
        out.addAll(measurePagePositions(label, type, setup));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkChapterSearches()
    {
        List<Result> results = new ArrayList<>();

        sweepFacet(results, "tag (1 value)", SearchType.CHAPTER, (c, r) -> c.setTagIds(List.of(variedTag(r))));
        sweepFacet(results, "tag (2 values, AND-ed)", SearchType.CHAPTER, (c, r) -> c.setTagIds(List.of(variedTag(r), variedTag(r + 1))));
        sweepFacet(results, "tag (2 values + 50% tag, AND-ed)", SearchType.CHAPTER, (c, r) -> c.setTagIds(List.of(halfTagId, variedTag(r), variedTag(r + 1))));
        sweepFacet(results, "tag (2 values + 100% tag, AND-ed)", SearchType.CHAPTER, (c, r) -> c.setTagIds(List.of(allTagId, variedTag(r), variedTag(r + 1))));
        sweepFacet(results, "artist", SearchType.CHAPTER, (c, r) -> c.setArtistIds(List.of(variedArtist(r))));
        sweepFacet(results, "character", SearchType.CHAPTER, (c, r) -> c.setCharacterIds(List.of(variedCharacter(r))));
        sweepFacet(results, "parody", SearchType.CHAPTER, (c, r) -> c.setParodyIds(List.of(variedParody(r))));
        sweepFacet(results, "group", SearchType.CHAPTER, (c, r) -> c.setGroupIds(List.of(variedGroup(r))));

        // Broad tags, the worst case for the count. One fixed value, so the deeper pages isolate the OFFSET
        // cost on top of the cached count.
        sweepFacet(results, "tag on 50% of chapters", SearchType.CHAPTER, (c, r) -> c.setTagIds(List.of(halfTagId)));
        sweepFacet(results, "tag on 100% of chapters", SearchType.CHAPTER, (c, r) -> c.setTagIds(List.of(allTagId)));

        // The cheap scalar facets, for comparison (also varied per run):
        sweepFacet(results, "name (matches all)", SearchType.CHAPTER, (c, r) -> c.setTitle(CHAPTER_TERMS.get(r % CHAPTER_TERMS.size())));
        sweepFacet(results, "name (matches few)", SearchType.CHAPTER, (c, r) -> c.setTitle("Perf Chapter " + (chapterCount - r)));
        sweepFacet(results, "language (single)", SearchType.CHAPTER, (c, r) -> c.setLanguages(List.of(LANGUAGES.get(r % LANGUAGES.size()))));
        sweepFacet(results, "status", SearchType.CHAPTER, (c, r) -> c.setStatuses(List.of(Status.values()[r % Status.values().length])));
        sweepFacet(results, "minScore", SearchType.CHAPTER, (c, r) -> c.setMinScore(4 + r % 6));
        sweepFacet(results, "date range (1 year)", SearchType.CHAPTER, (c, r) ->
        {
            int year = 2020 + r % 3;
            c.setUploadFrom(LocalDate.of(year, 1, 1));
            c.setUploadTo(LocalDate.of(year, 12, 31));
        });
        sweepFacet(results, "galleryId (exact)", SearchType.CHAPTER, (c, r) -> c.setGalleryId("perf-g-" + (r + 1)));
        sweepFacet(results, "tag + language + minScore", SearchType.CHAPTER, (c, r) ->
        {
            c.setTagIds(List.of(variedTag(r)));
            c.setLanguages(List.of(LANGUAGES.get(r % LANGUAGES.size())));
            c.setMinScore(6);
        });

        // Sorts do not change the match set, so they ride on a varied tag to stay off the cache.
        sweepFacet(results, "minPages", SearchType.CHAPTER, (c, r) -> c.setMinPages(20 + r));
        sweepFacet(results, "page range (min+max)", SearchType.CHAPTER, (c, r) ->
        {
            c.setMinPages(10);
            c.setMaxPages(40 + r);
        });
        sweepFacet(results, "tag + page_num sort", SearchType.CHAPTER, (c, r) ->
        {
            c.setTagIds(List.of(variedTag(r)));
            c.setSortBy(SortBy.PAGE_NUM);
        });
        sweepFacet(results, "tag + disk_size sort", SearchType.CHAPTER, (c, r) ->
        {
            c.setTagIds(List.of(variedTag(r)));
            c.setSortBy(SortBy.DISK_SIZE);
        });

        report("CHAPTER FACETS (paged: 1st/mid/last)", results);

        // Every seeded chapter has 5..54 pages (pageNumFor).
        assertThat(search(SearchType.CHAPTER, c -> c.setMinPages(5), 0).getTotalElements())
                .isEqualTo((long) chapterCount);
        assertThat(search(SearchType.CHAPTER, c -> c.setMinPages(55), 0).getTotalElements()).isZero();

        // The timed queries ran over real, non-empty result sets.
        assertChapterFacetCount(c -> c.setTagIds(List.of(tagIds.get(0))), tagCounts.get(tagIds.get(0)));
        assertChapterFacetCount(c -> c.setArtistIds(List.of(artistIds.get(0))), artistCounts.get(artistIds.get(0)));

        assertThat(tagCounts.get(halfTagId)).isEqualTo((long) (chapterCount / 2));
        assertThat(tagCounts.get(allTagId)).isEqualTo((long) chapterCount);
        assertChapterFacetCount(c -> c.setTagIds(List.of(halfTagId)), (long) (chapterCount / 2));
        assertChapterFacetCount(c -> c.setTagIds(List.of(allTagId)), (long) chapterCount);

        assertThat(results).allSatisfy(r -> assertThat(r.count).isNotNegative());
    }

    private void assertChapterFacetCount(Consumer<SearchCriteria> setup, Long expected)
    {
        assertThat(expected).isPositive();
        assertThat(search(SearchType.CHAPTER, setup, 0).getTotalElements()).isEqualTo(expected);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkSeriesSearches()
    {
        List<Result> results = new ArrayList<>();

        sweepFacet(results, "tag (1 value, derived)", SearchType.SERIES, (c, r) -> c.setTagIds(List.of(variedTag(r))));
        sweepFacet(results, "tag (2 values, AND-ed)", SearchType.SERIES, (c, r) -> c.setTagIds(List.of(variedTag(r), variedTag(r + 1))));
        sweepFacet(results, "artist (derived)", SearchType.SERIES, (c, r) -> c.setArtistIds(List.of(variedArtist(r))));
        sweepFacet(results, "language (derived)", SearchType.SERIES, (c, r) -> c.setLanguages(List.of(LANGUAGES.get(r % LANGUAGES.size()))));

        sweepFacet(results, "name (matches all)", SearchType.SERIES, (c, r) -> c.setTitle(SERIES_TERMS.get(r % SERIES_TERMS.size())));
        sweepFacet(results, "status", SearchType.SERIES, (c, r) -> c.setStatuses(List.of(Status.values()[r % Status.values().length])));
        sweepFacet(results, "minScore", SearchType.SERIES, (c, r) -> c.setMinScore(4 + r % 6));
        sweepFacet(results, "minPages (derived total)", SearchType.SERIES, (c, r) -> c.setMinPages(10 + r));
        sweepFacet(results, "tag + disk_size sort", SearchType.SERIES, (c, r) ->
        {
            c.setTagIds(List.of(variedTag(r)));
            c.setSortBy(SortBy.DISK_SIZE);
        });
        sweepFacet(results, "date range (created)", SearchType.SERIES, (c, r) ->
        {
            int year = 2020 + r % 3;
            c.setUploadFrom(LocalDate.of(year, 1, 1));
            c.setUploadTo(LocalDate.of(year, 12, 31));
        });

        report("SERIES FACETS (paged: 1st/mid/last)", results);

        long derivedTagMatches = search(SearchType.SERIES, c -> c.setTagIds(List.of(tagIds.get(0))), 0).getTotalElements();
        assertThat(derivedTagMatches).isPositive().isLessThanOrEqualTo(seriesCount);
        // Every seeded series holds at least one chapter of at least 5 pages.
        assertThat(search(SearchType.SERIES, c -> c.setMinPages(1), 0).getTotalElements())
                .isEqualTo((long) seriesCount);
        assertThat(results).allSatisfy(r -> assertThat(r.count).isNotNegative());
    }

    // ---------------------------------------------------------------------------------------------
    // Paged patterns - one @Test each, so any one can run alone, e.g.
    //   ./mvnw test -Dtest=PerformanceIT#benchmarkPagedTerm5AndHalfTag -Dperf.enabled=true -Dperf.reuse=true
    //
    // First / middle / last page for both entity types: a deep page pays a larger OFFSET scan on the same
    // cached count, the cost the jump-to-page box can trigger. The "50% tag" is halfTagId / halfSeriesTagId.
    // Anything that can vary rotates per run, so no timing is a cache hit.
    // ---------------------------------------------------------------------------------------------

    /** Gets the run index, the entity's 50% tag and the entity type, for patterns with a per-entity value. */
    @FunctionalInterface
    private interface PatternSetup
    {
        void apply(SearchCriteria c, int run, int halfTag, SearchType type);
    }

    /** Both entity types, reported separately; every measured page must lie within a real result set. */
    private void pagedPattern(String pattern, PatternSetup setup)
    {
        List<Result> chapter = measurePagePositions(pattern, SearchType.CHAPTER,
                (c, r) -> setup.apply(c, r, halfTagId, SearchType.CHAPTER));
        report("CHAPTER PAGED - " + pattern, chapter);

        List<Result> series = measurePagePositions(pattern, SearchType.SERIES,
                (c, r) -> setup.apply(c, r, halfSeriesTagId, SearchType.SERIES));
        report("SERIES PAGED - " + pattern, series);

        assertThat(chapter).allSatisfy(r -> assertThat(r.count).isNotNegative());
        assertThat(series).allSatisfy(r -> assertThat(r.count).isNotNegative());
    }

    /** A term matching every row of {@code type}, rotated per run. */
    private String variedName(SearchType type, int run)
    {
        List<String> terms = type == SearchType.CHAPTER ? CHAPTER_TERMS : SERIES_TERMS;
        return terms.get(run % terms.size());
    }

    /** The newest ~30% of rows (329 of the 1096 days), what a user asks for when looking for recent work. */
    private void date30(SearchCriteria c)
    {
        c.setUploadFrom(LAST_DAY.minusDays(328));
        c.setUploadTo(LAST_DAY);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedTag()   // high-cardinality metadata semi-join (chapter literal / series derived)
    {
        pagedPattern("tag", (c, r, half, type) -> c.setTagIds(List.of(variedTag(r))));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedName()   // leading-wildcard LIKE full scan, matching every row
    {
        pagedPattern("name (matches all)", (c, r, half, type) -> c.setTitle(variedName(type, r)));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedTerm5()
    {
        pagedPattern("term ~5%", (c, r, half, type) -> c.setTitle(termToken('A', r)));
        assertTermFractionExact('A');
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedTerm10()
    {
        pagedPattern("term ~10%", (c, r, half, type) -> c.setTitle(termToken('B', r)));
        assertTermFractionExact('B');
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedTerm50()
    {
        pagedPattern("term ~50%", (c, r, half, type) -> c.setTitle(termToken('C', r)));
        assertTermFractionExact('C');
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagScoreSort()   // 50% tag alone: native count, page walked in score order
    {
        pagedPattern("50% tag + score DESC", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half));
            c.setSortBy(SortBy.SCORE);
            c.setSortDir(Sort.Direction.DESC);
        });
        // Seeding guard: the 50% tags match exactly half of each entity.
        assertThat(search(SearchType.CHAPTER, c ->
        {
            c.setTagIds(List.of(halfTagId));
            c.setSortBy(SortBy.SCORE);
        }, 0).getTotalElements()).isEqualTo((long) (chapterCount / 2));
        assertThat(search(SearchType.SERIES, c ->
        {
            c.setTagIds(List.of(halfSeriesTagId));
            c.setSortBy(SortBy.SCORE);
        }, 0).getTotalElements()).isEqualTo((long) (seriesCount / 2));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagLangScoreSort()
    {
        pagedPattern("50% tag + lang + score DESC", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half));
            c.setLanguages(List.of(LANGUAGES.get(1)));
            c.setSortBy(SortBy.SCORE);
            c.setSortDir(Sort.Direction.DESC);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagReviewedMinScore()
    {
        pagedPattern("50% tag + REVIEWED + minScore6 + score DESC", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half));
            c.setStatuses(List.of(Status.REVIEWED));
            c.setMinScore(6);
            c.setSortBy(SortBy.SCORE);
            c.setSortDir(Sort.Direction.DESC);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagNewMinScore()
    {
        pagedPattern("50% tag + NEW + minScore6 + score DESC", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half));
            c.setStatuses(List.of(Status.NEW));
            c.setMinScore(6);
            c.setSortBy(SortBy.SCORE);
            c.setSortDir(Sort.Direction.DESC);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagNewDateAsc()
    {
        pagedPattern("50% tag + NEW + date ASC", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half));
            c.setStatuses(List.of(Status.NEW));
            c.setSortBy(SortBy.DATE);
            c.setSortDir(Sort.Direction.ASC);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagNewScoreSort()
    {
        pagedPattern("50% tag + NEW + score DESC", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half));
            c.setStatuses(List.of(Status.NEW));
            c.setSortBy(SortBy.SCORE);
            c.setSortDir(Sort.Direction.DESC);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagTwoTags()
    {
        pagedPattern("50% tag + 2 tags", (c, r, half, type) ->
                c.setTagIds(List.of(half, variedTag(r), variedTag(r + 1))));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagTwoTagsNew()
    {
        pagedPattern("50% tag + 2 tags + NEW", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half, variedTag(r), variedTag(r + 1)));
            c.setStatuses(List.of(Status.NEW));
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagTwoTagsNewLang()
    {
        pagedPattern("50% tag + 2 tags + NEW + lang", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half, variedTag(r), variedTag(r + 1)));
            c.setStatuses(List.of(Status.NEW));
            c.setLanguages(List.of(LANGUAGES.get(1)));
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagTwoTagsNewDate30()
    {
        pagedPattern("50% tag + 2 tags + NEW + date 30%", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half, variedTag(r), variedTag(r + 1)));
            c.setStatuses(List.of(Status.NEW));
            date30(c);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagNewDate30()
    {
        pagedPattern("50% tag + NEW + date 30%", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half));
            c.setStatuses(List.of(Status.NEW));
            date30(c);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagDate30()
    {
        pagedPattern("50% tag + date 30%", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half));
            date30(c);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedTerm5AndHalfTag()   // free-text term AND a tag - the single most common real search
    {
        pagedPattern("term ~5% + 50% tag", (c, r, half, type) ->
        {
            c.setTitle(termToken('A', r));
            c.setTagIds(List.of(half));
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedMultiStatusScoreSort()   // "everything I haven't marked Favourite", sorted by score
    {
        pagedPattern("NEW+REVIEWED (multi-status) + score DESC", (c, r, half, type) ->
        {
            c.setStatuses(List.of(Status.NEW, Status.REVIEWED));
            c.setSortBy(SortBy.SCORE);
            c.setSortDir(Sort.Direction.DESC);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedPageNumSortDesc()   // sort the whole collection by page count (indexed ORDER BY + OFFSET)
    {
        pagedPattern("page_num DESC", (c, r, half, type) ->
        {
            c.setSortBy(SortBy.PAGE_NUM);
            c.setSortDir(Sort.Direction.DESC);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedDiskSizeSortDesc()   // sort the whole collection by disk size (sort-only facet)
    {
        pagedPattern("disk_size DESC", (c, r, half, type) ->
        {
            c.setSortBy(SortBy.DISK_SIZE);
            c.setSortDir(Sort.Direction.DESC);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagTagMinPages()   // 50% tag + another tag, filtered by page count (owner-row filter)
    {
        pagedPattern("50% tag + tag + minPages", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half, variedTag(r)));
            c.setMinPages(20);
        });
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedHalfTagTagDiskSizeSort()   // 50% tag + another tag, sorted by disk size
    {
        pagedPattern("50% tag + tag + disk_size DESC", (c, r, half, type) ->
        {
            c.setTagIds(List.of(half, variedTag(r)));
            c.setSortBy(SortBy.DISK_SIZE);
            c.setSortDir(Sort.Direction.DESC);
        });
    }

    // Excluded tags. Chapter i carries probe tags i..i+5 (mod PROBE), so excluding t+4 and t+5 removes part of
    // what t matches, never all of it - the narrowing a user reaches for.
    //
    // Runs step EXCLUSION_STRIDE places, so no two runs share a wanted tag.
    private static final int EXCLUSION_STRIDE = 4;

    private int exclusionTag(int run, int offset)
    {
        return variedTag(EXCLUSION_STRIDE * run + offset);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedTwoTagsTwoExcluded()   // two wanted tags minus two disliked ones: INTERSECT ... EXCEPT count
    {
        pagedPattern("2 tags + 2 excluded tags", (c, r, half, type) ->
        {
            c.setTagIds(List.of(exclusionTag(r, 0), exclusionTag(r, 1)));
            c.setExcludedTagIds(List.of(exclusionTag(r, 4), exclusionTag(r, 5)));
        });
        assertExclusionExact(List.of(exclusionTag(0, 0), exclusionTag(0, 1)),
                type -> List.of(exclusionTag(0, 4), exclusionTag(0, 5)), null);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedTagTwoExcluded()
    {
        pagedPattern("tag + 2 excluded tags", (c, r, half, type) ->
        {
            c.setTagIds(List.of(exclusionTag(r, 0)));
            c.setExcludedTagIds(List.of(exclusionTag(r, 4), exclusionTag(r, 5)));
        });
        assertExclusionExact(List.of(exclusionTag(0, 0)), type -> List.of(exclusionTag(0, 4), exclusionTag(0, 5)), null);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedExcludeOnly()   // "everything but these two tags": all owners minus the union, ~70% match
    {
        pagedPattern("2 excluded tags only", (c, r, half, type) ->
                c.setExcludedTagIds(List.of(exclusionTag(r, 0), exclusionTag(r, PROBE / 2))));
        assertExclusionExact(List.of(), type -> List.of(exclusionTag(0, 0), exclusionTag(0, PROBE / 2)), null);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedNewTagTwoExcluded()   // the review queue minus disliked tags: status, tag and exclusions merged as streams
    {
        pagedPattern("NEW + tag + 2 excluded tags", (c, r, half, type) ->
        {
            c.setStatuses(List.of(Status.NEW));
            c.setTagIds(List.of(exclusionTag(r, 0)));
            c.setExcludedTagIds(List.of(exclusionTag(r, 4), exclusionTag(r, 5)));
        });
        assertExclusionExact(List.of(exclusionTag(0, 0)),
                type -> List.of(exclusionTag(0, 4), exclusionTag(0, 5)), Status.NEW);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkPagedTagExcludedHalfTag()   // a tag minus the tag on half the library: EXCEPT with a 50% operand
    {
        pagedPattern("tag + excluded 50% tag", (c, r, half, type) ->
        {
            c.setTagIds(List.of(exclusionTag(r, 0)));
            c.setExcludedTagIds(List.of(half));
        });
        assertExclusionExact(List.of(exclusionTag(0, 0)),
                type -> List.of(type == SearchType.CHAPTER ? halfTagId : halfSeriesTagId), null);
    }

    /** The exclusion must remove part of what the wanted tags match, not nothing and not everything. */
    private void assertExclusionExact(List<Integer> wanted, Function<SearchType, List<Integer>> excluded, Status status)
    {
        for (SearchType type : List.of(SearchType.CHAPTER, SearchType.SERIES))
        {
            long expected = seededTagMatches(type, wanted, excluded.apply(type), status);
            assertThat(expected).isPositive().isLessThan(seededTagMatches(type, wanted, List.of(), status));
            assertThat(search(type, c ->
            {
                c.setTagIds(new ArrayList<>(wanted));
                c.setExcludedTagIds(new ArrayList<>(excluded.apply(type)));
                if (status != null)
                {
                    c.setStatuses(List.of(status));
                }
            }, 0).getTotalElements()).isEqualTo(expected);
        }
    }

    /** Counted from the seeding assignment rather than the database, so it owes nothing to the queries under test. */
    private long seededTagMatches(SearchType type, List<Integer> wanted, List<Integer> excluded, Status status)
    {
        int owners = type == SearchType.CHAPTER ? chapterCount : seriesCount;
        long matches = 0;
        for (int owner = 1; owner <= owners; owner++)
        {
            Set<Integer> tags = type == SearchType.CHAPTER ? tagsFor(owner) : familyTags(owner);
            if (tags.containsAll(wanted) && Collections.disjoint(tags, excluded)
                    && (status == null || statusOrdinalFor(owner) == status.ordinal()))
            {
                matches++;
            }
        }
        return matches;
    }

    private Set<Integer> familyTags(int family)
    {
        var tags = new HashSet<Integer>();
        for (int i = familyStart[family]; i < familyStart[family + 1]; i++)
        {
            tags.addAll(tagsFor(i));
        }
        return tags;
    }

    /** The run-0 token hits exactly the intended fraction of chapters and series (see {@link #titleMarkers(int)}). */
    private void assertTermFractionExact(char fraction)
    {
        assertThat(search(SearchType.CHAPTER, c -> c.setTitle(termToken(fraction, 0)), 0).getTotalElements())
                .isEqualTo(termMatchCount(chapterCount, fraction, 0));
        assertThat(search(SearchType.SERIES, c -> c.setTitle(termToken(fraction, 0)), 0).getTotalElements())
                .isEqualTo(termMatchCount(seriesCount, fraction, 0));
    }

    /** What a user waits for when clicking a card. Read-only, so it runs on the seeded rows. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkReadViews()
    {
        List<Result> results = new ArrayList<>();

        results.add(measureMetric("chapter buildView", r ->
                chapterService.buildView(seededChapterId(r)).getDetailGroups().size()));
        results.add(measureMetric("series buildView", r ->
                seriesService.buildView(seededSeriesId(r), null).getChapterCount()));
        // The previous/next lookup and the series page read every chapter of the series.
        String longest = "longest series, " + sizeOf(longFamilies[RUNS - 1]) + "-" + longestSeriesSize() + " chapters";
        results.add(measureMetric("chapter buildView (" + longest + ")", r ->
                chapterService.buildView(longSeriesChapterId(r)).getDetailGroups().size()));
        results.add(measureMetric("series buildView (" + longest + ")", r ->
                seriesService.buildView(seriesIds.get(longFamilies[r] - 1), null).getChapterCount()));

        report("READ VIEWS", results);

        assertThat(results).allSatisfy(r -> assertThat(r.count).isPositive());
    }

    /**
     * On throwaway, series-less chapters, as the manual-add path makes them ({@link #benchmarkSeriesCrud()}
     * covers the recompute). {@code update} re-applies the same values, so it measures the pure round-trip.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkChapterCrud()
    {
        deleteByTitlePrefix("chapter", CRUD_CHAPTER_PREFIX, chapterService::delete);   // clear crashed-run leftovers

        List<Result> results = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();

        results.add(measureMetric("chapter create", r ->
        {
            ids.add(chapterService.create(crudChapterForm(r)));
            return TAGS_PER_CRUD;
        }));
        results.add(measureMetric("chapter update", r ->
        {
            chapterService.update(chapterService.toForm(ids.get(r)));   // re-apply same values
            return TAGS_PER_CRUD;
        }));
        results.add(measureMetric("chapter delete", r ->
        {
            chapterService.delete(ids.get(r));
            return 1;
        }));

        report("CHAPTER CRUD", results);

        assertThat(rowCountByTitlePrefix("chapter", CRUD_CHAPTER_PREFIX)).isZero();
        assertThat(rowCount("chapter")).isEqualTo(chapterCount);
        assertThat(results).allSatisfy(r -> assertThat(r.count).isPositive());
    }

    /**
     * The recompute-heavy write path. No overrides on the series, so the effective sets are <b>derived</b>
     * from its throwaway chapters, the heavier branch.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkSeriesCrud()
    {
        cleanupSeriesCrud();   // clear crashed-run leftovers (series first, then their chapters)

        List<List<Integer>> chapterGroups = new ArrayList<>();
        for (int r = 0; r < RUNS; r++)
        {
            List<Integer> group = new ArrayList<>();
            for (int k = 0; k < SERIES_CRUD_CHAPTERS; k++)
            {
                group.add(chapterService.create(scrudChapterForm(r, k)));
            }
            chapterGroups.add(group);
        }

        List<Result> results = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();

        results.add(measureMetric("series create", r ->
        {
            SeriesForm form = crudSeriesForm(r);
            form.setChapterIds(new ArrayList<>(chapterGroups.get(r)));   // attach -> recomputeDerived materializes
            ids.add(seriesService.create(form));
            return chapterGroups.get(r).size();
        }));
        results.add(measureMetric("series buildView", r ->
                seriesService.buildView(ids.get(r), null).getChapterCount()));
        results.add(measureMetric("series update", r ->
        {
            SeriesForm form = seriesService.toForm(ids.get(r));
            form.setChapterIds(new ArrayList<>(chapterGroups.get(r)));   // keep the same chapters attached
            seriesService.update(form);
            return chapterGroups.get(r).size();
        }));
        results.add(measureMetric("series delete", r ->
        {
            seriesService.delete(ids.get(r));   // unlinks its chapters (never deletes them)
            return 1;
        }));

        report("SERIES CRUD", results);

        // Deleting a series only unlinks its chapters.
        for (List<Integer> group : chapterGroups)
        {
            group.forEach(chapterService::delete);
        }

        assertThat(rowCountByTitlePrefix("series", CRUD_SERIES_PREFIX)).isZero();
        assertThat(rowCountByTitlePrefix("chapter", SCRUD_CHAPTER_PREFIX)).isZero();
        assertThat(rowCount("series")).isEqualTo(seriesCount);
        assertThat(rowCount("chapter")).isEqualTo(chapterCount);
        assertThat(results).allSatisfy(r -> assertThat(r.count).isPositive());
    }

    /**
     * Merge and remove touch all three tag join tables, so their cost scales with the tag's rows. They run on
     * throwaway broad tags ({@link #MCRUD_TAG_PREFIX}) wiped afterwards; each run consumes its subject, so
     * {@link #RUNS} tags and pairs are prepared up front.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkMetadataCrud()
    {
        cleanupMetadataCrud();   // clear any crashed-run leftovers first

        long tagsBefore = rowCount("tag");
        long chapterTagsBefore = rowCount("chapter_tags");
        long effTagsBefore = rowCount("series_effective_tags");
        long seriesTagsBefore = rowCount("series_tags");
        long linksPerTag = chapterCount / MCRUD_LINK_MOD;   // ~10% of chapters, the metric for merge/remove

        // Renamed RUNS times, keeping the prefix so cleanup still finds it.
        int renameTag = createMcrudTag("rename");

        // Pairs on DISJOINT chapter subsets, so merge MOVES the source's rows rather than dropping duplicates.
        List<int[]> mergePairs = new ArrayList<>();
        List<Long> mergeTargetExpected = new ArrayList<>();
        for (int r = 0; r < RUNS; r++)
        {
            int source = createMcrudTag("merge-src-" + r);
            int target = createMcrudTag("merge-tgt-" + r);
            long sourceLinks = linkTag(source, 0);
            long targetLinks = linkTag(target, 5);
            mergePairs.add(new int[]{source, target});
            mergeTargetExpected.add(sourceLinks + targetLinks);
        }

        List<Integer> removeTags = new ArrayList<>();
        for (int r = 0; r < RUNS; r++)
        {
            int id = createMcrudTag("remove-" + r);
            linkTag(id, 0);
            removeTags.add(id);
        }

        List<Result> results = new ArrayList<>();
        results.add(measureMetric("tag rename", r ->
        {
            metadataService.rename(MetadataType.TAG, renameTag, MCRUD_TAG_PREFIX + "rename-" + r, true);
            return 1;
        }));
        results.add(measureMetric("tag merge (~10% links)", r ->
        {
            int[] pair = mergePairs.get(r);
            metadataService.merge(MetadataType.TAG, pair[0], pair[1], true);
            return linksPerTag;
        }));
        results.add(measureMetric("tag remove (~10% links)", r ->
        {
            metadataService.remove(MetadataType.TAG, removeTags.get(r), true);
            return linksPerTag;
        }));

        report("METADATA CRUD (tags)", results);

        // Checked before the final wipe.
        for (int r = 0; r < mergePairs.size(); r++)
        {
            int[] pair = mergePairs.get(r);
            assertThat(rowCount("tag", "id = " + pair[0])).isZero();                 // source gone
            assertThat(rowCount("tag", "id = " + pair[1])).isEqualTo(1);             // target survived
            assertThat(rowCount("chapter_tags", "tag_id = " + pair[1])).isEqualTo(mergeTargetExpected.get(r));
        }
        for (Integer id : removeTags)
        {
            assertThat(rowCount("tag", "id = " + id)).isZero();
            assertThat(rowCount("chapter_tags", "tag_id = " + id)).isZero();
            assertThat(rowCount("series_effective_tags", "tag_id = " + id)).isZero();
        }

        cleanupMetadataCrud();   // wipe the surviving throwaway tags (rename + merge targets) + their links

        // No seeded tag or link changed.
        assertThat(rowCount("tag")).isEqualTo(tagsBefore);
        assertThat(rowCount("chapter_tags")).isEqualTo(chapterTagsBefore);
        assertThat(rowCount("series_effective_tags")).isEqualTo(effTagsBefore);
        assertThat(rowCount("series_tags")).isEqualTo(seriesTagsBefore);
        assertThat(results).allSatisfy(r -> assertThat(r.count).isPositive());
    }

    /**
     * Over tens of thousands of artists at 1.5M chapters. Both filter the cached list, so the first keystroke
     * after a restart or an eviction waits for its fill, and every keystroke for one pass over it.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkMetadataAutocomplete()
    {
        metadataService.autocomplete(MetadataType.ARTIST, artistTerm(RUNS), AUTOCOMPLETE_LIMIT);   // warm-up

        List<Result> results = new ArrayList<>();
        results.add(measureMetric("artist autocomplete, first keystroke (cache cold)", r ->
        {
            metadataCatalog.evict(MetadataType.ARTIST);
            return metadataService.autocomplete(MetadataType.ARTIST, artistTerm(r), AUTOCOMPLETE_LIMIT).size();
        }));
        results.add(measureMetric("artist autocomplete, next keystroke (cache warm)", r ->
                metadataService.autocomplete(MetadataType.ARTIST, artistTerm(r) + "7", AUTOCOMPLETE_LIMIT).size()));
        results.add(measureMetric("Manage filter box, artists (cache warm)", r ->
                metadataService.recent(MetadataType.ARTIST, artistTerm(r), MANAGE_FILTER_LIMIT).size()));

        int artists = ARTIST_COUNT + familyArtistIds.size();
        report("METADATA AUTOCOMPLETE (" + artists + " artists)", results);

        assertThat(metadataCatalog.all(MetadataType.ARTIST)).hasSize(artists);
        assertThat(results).allSatisfy(r -> assertThat(r.count).isPositive());
    }

    /** A few typed letters: "fartist-13" names about 2% of the family artists. */
    private static String artistTerm(int run)
    {
        return "fartist-" + (run + 1) + "3";
    }

    // ---------------------------------------------------------------------------------------------
    // Matching - chapter -> series (auto-linking, Add to series) and series -> chapters (Link chapters)
    //
    // Each scenario runs on RUNS families from different blocks, after a warm-up family. "Typical" sits in a
    // block as full as a typical series'; "crowded" in one of the fullest (~0.3-1% of series), the realistic
    // worst case where a block seek fills its cap. Only families without a probe artist are probed, so every
    // artist seek is a real artist's.
    // ---------------------------------------------------------------------------------------------

    private record Family(int index, int seriesId, String block, int blockSize)
    {
    }

    /**
     * The auto-linking ranking for each way a real chapter arrives: a known family (exact key), one misspelled
     * (only the artist finds it), and a new work with or without a known artist (only candidates to reject).
     * Then the Add-to-series page, its name box, and {@code create} with auto-linking on, as every download.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkMatchChapterToSeries()
    {
        cleanupMatchRows();
        Map<String, Integer> blocks = blockSizes();
        List<Family> typical = typicalFamilies(blocks);
        List<Family> crowded = crowdedFamilies(blocks);
        double threshold = settingsService.getMatchThresholdScore();

        List<Result> results = new ArrayList<>();
        try
        {
            // What Add to series is really opened for: a misspelled sibling downloaded with auto-linking off.
            var unlinked = new HashMap<Family, Integer>();
            for (Family f : typical)
            {
                unlinked.put(f, chapterService.create(matchChapterForm(misspelled(f.index()) + " 3", familyArtistFor(f.index()))));
            }

            results.add(measureOnFamilies("rank: sibling of a library family (exact key)", typical, f ->
            {
                List<ScoredSeries> ranked = rank(familyTitle(f.index(), 3), familyArtistFor(f.index()));
                assertThat(ranked).hasSizeBetween(1, 2);   // its own series, and a sequel extending it at most
                assertBest(ranked, f, threshold);
                return ranked.size();
            }));
            results.add(measureOnFamilies("rank: misspelled sibling (artist seek)", typical, f ->
            {
                List<ScoredSeries> ranked = rank(misspelled(f.index()) + " 3", familyArtistFor(f.index()));
                assertThat(ranked).hasSizeBetween(1, ARTIST_FANOUT_LIMIT - 1);
                assertBest(ranked, f, threshold);
                return ranked.size();
            }));
            results.add(measureOnFamilies("rank: new work by a known artist (artist seek)", typical, f ->
            {
                List<ScoredSeries> ranked = rank(freshName(f.index()), familyArtistFor(f.index()));
                assertThat(ranked).hasSizeBetween(1, ARTIST_FANOUT_LIMIT - 1);
                assertThat(ranked.getFirst().score()).isLessThan(threshold);   // nothing to join: a new series
                return ranked.size();
            }));
            results.add(measureOnFamilies("rank: new work, no artist (block seek)", typical,
                    f -> rankNewWorkByBlock(f, threshold)));
            results.add(measureOnFamilies("rank: new work, no artist - crowded block (block seek, capped)", crowded,
                    f -> rankNewWorkByBlock(f, threshold)));

            results.add(measureOnFamilies("Add to series page (chapter in no series)", typical, f ->
            {
                List<SeriesMatchDto> matches = matchingService.topMatches(unlinked.get(f), ADD_TO_SERIES_MATCHES);
                assertThat(matches).isNotEmpty();
                assertThat(matches.getFirst().getSeriesId()).isEqualTo(f.seriesId());
                return matches.size();
            }));
            results.add(measureOnFamilies("Add to series: series name search, two words", typical, f ->
            {
                List<SeriesMatchDto> found = matchingService.searchByName(twoWords(f.index()), ADD_TO_SERIES_MATCHES);
                assertThat(found).extracting(SeriesMatchDto::getSeriesId).contains(f.seriesId());
                return found.size();
            }));
            results.add(measureOnFamilies("Add to series: series name search, one common word", crowded, f ->
            {
                List<SeriesMatchDto> found = matchingService.searchByName(capitalized(leadWord(f.index())),
                        ADD_TO_SERIES_MATCHES);
                assertThat(found).hasSize(ADD_TO_SERIES_MATCHES);
                return found.size();
            }));

            settingsService.setMatchAutoLinkEnabled(true);
            results.add(measureOnFamilies("create + auto-link: sibling -> joins its series", typical, f ->
            {
                int id = chapterService.create(matchChapterForm(familyTitle(f.index(), 3), familyArtistFor(f.index())));
                assertThat(seriesOfChapter(id)).isEqualTo(f.seriesId());
                return 1;
            }));
            results.add(measureOnFamilies("create + auto-link: new work -> new series", typical, f ->
            {
                int id = chapterService.create(matchChapterForm(freshName(f.index()), familyArtistFor(f.index())));
                assertThat(seriesOfChapter(id)).isGreaterThan(seriesIds.getLast());   // a series matching created
                return 1;
            }));
        }
        finally
        {
            settingsService.setMatchAutoLinkEnabled(appProperties.isMatchAutoLinkDefault());
            cleanupMatchRows();
        }

        report("MATCHING: chapter -> series (" + blockContext(typical, crowded) + ")", results);
        assertDatasetRestored(typical);
    }

    /**
     * Link chapters in both scopes, its name box and the link itself. In the default scope the block seeks walk
     * their whole range for the few unfiled chapters of a library where nearly all are filed.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void benchmarkLinkChapters()
    {
        cleanupMatchRows();
        Map<String, Integer> blocks = blockSizes();
        List<Family> typical = typicalFamilies(blocks);
        List<Family> crowded = crowdedFamilies(blocks);

        List<Result> results = new ArrayList<>();
        try
        {
            var siblings = new HashMap<Family, List<Integer>>();
            for (Family f : Stream.concat(typical.stream(), crowded.stream()).toList())
            {
                siblings.put(f, List.of(
                        chapterService.create(matchChapterForm(familyTitle(f.index(), 3), familyArtistFor(f.index()))),
                        chapterService.create(matchChapterForm(familyTitle(f.index(), 4), familyArtistFor(f.index())))));
            }

            results.add(measureOnFamilies("Link chapters page, UNLINKED scope (default)", typical,
                    f -> linkCandidates(f, CandidateScope.UNLINKED, siblings.get(f))));
            results.add(measureOnFamilies("Link chapters page, ALL scope", typical,
                    f -> linkCandidates(f, CandidateScope.ALL, siblings.get(f))));
            results.add(measureOnFamilies("Link chapters page, UNLINKED scope - crowded block", crowded,
                    f -> linkCandidates(f, CandidateScope.UNLINKED, siblings.get(f))));
            results.add(measureOnFamilies("Link chapters page, ALL scope - crowded block", crowded,
                    f -> linkCandidates(f, CandidateScope.ALL, siblings.get(f))));
            results.add(measureOnFamilies("Link chapters: chapter name search, two words", typical,
                    f -> chapterNameSearch(twoWords(f.index()), f, siblings.get(f))));
            results.add(measureOnFamilies("Link chapters: chapter name search, one common word", crowded,
                    f -> chapterNameSearch(capitalized(leadWord(f.index())), f, siblings.get(f))));
            results.add(measureOnFamilies("link 2 chapters (POST)", typical, f ->
            {
                assertThat(seriesService.addChapters(f.seriesId(), siblings.get(f))).isEqualTo(2);
                return 2;
            }));
        }
        finally
        {
            cleanupMatchRows();
        }

        report("MATCHING: Link chapters, series -> chapters (" + blockContext(typical, crowded) + ")", results);
        assertDatasetRestored(Stream.concat(typical.stream(), crowded.stream()).toList());
    }

    /** The last family is the warm-up; the first {@link #RUNS} are measured. */
    private Result measureOnFamilies(String label, List<Family> families, ToLongFunction<Family> op)
    {
        op.applyAsLong(families.get(RUNS));
        return measureMetric(label, r -> op.applyAsLong(families.get(r)));
    }

    /** {@code artistId} null means no artist. */
    private List<ScoredSeries> rank(String title, Integer artistId)
    {
        return seriesCandidateFinder.rank(TitleKey.of(title), artistId == null ? Set.of() : Set.of(artistId));
    }

    private static void assertBest(List<ScoredSeries> ranked, Family family, double threshold)
    {
        assertThat(ranked.getFirst().series().getId()).isEqualTo(family.seriesId());
        assertThat(ranked.getFirst().score()).isGreaterThanOrEqualTo(threshold);
    }

    private long rankNewWorkByBlock(Family family, double threshold)
    {
        List<ScoredSeries> ranked = rank(freshName(family.index()), null);
        assertThat(ranked).hasSize(Math.min(family.blockSize(), BLOCK_CANDIDATE_LIMIT));
        assertThat(ranked.getFirst().score()).isLessThan(threshold);
        return ranked.size();
    }

    private long linkCandidates(Family family, CandidateScope scope, List<Integer> siblings)
    {
        List<ChapterMatchDto> matches = matchingService.topChapterMatches(family.seriesId(), scope, LINK_CHAPTERS_MATCHES);
        assertThat(matches).extracting(ChapterMatchDto::getChapterId).containsAll(siblings);
        return matches.size();
    }

    private long chapterNameSearch(String term, Family family, List<Integer> siblings)
    {
        List<ChapterMatchDto> found = matchingService.searchChaptersByName(term, family.seriesId(),
                CandidateScope.UNLINKED, LINK_CHAPTERS_MATCHES);
        assertThat(found).extracting(ChapterMatchDto::getChapterId).containsAll(siblings);
        return found.size();
    }

    private Map<String, Integer> blockSizes()
    {
        var sizes = new HashMap<String, Integer>();
        jdbc.query("select match_block, count(*) from series group by match_block",
                rs -> { sizes.put(rs.getString(1), rs.getInt(2)); });
        return sizes;
    }

    /** The median block over series, not over blocks: most blocks are small, but most series sit in big ones. */
    private List<Family> typicalFamilies(Map<String, Integer> blocks)
    {
        List<Integer> sizes = blocks.values().stream().sorted().toList();
        long total = sizes.stream().mapToLong(Integer::longValue).sum();
        long seen = 0;
        int typical = sizes.getLast();
        for (int size : sizes)
        {
            seen += size;
            if (2 * seen >= total)
            {
                typical = size;
                break;
            }
        }
        int median = typical;
        return pickFamilies(blocks, Comparator.comparingInt((String block) -> Math.abs(blocks.get(block) - median)));
    }

    private List<Family> crowdedFamilies(Map<String, Integer> blocks)
    {
        return pickFamilies(blocks, Comparator.comparingInt((String block) -> blocks.get(block)).reversed());
    }

    private List<Family> pickFamilies(Map<String, Integer> blocks, Comparator<String> order)
    {
        var picked = new ArrayList<Family>();
        for (String block : blocks.keySet().stream().sorted(order.thenComparing(Comparator.naturalOrder())).toList())
        {
            familyIn(block, blocks.get(block)).ifPresent(picked::add);
            if (picked.size() == RUNS + 1)
            {
                return picked;
            }
        }
        throw new IllegalStateException("fewer than " + (RUNS + 1) + " blocks hold a family without a probe artist");
    }

    /** From the middle of the block, so it is not simply the oldest. */
    private Optional<Family> familyIn(String block, int blockSize)
    {
        List<Integer> ids = jdbc.queryForList("select id from series where match_block = ? order by id",
                Integer.class, block);
        for (int j = 0; j < ids.size(); j++)
        {
            int seriesId = ids.get((ids.size() / 2 + j) % ids.size());
            int family = familyIndexOf(seriesId);
            if (family > 0 && !hasProbeArtist(family))
            {
                return Optional.of(new Family(family, seriesId, block, blockSize));
            }
        }
        return Optional.empty();
    }

    /** For the report heading, since every matching timing depends on it. */
    private static String blockContext(List<Family> typical, List<Family> crowded)
    {
        var typicalSizes = typical.subList(0, RUNS).stream().mapToInt(Family::blockSize).summaryStatistics();
        var crowdedSizes = crowded.subList(0, RUNS).stream().mapToInt(Family::blockSize).summaryStatistics();
        return "blocks of " + typicalSizes.getMin() + "-" + typicalSizes.getMax() + " series, crowded "
                + crowdedSizes.getMin() + "-" + crowdedSizes.getMax();
    }

    /** A downloaded chapter's metadata load and one artist. */
    private ChapterForm matchChapterForm(String title, int artistId)
    {
        int row = ++matchRows;
        ChapterForm form = crudChapterForm(0);
        form.setTitleFull(MATCH_PREFIX + row + "] " + title);
        form.setTitle(null);
        form.setGalleryId("perf-match-g-" + row);
        form.setArtistIds(new ArrayList<>(List.of(artistId)));
        return form;
    }

    private Integer seriesOfChapter(int chapterId)
    {
        return jdbc.queryForObject("select series_id from chapter where id = ?", Integer.class, chapterId);
    }

    /**
     * Deletes through the service so touched series are recomputed or deleted, then restores the seeded score,
     * which {@code recomputeDerived} replaces with the chapters' average. Runs before (a killed run's
     * leftovers) and after every matching benchmark.
     */
    private void cleanupMatchRows()
    {
        String prefix = MATCH_PREFIX + "%";
        List<Integer> joined = jdbc.queryForList(
                "select distinct series_id from chapter where title_full like ? and series_id is not null",
                Integer.class, prefix);
        List<Integer> ids = jdbc.queryForList("select id from chapter where title_full like ?", Integer.class, prefix);
        if (!ids.isEmpty())
        {
            chapterService.deleteAll(ids);
        }
        for (Integer seriesId : joined)
        {
            int family = familyIndexOf(seriesId);
            if (family > 0)
            {
                jdbc.update("update series set score = ? where id = ?", humanScore(family), seriesId);
            }
        }
        Cache counts = cacheManager.getCache(CacheConfig.SEARCH_COUNT);   // a minScore count may hold the old score
        if (counts != null)
        {
            counts.clear();
        }
    }

    /** Nothing a matching benchmark created is left, and every seeded series it touched is as seeded. */
    private void assertDatasetRestored(List<Family> touched)
    {
        assertThat(rowCount("chapter")).isEqualTo(chapterCount);
        assertThat(rowCount("series")).isEqualTo(seriesCount);
        for (Family f : touched)
        {
            assertThat(jdbc.queryForObject("select score from series where id = ?", Integer.class, f.seriesId()))
                    .isEqualTo((int) humanScore(f.index()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Measurement
    // ---------------------------------------------------------------------------------------------

    private Page<CardDto> search(SearchType type, Consumer<SearchCriteria> setup, int page)
    {
        SearchCriteria c = new SearchCriteria();
        c.setType(type);
        setup.accept(c);
        c.setSize(PAGE_SIZE);
        c.setPage(page);
        return searchService.search(c);
    }

    /**
     * Page indices come from run 0; probe values have comparable cardinality, so they fit every run. The 2nd
     * page is the usual "Next" click: it runs after the 1st page cached each run's count, as in real use.
     */
    private List<Result> measurePagePositions(String label, SearchType type, ObjIntConsumer<SearchCriteria> setup)
    {
        int totalPages = search(type, c -> setup.accept(c, 0), 0).getTotalPages();
        int last = Math.max(0, totalPages - 1);
        int middle = last / 2;
        return List.of(
                measure(label + " [1st page]", r -> search(type, c -> setup.accept(c, r), 0)),
                measure(label + " [2nd page]", r -> search(type, c -> setup.accept(c, r), 1)),
                measure(label + " [mid page " + middle + "]", r -> search(type, c -> setup.accept(c, r), middle)),
                measure(label + " [last page " + last + "]", r -> search(type, c -> setup.accept(c, r), last)));
    }

    /** The run index picks a different value each time, so no timing is a cache hit. */
    private Result measure(String label, IntFunction<Page<?>> run)
    {
        return measureMetric(label, r -> run.apply(r).getTotalElements());
    }

    /**
     * For any operation: its run-0 metric is kept on the {@link Result} for the correctness guard. The CRUD
     * benchmarks include their first-call warmup, acceptable for rare writes.
     */
    private Result measureMetric(String label, IntToLongFunction op)
    {
        long[] times = new long[RUNS];
        long metricFirst = 0;
        for (int r = 0; r < RUNS; r++)
        {
            long s = System.nanoTime();
            long metric = op.applyAsLong(r);
            times[r] = (System.nanoTime() - s) / 1_000_000;
            if (r == 0)
            {
                metricFirst = metric;
            }
        }
        Arrays.sort(times);
        Result res = new Result(label, times[0], times[RUNS / 2], times[RUNS - 1], metricFirst);

        if (!Boolean.getBoolean("perf.onlyTables"))
        {
            log.info("[perf]   {}: min={}ms med={}ms max={}ms metric(run0)={}", res.label, res.minMs, res.medMs, res.maxMs, res.count);
        }
        return res;
    }

    private void report(String kind, List<Result> results)
    {
        StringBuilder sb = new StringBuilder("\n[perf] ").append(kind)
                .append(" latency over ").append(chapterCount).append(" chapters, ")
                .append(seriesCount).append(" series")
                .append(" (page size ").append(PAGE_SIZE).append("; min/med/max over ").append(RUNS)
                .append(" runs, a different value each run - no cache reuse)\n");
        sb.append(String.format("  %-70s %9s %9s %9s %13s%n", "facet", "min(ms)", "med(ms)", "max(ms)", "metric/run0"));
        sb.append("  ").append("-".repeat(84)).append('\n');
        for (Result r : results)
        {
            sb.append(String.format("  %-70s %9d %9d %9d %13d%n",
                    r.label, r.minMs, r.medMs, r.maxMs, r.count));
        }
        log.info(sb.toString());
    }

    private record Result(String label, long minMs, long medMs, long maxMs, long count)
    {
    }

    // ---------------------------------------------------------------------------------------------
    // Seeding
    // ---------------------------------------------------------------------------------------------

    private void seedSeries()
    {
        List<Series> batch = new ArrayList<>(seriesCount);
        for (int i = 1; i <= seriesCount; i++)
        {
            Series s = new Series();
            // The markers serve term search; matching keys on the family name after the bracket group.
            String titleFull = "[Perf Series " + i + titleMarkers(i) + "] " + familyName(i);
            s.setTitleFull(titleFull);
            s.setTitle(titleFull);
            SeriesService.applyMatchKeys(s);   // match_key + match_block, exactly as every series title write fills them
            s.setStatus(Status.values()[statusOrdinalFor(i)]);
            s.setCreatedDate(dateFor(familyStart[i]));   // created when its first chapter arrived
            s.setScore(humanScore(i));
            s.setScoreSource(ScoreSource.DERIVED);
            batch.add(s);
        }
        seriesIds = new ArrayList<>(seriesCount);
        for (Series s : seriesRepository.saveAll(batch))
        {
            seriesIds.add(s.getId());
        }
    }

    private void seedChapters()
    {
        String sql = "INSERT INTO chapter " +
                "(id, title_pretty, title_full, native_title, upload_date, status, gallery_id, language, score, chapter_num, page_num, disk_size, series_id, match_key) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        List<Object[]> chunk = new ArrayList<>(JDBC_CHUNK);
        for (int i = 1; i <= chapterCount; i++)
        {
            // The markers serve term search; matching keys on the family title after the bracket group.
            String titleFull = "[Perf Chapter " + i + titleMarkers(i) + "] " + familyTitle(familyOf(i), positionInFamily(i));
            TitleKey key = TitleKey.of(titleFull);
            Integer seriesId = familyOf(i) <= seriesCount ? seriesIds.get(familyOf(i) - 1) : null;
            // ~9% unscored, like a real collection.
            Short score = (i % 11 == 0) ? null : humanScore(i);

            chunk.add(new Object[]{
                    i,
                    titleFull,
                    titleFull,
                    "ネイティブ " + i,
                    Date.valueOf(dateFor(i)),
                    statusOrdinalFor(i),         // 80% NEW(0), 18% REVIEWED(1), 2% REVIEWED_FAVOURITE(2)
                    "perf-g-" + i,
                    LANGUAGES.get(i % LANGUAGES.size()),
                    score,
                    seriesId == null ? null : key.getChapterNum(),   // what attaching it to its series numbers it
                    pageNumFor(i),               // spread page counts so the page-count filter/sort is realistic
                    diskSizeFor(i),
                    seriesId,
                    key.getMatchKey()
            });
            if (chunk.size() == JDBC_CHUNK)
            {
                jdbc.batchUpdate(sql, chunk);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty())
        {
            jdbc.batchUpdate(sql, chunk);
        }
    }

    /** The same totals {@code recomputeDerived} maintains, in one set-based UPDATE. */
    private void seedSeriesPageDiskTotals()
    {
        jdbc.update("UPDATE series SET "
                + "page_num = (SELECT COALESCE(SUM(page_num), 0) FROM chapter WHERE chapter.series_id = series.id), "
                + "disk_size = (SELECT COALESCE(SUM(disk_size), 0) FROM chapter WHERE chapter.series_id = series.id)");
    }

    /** 5..54 pages, so page filters select a real slice and the sorts a non-trivial order. */
    private int pageNumFor(int i)
    {
        return 5 + (i % 50);
    }

    /** ~100 KB per page; long, because a series's sum can exceed 2 GB. */
    private long diskSizeFor(int i)
    {
        return pageNumFor(i) * 100_000L;
    }

    /** Records each id's exact membership into {@code counts} for the assertions. */
    private void seedJoinTable(String table, String column, IntFunction<Set<Integer>> assignment,
                               Map<Integer, Long> counts)
    {
        String sql = "INSERT INTO " + table + " (chapter_id, " + column + ") VALUES (?,?)";
        List<Object[]> chunk = new ArrayList<>(JDBC_CHUNK);
        for (int i = 1; i <= chapterCount; i++)
        {
            for (Integer metaId : assignment.apply(i))
            {
                chunk.add(new Object[]{i, metaId});
                counts.merge(metaId, 1L, Long::sum);
                if (chunk.size() == JDBC_CHUNK)
                {
                    jdbc.batchUpdate(sql, chunk);
                    chunk.clear();
                }
            }
        }
        if (!chunk.isEmpty())
        {
            jdbc.batchUpdate(sql, chunk);
        }
    }

    /** No overrides, so effective = the union over the chapters: one set-based INSERT per facet. */
    private void seedSeriesEffectiveMetadata()
    {
        jdbc.update("INSERT INTO series_effective_tags(series_id, tag_id) "
                + "SELECT DISTINCT c.series_id, ct.tag_id FROM chapter c "
                + "JOIN chapter_tags ct ON ct.chapter_id = c.id WHERE c.series_id IS NOT NULL");
        jdbc.update("INSERT INTO series_effective_artists(series_id, artist_id) "
                + "SELECT DISTINCT c.series_id, ca.artist_id FROM chapter c "
                + "JOIN chapter_artists ca ON ca.chapter_id = c.id WHERE c.series_id IS NOT NULL");
        jdbc.update("INSERT INTO series_effective_characters(series_id, character_id) "
                + "SELECT DISTINCT c.series_id, cc.character_id FROM chapter c "
                + "JOIN chapter_characters cc ON cc.chapter_id = c.id WHERE c.series_id IS NOT NULL");
        jdbc.update("INSERT INTO series_effective_parodies(series_id, parody_id) "
                + "SELECT DISTINCT c.series_id, cp.parody_id FROM chapter c "
                + "JOIN chapter_parodies cp ON cp.chapter_id = c.id WHERE c.series_id IS NOT NULL");
        jdbc.update("INSERT INTO series_effective_groups(series_id, group_id) "
                + "SELECT DISTINCT c.series_id, cg.group_id FROM chapter c "
                + "JOIN chapter_groups cg ON cg.chapter_id = c.id WHERE c.series_id IS NOT NULL");
        jdbc.update("INSERT INTO series_effective_languages(series_id, language) "
                + "SELECT DISTINCT c.series_id, c.language FROM chapter c "
                + "WHERE c.series_id IS NOT NULL AND c.language IS NOT NULL");
    }

    // Values drawn from the digits of i in base PROBE, so all probe values have comparable cardinality and
    // several co-occur, which the AND-ed searches need. Artists differ: see artistsFor.
    private Set<Integer> tagsFor(int i)
    {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(tagIds.get(i % PROBE));
        ids.add(tagIds.get((i+1) % PROBE));
        ids.add(tagIds.get((i+2) % PROBE));
        ids.add(tagIds.get((i+3) % PROBE));
        ids.add(tagIds.get((i+4) % PROBE));
        ids.add(tagIds.get((i+5) % PROBE));
        ids.add(allTagId);                      // on 100% of chapters
        if (i % 2 == 0)
        {
            ids.add(halfTagId);                 // on exactly 50% of chapters
            ids.add(tagIds.get((i+4) % PROBE));
        }
        if (i % 3 == 0)
        {
            ids.add(tagIds.get((i+5) % PROBE));
        }
        // halfSeriesTagId: the chapters of even-numbered series.
        if (familyOf(i) <= seriesCount && familyOf(i) % 2 == 0)
        {
            ids.add(halfSeriesTagId);
        }
        return ids;
    }

    /** The family artist, plus the i % PROBE probe artist when that one is seeded. */
    private Set<Integer> artistsFor(int i)
    {
        Set<Integer> ids = new LinkedHashSet<>();
        if (i % PROBE < ARTIST_PROBES)
        {
            ids.add(artistIds.get(i % PROBE));
        }
        ids.add(familyArtistFor(familyOf(i)));
        return ids;
    }

    private Set<Integer> charactersFor(int i)
    {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(characterIds.get(i % PROBE));
        ids.add(characterIds.get((i / PROBE) % PROBE));
        return ids;
    }

    private Set<Integer> parodiesFor(int i)
    {
        return Set.of(parodyIds.get(i % PROBE));
    }

    private Set<Integer> groupsFor(int i)
    {
        return Set.of(groupIds.get(i % PROBE));
    }

    // Run indices stay < PROBE, so every run gets a distinct value.
    private int variedTag(int run)
    {
        return tagIds.get(run % PROBE);
    }

    private int variedArtist(int run)
    {
        return artistIds.get(run % PROBE);
    }

    private int variedCharacter(int run)
    {
        return characterIds.get(run % PROBE);
    }

    private int variedParody(int run)
    {
        return parodyIds.get(run % PROBE);
    }

    private int variedGroup(int run)
    {
        return groupIds.get(run % PROBE);
    }

    /** In id order, as downloads arrive, over ~3 years, so a date range selects a real slice: a run of ids. */
    private LocalDate dateFor(int chapter)
    {
        return FIRST_DAY.plusDays((long) (chapter - 1) * DATE_SPAN_DAYS / chapterCount);
    }

    /** 80% NEW / 18% REVIEWED / 2% favourite, on {@code i % 100} so the fractions are exact. */
    private int statusOrdinalFor(int i)
    {
        int bucket = i % 100;
        if (bucket < 2)  return Status.REVIEWED_FAVOURITE.ordinal();   // 2%  (buckets 0-1)
        if (bucket < 20) return Status.REVIEWED.ordinal();            // 18% (buckets 2-19)
        return Status.NEW.ordinal();                                  // 80% (buckets 20-99)
    }

    // Scores as humans give them, mostly 7-9: score->count = 1:2 2:3 3:4 4:6 5:10 6:12 7:17 8:19 9:14 10:10
    // (sums to 97), so "minimum rating 6" keeps ~72% of scored rows.
    //
    // Keyed on i % 97 while status uses i % 100: coprime, so the two are independent. A shared modulus would
    // correlate them, and "REVIEWED + minScore 6" could match nothing.
    private static final int SCORE_MOD = 97;
    private static final short[] HUMAN_SCORE_BY_BUCKET = buildHumanScoreBuckets();

    private static short[] buildHumanScoreBuckets()
    {
        int[][] dist = {{1, 2}, {2, 3}, {3, 4}, {4, 6}, {5, 10}, {6, 12}, {7, 17}, {8, 19}, {9, 14}, {10, 10}};
        short[] out = new short[SCORE_MOD];
        int idx = 0;
        for (int[] scoreAndCount : dist)
        {
            for (int k = 0; k < scoreAndCount[1]; k++)
            {
                out[idx++] = (short) scoreAndCount[0];
            }
        }
        return out;   // idx == SCORE_MOD
    }

    /** See {@link #HUMAN_SCORE_BY_BUCKET}. */
    private short humanScore(int i)
    {
        return HUMAN_SCORE_BY_BUCKET[i % SCORE_MOD];
    }

    // ---------------------------------------------------------------------------------------------
    // Free-text term markers (exact-fraction title search)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code 'A'} = 5%, {@code 'B'} = 10%, {@code 'C'} = 50%. Letters only and none a substring of another
     * (e.g. {@code ZMKAa}, {@code ZMKBc}), so a search hits exactly that fraction.
     */
    private static String termToken(char fraction, int run)
    {
        return "ZMK" + fraction + (char) ('a' + run);
    }

    private String titleMarkers(int i)
    {
        StringBuilder sb = new StringBuilder();
        appendMarker(sb, i, PCT5_MOD, 'A');    // one 5% token when i % 20 is a run residue
        appendMarker(sb, i, PCT10_MOD, 'B');   // one 10% token when i % 10 is a run residue
        if (i % PCT50_MOD == 0)                // all RUNS 50% tokens on the same even half (distinct strings)
        {
            for (int run = 0; run < RUNS; run++)
            {
                sb.append(' ').append(termToken('C', run));
            }
        }
        return sb.toString();
    }

    /** Each token lands on exactly {@code 1/modulo} of the rows. */
    private void appendMarker(StringBuilder sb, int i, int modulo, char fraction)
    {
        int residue = i % modulo;
        if (residue < RUNS)
        {
            sb.append(' ').append(termToken(fraction, residue));
        }
    }

    private long termMatchCount(int total, char fraction, int run)
    {
        int modulo = switch (fraction)
        {
            case 'A' -> PCT5_MOD;
            case 'B' -> PCT10_MOD;
            default -> PCT50_MOD;
        };
        int residue = (fraction == 'C') ? 0 : run;   // the 50% tokens all match the even half (i % 2 == 0)
        return fractionCount(total, modulo, residue);
    }

    private static long fractionCount(int total, int modulo, int residue)
    {
        long fullCycles = total / modulo;
        long remainder = total % modulo;
        if (residue == 0)
        {
            return fullCycles;   // i % m == 0 -> m, 2m, ... -> floor(total/m)
        }
        return fullCycles + (residue <= remainder ? 1 : 0);
    }

    // ---------------------------------------------------------------------------------------------
    // Families (see "Realistic families" in the class note)
    // ---------------------------------------------------------------------------------------------

    /**
     * A capped Pareto draw on its own evenly spread sequence, so a family's size is independent of its name,
     * block and artist.
     */
    private static int familySize(int family)
    {
        double v = fraction(family * BRONZE_FRACTION);
        return (int) Math.min(MAX_SERIES_SIZE, Math.pow(1 - v, -1 / SERIES_SIZE_TAIL));
    }

    /** Families in order until the chapters run out; the last one takes what is left. */
    private static int[] familyStarts(int chapters)
    {
        var starts = IntStream.builder().add(0);   // families count from 1
        int next = 1;
        for (int family = 1; next <= chapters; family++)
        {
            starts.add(next);
            next += familySize(family);
        }
        return starts.add(chapters + 1).build().toArray();
    }

    private static int[] familyOfChapter(int[] starts, int chapters)
    {
        var family = new int[chapters + 1];
        for (int f = 1; f < starts.length - 1; f++)
        {
            Arrays.fill(family, starts[f], starts[f + 1], f);
        }
        return family;
    }

    /** 1-based, and so also its series' position in seriesIds. */
    private int familyOf(int i)
    {
        return familyOfChapter[i];
    }

    private int positionInFamily(int i)
    {
        return i - familyStart[familyOf(i)] + 1;
    }

    /** Ties by index. */
    private static int[] longestFamilies(int[] starts, int families, int n)
    {
        return IntStream.rangeClosed(1, families).boxed()
                .sorted(Comparator.comparingInt((Integer f) -> starts[f + 1] - starts[f]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .limit(n).mapToInt(Integer::intValue).toArray();
    }

    /** As laid out: the last family has only the chapters left for it. */
    private int sizeOf(int family)
    {
        return familyStart[family + 1] - familyStart[family];
    }

    /** From the middle of the series, so its previous and next chapters both exist. */
    private int longSeriesChapterId(int run)
    {
        int family = longFamilies[run];
        return familyStart[family] + sizeOf(family) / 2;
    }

    private long longestSeriesSize()
    {
        return sizeOf(longFamilies[0]);
    }

    /** For the seeding log. */
    private String seriesSizes()
    {
        long single = IntStream.rangeClosed(1, seriesCount).filter(f -> sizeOf(f) == 1).count();
        long tenOrMore = IntStream.rangeClosed(1, seriesCount).filter(f -> sizeOf(f) >= 10).count();
        return String.format("%.0f%% of one chapter, %.1f%% of ten or more, the longest %d",
                100.0 * single / seriesCount, 100.0 * tenOrMore / seriesCount, longestSeriesSize());
    }

    /** The first chapter has the bare name, later ones a number, as scraped titles do. */
    private static String familyTitle(int family, int number)
    {
        return number == 1 ? familyName(family) : familyName(family) + " " + number;
    }

    /**
     * Unique, because the last two words spell the index in base {@link #WORDS}. A sequel adds a fourth word,
     * so it equals no other name and its key extends its base's.
     */
    private static String familyName(int family)
    {
        if (family % SEQUEL_EVERY == 0)
        {
            return familyName(family - 1) + " " + capitalized(word(family / SEQUEL_EVERY % WORDS));
        }
        return capitalized(firstWord(family)) + " " + capitalized(word(family / WORDS)) + " "
                + capitalized(word(family % WORDS));
    }

    private static String leadWord(int family)
    {
        return firstWord(family % SEQUEL_EVERY == 0 ? family - 1 : family);
    }

    /** What a user types into a name box to find the family. */
    private static String twoWords(int family)
    {
        String[] words = familyName(family).split(" ");
        return words[0] + " " + words[1];
    }

    /**
     * The final vowel doubled, as romanized titles disagree ("Yusha" / "Yuusha"). No family word has two
     * vowels together, so this is no family's key, yet matching still reads it as the same token.
     */
    private static String misspelled(int family)
    {
        String name = familyName(family);
        return name + name.charAt(name.length() - 1);
    }

    /** A work in {@code family}'s block whose key no series has: its last words spell an index past every family. */
    private String freshName(int family)
    {
        int fresh = chapterCount + 1 + freshFamilies++;
        return capitalized(leadWord(family)) + " " + capitalized(word(fresh / WORDS)) + " "
                + capitalized(word(fresh % WORDS));
    }

    /**
     * The rank is skewed so a few words start many titles, then scattered (1237 is coprime to the number of
     * syllable pairs), so the common first words are not all alphabetical neighbours.
     */
    private static String firstWord(int family)
    {
        int rank = (int) (FIRST_WORDS * Math.pow(fraction(family * GOLDEN_FRACTION), FIRST_WORD_SKEW));
        int pair = (int) ((long) rank * 1_237 % FIRST_WORDS);
        return SYLLABLES[pair / SYLLABLES.length] + SYLLABLES[pair % SYLLABLES.length];
    }

    /**
     * 104729 is coprime to the number of syllable triples, so distinct indices give distinct triples - and,
     * since every syllable ends in its only vowel, distinct words.
     */
    private static String word(int index)
    {
        int n = SYLLABLES.length;
        int triple = (int) ((long) index * 104_729 % (n * n * n));
        return SYLLABLES[triple / (n * n)] + SYLLABLES[triple / n % n] + SYLLABLES[triple % n];
    }

    private static String capitalized(String word)
    {
        return StringUtils.capitalize(word);
    }

    private static double fraction(double value)
    {
        return value - Math.floor(value);
    }

    private int familyArtistCount()
    {
        return Math.max(PROBE, seriesCount / SERIES_PER_ARTIST);
    }

    /** A sequel shares its base's artist. The skewed rank is independent of the first word's. */
    private int familyArtistFor(int family)
    {
        int base = family % SEQUEL_EVERY == 0 ? family - 1 : family;
        double v = fraction(base * SILVER_FRACTION);
        return familyArtistIds.get((int) (familyArtistIds.size() * Math.pow(v, ARTIST_SKEW)));
    }

    private boolean hasProbeArtist(int family)
    {
        for (int i = familyStart[family]; i < familyStart[family + 1]; i++)
        {
            if (i % PROBE < ARTIST_PROBES)
            {
                return true;
            }
        }
        return false;
    }

    /** 1-based position in {@link #seriesIds}; 0 for a series that was not seeded. */
    private int familyIndexOf(int seriesId)
    {
        int position = Collections.binarySearch(seriesIds, seriesId);
        return position < 0 ? 0 : position + 1;
    }

    /** Over JDBC: for tens of thousands of rows a Hibernate {@code saveAll} would cost more than all other metadata. */
    private List<Integer> insertFamilyArtists()
    {
        String sql = "INSERT INTO artist (id, name) VALUES (?, ?)";
        Integer maxId = jdbc.queryForObject("select coalesce(max(id), 0) from artist", Integer.class);
        List<Integer> ids = new ArrayList<>();
        List<Object[]> chunk = new ArrayList<>(JDBC_CHUNK);
        for (int j = 0; j < familyArtistCount(); j++)
        {
            int id = maxId + 1 + j;
            ids.add(id);
            chunk.add(new Object[]{id, FAMILY_ARTIST_PREFIX + j});
            if (chunk.size() == JDBC_CHUNK)
            {
                jdbc.batchUpdate(sql, chunk);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty())
        {
            jdbc.batchUpdate(sql, chunk);
        }
        log.info("[perf] seeded {} family artist rows", ids.size());
        return ids;
    }

    // ---------------------------------------------------------------------------------------------
    // CRUD / read benchmark fixtures
    // ---------------------------------------------------------------------------------------------

    /** Every seeded chapter is in a series, so the read benchmark always runs the prev/next query. */
    private int seededChapterId(int run)
    {
        return Math.max(1, (run + 1) * chapterCount / (RUNS + 1));
    }

    private int seededSeriesId(int run)
    {
        return seriesIds.get(Math.min(seriesIds.size() - 1, (run + 1) * seriesIds.size() / (RUNS + 1)));
    }

    /** Series-less, as the manual-add path makes it. */
    private ChapterForm crudChapterForm(int run)
    {
        ChapterForm form = new ChapterForm();
        form.setTitleFull(CRUD_CHAPTER_PREFIX + run);
        form.setTitle(CRUD_CHAPTER_PREFIX + run);
        form.setNativeTitle("CRUD " + run);
        form.setStatus(Status.NEW);
        form.setLanguage(LANGUAGES.get(run % LANGUAGES.size()));
        form.setGalleryId("perf-crud-g-" + run);
        form.setScore(5);
        form.setTagIds(probeIds(tagIds, TAGS_PER_CRUD));
        form.setArtistIds(probeIds(artistIds, 1));
        form.setCharacterIds(probeIds(characterIds, 2));
        form.setParodyIds(probeIds(parodyIds, 1));
        form.setGroupIds(probeIds(groupIds, 1));
        return form;
    }

    private ChapterForm scrudChapterForm(int run, int k)
    {
        ChapterForm form = crudChapterForm(run);
        form.setTitleFull(SCRUD_CHAPTER_PREFIX + run + "-" + k);
        form.setTitle(SCRUD_CHAPTER_PREFIX + run + "-" + k);
        form.setGalleryId("perf-scrud-g-" + run + "-" + k);
        return form;
    }

    /** No overrides, so {@code recomputeDerived} takes the heavier derived branch. */
    private SeriesForm crudSeriesForm(int run)
    {
        SeriesForm form = new SeriesForm();
        form.setTitleFull(CRUD_SERIES_PREFIX + run);
        form.setTitle(CRUD_SERIES_PREFIX + run);
        form.setStatus(Status.REVIEWED);   // manually created series default to REVIEWED
        return form;
    }

    /** Deterministic, so the link cost is stable across runs. */
    private List<Integer> probeIds(List<Integer> pool, int n)
    {
        List<Integer> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++)
        {
            out.add(pool.get(k));
        }
        return out;
    }

    /** Clears a crashed run's leftovers: series first, then their now unlinked chapters. */
    private void cleanupSeriesCrud()
    {
        deleteByTitlePrefix("series", CRUD_SERIES_PREFIX, seriesService::delete);
        deleteByTitlePrefix("chapter", SCRUD_CHAPTER_PREFIX, chapterService::delete);
    }

    private void deleteByTitlePrefix(String table, String prefix, java.util.function.IntConsumer delete)
    {
        for (Integer id : jdbc.queryForList(
                "select id from " + table + " where title_full like ?", Integer.class, prefix + "%"))
        {
            delete.accept(id);
        }
    }

    private long rowCountByTitlePrefix(String table, String prefix)
    {
        Long n = jdbc.queryForObject(
                "select count(*) from " + table + " where title_full like ?", Long.class, prefix + "%");
        return n == null ? 0 : n;
    }

    /** {@code where} is built only from ids the benchmark generated, never external input. */
    private long rowCount(String table, String where)
    {
        Long n = jdbc.queryForObject("select count(*) from " + table + " where " + where, Long.class);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------------------------------------
    // MetadataService (rename/merge/remove) benchmark fixtures
    // ---------------------------------------------------------------------------------------------

    private int createMcrudTag(String suffix)
    {
        return tagRepository.save(newTag(MCRUD_TAG_PREFIX + suffix)).getId();
    }

    /**
     * A broad tag's footprint in {@code chapter_tags} and {@code series_effective_tags}. Returns the
     * {@code chapter_tags} rows inserted, so the assertion is exact at any {@code chapterCount}.
     */
    private long linkTag(int tagId, int residue)
    {
        long chapterLinks = jdbc.update(
                "INSERT INTO chapter_tags(chapter_id, tag_id) "
                        + "SELECT id, ? FROM chapter WHERE id % " + MCRUD_LINK_MOD + " = " + residue, tagId);
        jdbc.update(
                "INSERT INTO series_effective_tags(series_id, tag_id) "
                        + "SELECT DISTINCT c.series_id, ? FROM chapter c "
                        + "WHERE c.series_id IS NOT NULL AND c.id % " + MCRUD_LINK_MOD + " = " + residue, tagId);
        return chapterLinks;
    }

    /** Runs before (a crashed run's leftovers) and after the benchmark. */
    private void cleanupMetadataCrud()
    {
        for (Integer id : jdbc.queryForList(
                "select id from tag where name like ?", Integer.class, MCRUD_TAG_PREFIX + "%"))
        {
            jdbc.update("delete from chapter_tags where tag_id = ?", id);
            jdbc.update("delete from series_tags where tag_id = ?", id);
            jdbc.update("delete from series_effective_tags where tag_id = ?", id);
            jdbc.update("delete from tag where id = ?", id);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata builders (separate because the entities share no constructor)
    // ---------------------------------------------------------------------------------------------

    private <T> List<Integer> insertMetadata(int count, String kind,
                                             java.util.function.Function<List<T>, List<T>> saveAll,
                                             IntFunction<T> factory)
    {
        List<T> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            batch.add(factory.apply(i));
        }
        List<Integer> ids = new ArrayList<>(count);
        for (T saved : saveAll.apply(batch))
        {
            ids.add(idOf(saved));
        }
        log.info("[perf] seeded {} {} rows", ids.size(), kind);
        return ids;
    }

    private Integer idOf(Object metadata)
    {
        return switch (metadata)
        {
            case Tag t -> t.getId();
            case Artist a -> a.getId();
            case Character ch -> ch.getId();
            case Parody p -> p.getId();
            case Group g -> g.getId();
            default -> throw new IllegalArgumentException("unknown metadata: " + metadata);
        };
    }

    private Tag newTag(String name)
    {
        Tag t = new Tag();
        t.setName(name);
        return t;
    }

    private Artist newArtist(String name)
    {
        Artist a = new Artist();
        a.setName(name);
        return a;
    }

    private Character newCharacter(String name)
    {
        Character c = new Character();
        c.setName(name);
        return c;
    }

    private Parody newParody(String name)
    {
        Parody p = new Parody();
        p.setName(name);
        return p;
    }

    private Group newGroup(String name)
    {
        Group g = new Group();
        g.setName(name);
        return g;
    }
}

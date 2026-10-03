import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The one supported way to fill the SQLite database: copies the old H2 library into the empty schema
 * Flyway built. Steps are in {@code migration/MIGRATION.md}.
 *
 * <ul>
 *   <li>Copies only columns both sides have, matched case-insensitively. A target table with no source
 *       counterpart (Flyway's history, the FTS5 tables) is skipped and never emptied.</li>
 *   <li><b>Idempotent:</b> each target table is emptied before its copy, so re-running it is also how an
 *       already-migrated database is repaired.</li>
 *   <li>Cleans the text on the way, the one place a whole library can be fixed at once (see
 *       {@link #cleanText}).</li>
 *   <li>Temporal columns use the same typed setters as Hibernate's default binding, so what this writes is
 *       exactly what the app reads.</li>
 *   <li>{@code page_num}/{@code disk_size} do not exist in H2, so they are computed from the disk
 *       afterwards.</li>
 * </ul>
 *
 * <p>Only {@code java.sql}, so it compiles with a bare {@code javac}.
 */
public final class H2ToSqliteMigration
{
    /** Source (H2) column -> target column. */
    private static final Map<String, String> RENAMES = Map.of(
            "serie_id", "series_id",
            "title_jp", "native_title");

    /** Substituted for a NULL source value in a column that is NOT NULL only in the target. */
    private static final Map<String, Object> NOT_NULL_DEFAULTS = Map.of(
            "chapter.status", 0,   // Status.NEW
            "series.status", 0);   // Status.NEW

    private static final Set<String> LANGUAGE_COLUMNS = Set.of("chapter.language", "series.language");

    private static final int BATCH = 1000;

    private static long nulledLiterals;
    private static long recasedLanguages;

    public static void main(String[] args) throws Exception
    {
        if (args.length < 2)
        {
            System.err.println("Usage: H2ToSqliteMigration <h2-jdbc-url> <sqlite-jdbc-url> [h2-user] [h2-password] [data-dir]");
            System.err.println("  e.g. \"jdbc:h2:file:./db/mydbNew;ACCESS_MODE_DATA=r\" \"jdbc:sqlite:./db/mydbNew.db\" sa \"\" ./data");
            System.exit(2);
        }
        String h2Url = args[0];
        String sqliteUrl = args[1];
        String h2User = args.length > 2 ? args[2] : "sa";
        String h2Pass = args.length > 3 ? args[3] : "";
        String dataDir = args.length > 4 ? args[4] : "./data";

        if (!h2FileExists(h2Url))
        {
            System.err.println("H2 database not found: " + h2Url);
            System.exit(1);
        }
        Path sqlitePath = Paths.get(stripJdbcPrefix(sqliteUrl, "jdbc:sqlite:"));
        if (!Files.exists(sqlitePath))
        {
            System.err.println("SQLite database not found: " + sqliteUrl);
            System.exit(1);
        }

        try (Connection src = DriverManager.getConnection(h2Url, h2User, h2Pass);
             Connection dst = DriverManager.getConnection(sqliteUrl))
        {
            // On, so a link to a row the library does not have fails the import instead of being copied.
            // The pragma must run outside a transaction.
            try (Statement pragma = dst.createStatement())
            {
                pragma.execute("PRAGMA foreign_keys=ON");
            }
            dst.setAutoCommit(false);

            Map<String, String> sourceTables = tableNames(src);  // lower-case -> actual
            List<String> targetTables = parentsFirst(dst, tableNames(dst));

            long grandTotal = 0;
            int copied = 0;
            for (String targetTable : targetTables)
            {
                String sourceTable = sourceTables.get(targetTable.toLowerCase(Locale.ROOT));
                if (sourceTable == null)
                {
                    System.out.println("skip  " + targetTable + "  (no matching source table)");
                    continue;
                }
                long n = copyTable(src, dst, sourceTable, targetTable);
                dst.commit();
                grandTotal += n;
                copied++;
            }

            computeImageStats(dst, dataDir);
            dst.commit();

            System.out.println("clean text  (" + nulledLiterals + " blank/\"null\" values -> NULL, "
                    + recasedLanguages + " languages re-cased)");
            System.out.println("Done. Copied " + grandTotal + " rows across " + copied + " tables.");
        }
    }

    private static void computeImageStats(Connection dst, String dataDir) throws SQLException
    {
        Map<String, String> targetTables = tableNames(dst);
        if (!targetTables.containsKey("chapter"))
        {
            System.out.println("skip  page_num/disk_size  (no chapter table on target)");
            return;
        }
        Path root = Paths.get(dataDir).toAbsolutePath().normalize();
        System.out.println("stats page_num/disk_size from " + root + " ...");

        long chapters = 0;
        String update = "UPDATE chapter SET page_num = ?, disk_size = ? WHERE id = ?";
        try (Statement ids = dst.createStatement();
             ResultSet rs = ids.executeQuery("SELECT id FROM chapter");
             PreparedStatement ps = dst.prepareStatement(update))
        {
            int inBatch = 0;
            while (rs.next())
            {
                int chapterId = rs.getInt("id");
                long[] pagesAndSize = countPagesAndSize(root.resolve(String.valueOf(chapterId)));
                ps.setLong(1, pagesAndSize[0]);
                ps.setLong(2, pagesAndSize[1]);
                ps.setInt(3, chapterId);
                ps.addBatch();
                if (++inBatch == BATCH)
                {
                    ps.executeBatch();
                    inBatch = 0;
                }
                chapters++;
            }
            if (inBatch > 0)
            {
                ps.executeBatch();
            }
        }

        int series = 0;
        if (targetTables.containsKey("series"))
        {
            try (Statement s = dst.createStatement())
            {
                series = s.executeUpdate(
                        "UPDATE series SET "
                        + "page_num = (SELECT COALESCE(SUM(page_num), 0) FROM chapter WHERE chapter.series_id = series.id), "
                        + "disk_size = (SELECT COALESCE(SUM(disk_size), 0) FROM chapter WHERE chapter.series_id = series.id)");
            }
        }
        System.out.println("stats page_num/disk_size  (" + chapters + " chapters, " + series + " series)");
    }

    /** {@code [pageCount, totalBytes]}. */
    private static long[] countPagesAndSize(Path chapterDir)
    {
        if (!Files.isDirectory(chapterDir))
        {
            return new long[]{0, 0};
        }
        long count = 0;
        long size = 0;
        try (Stream<Path> files = Files.list(chapterDir))
        {
            for (Path p : (Iterable<Path>) files::iterator)
            {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p) || !isImage(name) || pageNumber(name) == Integer.MAX_VALUE)
                {
                    continue;   // a non-numeric name like thumbnail.jpg is not a page
                }
                count++;
                try
                {
                    size += Files.size(p);
                }
                catch (IOException ignored)
                {
                    // unreadable file contributes 0 bytes
                }
            }
        }
        catch (IOException e)
        {
            return new long[]{0, 0};
        }
        return new long[]{count, size};
    }

    // Copied by hand from the app's ImageDirectory, since this tool has no dependencies.
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp");

    private static boolean isImage(String filename)
    {
        int dot = filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1);
        return IMAGE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }

    /** {@code Integer.MAX_VALUE} for a non-numeric name. */
    private static int pageNumber(String filename)
    {
        int dot = filename.lastIndexOf('.');
        String base = dot >= 0 ? filename.substring(0, dot) : filename;
        try
        {
            return Integer.parseInt(base.trim());
        }
        catch (NumberFormatException e)
        {
            return Integer.MAX_VALUE;
        }
    }

    /** Also tries the {@code .mv.db} suffix, which H2 adds itself and URLs usually leave out. */
    private static boolean h2FileExists(String h2Url)
    {
        String path = stripJdbcPrefix(h2Url, "jdbc:h2:file:");
        int semicolon = path.indexOf(';');
        if (semicolon >= 0)
        {
            path = path.substring(0, semicolon);
        }
        return Files.exists(Paths.get(path)) || Files.exists(Paths.get(path + ".mv.db"));
    }

    private static String stripJdbcPrefix(String url, String prefix)
    {
        return url.startsWith(prefix) ? url.substring(prefix.length()) : url;
    }

    /** Lower-case name -> actual name. */
    private static Map<String, String> tableNames(Connection c) throws SQLException
    {
        Map<String, String> out = new LinkedHashMap<>();
        try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"}))
        {
            while (rs.next())
            {
                String name = rs.getString("TABLE_NAME");
                if (name == null)
                {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                // H2's INFORMATION_SCHEMA is reported as VIEW, so only SQLite's own tables need skipping.
                if (lower.startsWith("sqlite_"))
                {
                    continue;
                }
                out.put(lower, name);
            }
        }
        return out;
    }

    /**
     * Every table after the tables it references, so each link row finds the row it points at. Read from the
     * schema, so a new foreign key needs no change here. Emptying a parent before its copy also empties its
     * link tables (ON DELETE CASCADE); they are copied again afterwards.
     */
    private static List<String> parentsFirst(Connection c, Map<String, String> tables) throws SQLException
    {
        List<String> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String table : tables.values())
        {
            place(c, table, tables, placed, ordered);
        }
        return ordered;
    }

    private static void place(Connection c, String table, Map<String, String> tables, Set<String> placed,
                              List<String> ordered) throws SQLException
    {
        // Marked before its parents are placed, so a cycle or a self-reference cannot recurse for ever.
        if (!placed.add(table.toLowerCase(Locale.ROOT)))
        {
            return;
        }
        for (String parent : referencedTables(c, table))
        {
            String actual = tables.get(parent.toLowerCase(Locale.ROOT));
            if (actual != null)
            {
                place(c, actual, tables, placed, ordered);
            }
        }
        ordered.add(table);
    }

    private static Set<String> referencedTables(Connection c, String table) throws SQLException
    {
        Set<String> out = new LinkedHashSet<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA foreign_key_list(" + q(table) + ")"))
        {
            while (rs.next())
            {
                out.add(rs.getString("table"));
            }
        }
        return out;
    }

    private static List<String> columns(Connection c, String table) throws SQLException
    {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, null, table, "%"))
        {
            while (rs.next())
            {
                out.add(rs.getString("COLUMN_NAME"));
            }
        }
        return out;
    }

    private static long copyTable(Connection src, Connection dst, String sourceTable, String targetTable)
            throws SQLException
    {
        List<String> targetCols = columns(dst, targetTable);
        Set<String> nullableTargetCols = nullableColumns(dst, targetTable);
        Map<String, String> sourceByLower = new LinkedHashMap<>();
        for (String sc : columns(src, sourceTable))
        {
            sourceByLower.put(sc.toLowerCase(Locale.ROOT), sc);
        }

        // Parallel lists, one entry per copied column.
        List<String> tCols = new ArrayList<>();
        List<String> sCols = new ArrayList<>();
        List<Object> nullDefaults = new ArrayList<>();
        List<Boolean> nullable = new ArrayList<>();
        List<Boolean> language = new ArrayList<>();
        for (String tc : targetCols)
        {
            String sc = sourceColumnFor(tc, sourceByLower);
            if (sc != null)
            {
                String qualified = (targetTable + "." + tc).toLowerCase(Locale.ROOT);
                tCols.add(tc);
                sCols.add(sc);
                nullDefaults.add(NOT_NULL_DEFAULTS.get(qualified));
                nullable.add(nullableTargetCols.contains(tc.toLowerCase(Locale.ROOT)));
                language.add(LANGUAGE_COLUMNS.contains(qualified));
            }
        }
        if (tCols.isEmpty())
        {
            System.out.println("skip  " + targetTable + "  (no common columns with " + sourceTable + ")");
            return 0;
        }

        String selectSql = "SELECT " + join(quote(sCols)) + " FROM " + q(sourceTable);
        String insertSql = "INSERT INTO " + q(targetTable) + " (" + join(quote(tCols)) + ") VALUES ("
                + placeholders(tCols.size()) + ")";

        try (Statement del = dst.createStatement())
        {
            del.executeUpdate("DELETE FROM " + q(targetTable));   // replace, never append
        }

        long n = 0;
        try (Statement s = src.createStatement();
             ResultSet rs = s.executeQuery(selectSql);
             PreparedStatement ps = dst.prepareStatement(insertSql))
        {
            ResultSetMetaData md = rs.getMetaData();
            int cc = md.getColumnCount();
            int inBatch = 0;
            while (rs.next())
            {
                for (int i = 1; i <= cc; i++)
                {
                    // Typed setters, so the stored form matches what Hibernate reads.
                    switch (md.getColumnType(i))
                    {
                        case Types.DATE -> ps.setDate(i, rs.getDate(i));
                        case Types.TIME -> ps.setTime(i, rs.getTime(i));
                        case Types.TIMESTAMP -> ps.setTimestamp(i, rs.getTimestamp(i));
                        default -> {
                            Object value = cleanText(rs.getObject(i), nullable.get(i - 1), language.get(i - 1));
                            ps.setObject(i, value != null ? value : nullDefaults.get(i - 1));
                        }
                    }
                }
                ps.addBatch();
                if (++inBatch == BATCH)
                {
                    ps.executeBatch();
                    inBatch = 0;
                }
                n++;
            }
            if (inBatch > 0)
            {
                ps.executeBatch();
            }
        }
        System.out.println("copy  " + sourceTable + " -> " + targetTable + "  (" + tCols.size() + " cols, "
                + n + " rows)");
        return n;
    }

    /**
     * <ul>
     *   <li>A blank or literal {@code "null"} becomes a real NULL, since everything reading the column treats
     *       the string as a real title. Only in nullable columns, so a NOT NULL one never fails the INSERT.</li>
     *   <li>A language is re-cased to its canonical name: language is searched by exact value, so
     *       {@code english} and {@code English} would be two facets.</li>
     * </ul>
     */
    private static Object cleanText(Object value, boolean nullable, boolean isLanguage)
    {
        if (!(value instanceof String text))
        {
            return value;
        }
        String stripped = text.strip();
        if (nullable && (stripped.isEmpty() || stripped.equalsIgnoreCase("null")))
        {
            nulledLiterals++;
            return null;
        }
        if (isLanguage)
        {
            String canonical = canonicalLanguage(stripped);
            if (!canonical.equals(text))
            {
                recasedLanguages++;
            }
            return canonical;
        }
        return text;
    }

    /** Copied by hand from the app's {@code LanguageService}. An unknown language is returned unchanged. */
    private static String canonicalLanguage(String language)
    {
        for (String code : Locale.getISOLanguages())
        {
            String name = Locale.of(code).getDisplayLanguage(Locale.ENGLISH);
            if (name.equalsIgnoreCase(language))
            {
                return name;
            }
        }
        return language;
    }

    private static Set<String> nullableColumns(Connection c, String table) throws SQLException
    {
        Set<String> out = new java.util.HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, null, table, "%"))
        {
            while (rs.next())
            {
                // Unknown nullability counts as NOT NULL, so a driver that cannot tell makes this a no-op
                // rather than a failed INSERT.
                if ("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"))
                        || rs.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable)
                {
                    out.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** A case-insensitive match, else a {@link #RENAMES} source; null if neither. */
    private static String sourceColumnFor(String targetCol, Map<String, String> sourceByLower)
    {
        String direct = sourceByLower.get(targetCol.toLowerCase(Locale.ROOT));
        if (direct != null)
        {
            return direct;
        }
        for (Map.Entry<String, String> rename : RENAMES.entrySet())
        {
            if (rename.getValue().equalsIgnoreCase(targetCol))
            {
                String renamed = sourceByLower.get(rename.getKey().toLowerCase(Locale.ROOT));
                if (renamed != null)
                {
                    return renamed;
                }
            }
        }
        return null;
    }

    // Identifiers are double-quoted in their real case, which is safe for reserved words on both engines.

    private static List<String> quote(List<String> names)
    {
        List<String> out = new ArrayList<>(names.size());
        for (String n : names)
        {
            out.add(q(n));
        }
        return out;
    }

    private static String q(String identifier)
    {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String join(List<String> parts)
    {
        return String.join(", ", parts);
    }

    private static String placeholders(int n)
    {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private H2ToSqliteMigration()
    {
    }
}

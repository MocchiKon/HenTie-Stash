# One-time H2 → SQLite data migration

The library still lives in **H2** (`db/mydbNew.mv.db`); the app now reads **SQLite** (`db/mydbNew.db`),
whose schema is built by **Flyway**. This is the one supported way to get the existing data across:
Flyway creates an empty SQLite database, then `H2ToSqliteMigration` copies the rows into it. Nothing
happens automatically, and nothing here runs as part of the app.

The two DB files live side by side (`.mv.db` = old H2, `.db` = new SQLite) and the H2 file is opened
**read-only**, so the source is never touched and you can always go back to it.

`H2ToSqliteMigration.java` copies every row table-by-table over JDBC. It copies only columns present in
both schemas, handles the historical `serie_id`→`series_id` / `title_jp`→`native_title` renames, coalesces
a legacy NULL `chapter.status` to `0` (`NEW`, now NOT NULL), **cleans up the old text values** (below),
keeps dates byte-identical (typed `DATE`
binding), and is **idempotent** (each target table is emptied before copy, so you can re-run it). Tables
that only exist on the SQLite side (the `series*` / `series_effective_*` ones — Series never held data —
and Flyway's own `flyway_schema_history`) are skipped automatically. It also **computes the new
`page_num`/`disk_size` columns** (which the old H2 schema lacked): per chapter, the count and total byte
size of its page images under `<data-dir>/<chapterId>/`; per series, the sum over its chapters. The data
directory is the optional 5th argument (default `./data`, the app's default).

### Text clean-up during the copy

Two things the scraped H2 data got wrong are repaired on the way across (reported as a
`clean text (N blank/"null" values -> NULL, M languages re-cased)` line at the end of the run):

- **The literal string `"null"`** — the scraper wrote the four-letter word into `native_title` for works
  that have none, and everything downstream (the detail view's `th:if`, matching, search) then treats it
  as a real title. It becomes a genuine SQL `NULL`, as does a blank string — but **only in columns the
  target schema declares NULLABLE**, so a NOT NULL column (`title_pretty`, `title_full`, …) is never
  emptied.
- **Language casing** — `english` is rewritten to `English`. Language is stored as a plain string and
  searched/faceted by **exact value**, so the two spellings are two separate facets: a migrated chapter
  would not appear under the same language chip as one added through the app's form. (The app's own write
  paths canonicalize too, via `LanguageService.canonical`.)

Both run per value during the copy, so **re-running step 5 repairs a database migrated by an older
version of this tool** — nothing else is needed.

## Steps

1. **Stop the app** and **back up** the whole `db` folder (copy it somewhere safe).

2. **Let Flyway create the SQLite database.** There must be no `db/mydbNew.db` yet — delete it if a
   previous run left one, because Flyway refuses to adopt a database it did not create (it aborts with
   "found non-empty schema without a schema history table" rather than guessing). Then start the app once
   so it builds the full schema, and stop it:
   ```
   ./mvnw spring-boot:run      # wait for "Started HenTie..." in the log, then Ctrl-C
   ```

3. **Locate the two driver jars** (both are already in your local Maven repo). In Git Bash:
   ```bash
   find "$HOME/.m2/repository/com/h2database/h2"   -name 'h2-*.jar'         ! -name '*-sources.jar'
   find "$HOME/.m2/repository/org/xerial/sqlite-jdbc" -name 'sqlite-jdbc-*.jar' ! -name '*-sources.jar'
   ```
   Note the newest jar path from each.

4. **Compile the migrator** (no dependencies needed — it uses only `java.sql`):
   ```
   javac -d migration/out migration/H2ToSqliteMigration.java
   ```

5. **Run it** from the project root (where `./db` lives). The classpath separator is **`;` on Windows**
   (Git Bash still launches the Windows JVM) and `:` on Linux/macOS. Substitute the jar paths from step 3:
   ```
   # Windows (Git Bash / PowerShell / cmd) — note the ';' separators
   java -cp "migration/out;<H2_JAR>;<SQLITE_JAR>" H2ToSqliteMigration ^
        "jdbc:h2:file:./db/mydbNew;ACCESS_MODE_DATA=r" "jdbc:sqlite:./db/mydbNew.db" sa "" ./data

   # Linux / macOS — note the ':' separators
   java -cp "migration/out:<H2_JAR>:<SQLITE_JAR>" H2ToSqliteMigration \
        "jdbc:h2:file:./db/mydbNew;ACCESS_MODE_DATA=r" "jdbc:sqlite:./db/mydbNew.db" sa "" ./data
   ```
   - `ACCESS_MODE_DATA=r` opens the H2 file **read-only**, so the source is never modified.
   - Args 3–4 are the H2 user/password (defaults `sa` / empty — the app's defaults).
   - Arg 5 is the image **data directory** (default `./data`); it is scanned to compute each chapter's
     `page_num`/`disk_size`. Pass the real path if your images live elsewhere.
   - Use the **same H2 version** that created the file (the jar now in `.m2` is that version, since it
     was a managed dependency before this migration).

   It prints one line per table (`copy <src> -> <dst> (N cols, M rows)`) and a final total.

   On Windows this worked:
   ```
   MSYS2_ARG_CONV_EXCL="*" java -cp "migration/out;C:/Users/MS/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar;C:/Users/MS/.m2/repository/org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0.jar" H2ToSqliteMigration "jdbc:h2:file:./db/mydb;ACCESS_MODE_DATA=r" "jdbc:sqlite:./db/mydbNew.db" sa "" ./data
   ```

6. **Give SQLite statistics for the freshly loaded rows** — one `ANALYZE` against the new file, so the
   planner turns the search semi-joins into index lookups instead of scans. It is persisted in the file,
   so this is a one-off:
   ```bash
   sqlite3 ./db/mydbNew.db "ANALYZE;"
   ```

7. **Start the app** and verify: check that your chapter/metadata counts look right and spot-check a few
   upload dates. Because the migrator is idempotent, you can re-run step 5 if anything looks off.

8. **Fold the metadata names**: Manage → Library maintenance → **"Rewrite metadata names in lower case"**.
   The app stores every tag/artist/character/parody/group name in lower case, and this copy brings the old
   library's spellings across as they are — the migrator deliberately does not fold them itself, because
   two capitalisations of one name are two rows that have to be *merged* (links moved onto the survivor),
   which is what that button does. Until it is run, such names simply display as they were; matching was
   case-insensitive all along, so nothing is broken in the meantime.

9. Once satisfied, you may delete the old `db/mydbNew.mv.db` (keep your backup from step 1 for a while).

## Notes

- Foreign keys are enforced during the load, so a link row whose chapter, series or metadata row is missing
  stops the import with `FOREIGN KEY constraint failed` in the table after the last `copy` line. Tables are
  copied in dependency order (parents before the join tables), read from the schema.
- If H2 refuses to open the file with a version error, it means the `.m2` H2 jar is newer than the one
  that wrote the file — point the classpath at the exact H2 version you last ran the app with.
- This copies **data only**. The schema (tables, columns, indexes, FTS5 title index) is owned by Flyway
  (`src/main/resources/db/migration/`, `ddl-auto=none`); step 2 is what puts it there.
- Because step 2 leaves the FTS5 title index and its triggers in place, the copied rows are indexed for
  title search as they are inserted; no rebuild is needed afterwards. (If you ever suspect drift, use
  Manage → Library maintenance → "Rebuild title search index".)

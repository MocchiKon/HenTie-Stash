# CLAUDE.md — HenTie design decisions

Why the codebase is shaped this way. **Only decisions, constraints and invariants live here** — what the
code does is in the code. Read the relevant section before changing the data model, search, matching,
downloading, image handling, security, packaging, or anything that writes (see "Writes and SQLite's lock").

## What it is
A **single-user, local** web app (MPA) for browsing and managing comics: `Series` → ordered `Chapter`s,
each with page images on disk. Chapter metadata comes from an external scraper; series are curated by the
user and also created by auto-matching. Ships as one executable jar (plus a Windows `.exe`), reachable on
the LAN.

Stack: Spring Boot 4.1, Java 21, Thymeleaf, Spring Data JPA/Hibernate, SQLite (xerial + Hibernate
community `SQLiteDialect`), Flyway, Spring Security, MapStruct, Lombok, Apache Commons, Caffeine.
**No npm** — JS is handwritten in `static/js`, CSS in `static/css/app.css`.
Use Lombok for constructors and simple getters/setters.

## Writing comments, javadoc and this file
- **Describe the code as it is, never its history.** No "used to", "previously", "now does", "the old X".
  A rejected alternative may be named in the conditional ("a per-page name would let one call delete
  another's output").
- **Javadoc answers "why?", not "what?"**: design decisions, constraints, invariants, trade-offs, and what
  breaks if they are ignored. No implementation details. No javadoc is better than one that repeats the
  name or body. Keep `@throws` — it is the contract.
- **Javadoc stays in its own scope**: class X does not explain the design of class Y.
- **Comments say why** (or why not the obvious alternative). "What" or "how" only where the code is
  genuinely hard to follow.
- Short, plain English (B2/C1 level). Say each thing once, where it belongs.

## The premise everything follows from
- **Read and search dominate.** The user browses and searches far more than they write, so read latency
  wins every trade-off against write cost.
- **Design for ~5 million `Chapter` rows.** No unbounded scans, no whole tables in memory, no
  O(all chapters) work on a request path. Benchmark at that scale.
- Recurring consequences: values that search filters on are **materialized** on the owner row; every list
  is **paginated**; expensive work runs in **committed slices** so SQLite's single writer is never held
  for long.

## Run / build / test
```
./mvnw spring-boot:run                 # runs against ./db (real data); opens the browser
./mvnw test                            # unit + integration tests (PerformanceIT: -Dperf.enabled=true;
                                       # ComfyUiLiveIT against a real ComfyUI: -Dcomfyui.live=true)
./mvnw test -Dtest='!*IT'              # unit tests only
./mvnw test -Dtest='*IT'               # integration tests only
./mvnw test -Pe2e                      # end-to-end tests (*E2E) against the real sites, and nothing else
./mvnw clean package                   # runs all tests; -DskipTests to skip
                                       # -> target/HenTie.jar and, on Windows, target/HenTie.exe
./mvnw package -PpackageForRelease     # + target/HenTie-all-platforms.zip and, on Windows,
                                       #   target/HenTie-windows.zip
```
DB is `./db/mydbNew.db` (WAL). Neither the file nor `./db` must exist: `config/DatabaseDirectory` creates
the folder (xerial fails with `SQLITE_CANTOPEN` on a missing parent) and Flyway builds the schema. Images
live under `app.data-dir`, served at `/data/**`. `mock_server/` stands in for a real source — paste
`mock:<id>`.

## Configuration (`app.*`, `config/AppProperties`)
- **`AppProperties` field initializers are the only place an `app.*` default is written** — not
  `application.properties`, not `@Value("${…:default}")`, not a constant. The test profile's
  `application.properties` **replaces** the main one, so a default kept only in the main file would be
  0/false/null in every test. An install overrides a value by setting it.
- Exceptions: `app.download.mock-dir` belongs to `MockDataDownloader`, and `app.download.nhentai.*`,
  `app.download.ehentai.*` and `app.download.chaika.*` to their sources' `*Properties` (a source owns its
  config), and the built-in compression modes live in `image-compression-modes.properties` (see "Image
  compression").

## Package layout (`io.github.mocchikon.hentie`)
`entity/` JPA entities, ORDINAL enums, `entity/link/` read-only search projections · `repository/` +
`repository/spec/` search predicates · `service/` business logic, with `match/` (chapter→series matching),
`download/` (download pipeline), `compress/` (downscaling + re-encoding), `comfy/` (ComfyUI in the image
viewer), `scratch/` (temporary image files) · `scrapper/` data sources (a real one in its own package,
`scrapper/nhentai/`, `ehentai/`, `hitomi/`, `chaika/`; `scrapper/gallerydl/` runs gallery-dl for the sources
that use it) · `dto/` view models, forms, search
criteria · `mapper/` MapStruct · `web/` controllers · `security/` · `config/` · `resources/db/migration/`
Flyway history.

## Invariants — breaking these corrupts data
- **ORDINAL enums are never reordered and never switched to `STRING`**: `Chapter.status` (`Status`),
  `Series.scoreSource` (`ScoreSource`), `Chapter.downloadStatus` (`DownloadStatus`),
  `ImageCompressionMode.encoder` (`ImageEncoder`). Existing rows hold the integer. `NONE` is first in
  `DownloadStatus` so the column's `DEFAULT 0` means "not from a download".
- **`status` is `NOT NULL`** on `Chapter` and `Series` (field default `NEW`; services coalesce null).
- **`Group` maps to table `group_artists`** — `GROUP` is a SQL keyword.
- **Flyway owns the schema; Hibernate never generates DDL** (`ddl-auto=none`). `V1__initial_schema.sql`
  builds everything from an empty file (tables, indexes, FTS5 tables, triggers). There is **no
  `baseline-on-migrate`**, so a non-empty foreign database makes `migrate()` fail loudly. During
  development a change goes in a new `V<n>__*.sql`; the history is folded into V1 before release.
  `SchemaMigrationIT` boots with `ddl-auto=validate` and checks the FTS tables, triggers, foreign keys and
  indexes that Hibernate cannot validate.
- **Every join table has `ON DELETE CASCADE` foreign keys to both its owner and its metadata row**
  (hand-written in V1: the dialect emits none, and SQLite cannot add one later without rebuilding the
  table). So no delete path (Hibernate, a batch delete, native SQL) can leave a link naming a missing row,
  which the native search relies on. Hibernate still deletes an owner's links itself; the cascade then
  finds nothing, one index seek per join table. **Each FK column must lead an index**, or every parent
  delete scans the join table. **`chapter.series_id` has none on purpose**: unlinking must go through
  `SeriesService` (recompute, `deleteIfEmpty`), which a `SET NULL` would skip. A new join table needs the
  same keys and indexes (`SchemaMigrationIT` checks both). Links are inserted after the rows they name.
- **`migration/` is the one-time H2→SQLite import**, not the Flyway history. `H2ToSqliteMigration.java`
  copies the old H2 library into the empty Flyway schema with plain `java.sql`, with foreign keys enforced
  and tables in dependency order read from the schema (parents before join tables). It is idempotent, never
  runs automatically, and is the **only** supported way to fill the database. It also repairs text on the
  way: blank or literal `"null"` becomes SQL NULL (only in nullable columns) and `language` is written in
  its canonical spelling, because language is searched by exact value.

## Data model
- **`Series` is a free-form bucket of chapters**, curated by the user or created by matching.
  `titleFull` is the only required field and is **not unique** on either entity — the scraper produces
  duplicates.
- **A series may hold one language or many.** `Series.language` is only an optional override. Never force
  one series per language.
- **Optional overrides, else derived from the chapters** (`nativeTitle`, `language`, `score`, metadata).
  `title` (`title_pretty`) is `NOT NULL` and falls back to `titleFull`, not to the chapters. `createdDate`
  is set on creation only; series search filters on it. Series metadata are unidirectional `@ManyToMany`
  via `series_*` join tables, so the shared Tag/Artist/… rows stay untouched.
- **Everything search reads is materialized on the owner row** — the core performance decision and the
  reason `SeriesService.recomputeDerived` exists:
  - `Series.score` is the **effective** score (`score_source` says override or rounded chapter average),
    so the filter is one indexed `score >= minScore` that agrees with the displayed value.
  - `series_effective_{tags,artists,characters,parodies,groups,categories,languages}` hold override-else-derived
    values, so series facet search is the same cheap semi-join as chapter search, with no join to chapters.
  - `page_num` / `disk_size` (`NOT NULL DEFAULT 0`, indexed) count page images; on a series they are the
    sum over its chapters.
  - `recomputeDerived` runs on **every** path that changes chapter scores, metadata, language, images or
    series membership. **Anything new that changes these must go through `SeriesService` /
    `ChapterService`**, or the effective sets go stale. Display still derives on the fly (it needs
    per-value chip counts), so keep both paths.
- **Image stats are only correct if they are resynced.** The scraper writes images straight into
  `<data-dir>/<id>/`, and imported rows keep `DEFAULT 0`. A stale `0` breaks search silently (every row
  ties, so a `page_num` sort looks ignored). Everything goes through `ChapterService.syncImageStats`:
  upload/delete, "Rescan images", Manage → Library maintenance resync, and **opening the chapter detail
  page**. Anything new that puts images on disk must call it too.
  - **Repairs read the disk uncached** (`ImageDirectory.stats` / `pageNumbers`). The listing cache only
    knows the app's own writes, so a repair through it would recompute the old numbers. Never route a
    repair or the pipeline's "which pages are missing" through `filenames`.
  - **An unreadable directory is not "zero pages".** `stats` / `pageNumbersIn` return empty only for a
    *missing* directory; any other `IOException` throws. Otherwise a resync writes a false `0` and the
    pipeline re-fetches over pages the user replaced or deleted. `applyImageStats` keeps the stored
    numbers on error (one bad folder must not abort a sweep); the pipeline treats it as a transient
    failure.
  - **Display reads the stored columns, but the detail page repairs them before showing them**: the read-only
    `ChapterService.buildView`, then `ImageStatsService.healIfDrifted` with the count and listing the view read,
    and only after a repair the view again. So a badge can never disagree with what search filters on, and a
    chapter in sync costs no query beyond the view's. **The repair is skipped while the library is busy**
    (`WriteGate.ifFree`): a page view never waits for the write lock, and the next view repairs. The check is
    page count only: checking `disk_size` would need a `stat` per file per view.
  - **Not done on search grids or the series page** — up to 36 thumbnails per page would mean a DB write
    on the hottest read path. Chapters heal when opened; `recomputeDerived` carries it up.
- **Opening the image viewer touches no database row.** The model holds only page URLs, a counter and a
  Back link; `buildView` would load six metadata collections and every sibling chapter for nothing. So
  there is **no stats heal and no 404 for a bogus id** in the viewer — don't add those queries back.
  - **Moving to the next/previous chapter is a separate request, made only at a chapter's end**
    (`GET /chapter/{id}/neighbour`). The first press past the last page shows which chapter is next; the
    second opens it (the previous one at its last page, `page=last`). Two presses because the click zones
    cover the whole page: a tap meant for a still-loading last page must not leave the chapter. It shares
    `ChapterService.neighboursOf` with the detail page (same series, same language) and **heals the stats
    of the chapter it names** (skipped while busy, like the detail page's; the stored count comes from the row it
    loaded anyway), since it is the one route into the reader that skips the detail page.
- **Deleting a series or removing a chapter only unlinks** (`chapter.series = null`); no
  `orphanRemoval`/`REMOVE` cascade. The one exception is `SeriesService.deleteWithChapters` — its own
  danger-zone button with a confirmation that names the chapter count.
- **A series that loses its last chapter is deleted** (`SeriesService.deleteIfEmpty`). An empty series has
  no score, metadata, cover or pages, and since auto-linking puts every new chapter in a series, each user
  correction would otherwise leave a dead row. Called from every path that can empty a series; **anything
  new that unlinks or deletes a chapter must call it too.** No exemption for user-curated series. A series
  *created* empty survives, since it reaches none of those paths.

## Indexing
- **Each sort column has `(<sort>, id, status)`, `(status, <sort>)` and, on chapters, `(language, <sort>)`.**
  With single columns SQLite filters by one index and sorts every match in a temp B-tree (1.5–1.9 s at the
  last page of 1.5M chapters); a composite is walked in sort order and covers the filter (47 ms). Status
  after the id keeps the order `(<sort>, id)` and lets several statuses be filtered from the index (438 ms
  → 36 ms). Chapter language is a plain column, so without its composites each candidate row was read to
  check it (591 ms → 123 ms for tag + language by score). **A new `SortBy` needs all of them on both
  entities** (language ones on chapters), in `@Table(indexes = …)` and in a Flyway migration
  (`SchemaMigrationIT` checks).
- **`(status)` and `(language)` are the DATE sort's composites, not prefixes of the others.** SQLite ends every
  index with the rowid and DATE sorts by id, so they are `(status, id)` and `(language, id)`. An index that
  starts with the same column gives its rows in its second column's order, and SQLite sorts every match
  (last page of one language by date: 11 ms against 1.4 s; of one status: 5 ms against 0.6 s).
- **`idx_<owner>_status`, `ix_chapter__language`, `ix_<owner>__id` and `(language, series_id)` on
  `series_effective_languages` are streams for `CompoundSearchQuery`**: each gives owner ids in order, so it
  can be merged (see Search).
- **A series' chapters are found through `ix_chapter__series_match_key`**, which `series_id` leads; a plain
  `series_id` index would duplicate it.
- **`Chapter` and `Series` are `@DynamicUpdate`**: an UPDATE names only the changed columns, so SQLite rewrites
  only the indexes holding them (a status change on 20k chapters: 0.4 s against 1.2 s with every column
  named). Read indexes are cheap to keep this way; don't drop one to save write time.
- **Planner statistics are kept current on every start** (`config/PlannerStatistics`, a Flyway callback run
  after each migrate). SQLite never gathers them itself, and without them, or with ones gathered before the
  library grew, some searches sort every match (a 1-year date range, last page of 1.5M chapters: 10 s against
  0.1 s; the 300k chapters added since the last analysis, first page: 430 ms against 0 ms).
  - `PRAGMA optimize=0x10002` analyzes a table with an index that has no statistics, or one whose size changed
    10-fold. Empty tables get none, so a new library is analyzed on its first start with data. **Keep the
    mask**: the 0x10 bit of the default one (an analysis limit) re-analyzes without `sqlite_stat4` and deletes
    the table's stat4 rows.
  - A chapter or series count 25% away from the one the statistics were gathered at re-analyzes everything,
    once per quarter of growth or loss (~7 s at 1.5M chapters). The 10-fold rule alone would keep a library's
    first statistics for good.
  - **New statistics end with a schema change** (a view created and dropped), so a start that analyzed
    nothing writes nothing. ANALYZE writes rows, not schema, so pooled connections opened before it (Hikari
    fills the pool while Flyway runs) would plan without its results until replaced, 30 minutes later.
  - **It runs at startup, never in the background.** ANALYZE holds SQLite's only write lock for seconds (16 s
    for every table at 1.5M chapters), and every write would wait for it, a request giving up after its
    budget (see "Writes and SQLite's lock").
  - `PerformanceIT` runs the same pass (`flyway.migrate()`), so it measures the plans the app gets.
  - **Never run `ANALYZE` with a SQLite built without STAT4** (the winget `sqlite3` CLI is one): it deletes
    `sqlite_stat4`, and several searches go back to sorting every match. The app's xerial build has it.
  - `temp_store=MEMORY` is in the JDBC URL for the same searches; see `application.properties`.
- **Metadata join tables have both directions indexed.** `(meta_id, owner_id)` is covering and drives the
  search semi-join; `(owner_id, meta_id)` drives loading and rebuilding an owner's metadata. SQLite, unlike
  H2, creates no index for a foreign key, so without it `where chapter_id = ?` scans the whole join table.
  A composite PK `(owner_id, meta_id)` would do the same but needs `List`→`Set`.

## Search (`repository/spec`, `service/SearchService`)
- **Facets are AND-ed, and so are several values of one facet.** How each included value (a metadata
  value, the title term, series languages) is checked depends on its size. `SearchService.plan` probes
  each with a `LIMIT`ed count (well under 1 ms a tag) before the page runs; **never a full count first**,
  which would run before the page instead of beside it.
  - **Some value is small** (under `app.search.large-list-rows`, 30k, where both plans cost the same on
    1.5M chapters): the spec **starts from the smallest** as a non-correlated `IN` and checks every other
    value with a correlated `EXISTS`, one index seek per candidate. An `IN` list is built in full before the
    first row, so beside a small value a broad one would cost more than the search (66 ms → 0 ms; beside a
    term in every title: 198 ms → 3 ms).
  - **Every value is broad**: `CompoundSearchQuery` for page and count (below). So is **one metadata value
    (or the series languages) alone, sorted by date, with no owner filter**, without a probe: its own index
    gives the page at any size. Not a title term: FTS5 reads rowids backwards slowly, so a rare term is
    faster from its list (7 ms against 22 ms) and the probe decides.
  - **No included value**: the spec as it is, or natively for exclusions only (below).
  - Without a driver (`idsAfter`, `chapterCountInMatchingSeries`) every value is a non-correlated `IN`.
- **Broad searches run natively** (`CompoundSearchQuery`). The join-table `(meta_id, owner_id)` indexes,
  the title index and the status/language indexes all give owner ids in order, so an
  `INTERSECT`/`EXCEPT` of them is a merge that reads each entry once and builds nothing, and `ORDER BY 1 …
  OFFSET` over it skips index entries, not rows (tag on half the library, last page: 203 ms → 10 ms). The
  page returns ids; the rows are then read by primary key.
  - **The date order** merges a single status or chapter language in as one more stream when that leaves
    no filter. Otherwise every owner filter is applied over the merge, which then has to be built in full
    first, so a status stream would only make it bigger (tag + NEW + date range: 69 ms against 117 ms).
  - **Other sorts** walk the sort index with `+o.id IN (…)`. SQLite prices any `IN (subquery)` at ~25 rows
    and would start from the list, look up every match and sort them all (1.1 s → 0.13 s for a middle
    page); the unary plus leaves the list a filter only. A single status or language stays a filter there,
    so the `(status, …)`/`(language, …)` indexes can be walked.
  - **Exclusions only**: the count is all owners minus the excluded union (30 ms); the date-ordered page
    subtracts from the id stream when no row filter is left, else it stays Criteria (which stops after one
    page).
  - Exact only while every join-table row and title-index entry names an existing owner (links are removed
    by the foreign keys' cascade, index entries by the triggers).
  - **Kept in sync with the specs by hand.** `CompoundSearchIT` (with `large-list-rows=1`, so everything is
    broad) compares every page, order and total with the spec; a title under 3 characters (the `LIKE`
    fallback) is never sent native.
- **Excluded metadata uses one correlated `NOT EXISTS` per facet** in the specs (natively: one `EXCEPT`
  stream per value), for content and count. An exclusion
  keeps almost every row, so a non-correlated `NOT IN` would materialize every row carrying the value
  (~375 ms vs ~2 ms for a page); the correlated form is one seek into `(owner_id, meta_id)` per visited
  row, so a `LIMIT`ed page stops early. Series exclude on the **effective** tables. Included and excluded
  ids are **separate request lists** (`tagIds` / `excludedTagIds` …), so a link that knows nothing about
  exclusion (a detail-page chip) still means "include". Only the six metadata kinds support exclusion.
  - The search form toggles a token by **renaming its hidden input** (`tokenized-input.js`,
    `data-excluded-name`). That lives in its own `searchTokenField` fragment: on edit forms, "exclude"
    means nothing.
- **Never set `query.distinct(true)`.** Nothing joins in a row-multiplying way, and it forces an expensive
  `count(distinct id)`.
- **Semi-joins root on a read-only `MetadataLink` projection** (`entity/link/`), not the owner entity.
  Rooting on the entity emits a redundant re-join to `chapter` plus a PK lookup per match; the
  `@Immutable @Subselect` projections give a bare covering-index semi-join. They exist only for tables
  search reads (`chapter_*`, `series_effective_*`), generate no DDL, and their `@Subselect` table names
  must be kept in sync.
- **Title search matches `titleFull` and `nativeTitle` only** (`TitlePredicate`). The pretty `title` falls
  back to `titleFull`, so searching it would add a column without finding anything new. The same
  predicate backs the Add-to-series name box, so both agree on what exists. That box ranks a *bounded*
  fetch ordered by the length of the **shorter** title, because either title may be the one that matched.
- **Title search uses an FTS5 `trigram` index** created by Flyway; `TitleSearchIndex` only uses and
  repairs it. `LIKE '%term%'` cannot use a B-tree (~0.5–0.7 s over 1.5M rows vs ~10 ms).
  - **The index is what makes search case-insensitive**: SQLite `LIKE`/`lower()` fold ASCII only, so a
    scan cannot match `äöü` against `ÄÖÜ`.
  - **The lookup stays a semi-join in SQL** (`entity/link/TitleMatch`; FTS5 treats `<table> = ?` as
    `MATCH ?`). **Never resolve ids in Java**: that needs a cap, a `LIKE` fallback above it with different
    semantics, and hits SQLite's bind-variable limit.
  - **The `LIKE` fallback is only for terms under 3 characters**, which cannot form a trigram. It matches
    every case variant of the term, which is bounded because the term is 1–2 characters. There is **no
    "index missing" fallback** — Flyway creates it or fails.
  - **Sync is by SQL triggers**, so every write path (Hibernate, native SQL, a manual `sqlite3` session)
    keeps it current. The update triggers fire only when a title or the id changes (`UPDATE OF` + `WHEN`):
    unconditional, they would re-index an unchanged title on every status, page count or series change
    (182 ms against 8 ms per 20k updates), and every `recomputeDerived` its series' title. Manage →
    **"Rebuild title search index"** repairs what triggers cannot see. The H2 import copies into a schema that
    already has the triggers.
  - Input is always **literal**: a `MATCH` phrase neutralizes FTS5 operators; the `LIKE` path escapes
    `%`/`_`.
- **The pagination count is cached, not skipped** (the UI shows total pages): content query plus an exact
  count cached by `SearchCriteria.filterKey()`. Result **content** is never cached.
- **On a cache miss the count runs in parallel with the content.** `SearchService.search` is deliberately
  **not** `@Transactional`: without a transaction the count gets its own connection. When a transaction
  *is* active (tests, nested calls) it counts sequentially, because a second connection could not see
  uncommitted rows. It keys off `isActualTransactionActive()`, not open-in-view. Don't add
  `@Transactional` without revisiting this.
- **A lone small value counts natively too** (no owner table, a distinct count of its links); beside other
  values the count follows the page's plan.
- **Series language search has two SQL shapes** without a driver (told apart by `query.getResultType()`).
  Language matches a large share of series, so the `LIMIT`ed content page uses a correlated `EXISTS` to
  stop early, while the count uses a non-correlated `IN` (as `EXISTS` it becomes an O(series) nested loop).
  **Don't merge them.** With a driver, both take the driver's shape.
- `galleryId` is chapter-only; the date filter runs on `chapter.uploadDate` / `series.createdDate`;
  `DATE` sorts by `id` as a cheap proxy. The card title is a user setting (`TitleDisplayMode`), falling
  back to `titleFull`.
- **A page of series covers is one query** — never load a series' chapters to pick its first one, never
  one query per card.

## Bulk delete & review mode (search results)
Three actions on their own row under the results heading — never next to Refine / New search, where a
destructive button is one mis-tap from a navigation one.
- **On a series search every delete also deletes the series' chapters** (`deleteWithChapters`), review mode
  included. These actions exist to get rid of galleries.
- **The confirmation names real counts, fetched when the button is pressed** (`/search/results/delete-count`),
  not rendered with every page. "All matching" uses the cached search count, so it matches the number
  above the grid. The page count endpoint (`delete-count/page`) **requires** ids, so it can never fall
  through to the whole-search count.
- **"Delete this page" posts the ids of the shown cards** and returns to the **same** page, which then
  holds the next results. A page past the end redirects to the last one, keeping the flash message.
- **"Delete all matching" walks the search in committed slices** (`BulkDeleteService`): not
  `@Transactional`, `long-running` form, no cap, no job. **A slice is measured in chapters** (50, or series
  up to 50 chapters together): each costs its rows and its folder, and a write in another tab waits for one
  slice (50 chapters: under a second at 1.5M; 200: two to four). Ids come from an **id keyset**
  (`SearchService.idsAfter`) in batches larger than a delete slice, so a row that fails to delete cannot
  loop the walk. Chapters go through `ChapterService.deleteAll`, which handles each touched series **once
  per batch** and recomputes only series that keep a chapter; the rest are `deleteIfEmpty`d.
- **Every chapter delete ends in `ChapterRemoval.finish`**: delete download queue rows, flush, then delete
  image folders **last** — a file delete cannot be rolled back, and after the flush only an I/O error at
  commit can still undo the rows. (Queue rows: see "The worker owns the staging folder".)
- **An unfiltered search gets no "Delete all matching"**, and the endpoint refuses one
  (`SearchCriteria.hasFilter`). `hasFilter` reads criteria **after `SearchCriteria.normalize()`**, which
  every search endpoint calls first and which drops values that match every row (null id, blank language,
  `minScore`/`minPages` < 1, every status at once). A blank title or a gallery id on a series search does
  not count. The specs, the count cache key and the native count use the same normalized criteria.
- **Review mode keeps no server state.** "Start review" stores the page's card links and the results URL in
  `sessionStorage` and opens the first with `?review=true`, which only renders the `reviewBar` fragment.
  The position is **read from the URL, never stored**, so it cannot drift. Actions post via `fetch` with
  `redirect: "manual"` and move on with `location.replace`, so Back returns to the results. The last item
  returns to the results URL. A **404** also moves on (the item was deleted elsewhere); any other failure
  reloads the item.
- **"Mark as reviewed" only promotes `NEW`**, so a stale tab cannot demote a favourite. It evicts
  `searchCount` only when the status really changed.

## Chapter matching (`service/match`)
Puts a chapter into a series automatically. **Two steps only**: the best existing series above the
threshold gets the chapter (with the chapter number its title implies), otherwise a new series is created
from it. So every chapter ends up in a series and families assemble themselves. Two entry points, same
code: `ChapterService.create` (in the service layer, so every way of adding a chapter gets it; toggled by
`matching.auto-link`) and Manage → **"Match chapters"** (`ChapterMatchingSweep`, unlinked chapters only).
Normalization, scoring and seeks are documented in `TitleKey`, `MatchScore`, `SeriesCandidateFinder`.
- **Matching uses `titleFull` on both entities** — the required, scraper-supplied title. The pretty `title`
  is user-editable, so a cosmetic rename would move a chapter to another family. **`nativeTitle` takes no
  part** — it is another script, not comparable with a Latin-script sibling. Search matches either title;
  matching needs one canonical name.
- **Family members relate by token PREFIX, not key equality** — a prefix is a B-tree range seek, a
  substring is not.
- **One score, one threshold** (`matching.threshold`, default 75). The artist veto is arithmetic, which is
  why the artist weight stays ≤ 0.4 and a threshold below ~71 starts merging unrelated series.
- **Candidates are seeked, never scanned**, and every seek is capped **and ordered**, so the survivors
  never depend on the query plan. `match_block` exists on **`series` only**; chapters are found by block
  through `ix_chapter__condensed_match_key`, an expression index on `replace(match_key, ' ', '')`. Don't
  add a chapter `match_block` column: every write would have to fill it, and it could only be capped by id.
- **Add to series uses the same component**, so manual and automatic agree. It adds a floor and **excludes
  the chapter's current series** (with auto-linking it would score ~1.00 for a no-op link). **The exclusion
  is part of every seek's predicate** (`SeriesCandidateFinder.rank(chapter, excludedSeriesId)`), never a
  filter on the ranking: as the exact seek's hit that series would switch off the fuzzy pass — the only one
  that finds the family of a misspelled title. Auto-linking excludes nothing and skips the fuzzy pass on an
  exact hit, which keeps the sweep cheap.
- **The series page's "Link chapters" is matching turned round** (`ChapterCandidateFinder`). `MatchScore`
  is symmetric, so a chapter gets the same score as on its Add-to-series page. **The block and artist
  seeks both always run**: here the chapter is what is searched for, so its artists cannot choose between
  them, and it is one page view, not a sweep. The seeks **select a projection**, since `Chapter.series` is
  an eager to-one and entities would cost a query each.
  - **The block seek walks outward from the series' title** on the spaceless-key index: chapters starting
    with its first 12 spaceless letters (`MatchScore.MIN_SHARED_HEAD`), shorter starts of it (by equality),
    and the nearest ones on each side, where misspellings sort. So a crowded block yields the nearest
    titles, not the first by id. These are **native SQL with `INDEXED BY`**: SQLite uses an expression
    index only for the exact declared expression, and otherwise prefers `ix_chapter__series_match_key`
    over every unlinked chapter.
  - **The scope (chapters in no series, the default, or all chapters) is part of every seek's predicate**
    (`ChapterSpecifications.linkableTo`), never a later filter — the series' own chapters would fill the
    cap. The name search uses the same scope.
  - **Linking goes through `SeriesService.addChapters`**. Moving out of another series recomputes it and
    deletes it if empty; `app.js` asks first, naming how many series would go.
- **The sweep's order is its algorithm.** Walking unlinked chapters by `(match_key, id)` makes a family
  arrive **together, base first**: the base creates the series, the siblings match it moments later — no
  clustering, no lookahead. A backfill pass first computes missing keys (imported rows).
  - **Deliberately unbounded and not a background job.** Each slice commits on its own, so the user can
    keep working in another tab. No per-run cap, no job/progress machinery.
  - The persistence context is cleared after every chapter (candidate lookup can load thousands of
    `Series`). `recomputeDerived` runs **once per touched series per slice**, not per chapter (that would
    be quadratic in family size). Since scoring reads effective artists, artists linked but not yet
    materialized are passed as `pendingArtists`; without them a fresh series looks artist-less and the
    artist veto does not fire.
- **Match keys are rewritten on every title write** (chapter create/update, series save). **Anything new
  that writes a title must do the same.**
- **An auto-created series is bare** — `status = NEW`, no overrides — so everything stays `DERIVED` and
  grows with its chapters. Matching ignores language.
- **Auto-linking defaults to `app.match-auto-link-default`**, so the tests can turn it off (see Tests).

## Chapter downloading (`scrapper/` + `service/download`)
Turns pasted links into chapters with images. **`scrapper/` knows how to fetch, `service/download`
decides what to keep** — a `DataDownloader` never touches the database, and the filesystem only in a folder
the pipeline hands it (gallery-dl's run folder, see "gallery-dl").
- **A new source is one class**, of one of two kinds: a **`PageDownloader`** fetches one page at a time from
  the addresses `GalleryData.pageUrls` lists (nhentai, chaika, mock), and the pipeline owns the loop, the
  retries and the staging; a **`GalleryDlDownloader`** has gallery-dl fetch many pages into a folder
  (hitomi, e-hentai) and gives `GalleryData.pageCount` instead. `ChapterDownloadService` switches on the kind.
  `DataDownloaderRegistry` asks every bean whether it `accepts(link)`; two sources sharing a prefix fail at
  startup.
- **Each source parses its own links** (`mock:<id>` is a folder, a real source is a URL). `accepts` and
  `resourceId` must agree, and **neither goes over the network**: a link whose id needs a request to find
  (an e-hentai page link, a chaika gallery link) is refused at enqueue, and the Download page says which
  link to paste.
- **A page's file extension comes from the source** (`PageDownloader.pageExtension`, the address's own by
  default): a chaika page address names the archive, not the page.
- **The chapter page links a gallery id to the source's website through `pageLinkTemplate`**, not `link`:
  `link` is what the pipeline fetches from (`mock:<id>` is no web page). A source without a website returns
  null. Only an id `linkFor` accepts gets a link, so a hand-edited id never points somewhere odd.
- **Gallery ids are namespaced by source** (`mock` + `12` → `mock:12`), so two sources can both count from
  1 under the unique `chapter.gallery_id`. Renaming a prefix orphans everything already downloaded.
- **Crash recovery is two pieces of state, no resume logic.** The **queue row** survives until the very
  last step, so a killed app re-runs the item from the top on next boot; every step recognizes what an
  earlier run did. The row has **no status column** — an "in progress" flag before a power cut would lie.
  Its only states are pending (`error is null`) and failed.
- **`chapter.download_status` decides what a re-run may touch** — a page count cannot tell "download cut
  short" from "user deleted a page". Only `PENDING` lets the pipeline back in, and it fetches **only
  absent page numbers**, never overwriting a file (except the full-quality re-download, see "Image
  compression"). `SUCCESSFUL` means the pages belong to the user — including a lenient retry that is
  missing pages on purpose — so re-queueing is a clean no-op. `NONE` never came from the pipeline and its
  images are never touched. Every skip path still calls `syncImageStats`. The status is **not** on
  `ChapterForm`: an edit must never promote a half-downloaded chapter.
  - **A chapter is `PENDING` before its first page is published**, also one filled from empty (`NONE`, or
    `SUCCESSFUL` with every page deleted). Otherwise a crash while publishing would leave it half-filled for
    good. The full-quality re-download is exempt: its queue row repeats the whole replacement.
- **Nothing becomes `SUCCESSFUL` without pages.** A source listing no pages is a **transient** failure (it
  may serve metadata before images), so the item ends in the Failed list with the chapter still
  `PENDING`, where the user can see and retry it.
- **The worker owns the staging folder of the item it runs.** Remove goes through `DownloadWorker.remove`,
  because only the worker knows what is in flight; deleting staging from a request thread would let it
  publish the remainder as `SUCCESSFUL`. For the in-flight item the folder is left alone, and
  `ChapterDownloadService` checks its row still exists **right before publishing**. The chapter is found
  by **gallery id**, never `download_queue.chapter_id` alone (written only by `recordFailure`).
  - **Deleting a chapter cancels its download** (`ChapterRemoval`). Otherwise the worker would publish into
    `data/<id>` of a deleted row, where the pages sit orphaned with nothing ever cleaning them up. The
    pre-publish check also confirms the **chapter** still exists.
- **Chapters are created through `ChapterService.create`**, like a hand-added one, so pretty title,
  canonical language, match key and auto-linking cannot drift. Metadata arrives as names;
  `MetadataService.resolveOrCreate` matches them case-insensitively and creates only what is new.
- **The pipeline is not `@Transactional`**: it spans minutes of network and disk work. Each DB touch is its
  own short transaction.
- **Pages are staged, not written straight into `data/`** — a crash would leave a half-written page that
  the next run (fetching only absent pages) takes as complete. Staging is discarded before every attempt.
  **The staging folder is chosen per run** (see "Temporary image files") and kept in `StagedRun`, because
  the automatic choice follows the compression mode and RAM disk free space; discards look in
  **every** folder staging could have used (`ScratchSpace.everyRoot`).
  - **Publishing is atomic per page**: an `ATOMIC_MOVE` on the same filesystem, otherwise copy to a `.part`
    beside the target, fsync, rename. A plain `Files.move` across filesystems copies onto the final name —
    the torn page this avoids. Don't simplify it.
- **"Avoid duplicated titles from other sources" is an opt-in, per-paste check**
  (`download_queue.avoid_duplicate_titles`). A gallery **new to the library** is refused when a chapter
  with **exactly** its `titleFull` comes from a **different** source (another prefix, or no gallery id).
  Same-source pairs are never compared (their ids already tell them apart). A gallery found by id is not
  checked, so a `PENDING` download can always finish. It runs **before** `importChapter`, which creates
  metadata rows. A refusal is **permanent** (Failed list, naming the clashing chapter). Re-pasting
  **replaces** the flag, Retry **keeps** it; a Failed row with the flag gets **"Retry allowing duplicate
  title"**, which clears it. Retry never turns the check on. The lookup is an equality seek on
  `ix_chapter__title_full_gallery_id`; the prefix is compared with `substring` (not `LIKE`), derived via
  `DataDownloader.galleryId("")`.
- **Failures**: `app.download.max-attempts` attempts with growing backoff, staying at the queue head.
  `PermanentDownloadException` / `GalleryNotFoundException` skip the remaining attempts. Successful rows
  are **deleted**; `downloaded_gallery` is the permanent record.
- **A page that fails is fetched again before the attempt fails** (`app.download.page-retries`, 5, every
  `page-retry-backoff-millis`, 1 s), in `ChapterDownloadService`, the same for every source. A source never
  retries on its own, except to sit out its site's 429. Without it one dropped connection would cost an
  attempt, and three of them a whole gallery. **An interrupt is told by the thread's flag**, not the
  exception's type (a socket timeout is an `InterruptedIOException` too): it is not retried, and lenient mode
  does not skip it as a missing page, since every page after it would fail the same way.
- **"Retry ignoring image errors" is persisted and assigned, not OR-ed.** Persisted because the retry runs
  later on the worker; assigned so a plain retry or re-paste returns to strict mode. Lenient mode is only
  ever an explicit user action, so a network blip never quietly produces a chapter missing pages. A
  skipped page **keeps its position** (a gap), so it can drop into its slot later.
  - **It skips a failed fetch, never a failed write.** Keep fetch and write in separate `try` blocks:
    together, a full staging disk would skip every page and still mark the chapter `SUCCESSFUL`.
- **One worker thread**, started on `ApplicationReadyEvent` — that start *is* the resume. Single-threaded
  because matching runs on create and SQLite has one writer; no job framework. `processNext()` is public
  as the test seam. Pause is a **persisted** setting (the user's choice survives a restart).
  - **Stopping waits 10 s for the item in flight and never interrupts it.** An item still running then dies
    with the JVM (a daemon thread) and runs again on the next start. **Once `stop()` has run, a failure is
    not recorded**: the pools and the database close under the item, and counting that failure could use up
    the link's last attempt.
- **Progress is `[done/total]` for the current batch** — successful rows are deleted, so no lifetime total.
- **The queue page's two lists are capped (200) with no pagination — don't add it.** Counts are exact;
  only rows are cut, and the page says so. The queue empties itself: the waiting list is in execution
  order, bulk actions cover **every** matching row, and paging a self-refreshing list makes no sense.
- Queueing redirects to the **queue**, the only page that shows the outcome. Unrecognized links are refused
  at enqueue. Re-pasting a failed link revives its row **by parsed `gallery_id`, not link text** (the
  registry accepts many spellings of one gallery, while `link` is UNIQUE under BINARY collation). The link
  is stored trimmed. The page refreshes every 3 s **only while there is something to watch**.

### Download all favourites (`service/download/FavouritesDownloadService`)
Queues every gallery in the user's favourites on a site that the library does not have yet. A source offers
it by also implementing **`FavouritesSource`**; the Download page lists those.
- **On the request thread, page by page**, behind a long-running button like the sweeps; no background job.
  Each listed page is queued in its own transaction and the worker kicked, so downloads start while the rest
  is listed, and a crash part-way loses only what pressing the button again redoes. Its writes wait like a
  sweep's (`WriteGate.background`). The time goes into the site's rate limit (nhentai: 15 pages a minute).
  **One listing at a time** (a `tryLock`): a second is refused, not queued.
- **The library is checked before anything is queued** (`GalleryImportService.holdings`). A chapter with the
  gallery id is left out unless it is `PENDING` (then queued, to finish it), and so is a gallery in
  `downloaded_gallery` whose chapter was deleted since: the user removed it, and a bulk action must not bring
  it back (pasting its link still does). Queueing everything would fetch every gallery's details again, at
  the API's rate, only to find its chapter there.
- **The same choices as a paste.** The button submits the paste form (`formaction`), so its Image Compression
  mode and duplicate-title check apply, and the rows go through `DownloadQueueService.enqueue` (a failed row
  is queued again, a waiting one counts as waiting).
- A refusal (no API key, no such source, already listing) returns to the Download page, since nothing was
  queued. Anything else goes to the queue page, saying what was listed, queued and left out, and where the
  listing stopped if a page failed; what was queued before that stays queued.

### gallery-dl (`scrapper/gallerydl`)
hitomi's pages and e-hentai's are fetched by gallery-dl (<https://codeberg.org/mikf/gallery-dl>; the GitHub
mirror no longer has these extractors and its releases carry no binaries). It knows each site's image
servers, fallbacks and limits, and follows the sites when they change, which a hand-written source would have
to repeat. **`GalleryDlTool` is the only class that starts it; `GalleryDl` is how a source uses it.**
- **Bundled in `bin/` (`win/gallery-dl.exe`, `linux/gallery-dl.bin`) or from `PATH`** (Settings, its own toggle,
  not the image tools'); `app.gallery-dl.command` replaces both (a Python module, the tests' fake). No macOS
  build exists, so macOS is `PATH` only. Settings shows the version (checked in the background, never waited
  for: a one-file executable takes seconds to start on Windows) and updates the bundled copy with its own
  `-U`; **an update and a run exclude each other** (a read/write lock; an update is refused while a download
  runs and waits for a version check, a run is refused while an update runs). The worker retries a download
  refused that way without counting an attempt, like a lock error.
- **Every run gets `--config-ignore`**: a user's gallery-dl configuration could set an archive that skips
  files, post-processors, other file names or credentials. Everything a run needs is on its command line,
  and every value there comes from a whitelist or a validated id — the URL is built by the source, never
  taken from the pasted text. **`-v`**, because it prints a line per HTTP request: the only sign of life during
  e-hentai's walk through the pages before the first wanted one, so the **idle timeout**
  (`app.gallery-dl.idle-timeout-seconds`) can be short and still never stop a working run.
- **Metadata is one `-j --range 1` run** (JSON on stdout: the Directory message, with `count`), never per page.
  `-j` always exits 0 and reports an error as `[-1, {error, message}]`.
- **Pages are one run** with `--range` for exactly the missing pages (`1-3,7`), into **a run folder inside the
  chapter's staging folder** (`-D`, `-f {num}.{extension}`). gallery-dl writes `.part` files and prints a
  file's path only once it is whole; each printed page is **moved** into staging (a rename) and handed to
  compression at once. The run folder is per run, so a gallery-dl left running by a killed app writes only
  where nothing reads; discarding staging removes it.
- **Failures are read from the exit status bits and gallery-dl's words** (`GalleryDl.classify`), the only
  things it reports, words first: a ban and a refused account share a bit. A failed page is no failure of the
  run (gallery-dl goes on); the pipeline sees it as missing. An error the extractor logs ends the run there, so it
  fails the attempt, never skips pages; so does a status without an error line (a kill). **Exit bit 128 (a failed write) is never a page to
  skip**, in lenient mode either. Permanent kinds (not found, refused, ban, image limit, no GP, unsupported, not
  runnable — including Windows' missing Visual C++ runtime) fail the item at once.
- **Nothing it starts outlives its run.** A one-file executable is a bootloader with the real program as its
  child, so a stop kills the **children first** (the bootloader then exits and removes what it unpacked).
  Its temp folder is the app's own (`<data>/.gallery-dl`, via `TMP`/`TMPDIR`); each run records pid + start
  time there, and the next start kills a recorded run **only if the start time matches**, then empties the
  folder. A graceful stop kills running runs.
- **The Download page's choices** (`GalleryDlOptions`): cookies (`--cookies-from-browser <browser>`), originals
  and the delay, on the queue row like the compression mode (re-paste replaces, retry keeps; a full-quality
  re-download takes Settings' defaults). The form starts at Settings' defaults every visit; an unreadable delay
  is shown on the form with the links kept, never replaced. **The delay maps per source**: e-hentai gets
  `--sleep-request` (each image needs one page/API request there, which its ban counts), hitomi `--sleep`
  (its extractor makes one request per gallery, so a request delay would never fall between images).

### The e-hentai source (`scrapper/ehentai`)
e-hentai.org and exhentai.org are **one source**: a gallery has the same gid and token on both, so a link from
either is `ehentai:<gid>/<token>`. The token is in the id because nothing can be fetched without it.
- **Metadata from the JSON API** (`EhentaiApi`, `gdata` with `namespace=1`), one gallery a request, paced at
  1.25 s (the API's documentation: "4-5 sequential requests usually okay before having to wait for ~5
  seconds"; evenly spaced, never more than 4 in any 5 s). No cookies: metadata needs none. Titles carry HTML
  entities. **A gallery without a `language:` tag is Japanese** (e-hentai's convention, `EhTags`, shared with
  chaika); `cosplayer:` counts as an artist, `reclass:` is dropped, and tag namespaces are left for
  `MetadataService.canonical`.
- **Pages through gallery-dl, on the domain the cookies decide**, not the pasted one: with cookies
  exhentai.org (it also has what e-hentai hides), falling back **once** to e-hentai.org when exhentai refuses
  the account; without them e-hentai.org. `link()` is always e-hentai.org.
- **A ban or a used-up image limit starts a cooldown** (`app.download.ehentai.cooldown-minutes`, in memory):
  every e-hentai item then fails at once, saying until when, without asking the API or starting gallery-dl —
  requests during an IP ban can extend it. Other sources keep running.
- Without the Multi-Page Viewer gallery-dl walks image pages from 1 even under `--range`, one paced API request
  per page before the first wanted one (no image download). Partial runs are rare (a `PENDING` resume), so it
  is accepted.

### The hitomi source (`scrapper/hitomi`)
Entirely through gallery-dl: hitomi computes image addresses from a script that changes every few hours.
`link()` is `/galleries/<id>.html`. **hitomi serves no originals** (webp/avif only, `-o format=webp`), so
"Download originals" means nothing here; no login, so **no cookies are read** (reading a browser's store can
fail, for nothing). Its type maps to the categories the other sources use (`artistcg` → `artist cg`); an
`anime` gallery is a video and refused. gallery-dl already formats its tags (`Big Breasts ♀`). **A gallery
without a language is Japanese**, as on e-hentai (game CGs and image sets often have none; the import would refuse
them for good).

### The chaika source (`scrapper/chaika`)
panda.chaika.moe archives e-hentai galleries as zip files; its metadata is e-hentai's (`EhTags`, after
putting back the spaces it writes as `_`).
- **Pages are read straight out of the remote zip with byte ranges** (`ZipIndex`): the tail for the end
  record, the central directory, then one range per page. So a partial download fetches only what it misses,
  never the whole archive (often hundreds of MB), and every page goes through the per-page pipeline. **A
  server that answers a range with `200` is refused** and its answer dropped unread (a transient failure), or
  every page would download the archive.
- **Sizes, offsets and CRCs come from the central directory**, never local headers (zero when written as a
  stream); every page is checked against its CRC and size. ZIP64 is read; encrypted entries and methods
  other than stored/deflate are refused. Pages are the image entries in **natural order** (`9.jpg` before
  `10.jpg`): chaika's names have gaps and come in no order.
- **A page address carries where the page is** (`#n=…&o=…&c=…&u=…&m=…&crc=…`, a fragment, never sent), so
  fetching a page needs nothing from reading the index; `pageExtension` reads the name from it.

### The nhentai source (`scrapper/nhentai`)
nhentai's API v2 (`https://nhentai.net/api/v2/docs`, changes at `/api/v2/changelog`). `NhentaiApi` is the
only class that speaks HTTP to it; `NhentaiDownloader` turns the answers into `GalleryData`.
- **Hand-written on `java.net.http` and Jackson**, not a client generated from nhentai's OpenAPI file: three
  endpoints (gallery, favourites, image servers) do not justify the generator, its dependencies and the
  `jre.modules` they would need.
- **Links**: `nhentai.net/g/<id>` with any scheme, subdomain, case or tail (a page, a query), and
  `nhentai:<id>`, the form the Download page names. Leading zeros are dropped, so one gallery has one gallery
  id. `link()` is always `https://nhentai.net/g/<id>/`, whatever `base-url` says, so it parses back.
- **Only page images, by the path nhentai gives.** A gallery also lists a cover, a thumbnail and a thumbnail
  per page; none is fetched. A path is relative and goes on a server from `GET /api/v2/cdn` (one at random
  per gallery; the list is cached an hour, keyed by `base-url`, and kept when a refresh fails). nhentai asks
  for exactly this and bans clients that request paths it never gave, so **never build a path** (another
  extension, a guessed number). A page without a path fails the gallery, since pages are numbered by
  position. An empty or text/JSON answer from an image server is a failure, not a page.
- **Rate limits are nhentai's published ones, per endpoint** (`NhentaiProperties`): gallery details 20 a
  minute without a key and 45 with one, favourites 15, images 250 ms apart (no number is published, only bans
  for rates "well beyond normal browsing"). `RequestPacer` spaces requests evenly, which keeps within a limit
  however the site counts its minute; the interval is picked per call, by whether a key is sent.
  - **A 429 is sat out, not failed**: as long as `Retry-After` (or a rate-limit reset) says, else a minute;
    at most 15 minutes a wait and 5 waits a request. It holds the endpoint's pacer back for **every** caller.
    The limits count per IP, so the user browsing nhentai meanwhile shares them, and a 429 is normal.
- **The API key** (Settings, `apikey.nhentai`) is read per call. It goes to the API only (`Authorization: Key
  …`), **never to the image servers**, whose addresses come from nhentai's answers; for the same reason the
  API client follows no redirect. A 401 is worded to send the user to Settings.
- **nhentai's data quirks are handled here**, where the data is read. Artists joined as `"a | b"` are split,
  only at a pipe with whitespace on both sides and only when every part is a name, so `"|||naka|||"` stays
  whole. "translated", "rewrite" and the like are filed as languages, so the language is the first tag
  `LanguageService` knows; without one (a "speechless" gallery) the first tag is kept, and the import's
  refusal names it.
- **Page by page, against nhentai's advice.** nhentai offers whole-gallery archives
  (`POST /api/v2/galleries/{id}/download`, a few per 5 minutes) and asks apps not to rebuild galleries from
  image-server pages. The pipeline works per page (absent pages only, lenient gaps, full-quality
  replacement), so this source does too, at a reader's pace. If nhentai starts enforcing it, the archive
  endpoint is the way out.

## Image compression (`service/compress`, `bin/`)
Downscaling with **ImageMagick** and re-encoding to **JPEG XL** (`cjxl`) or **AVIF** (`avifenc`), for
downloaded and uploaded images. Default mode is **None**: bytes are stored as they arrived.
- **`service/compress` knows how to run the tools; everything else decides when.** `ImageCompressor` does
  one file, `ImageCompressionService` many, `ImageToolLocator`/`ImageToolRunner` are the only code that
  starts a process. `ImagePostProcessor` is the download package's interface to it.
- **A mode is referred to by a key**, never a name or position: built-ins by enum constant (`LOSSLESS`),
  user modes as `custom:<id>`. So a stored choice survives a rename and a new built-in cannot repoint
  queued downloads. **A key that no longer resolves means None**, so deleting a mode in use is allowed.
  - **What may be stored is a whitelist** (`ImageCompressionModeService.storableKey`, applied in
    `DownloadQueueService.enqueue` and `SettingsController`): the `CUSTOM` dropdown sentinel is blocked
    only by JS, and SQLite does not enforce `VARCHAR(n)`. Custom keys are canonicalized on the way in.
- **`chapter.compression_mode` is the key of the mode that last re-encoded the chapter's pages; null while
  the pages are original.** It drives the "Compressed: <mode>" label and the full-quality re-download
  offer. It is written **only when a run replaced a page** (`Summary.replaced() > 0`), by all four paths
  (download, upload, per-chapter button, sweep); **anything new that re-encodes pages must record it**
  (`ChapterService.setCompressionMode`). A download also counts pages an earlier in-process attempt
  compressed (in `StagedRun`). A deleted custom mode reads "a mode since deleted". Not on `ChapterForm`.
  - **Never written late.** Nothing rebuilds it from the files, and a rerun finds no input left in an
    encoded page, so a crash between the files and the label shows compressed pages as full quality for
    good. A download writes it before publishing, the sweep after each chapter (not per slice). A label
    ahead of its pages is harmless: a full-quality re-download clears it.
- **"Re-download in full quality" is a queue row with `replace_pages`**, offered only for a compressed
  chapter whose gallery id names a known source. `DataDownloaderRegistry.linkFor` rebuilds the link via
  `DataDownloader.link` and returns it only if it parses back to the same gallery id.
  - **Only pages the chapter has, by canonical name** (`ImageDirectory.canonicalPageNumbers`: `3.jpg`, not
    `03.jpg`), so a deleted page stays deleted and no page ends up twice. A `PENDING` or empty chapter also
    gets its absent pages.
  - **Always uncompressed and strict**; the gallery's existing row is re-used. Retry keeps the flag; a
    paste clears it.
  - **Nothing is replaced before every original has arrived**, and **the new page goes in before the old
    comes out** (`ImageService.publishReplacingPages`) — a crash leaves both, which the listing counts once
    and the still-queued row fixes. An old version that cannot be deleted fails the item.
  - **It never creates a chapter** (no chapter for the gallery → permanent failure).
  - It clears `compression_mode` only if no page was skipped **and no `.jxl`/`.avif` survives** the
    publish. The folder is read under the run lock; a failure to clear is logged, not a failed item.
  - **Its publish takes the run lock** (with clearing the mode), and is refused as a transient failure
    while a run holds it — checked before fetching too, since a sweep holds it for hours.
- **The three built-in modes are not rows.** They always exist, are not editable, and live in
  `image-compression-modes.properties` — **a separate file because the test profile replaces
  `application.properties`**; `ImageCompressionModeIT` checks the shipped values. "Custom" in the dropdown
  is **not a mode** (it opens the form); its label is reserved like the built-in names.
- **One user-started run at a time** (`ImageCompressionService.exclusively`, non-blocking `tryLock`).
  Upload, per-chapter button and sweep take the lock; a second one is **refused, never queued** (the pool
  is already full, and a sweep holds it for hours). The sweep holds it for the whole walk. A refused
  **upload still saves the images** uncompressed and says so. **Downloads don't take the lock**: they
  compress staged files only, and every intermediate has its own name. (Exception: the full-quality
  re-download's publish.)
- **Uploads use the Settings mode; downloads carry theirs on the queue row** (it runs later and must
  survive a restart). The download form **starts at the Settings mode**, never at the last paste's choice,
  via `storableKey` (a deleted mode starts at None). Re-pasting **replaces** the row's mode. An in-process
  retry reuses staged pages **only under the same mode** (`StagedRun`), or a chapter would be half in each.
- **Any failure keeps the original image and logs it** (missing binary, codec error, timeout, unknown
  mode). A download never fails because of a codec, and a page is never lost to one.
- **The timeout works only because tool output is drained on its own thread** (`ImageToolRunner`). Reading
  to EOF before `waitFor` would block until the tool exits and disable the timeout for exactly the tool
  that hangs. Don't move the read back.
- **Running a mode twice re-encodes again — on purpose.** There is no "already compressed" marker; a mode's
  format list decides its inputs. The downscaling built-ins list only `JPG,PNG,WEBP`, and `Lossless` lists
  `JPG,PNG`, so repeating them is a no-op.
- **Order per file is fixed** (`ImageCompressor`): format filter → ImageMagick (only with arguments) →
  encoder → reduction thresholds. **ImageMagick always writes PNG**, and inputs the encoder cannot read
  (WebP for both; GIF for AVIF) take a **lossless PNG detour**. Nothing lossy in the middle. The ImageMagick
  input gets `[0]` so an animated GIF yields one PNG.
- **A result that is not smaller is always rejected**, whatever the thresholds. Both at 0 means "no
  particular saving required"; above that each applies only when set.
- **The result is checked against the format's signature** (`ImageEncoder.producedValidFile`) *before* the
  size check — an empty file is the "smallest" result and would replace the page. It catches missing or
  broken headers, not a file truncated at the end (that needs a full decode).
- **The replacement keeps the base name with the encoder's extension** (`3.png` → `3.jxl`), then deletes
  other images with **that base name** only — never `03.png` (a different page) or `3.json`. An interrupted
  run leaves both, so **`ImageDirectory.list`/`stats` skip a file superseded by an encoder output with the
  same base name**. `jxl` is in `IMAGE_EXTENSIONS` so the app's own output is listed.
- **Intermediates go in the compression work folder** (`ScratchSpace.compressionWork()`, `<root>/<chapterId>`)
  for every kind of run. A run resolves the folder once and removes its `<id>` folder (and the root)
  **only if empty** afterwards.
  - **Every intermediate is named per call** (`<base>.<uuid>…`). The folder is per chapter, so a download
    and a sweep on the same page would otherwise share a file and delete or tear each other's output —
    and the run lock does not cover downloads.
  - **It is never inside the staging folder.** The "remove if empty" cleanup could otherwise delete a
    staging folder that `stagePage` has just created but not yet written into. `ScratchSpace` refuses two
    areas sharing a root.
  - **`discardStagedPages` leaves the work folder alone** (a sweep may be using it); `deleteAll` removes
    it. Leftovers from a killed process are cleared at startup (`ScratchSpace.removeLeftovers`).
- **Downloads hand over each page as it lands**: `ImagePostProcessor.Run` wraps the fetch loop and
  `close()` waits for the rest. It must close on **every** exit (try-with-resources), or an encoder could
  still be writing while staging is discarded or published.
- **Compression never runs inside a transaction** (seconds to minutes per page, one SQLite writer).
  `ChapterImageService` saves, compresses, then calls `ChapterService.rescanImages` on another bean so its
  `@Transactional` applies. The sweep commits per slice.
- **An upload compresses only the files just uploaded** — re-encoding existing pages would lose quality
  each time. The per-chapter "Compress images" button and the library sweep redo existing pages.
- **Binaries are resolved per platform**, bundled (`bin/win/…`, `bin/linux/…`, `app.image-compression.bin-dir`)
  or from `PATH` (a Settings option). The layouts differ (ImageMagick is a folder on Windows, one AppImage
  on Linux) — that is what `ImageToolLocator` is for. Nothing is cached, so a changed setting or a newly
  added binary works without restart. A missing `PATH` tool surfaces as a failed process start, which
  keeps the original.
- **`cjxl` is `INPUT OUTPUT [OPTIONS]`, `avifenc` is `[OPTIONS] INPUT OUTPUT`** — so `ImageEncoder` builds
  the command line, not the caller.
- **Its own thread pool** (`ImageCompressionConfig`), sized to the machine: long CPU-bound tasks must not
  take threads from anything latency-sensitive.

### Serving JPEG XL to a browser that cannot show it
- **The URL never changes** (`/data/{chapter}/{page}.jxl`); `JxlTranscodeInterceptor` decides per client
  which bytes to send. Rewriting URLs would spread browser-support logic everywhere and break shared links.
- **An interceptor, not a filter**, so it runs after Spring Security — no decoding for unauthenticated
  requests.
- **Support is detected by a cookie, and an unknown client is assumed unable.** `app.js` decodes a tiny JPEG
  XL and records the answer; until then the server sends PNG, which always renders. A Settings dropdown
  (Automatic / Always / Never) overrides it.
- **Decoded copies are cached — required, not an optimization**: a results page asks for up to 36
  thumbnails at once. The cache is `ScratchSpace.transcodeCache()`, pruned oldest-first past
  `transcodeCacheCapBytes()`, and evicted per chapter/page on delete or re-compression. **Housekeeping only
  deletes files shaped like its own entries** at `<root>/<digits>/`, and a chapter folder only once empty,
  because the root sits inside a folder the user chose.
- **Every `.jxl` response carries `Vary: Cookie` + `private`**, decoded or passed through, since one URL has
  two bodies. `Vary` comes from the interceptor; `private` **must** be on the `/data/**` resource
  registration, because the resource handler overwrites `Cache-Control` set by an interceptor. The
  lifetime is **1 hour** (`JxlTranscodeInterceptor.CACHE_TTL`), short because the Settings dropdown changes
  the answer without any request header changing.
  - **An entry is named after the exact source version** — `3.jxl.<mtime>-<size>.png` — and valid if it
    exists; never "newer than the page" (a copied file can keep an older timestamp). Older versions are
    deleted once the current one is cached.
  - **The decoded variant uses an `ETag` (the entry name) and no `Last-Modified`.** A `Last-Modified` would
    be the decode time, and after switching delivery to Never the resource handler would answer the PNG's
    revalidation with 304 forever. Each variant carries a validator only its own path honours. 304s and
    `HEAD` are answered **without decoding** (`JxlTranscoder.cacheEntry`).
  - **Decodes are bounded and de-duplicated**: one per processor (a semaphore, not the compression pool),
    a request for a version being decoded waits for it, and each decode writes to its own temporary name
    (a shared name let concurrent requests tear the file). Prune skips temporary files younger than any
    tool may run.
- **No decoder is not a failed request**: the `.jxl` is served and the log says why.

### Reader prefetching
The viewer requests the page being read first, **alone**, then **the next pages** (`viewer.pages-ahead`,
default 6) **with the previous page after the first four** (after all of them when fewer) — **one request
in flight**, and the plan is dropped whenever the reader moves. With a ComfyUI workflow a page can take
seconds or minutes of server work, so a queue from an earlier position would sit in front of the page the
user jumped to. A token invalidates the old chain; a stored image in flight finishes, a processed page's
request is **aborted**. **Don't turn this into a queue.** The server applies the same rule.
- **The order is the likelihood of being wanted**: reading on is likeliest, going back one page next.
- **0 prepares only the page on screen**, not even the previous one — for a slow ComfyUI.
- **One setting for every kind of preparing** (JPEG XL decode, ComfyUI run), because it is one plan. The
  plan is sent with every processed-page request, hence the cap of 100.

## Temporary image files (`service/scratch`)
Download staging, compression intermediates, the JPEG XL decode cache and ComfyUI results each have **their
own folder** (`ScratchArea`), resolved by `ScratchSpace`: the Settings value, else automatic. There is **no
property for the folders** — a property fallback would make a cleared field mean "whatever the properties
say", and a bad value there would block every Settings save. `app.storage.*` only steers the automatic
choice. They are separate because they differ: staging only benefits from RAM for a compressed download,
intermediates always do, and the two caches live for weeks and need **their own size cap** each (a quarter
of an automatic RAM disk each), so one cannot prune the other away.
- **Automatic = a RAM disk if usable, else a dot-named folder in the data folder** — except **uncompressed
  staging always stays in the data folder**, so publishing is a free atomic rename
  (`ImagePostProcessor.compresses(mode)` decides).
- **RAM disk detection is Linux-only** (`RamDiskDetector`): only there is it standard, identifiable
  (`tmpfs`) and size-limited. Windows/macOS RAM disks look like NTFS/APFS; the user sets folders by hand.
  `ramfs` is rejected (no limit). Candidates: `/dev/shm`, then `/tmp` (never aged out by
  `systemd-tmpfiles`). Detected once at startup.
  - **The app writes only into its own `hentie-<user>-<hash of data folder>` folder**
    (`ScratchSpace.libraryFolderName`), created `0700`; an existing one is accepted only if it is a real
    directory, **owned by this user** and owner-only — the mounts are world-writable, and root can write
    into another user's `0700` folder. An unresolvable `user.name` disables the RAM disk. Per data folder
    because chapter ids are unique only within one library.
  - **The check runs on every use** (`RamDisk.ensurePrivate`), since `/tmp` can be cleaned while the app
    runs. A missing folder is recreated owner-only; one that cannot be made private sends every area to
    the data folder. It is **one `lstat`**, because every thumbnail asks.
- **Memory is bounded three ways.** (1) A free-space floor (`app.storage.ram-disk-min-free-mb`) checked at
  startup **and at the start of each download/compression run**; below it the run uses the data folder.
  (2) Each cache on an *automatic* RAM disk is capped at a quarter of the mount, whatever its setting says;
  cache location does **not** follow free space (moving it would orphan it). An explicit folder is taken
  at its word. (3) Leftover numbered folders in staging/work roots are deleted at startup
  (`removeLeftovers`) — safe because the worker starts on `ApplicationReadyEvent`. Moving a cache in
  Settings clears its old folder.
- **A folder chosen in Settings is a parent, never the area's root.** The area lives at
  `<chosen>/hentie-<user>-<hash>/<staging|work|transcoded|comfyui>`. Cleanup deletes `<root>/<chapterId>`
  recursively, so rooting at the chosen folder would reach the user's own `D:\2024`, a plain `D:\staging`
  could adopt an existing folder, and without the hash two libraries would clear each other. **The app
  deletes only inside folders it named itself.** Moving the data folder orphans the old folder — the safe
  direction.
- **No delete follows a link** (`ImageService.deleteRecursively`, used by every recursive cleanup). A
  symlink is removed as a link. A **Windows junction** looks like a directory to the JDK, so it is detected
  by its real path differing from its location (the `isOther` flag alone also marks OneDrive folders).
  Don't go back to `Files.walk`.
- **Explicit folders are validated as a set; overlaps are refused**: none may equal the data folder, sit in
  a chapter folder, or overlap another area's root — **including its automatic roots and explicit roots it
  had earlier in this process** (`everyRoot`). **Paths are compared as written and as resolved**
  (`toRealPath`), since symlinks, junctions, `subst` and 8.3 names give one folder several spellings. A
  folder must be creatable and writable; it is created only after the overlap check passes, and a refused
  save removes the empty folders it created. A refused save changes none of the four folders but saves the
  rest of the form, without "Settings saved.". `ScratchSpace.update` is `synchronized`. A bad value at
  **startup** is logged and ignored, so the user can still reach Settings to fix it. Nesting under a
  non-numeric name is harmless.
- A run keeps the folder it started with. **A replaced explicit root stays in `everyRoot` for the rest of
  the process**, so discards still reach pages staged there.

## ComfyUI processing (`service/comfy`, `COMFYUI.md`)
The viewer can show pages through the user's own ComfyUI workflows. **Viewer-only: nothing in `data/` is
ever written** — a result is a cached derivative. `COMFYUI.md` is the user guide. **`ComfyUiClient` is the
only class that speaks ComfyUI's protocol; `PageProcessingService` decides what runs when**;
`WorkflowCatalog` lists and checks workflows, `ComfyResultCache` keeps results, `ComfyUiLauncher` owns the
process.
- **Nothing assumes where or how ComfyUI is installed.** Workflows are read through ComfyUI's `/userdata`
  API from a folder of its user directory (default `api_workflows`). The address is a Settings value (blank
  = `app.comfyui.default-url`, which must be valid or startup fails) and may include a path (reverse proxy),
  so request URLs are appended, never resolved. The start script is the user's own, run in its own folder;
  the app never builds a ComfyUI command line.
- **Workflow contract** (`ApiWorkflow`): an *Export (API)* file; Input = node titled "Input", else the only
  Load Image; Output = node titled "Output", else the only Save/Preview Image. Everything else runs as
  exported, **seeds included — which is what makes caching correct**. Only the output's branch runs
  (`partial_execution_targets`). A workflow's **version is the SHA-256 of its file**.
- **Each workflow file is read once per (size, mtime)**; the listing is trusted for 5 s and **kept when
  ComfyUI stops answering**, so cached results are still served. Cache lookups use the (TTL-bound) listing
  so a re-exported workflow never serves old results.
  - **A listing older than 5 s still answers while a background refresh runs** (`WorkflowCatalog.listing`)
    — with ComfyUI down each refresh costs ~2 s on Windows. Only the first listing, the first after
    `invalidate()`, and the one a job runs from (`currentListing`) are waited for.
- **Scheduling mirrors the reader's prefetch rule.** One dispatcher hands ComfyUI one prompt at a time: the
  page being *read* before any *prefetch*, the latest request first, and a job nobody asked about for
  `app.comfyui.abandon-after-millis` is dropped. **Never hand ComfyUI a queue.**
- **A viewer wants only what its latest request asks for** (`PageProcessingService.Asker`). Each viewer
  sends an id and a sequence (`viewer`/`seq`); a request replaces that viewer's earlier interest, a lower
  `seq` changes nothing, and an answer (page or failure) ends the interest in what may *start*. The server
  cannot see a viewer give up, so without this every page turned past would stay wanted and run first.
  Requests without a viewer count per page and are never replaced (tests and scripts rely on this).
  Repeating a request only keeps it wanted.
- **The running page is stopped as soon as nobody wants it** (`PageProcessingService.stopUnwanted`): removed
  from ComfyUI's queue and interrupted, by prompt id. Nobody wants it once every asker has asked for another
  page that needs processing, or left (**`POST /comfyui/leave`**, a beacon on `pagehide` and on picking
  None). Two things do **not** count as moving on:
  - **a cache hit for a page in the asker's `plan`.** Every request carries the plan (the page on screen
    plus what comes next); without it, collecting ready pages would stop and restart the page prefetching
    is for. A plan that no longer includes the running page stops it.
  - **silence.** A sleeping tab still wants its page; silence only keeps pages from *starting*.
  Each job runs on **its own thread**, because stopping is `Thread.interrupt()` and must never reach the
  next job; the dispatcher takes the next job only after that thread ends. **Once the result is in, the job
  is committed and never stopped** (`RunListener.finished`), and any earlier interrupt is cleared — an
  interrupt during the write would fail storing a finished page.
  - **A job's future is completed under the lock, together with `running = false`**, or a dispatcher could
    start the failed job again.
  - **`/interrupt` is sent only when `/queue` lists the prompt as running.** Older ComfyUI versions ignore
    the prompt id and would stop the user's own generation. The prompt is deleted from the queue first, so
    it cannot start after the check.
- **Every processed-page request the reader moved on from is aborted** (`AbortController`), all of them on
  `beforeunload`. A browser has six connections per server; a few page turns with open 15 s polls would
  fill them and block the page on screen.
- **"Show this page as stored" (`s`) is a peek, not a mode**: it swaps the image only, ends on page turn, and
  processing continues. Keys with Ctrl/Alt/Meta are left to the browser.
- **Requests long-poll, never wait for the whole run.** `/comfyui/pages/…` waits at most 20 s, then answers
  202 with progress; the viewer asks again (which also keeps the job wanted). So processed pages are
  *fetched* into object URLs — an `<img>` cannot handle 202, progress or errors. The stored page shows
  meanwhile; only the next page is kept in memory, further pages are warmed server-side (`warm` → 204).
  **The page on screen polls every second** (for the progress line); prefetches let the server hold the
  request. **The first request is answered at once and the second sent straight after**, so "Queued" is
  corrected within a second instead of leaving no line for a second. A failure is kept 10 s for every
  poller, then forgotten so a new request retries.
- **The percent is based on time once the workflow has run before**: node step counts are uneven (upscaler
  tiles). The estimate is elapsed time vs the median and 75th percentile of recent runs (last 5, in memory;
  runs under 1 s are ComfyUI's cache and ignored), shown as "~3-4 s left". Node progress is the fallback
  (first run, or slower than any recent run: "taking longer than usual").
  - **Earlier runs are scaled to the page's pixels** (`Progress.Estimate.of`, `sizeExponent`): time ~
    pixels^k, with k learnt as the **median log-log slope over pairs of runs at least 1.5x apart in
    pixels**, clamped to 0–2 (a median, so one slow run does not skew it). Default k = 1 (right for
    upscalers).
  - **Time comes from the latest 5 runs, k from the last 30**, kept **per workflow version**. The size is
    read from the image header (`ImageSize`, by hand since ImageIO reads neither WebP nor AVIF); an
    unreadable size leaves that run unscaled and out of k.
- **Disk writes are minimized** — the user's explicit requirement. Uploads are named by content, so ComfyUI
  writes a page once for all workflows. The output node becomes `SaveImageWebsocket` when installed
  (checked via `/object_info`, trusted 30 s), else `PreviewImage` (temp folder, never `output/`). Results go
  to the `COMFYUI_RESULTS` scratch area and are sent **`no-store`** so the browser keeps no disk copy. A
  JPEG XL page is sent as the transcode cache's PNG.
- **The result cache key is exactly what a result was made from**: `<page>.<mtime>-<size>.<name
  hash>-<version hash>.png`, valid if it exists. Both caches share `scratch/CacheFolder` housekeeping and
  implement `PageDerivedCache`; every path that deletes or renames pages evicts through
  `ImageService.evictDerived`, so **a new page-derived cache implements the interface**.
- **The launcher starts ComfyUI at startup when enabled, never a second one, and stops only what it
  started.** Skipped when something already answers. A PowerShell script runs through PowerShell,
  anything else directly (`CreateProcess` handles `.bat` quoting correctly, `cmd /c` does not); stdin is
  closed so a trailing `pause` returns; `PYTHONUNBUFFERED` for live output. **Output is always drained**
  (an undrained pipe blocks the child) into the last 200 lines shown on Settings, colour codes stripped —
  the one place a failed start can explain itself, since the app's log carries ComfyUI's output only at
  DEBUG. Stopping kills the **process tree**, listed before killing. The launch is recorded as **pid +
  start time**, and the next start stops a leftover **only if the start time matches** (pids are reused).
  That and autostart run on their own thread so they do not delay other startup listeners.
  - **A start script exiting with 0 while starting is not ComfyUI exiting** — it may start ComfyUI in the
    background. If the address answers → `DETACHED` (nothing to stop it with); no answer by the startup
    timeout → `EXITED`. Until then the state is `STARTING`, so viewers wait and Start does not launch a
    second one.
  - **Nothing the app writes may become the start script** (`ComfyUiLauncher.problemWith`, on save and on
    every start): with login off, anyone on the LAN can upload a "page". A script in the data folder or a
    scratch root (`ScratchSpace.isInAppFolder`, compared as written and resolved) is refused; outside
    Windows the script needs its executable bit (no app-written file has one) and runs directly, never via
    `/bin/sh`.
- **A refused connection costs ~2 s on Windows**, so `ComfyUiClient` remembers a refusal for 3 s; the
  launcher's readiness probe bypasses that.
- **The websocket is closed with the handshake, not aborted** — aborting makes ComfyUI on Windows log a
  `ConnectionResetError` per page into the console shown in Settings.
- **ComfyUI Settings fields are refused one by one**: a bad address, folder, workflow or missing script
  keeps its old value with a reason; the rest are saved, without "Settings saved.".

## Score & status
- **Score is an integer 1–10 end to end** (DB `Short`, form/search `Integer`); the backend only narrows and
  clamps, never scales. The 0.5–5 half-star UI is presentation only: `<select>` values are 1–10 and views
  divide by 2. "Minimum rating" is `score >= minScore`.
- `Status.REVIEWED_FAVOURITE` displays as **"Favourite"**.
- **`status` is required on `Series`** (no "None" in the UI, `@NotNull` on the form). The new-series form
  preselects `REVIEWED`; auto-created series get `NEW`.

## Writes and SQLite's lock (`config/WriteGate`)
SQLite has one writer. **No user action may end in a 500 because the write lock was taken**, whoever holds it
(the worker, a sweep, a bulk delete, ANALYZE, a `sqlite3` session): a short wait is invisible, a long one is a
clear "busy, nothing was changed" answer, and background writers wait instead of failing.
- **Every write transaction first takes its turn at the write gate**, a fair lock in the app. Two app writers
  must never meet inside SQLite, where they fail rather than wait: a transaction that reads before it writes
  (every Hibernate one) gets `SQLITE_BUSY` at once, or `SQLITE_BUSY_SNAPSHOT` if another writer committed after
  its first read, and `busy_timeout` helps neither. **Fair**, because SQLite's busy handler polls, so a loop of
  short commits would take the lock back before a waiting request woke up. With the gate a request waits for
  at most one unit of a background loop.
- **Then SQLite's lock, taken by the transaction's first statement**, a write that changes nothing
  (`WriteGateDialect`, the transaction manager's JPA dialect, `TransactionConfig`). `busy_timeout` does help a
  statement that writes first, so the one statement that can meet a writer outside the app is this one, before
  any work; nothing later in the transaction can fail with a lock error. The gate comes before the pooled
  connection, so a waiting writer holds none. Read-only transactions skip both: WAL readers never wait. Not
  xerial's `transaction_mode=IMMEDIATE` (it begins the next transaction right after every commit, so each
  commit would take the lock again, read-only ones too) nor its `explicit_readonly` (upgrades only at the first
  statement, after any file work before it, and leaves `query_only` set on the pooled connection).
- **How long a write waits depends on its thread**; `RequestWritesFilter` marks request threads:
  - **A request's first write waits `app.writes.request-wait-millis`** (10 s), then fails with
    `LibraryBusyException` before anything changed. 10 s is longer than any slice of a loop and shorter than the
    few single transactions that hold the lock longer (below).
  - **After its first turn, a request waits as long as it must**, since it may have changed something: a
    request never stops half-way for waiting. **An action that changes something before its first write
    transaction calls `WriteGate.claimTurn()` first**, so a busy library refuses it while nothing has changed:
    uploads and per-chapter compression (`ChapterImageService`), dividing, the Settings save (password file).
    **Anything new of that kind must claim too**, unless its write can leave the request: ComfyUI start/stop
    writes its launch record on a thread of its own, so a busy library neither refuses nor delays it.
  - **`WriteGate.background(activity, …)` waits as long as it must**: the sweeps, the bulk deletes, merge,
    remove, the title index rebuild, deleting a series with its chapters. They run on request threads, but the
    user started them and watches a spinner. `activity` names them in busy messages and the log.
  - **A thread that serves no request always waits as long as it must, unasked**: the download worker, and
    anything scheduled (future Subscriptions/Watchers), need nothing. **Except during shutdown**, which another
    program holding the lock must not stall for good: a write there goes through `WriteGate.withinRequestBudget`
    (ComfyUI's launch record).
  - **`WriteGate.ifFree(…)` does not wait at all**, not even behind a queued writer: for repairs a GET may skip.
- **The rule for background writers** (worker, sweeps, subscriptions): write in **short transactions** (a slice
  of a second or two at most, since a request waiting for the gate waits for one), **never around network or
  disk work that can run before or after**, and **always through
  the transaction manager** (a `@Transactional` method on another bean, or a `TransactionTemplate`). Autocommit
  JDBC would bypass the gate; `TitleSearchIndex` runs its rebuild in a transaction for that reason. Where a
  transaction must cover disk work (the resync's folder scans, `ChapterRemoval.finish`'s folder deletes), the
  slice is small: 100 chapters for the resync, 50 for bulk deletes.
- **Enforced by the transaction manager**: a write joining a read-only transaction is refused
  (`validateExistingTransaction`; it would bypass the gate, and Hibernate would not flush it), and so is a write
  transaction begun inside another on the same thread (`REQUIRES_NEW`): its first statement would wait for the
  lock the suspended one holds. **A begin that fails releases the gate in the manager** (`doBegin`): Spring
  never cleans up a failed begin, and a gate left held would stop every write until a restart. **Never write
  inside a read-only transaction by other means** (native `executeUpdate`, `JdbcTemplate`) — nothing catches
  that.
- **The answer to a refused write** (`GlobalExceptionHandler`): 503 with `Retry-After`, the error page with the
  message and a Go back button, or `{message, retryAfterSeconds}` for a `fetch()` sending
  `Accept: application/json`. `app.js`'s fetch posts (review actions, in-place page delete, chapter number)
  show the message and leave the page as it is. Any lock error or `LibraryBusyException` in a cause chain, under
  any wrapper (error code 5 or 6, `SqliteLocks`; never by exception type, which differs per API), gets the same
  answer, logged at WARN. The handlers match `LibraryBusyException` and `SQLException`, which Spring finds anywhere
  in the cause chain, and rethrow an `SQLException` that is no lock error to its usual handling.
- **The download worker never counts a lock error as a failed attempt**, and retries it without a limit; its
  writes wait anyway, and the next item would fail the same way.
- **Long single holds stay atomic** (1.5M chapters): the title index rebuild (14 s warm, 32 s cold; FTS5
  `rebuild` cannot be sliced, since between slices the triggers would delete rows the index does not hold yet,
  corrupting an external-content index), merging or removing a tag on much of the library (13–30 s), deleting a
  series with thousands of chapters. A request writing meanwhile gets the busy message after 10 s. A commit also runs
  SQLite's automatic checkpoint, which the gate waits for (up to 10 s after such a transaction). Holds longer
  than a request's budget are logged at INFO.
- **`busy_timeout=60000` in the JDBC URL covers what the gate does not**: Flyway and ANALYZE at startup, readers
  during a crash recovery. Each write transaction sets its own for its first statement (the request's remaining
  budget, 0 for `ifFree`, a minute at a time with a log line for background writers) and puts the connection's
  back.

## Caching (Caffeine, `config/CacheConfig`)
⚠️ **`@Cacheable`, `@CacheEvict` and `@Transactional` only work on calls through the bean's proxy — never
on self-invocation.** Each rule below prevents a silently dead cache.
- **A `@Cacheable` read that its own class also calls goes in a separate bean** (`MetadataCatalog`,
  `ImageDirectoryCache`). Don't use self-injection, and never put a cached list method back on
  `MetadataService`.
- **A method reached by self-invocation must not rely on an annotation for correctness.** Evict
  **programmatically** through the `CacheManager` (`ChapterService.evictSearchCount`,
  `SeriesService.evictSearchCount`), which also evicts only on real change. `SeriesService.deleteIfEmpty`
  is called from inside `SeriesService`, so a `@CacheEvict` there would be skipped.
- **Every public entry point that self-invokes a `@Transactional` method must itself be `@Transactional`**,
  so the self-call joins an open transaction.
- **`@Transactional` on `@PostConstruct` never works** (runs on the raw bean, before the proxy exists), and
  a self-injected proxy there is also unproxied.
- `CacheUsageIT` checks every cache in `CacheConfig` is actually **filled** by its owner.

Per cache:
- **`metadata`** — per-type lists for autocomplete, the Manage filter and id→label. Evicted on
  add/rename/remove/merge. **A read cache for the UI, never a source of ids to write**: it can be filled
  inside a transaction that rolls back and name a row that does not exist. Hence `resolveOrCreate` queries
  by name and `MetadataNameFolder` uses `MetadataCatalog.allFresh`.
- **`imageList`** — per-chapter listing, **validated against the directory mtime on every read**, because
  images appear without the app writing them. An unchanged mtime is trusted only once ~2.1 s older than
  the listing (file-time granularity: ~15.6 ms on Windows, 2 s on FAT). Also evicted by the app's own
  writes and by "Rescan images" (which catches pages overwritten in place). Worth it: one `stat` per card
  instead of one `readdir`.
- **`languages`** — distinct chapter languages.
- **`searchCount`** — exact total per search **filter** (page/size/sort not in the key). **Eviction keeps it
  correct**; the 10-minute TTL is only a safety net. Evicted on chapter/series create/update/delete,
  metadata merge/remove, and programmatically when an image-stats resync **actually repairs** something.
- `SettingsService` keeps its own in-memory map.

## Metadata management (`MetadataService`, `dto/MetadataType`)
- **Names are stored in lower case** (`MetadataService.normalize`: trim + lower-case). Lookups fold case
  anyway, so two capitalisations only ever *looked* like two values. **Anything that writes a name must go
  through `MetadataService.canonical`** (or `MetadataService`), which `add`, `rename`, `resolveOrCreate` and the
  rules use.
  - **Tags lose e-hentai's namespaces, for every source** (`canonical`): `female:x` → `x ♀`, `male:x` → `x ♂`,
    and `mixed:`/`other:`/`location:`/`temp:` are dropped, so a tag from e-hentai, chaika, hitomi (gallery-dl
    writes `X ♀`) or typed by hand is one row. Only those namespaces: a colon may belong to a name. It is
    idempotent (an existing symbol is kept). **Not for search needles**: a half-typed `female:ha` must still
    filter.
  - **An imported gendered tag brings its plain tag along** (`resolveOrCreate`, `plainTagOf`): `halo ♀` is
    stored with `halo`, so searching `halo` finds a gallery whether its source tags by gender (e-hentai, chaika,
    hitomi) or not (nhentai). **Stored, not expanded at search time**: one link more per gendered tag keeps every
    searched value one index range, which the plans under "Search" depend on; `halo` as `halo OR halo ♀ OR halo ♂`
    would be a union to build and a distinct count. **Detail pages hide a plain tag beside its version**
    (`plainTagsCoveredBy`), since it says nothing new there; the series page hides it only when every chapter
    carrying it carries a version too, as otherwise its count shows chapters no version does. Edit forms, search
    and autocomplete show both; tags picked by hand on an edit form are taken as picked.
  - **A tag is managed through its plain name**, so a version never drifts from the plain tag search finds it by.
    The Manage page lists and picks plain tags only (`recent`, `autocompleteManaged` behind `/manage/options`).
    Rename, merge and remove of `halo` do the same to `halo ♀`/`halo ♂`: `ring` renames them to `ring ♀`/`ring ♂`;
    a merged version goes where an import would now put it (the target's version, which it becomes if missing).
    The new name must be plain, and a rename is checked for every version before anything changes. `add` of a
    gendered tag adds its plain one too. **"Remove ♀/♂"** (`removeGender`) merges both versions into the plain tag.
  - **Name matching is decided in Java, never by the database.** SQLite `lower()` folds ASCII only, while
    `fold` is `Locale.ROOT` full Unicode; matching in SQL would fork `Ärger`/`ärger` into two rows. So
    `findByNames` / `idsOfName` OR a raw-name equality (seeks the UNIQUE index) with the `lower()` predicate
    (for pre-fold rows), then confirm each hit with `fold`.
  - Backfill: Manage → **"Rewrite metadata names"**. **Two spellings are two rows, so folding is a `merge`,
    not a `rename`** — links move across all three join tables and rules follow. It drives
    `MetadataService.mergeSpellings`/`respell`, which work **per row** (a version's spellings are merged in its
    own group, not with its plain tag), groups by canonical name onto **one** survivor (`female:halo` into
    `halo ♀`), records **no rules**, reads uncached, and reports rows a rule blocks as `blocked`.
- **Generic over six kinds via `MetadataType`** (tag, artist, character, parody, group, category: entity, name
  property, FK column, and **three** join tables: `chapter_*`, `series_*` override, `series_effective_*`).
  **Merge/remove must touch all three** — the effective one's FK would otherwise block the delete and series
  search would go stale. After native join-table changes, `em.flush(); em.clear();` before deleting the entity
  row. Both evict `searchCount`.
  - **A new kind reaches further than the services, which loop over `MetadataType`**: its three tables in V1
    (keys and indexes as under "Invariants"), the `Chapter`/`Series` collections (effective one included), a
    `MetadataLink` projection per owner, the form, `SearchCriteria` (`filterKey`, `hasFilter`, `normalize`),
    both specs and `CompoundSearchQuery`'s switches, `recomputeDerived`, the views' chip groups, the import
    (`GalleryData`, `GalleryImportService`) and the templates. `CategoryIT` checks one kind end to end.
- **A type can hold thousands of items, so the Manage page renders only the most recent**, with a filter box
  that queries. `app.js` rebuilds rename/delete forms client-side, so **anything the template puts in those
  forms must be built there too**: CSRF token, hidden `createRule` field, `long-running` and `blocks-writes`
  classes.
- **Renaming onto a name another item holds is refused** (`NameTaken`) — the columns are UNIQUE and SQLite
  would fail at commit with a useless 500. Checked **first**, on the **normalized** name, ignoring the row
  itself. It is not turned into a merge: that is the Merge form's job, and it cannot be undone.
- **Merging an item into itself is a silent no-op** (a rule there would block the name for ever).

## Metadata rules (`MetadataRuleService`, `entity/MetadataRule`)
Delete, merge and rename fix only existing rows, so the next download would bring the name back. A
**rule** keeps that decision: one row per ruled-out name, written when the section's "Add rule" checkbox
is ticked (default on; clearing it makes a rename a one-off).
- **Only the folded name is stored** (matching is case-insensitive). No `created_date` — rules order by id.
- **Rules match on the NAME and apply in exactly one place**: `MetadataService.resolveOrCreate`, where every
  import turns names into rows. Blocked names are dropped, rewritten ones become the target's id, and the
  result is a **set** (a rewrite can land on a value the gallery already has).
  - **Chapter/series create and edit do NOT run rules** — they take ids the user picked from existing rows.
- **The target is an ID** (null = drop the name), so renaming the target carries the rule along and rules
  are **one hop deep** (no chains, no cycles). Two staleness cases are fixed in `MetadataService`,
  **regardless of the checkbox**: removing a target turns its rules into blocking rules; merging a target
  away retargets them to the survivor. A rule whose target vanished anyway drops the name.
- **A tag's rule covers its versions** (`MetadataService.fatesOf`). A version without a rule of its own is
  dropped with its plain tag, or becomes the target's version (`halo ♀` under `halo → ring` is `ring ♀`, created
  if missing). That version's own rule then applies too; it is the one second lookup, and nothing is followed
  further.
  - **"Remove ♀/♂" rules are on the versions' names** (`halo ♀ → halo`, `halo ♂ → halo`) and belong to the tag:
    a rename moves them to the new names (a one-off rename too), a merge retargets them like any rule, a removal
    turns them into blocks. So the gender stays removed whatever the tag is called.
- **Invariant: a ruled-out name has no row.** Delete/merge/rename free the name they record; `resolveOrCreate`
  never creates one; `add` and `rename` **refuse** a ruled-out name (`RuleConflict`), and a version is ruled out
  by its plain tag's rule too. Exception: a rule pointing at the row being renamed — the rename proceeds and the
  rule is deleted. A case-only rename records nothing. Recording is an **upsert** on (type, name).
- A refused action **redirects to the section title**, so the message at the top is visible; the message
  names the metadata kind.
- **Removing a rule takes a name back** (the rules page). Rules are only created by delete/merge/rename and
  "Remove ♀/♂" — no add form. The page shows each target's **current** name. **No rule counts** on the Manage page or tab
  bar — six queries for a number nothing uses.
- **One "Add rule" checkbox per section, not per form** (a checkbox belongs to one form, and one per row
  broke the layout). Each form carries a hidden `createRule` that `app.js` keeps in sync; it renders `true`
  so a browser without JS submits the default, and a request without the field means `false`.
- No `searchCount` eviction — a rule changes no existing row.

## Security
- **Password-only login**: the form sends a fixed hidden `username=user`; the BCrypt hash is in a text file
  next to the app (`app.password-file`), **not** in the DB.
- **There is no default password.** A missing or empty password file means no password, and startup turns
  login off (persisted, logged) — deleting the file is the simple reset, hence its self-describing name. A
  default would have to be shown where a forgetful user finds it, which protects nothing.
- **Login is off on a fresh install and can never be on without a password** (a lockout nothing could
  undo). `SettingsService.setLoginRequired(true)` throws without one; `isLoginRequired()` is false without
  one whatever the setting says. Ticking "Require login" without a password shows password fields; the
  save takes the password from them, or keeps login off and says why, without "Settings saved.". Every
  password form has a repeat field; BCrypt's 72-byte limit is refused, not truncated.
- `LoginToggleFilter` applies the toggle at runtime by injecting an authenticated token when login is off,
  instead of rebuilding the filter chain. So **Logout** is gated on the `loginRequired` model flag, not on
  `sec:authorize`.
- Always public: `/login`, `/css/**`, `/js/**`, `/images/**`. Everything else, **including `/data/**`**,
  needs auth when login is on. CSRF is on for all forms.

## Images (`config/WebConfig`, `service/Image*`)
- Served from the filesystem by a resource handler (streaming, HTTP range, long cache).
- **`ImageDirectory` reads the disk; `ImageDirectoryCache` remembers a read.** The type a caller injects
  shows whether it may get a remembered answer. Code that needs the disk as it is now injects
  `ImageDirectory`; rendering goes through the cache.
- **A page name from a request reaches the disk only through `ImageDirectory.pageFile`** (delete, JPEG XL
  decode, ComfyUI). One guard for every case (empty name, root-only path, `InvalidPathException`); a name
  containing a folder is refused, not stripped.
- **Pages are the numerically named files** in `data/{chapterId}/`, sorted numerically; the lowest is page
  1 and the card thumbnail. **Non-numeric names (a scraper's `thumbnail.jpg`) are ignored.**
- **A source file next to the encoder output that replaced it is one page** (`3.png` + `3.jxl`;
  `ImageDirectory.partition`), so an interrupted compression cannot double-count `page_num`.
  - **The match is BASE NAME + encoder extension, never page number alone.** `3.jpg` next to `03.jpg` or
    `3.png` are both pages — nothing says which is unwanted, and repairs delete what is superseded.
  - **Encoder output sorts first** (`jxl`/`avif` lead `PAGE_ORDER`), so one pass sees it before its
    sources.
  - **Only explicit repairs delete superseded files** (`ImageDirectory.supersededVariants` →
    `ImageService.removeSupersededPages`, from "Rescan images" and `compressChapter`); otherwise they sit on
    disk while `disk_size` under-reports. Listings, stats scans, grids and the detail self-heal never
    delete.
- **Allowed upload types come from one place** (`ImageDirectory.IMAGE_EXTENSIONS`); the upload input's
  `accept` is derived from it.

## Frontend & CSS (responsiveness is a hard requirement)
Usable from ~360px phones to wide desktops, and **the page body never scrolls horizontally**. Check a
narrow width after any CSS/template change.
- **Every colour is a custom property in `:root`; nothing below that block writes a hex, `rgba()` or colour
  keyword.** So similar things cannot drift apart and a retheme is one block. Names are **semantic**
  (`--danger-bg`, `--chrome-bg`, `--text-on-accent`, `--star`); an alias says so (`--danger: var(--accent)`).
  `--black`/`--white` are the only literal names. A missing colour gets a **new semantic variable**.
  (`transparent`, `currentColor`, `inherit`, `none` are not colours.)
- **Don't size layout with fixed `px`** — use `%`, `rem`, `dvw/vw`, flex/grid, `max-width`, `min()`/`clamp()`.
  `px` is for borders, hairlines, small icons. Card grids use
  `repeat(auto-fill, minmax(clamp(...), 1fr))`.
- **Cap inputs with `max-width`, never a fixed `width`**; don't let an input fill the whole row.
- **`clamp()`/`min()` trap**: a bound larger than the viewport is only safe on **`max-width`**. On `width`,
  `flex-basis` or `min-width` it forces overflow. Hence `.container` is `width: 100%` + large `max-width`.
- **Mind the base `input[type=text]` specificity `(0,1,1)`**: a single-class selector like `.token-input`
  loses to it. Qualify past it (`.token-field input.token-input`), and use `align-items: center` on wrapping
  flex rows mixing controls of different heights.
- Flex children holding text need `min-width: 0` to shrink.
- **Controls that only make sense together go in a non-wrapping wrapper** (`.btn-pair`), because a wrapping
  row can break between any two children. Render it only when it has a child (an empty flex item still
  takes a `gap`). **Merely adjacent buttons use `.btn-row`** — `.btn-pair`'s `nowrap` pushes the page
  sideways on a phone.
- **Long titles without spaces are real**: titles and captions use `overflow-wrap: anywhere;
  word-break: break-word`, their flex parents `min-width: 0`. Test with a 250+ character title.
- **Chapter-number inputs use `step="any"`** (numbers are `main + sub/100`, dated issues `year.month`), or
  native validation blocks the whole series form. The rendered `value` must use a period, never a locale
  comma.
- **The detail `<h1>` prints `titleFull` in three spans** (`dto/TitleParts`): the pretty title in the middle,
  the bracket decoration muted around it; nothing is muted if the pretty title is not contained. **The spans
  stay on one source line without whitespace** — Thymeleaf would render a newline as a space.
- **A form whose POST works through the whole library has `class="long-running"`** (library maintenance,
  metadata merge/remove, delete-series-with-chapters). `app.js` adds a spinner, disables the button on the
  **next tick** (a disabled control is not submitted) and adds a `role="status"` note that **the app stays
  usable in another tab** — true for reads, which never wait, and for writes, which wait for one slice at
  most (see "Writes and SQLite's lock"). A form that is **one** long write (merge, remove, rebuild the title
  index, delete a series with its chapters) also has `blocks-writes`, so the note says a change saved
  meanwhile waits for it and may be refused. No background job, progress bar or polling. The listener is
  registered **after** `confirm-delete` and returns on `e.defaultPrevented`.
  - **In a form whose other buttons are quick, the class goes on the one long submit button** ("Download all
    favourites" beside "Queue downloads"), and the spinner and note follow it. `data-busy-note` on the
    button or form replaces the note where the wait has another cause than the library's size.
- `:root`/`body` set `color-scheme: dark` so native controls render light-on-dark.
- **Card captions expand over the cards below** (absolute position, higher `z-index`), so the full title
  shows without changing card height. Touch-hold and focus trigger it; `title` is the fallback.
- **The image viewer centers with the `margin: auto` flex trick** in all `ViewMode`s: centered when smaller,
  top-left and scrollable when larger, avoiding the `align-items: center` + `overflow` clipping bug.

## Chapter create / edit
Manage → Chapters offers **Download** (many links, one per line), **View download queue**, **Add manually**.
- `titleFull` and `language` are **required** on manual create; a blank or unknown language is a field
  error, never defaulted. The value is stored via `LanguageService.canonical`, because language is searched
  by exact value. **Anything new that writes a language must canonicalize.**
- **A blank `title` is derived from `titleFull` minus bracket decoration, keeping the numbering**
  (`Comic Hero 2024-06 [Digital]` → `Comic Hero 2024-06`). Clearing it on edit re-derives it.
- `chapterNum` is not set on create (series-only). `uploadDate` is today on create, never editable.
  `nativeTitle` is nullable without fallback. `status` defaults to `NEW`.
- **A chapter linked by hand is numbered from its title**: Add to series, "Create series from this chapter",
  Link chapters and "Add chapter by id" all use `SeriesService.attachChapters`, which gives a chapter
  without a number the one `TitleKey` reads from its title (`Comic Hero 3` → 3). A number the chapter
  already has is kept.
- `ChapterController` id routes are `{id:\d+}` so `/chapter/new`, `/download`, `/queue` never parse as ids.
- **Deleting a page on the edit page removes its thumbnail in place** (`app.js`, `page-delete` forms), so
  the scroll position stays. The request carries `inPlace`; only a **204** counts as done (a redirect may be
  an expired login). Anything else falls back to a plain post, which (like no-JS) redirects to `#page-<n>`.
- **Library maintenance** holds the five whole-library repairs (page counts & sizes, title search index,
  lower-case metadata names, match chapters, compress images), each a `long-running` form.

## Dividing a chapter (`service/ChapterDivisionService`)
**"Divide into separate chapters"** turns a compilation into one chapter per part: the user marks the first
page of each inner chapter, each mark starts a part up to the next mark, and pages before the first mark
stay. One submit creates all parts.
- **Marks, not a page selection** — one pass instead of one round per chapter. So a part is always
  contiguous and the part that stays is always the leading run.
- **Parts are created through `ChapterService.create`** from the original's form: language, native title,
  rating and all metadata are copied; the title is the typed one; the **status is the one picked on the
  page** (preselected with the original's); **matching decides the series**. The gallery id is **not**
  copied (unique), so a part is `NONE` to the pipeline. `uploadDate` is today (`DATE` sorts by id). A part
  without a posted title (no JS) gets the original's; a blank one is refused.
- **Remaining pages are never renamed; a part's pages are numbered from 1.** For a downloaded chapter the
  names are the source's page numbers, which a full-quality re-download relies on. `ImageService.movePages`
  never overwrites; it numbers after what the target holds.
- **Refused before anything changes** (`refusal`, also shown before the form): a `PENDING` download (a
  re-queue would fetch the moved pages again), a **waiting** queue row (a full-quality re-download works out
  its pages at start), and an unknown language. Moves run under the **compression run lock**, refused if
  taken, so no encoder writes output back into the old folder. Superseded sources are removed first.
- **Part by part, every page always in exactly one chapter.** Per part: create and **commit** the chapter
  (its folder is named by id), move pages, sync stats. `movePages` renames atomically and moves pages back
  if one fails, so a part gets all its pages or none; the division then deletes that part's chapter (unless
  pages it could not move back are in it), stops and reports. The original's stats and compression label
  are synced **in a `finally`**; its new title is applied **only once every part was made**. After a crash,
  the detail page heals page counts.
- **Moves retry briefly on a file held open** (Windows sharing violation): djxl opens a page without
  `FILE_SHARE_DELETE` while the interceptor decodes it, and the divide page shows every page as a thumbnail.
- **The compression label follows the files**: a part holding `.jxl`/`.avif` takes the original's mode; the
  original loses its mode once no encoder output remains.
- **The form names pages by file name, never position** (a position may mean another page after an edit; a
  missing name is refused). Titles bind as a map keyed the same way (`titles[31.jpg]`), so each stays with
  its part and a title with commas is not split. A refused post, or a division that stopped part-way,
  re-renders the page with marks and titles. Unmarking hides a divider and **disables** its input, so
  re-marking restores the typed title and only real parts send one.
- **Nothing joins chapters back**, so the submit asks first, naming each part's pages, title and status.

## Input length / DB constraints
- **SQLite does not enforce `VARCHAR(n)`.** The guard is client `maxlength="255"` **and** server
  `@Size(max = 255)` on every `String` form field. `SQLSTATE 22001` handling stays only as a safety net for
  another engine.
- **The one duplicate-checked unique value is `gallery_id`**, checked **before save** and thrown as
  `DuplicateValueException(field, msg)` for a per-field error. `web/GlobalExceptionHandler` turns any
  remaining `DataIntegrityViolationException` into a friendly error page.
- **Never branch on DB error message text — it is locale-dependent.** `web/SqlStates` keys off **SQLite
  result codes** (its `SQLException` has a null SQLSTATE); base code `19` is split by SQLite's message,
  which is stable English. Standard SQLSTATEs are a secondary net. Column names are safe to match, but the
  echoed INSERT names every column, so prefer the pre-save check.

## Tests
**Integration-first** (`@SpringBootTest`, **file-based SQLite under `./target`**, profile in
`src/test/resources`). Not in-memory: `jdbc:sqlite::memory:` gives each pooled connection its own empty
database, which breaks `SearchService`'s second-connection count. Unit tests only for non-trivial pure
logic. **The schema comes from the real Flyway migrations** — `TestSchemaConfig` wipes and re-migrates
(dropping objects itself, since `flyway.clean()` cannot drop FTS5 shadow tables). Prefer **AssertJ**. Aim
for good coverage.
- ⚠️ **`app.match-auto-link-default=false` in the test profile.** Otherwise every chapter-creating suite
  would pay for matching and reach `addChapters` with the chapter already filed, changing what it tests and
  what `PerformanceIT` measures. Matching suites turn it on themselves. Restore
  `AppProperties.isMatchAutoLinkDefault()`, **not** a literal `true` (the settings cache is shared).
- ⚠️ **Every test context starts with a password and login required** (`TestLogin`). A suite that changes the
  password, deletes the file or turns login off must call `TestLogin.restore()`.
- ⚠️ **`app.storage.ram-disk-candidates=` (empty)**, so Linux machines with tmpfs behave like everywhere else;
  `ScratchSpaceTest` covers the automatic choice with a plain folder. A suite that sets a folder in Settings
  must clear it (`ScratchSpace` is a singleton).
- ⚠️ **`app.download.worker-enabled=false`**, so the live worker does not race the suites. Tests call
  `DownloadWorker.processNext()` — the method the loop calls. The profile points the mock source at a
  fixture each test writes (no binaries in the repo) and zeroes the retry backoff.
- ⚠️ **Image Compression tests use the REAL binaries in `bin/`** (`app.image-compression.bin-dir=./bin`).
  Mocks would only repeat our assumptions about the tools. So a platform missing a tool must skip: each such
  test starts with an assumption naming the tool (Linux has no `cjxl` in `bin/` yet). Fixtures are real
  images made with `ImageIO` (`TestImages`). A suite that changes `bin-dir` or a compression setting **must
  restore it**.
- ⚠️ **`hibernate.hbm2ddl.jdbc_metadata_extraction_strategy=individually`**, because `SchemaMigrationIT` uses
  `ddl-auto=validate` and the default `grouped` strategy fails on the empty column types of FTS5 (shadow)
  tables. Production (`ddl-auto=none`) does not need it — a future `validate`/`update` would.
- Suites that create metadata through repositories **clear the `metadata` cache in `@BeforeEach`**.
- ⚠️ **`app.download.nhentai.base-url=http://127.0.0.1:1`, every nhentai limit off** — no suite reaches
  nhentai or waits out its rate limits. nhentai suites run against **`FakeNhentai`** (JDK `HttpServer`; it is
  its own image server, so every request is visible) and **must restore `base-url` and the API key**.
  `RequestPacerTest` covers the pacing. `app.download.page-retry-backoff-millis=0`, so failure tests do not
  sleep.
- ⚠️ **`app.gallery-dl.command` starts `FakeGalleryDl`** (a `main` in the test classes, run by this JVM's
  `java`), so no suite starts the real gallery-dl or reaches a site through it. It behaves as the fixture a
  suite writes says (`FakeGalleryDl.set`: pages, failures, exit status, stderr, `-j` output, per host) and
  records every call's arguments (`FakeGalleryDl.calls`). A suite calls `FakeGalleryDl.reset()` first.
- ⚠️ **`app.download.ehentai.api-url` and `app.download.chaika.base-url` point nowhere, unpaced** — suites point
  them at a fake API and **`FakeChaika`** (which answers byte ranges and records them) and restore them. A
  suite that starts e-hentai's cooldown clears it (`EhentaiDownloader.clearCooldown`).
- **`*E2E` suites talk to the real sites and run only with `-Pe2e`** (the profile replaces surefire's includes),
  so the default build passes offline and never touches a site. One sets the real configuration back for itself
  (`NhentaiDownloadE2E` copies `new NhentaiProperties()` over the test profile's) and restores it afterwards.
  They assert everything they read, so an edit on the site fails them; the expectations then follow the site.
- ⚠️ **`app.comfyui.default-url=http://127.0.0.1:1`** — nothing answers there, so no suite reaches a real
  ComfyUI on the developer's machine. ComfyUI suites run against **`FakeComfyUi`** (HTTP API and websocket on
  raw sockets; the JDK `HttpServer` cannot upgrade to websocket). A suite pointing Settings at a fake **must
  reset the address, folder and default workflow**, and call **`PageProcessingService.forgetAll()` before closing
  the fake**: the service is shared, and a page a test left wanted runs in the next test, against its fake and
  page files, and answers that test from the cache. `ComfyUiLiveIT` is opt-in (`-Dcomfyui.live=true`,
  optionally `-Dcomfyui.live.url/.workflow/.script`).
- ⚠️ **`app.writes.request-wait-millis=300`**, so the busy tests (`WriteGateIT`, `BusyLibraryWebIT`,
  `DownloadWriteGateIT`) run fast. **A `@Transactional` test holds the write gate and SQLite's lock for the whole
  test**, so a contention test must not be one: its holders are other threads (`GateHolder`) and connections
  (`SqliteLockHolder`), and it removes what it committed.

## Shutdown
- **Nothing may depend on a graceful stop.** Task Manager's End task, a power cut, or closing the console
  window (Windows kills the process a few seconds later) run no `@PreDestroy`, so every path must survive
  being killed at any point: SQLite transactions, committed slices, the download queue row, staging.
- **The graceful stops are Ctrl+C in the console and Settings → Shut down.** Requests get a minute
  (`spring.lifecycle.timeout-per-shutdown-phase`), then the download worker 10 s (see "Chapter
  downloading").
- **Shut down runs `SpringApplication.exit` on its own non-daemon thread, a second after answering**
  (`AppShutdown`). Closing the context waits for running requests, so on the request thread it would wait
  for itself. The server takes no new request once shutdown starts, so the browser needs that second to
  fetch the page's stylesheet, and the endpoint answers with a page, not a redirect. `ShutdownWebIT`
  replaces the bean: the real one would end the test JVM.

## Packaging
- `spring-boot-maven-plugin` builds the fat jar (`finalName=HenTie`). The **`windows-exe` profile**
  (active on Windows) wraps it into `HenTie.exe` with Launch4j. It is a **console** program
  (`headerType=console`), so its window shows the log and Ctrl+C in it stops the app gracefully.
- **The exe runs without an installed Java**: the same profile `jlink`s a runtime into `target/jre` (~50 MB)
  from the JDK running Maven, and Launch4j looks in `jre\` beside the exe first, then for an installed Java
  21+. Launch4j cannot embed it, so **`jre/` ships next to the exe**, like `bin/`. `start.bat` prefers it too.
  **The module list is `jre.modules` in the POM**: what `jdeps` finds in the fat jar, plus modules only
  loaded by name at runtime, which `jdeps` cannot see. A new dependency may need a module added, or it fails
  only on a machine without Java (`NoClassDefFoundError` for a `java.*`/`jdk.*` class).
- **The exe icon is built from `static/images/logo.svg` at package time**, never committed, so it cannot
  drift from the logo. `src/tools/IconGen.java` runs as a single-file program from antrun, with JSVG only on
  that plugin's classpath (not in the app). Sizes under 96 px zoom in on the head, in frames anchored at the
  drawing's top-left: a logo laid out differently needs new frames. It lives in `src/tools/`, not
  `src/build/`: `.gitignore`'s NetBeans `build/` rule would hide it.
- **Release zips** (`packageForRelease` profile, `src/assembly/`): **windows** = exe + `jre/` + `bin/win`, no
  Java needed; **all-platforms** = jar + `start.bat` + `start.sh` + all of `bin/`, needs Java 21+. The
  Windows one is built only on Windows (`release.skipWindowsZip`), and the profile is declared after
  `windows-exe` so it zips after the exe and runtime exist. **The all-platforms descriptor sets `start.sh`'s
  LF endings and the `0755` modes** rather than trusting the checkout: a Windows checkout has neither.
- **`bin/` ships next to the jar, not inside it** — tens of MB of platform executables, resolved at runtime
  (`app.image-compression.bin-dir` and `app.gallery-dl.bin-dir`, default `./bin`). Without it the app still runs
  and compression keeps originals (logged); "use the … installed on this system" is the alternative.
- **gallery-dl is the unmodified standalone release from Codeberg** (`bin/win/gallery-dl.exe`,
  `bin/linux/gallery-dl.bin`, checked against the release's `SHA256SUMS`), kept under its upstream name because
  its `-U` replaces itself in place. **It is GPL-2.0, so `gallery-dl-LICENSE.txt` and `gallery-dl-SOURCE.txt`
  (the release's source) ship beside each copy.** The Windows exe needs the Microsoft Visual C++
  Redistributable; without it the start fails with a message saying so. There is no macOS build.
- `BrowserLauncher` opens the browser on `ApplicationReadyEvent` (not when headless or
  `app.open-browser=false`). `start.bat` is the no-tooling Windows launcher.
- **MapStruct and Lombok are both in `maven-compiler-plugin`'s `annotationProcessorPaths`** (`lombok`,
  `mapstruct-processor`, `lombok-mapstruct-binding`) — keep that order, or MapStruct won't see Lombok's
  accessors.

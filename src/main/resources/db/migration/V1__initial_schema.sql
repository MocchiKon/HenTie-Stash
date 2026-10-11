-- ---------------------------------------------------------------------------------------------------
-- Initial schema (SQLite).
--
-- This is the whole database as the app expects it: the file starts empty and Flyway builds every table,
-- index, virtual table and trigger from here, so no Hibernate DDL ever runs (ddl-auto=none).
--
-- The table/index statements were generated from the JPA mappings (jakarta.persistence.schema-generation,
-- Hibernate 7 + community SQLiteDialect), with two things worth knowing:
--   * Every generated id is "integer primary key autoincrement" (hand-edited: the dialect writes a plain
--     "primary key (id)"). Without AUTOINCREMENT SQLite gives max(rowid) + 1, so deleting the newest row
--     hands its id to the next insert - and a chapter id names its image folder, scratch folders and URLs.
--   * The join tables' foreign keys are hand-written (the dialect emits none, and SQLite cannot add one
--     with ALTER). ON DELETE CASCADE, so no delete - Hibernate, a batch delete or native SQL - can leave a
--     link to a missing row, which would make the native search inexact. Every one is backed by an index
--     that starts with its column, or each parent delete would scan the join table. chapter.series_id has
--     none on purpose: unlinking goes through SeriesService, which a SET NULL would bypass.
--
-- The FTS5 title index at the bottom is hand-written (Hibernate does not know about virtual tables).
-- ---------------------------------------------------------------------------------------------------

-- ===== Tables =====
-- chapter.match_key, series.match_key - title_full normalized for matching (see service/match): bracket
--     groups removed, accents/case folded, punctuation collapsed and trailing sequence markers ("2",
--     "part 2", "omake", ...) stripped. Family members relate by one key being a token PREFIX of the other,
--     which is a B-tree range seek, unlike the substring match a LIKE '%...%' would need.
-- chapter.native_match_key - native_title normalized like match_key (TitleKey.nativeKey); for a chapter without a
--     native title whose own title is written in Japanese, Chinese or Korean, that title's key. '' when there is
--     nothing to compare. Through it a translated chapter finds its original, and two romanizations of one work
--     find each other, through the Japanese title both carry.
-- chapter.download_status - ORDINAL, 0 NONE / 1 PENDING / 2 SUCCESSFUL (entity/DownloadStatus). NONE is 0,
--     so the default means "not from a download". No index: it is read one chapter at a time, after that
--     chapter has been found by its gallery id.
-- chapter.compression_mode - the key of the Image Compression mode that last re-encoded the chapter's
--     pages (LOSSLESS, custom:7, ...), NULL while they are original. A key because it survives a rename.
--     No index: it is read only for the one chapter a detail page shows.
-- chapter.source_site - which of its source's websites a chapter's pages last came from, for a source with more
--     than one (exhentai for e-hentai's galleries); null for the source's usual one. The chapter page links there:
--     exhentai has galleries e-hentai hides.
create table app_setting (setting_key varchar(100) not null, setting_value varchar(2000), primary key (setting_key));
create table artist (id integer primary key autoincrement, name varchar(255) not null unique);
create table category (id integer primary key autoincrement, name varchar(255) not null unique);
create table chapter (id integer primary key autoincrement, chapter_num float, page_num integer not null default 0, score smallint, series_id integer, status tinyint not null check ((status between 0 and 2)), upload_date date not null, disk_size bigint not null default 0, gallery_id varchar(255) unique, language varchar(255) not null, native_title varchar(255), title_full varchar(255) not null, title_pretty varchar(255) not null, match_key varchar(255) not null default '', download_status tinyint not null default 0, compression_mode varchar(64), native_match_key varchar(255) not null default '', source_site varchar(32));
create table chapter_artists (artist_id integer not null references artist (id) on delete cascade, chapter_id integer not null references chapter (id) on delete cascade);
create table chapter_categories (category_id integer not null references category (id) on delete cascade, chapter_id integer not null references chapter (id) on delete cascade);
create table chapter_characters (chapter_id integer not null references chapter (id) on delete cascade, character_id integer not null references character (id) on delete cascade);
create table chapter_groups (chapter_id integer not null references chapter (id) on delete cascade, group_id integer not null references group_artists (id) on delete cascade);
create table chapter_parodies (chapter_id integer not null references chapter (id) on delete cascade, parody_id integer not null references parody (id) on delete cascade);
create table chapter_tags (chapter_id integer not null references chapter (id) on delete cascade, tag_id integer not null references tag (id) on delete cascade);
create table character (id integer primary key autoincrement, name varchar(255) not null unique);
create table group_artists (id integer primary key autoincrement, name varchar(255) not null unique);
create table parody (id integer primary key autoincrement, title varchar(255) not null unique);
create table series (id integer primary key autoincrement, created_date date not null, page_num integer not null default 0, score smallint, score_source tinyint not null check ((score_source between 0 and 1)), status tinyint not null check ((status between 0 and 2)), disk_size bigint not null default 0, native_title varchar(255), title_full varchar(255) not null, title_pretty varchar(255) not null, match_key varchar(255) not null default '');
create table series_artists (artist_id integer not null references artist (id) on delete cascade, series_id integer not null references series (id) on delete cascade);
create table series_categories (category_id integer not null references category (id) on delete cascade, series_id integer not null references series (id) on delete cascade);
create table series_characters (character_id integer not null references character (id) on delete cascade, series_id integer not null references series (id) on delete cascade);
create table series_effective_artists (artist_id integer not null references artist (id) on delete cascade, series_id integer not null references series (id) on delete cascade);
create table series_effective_categories (category_id integer not null references category (id) on delete cascade, series_id integer not null references series (id) on delete cascade);
create table series_effective_characters (character_id integer not null references character (id) on delete cascade, series_id integer not null references series (id) on delete cascade);
create table series_effective_groups (group_id integer not null references group_artists (id) on delete cascade, series_id integer not null references series (id) on delete cascade);
create table series_effective_languages (series_id integer not null references series (id) on delete cascade, language varchar(255));
create table series_effective_parodies (parody_id integer not null references parody (id) on delete cascade, series_id integer not null references series (id) on delete cascade);
create table series_effective_tags (series_id integer not null references series (id) on delete cascade, tag_id integer not null references tag (id) on delete cascade);
create table series_groups (group_id integer not null references group_artists (id) on delete cascade, series_id integer not null references series (id) on delete cascade);
create table series_parodies (parody_id integer not null references parody (id) on delete cascade, series_id integer not null references series (id) on delete cascade);
create table series_tags (series_id integer not null references series (id) on delete cascade, tag_id integer not null references tag (id) on delete cascade);
create table tag (id integer primary key autoincrement, name varchar(255) not null unique);

-- metadata_rule - the standing decisions behind deleting, merging and renaming a tag/artist/character/
--     parody/group/category, so the next import does not bring the name back.
--     source_name_lower is the ruled name, folded, and the only name kept: matching is case-insensitive,
--     so a stored spelling would suggest the rule covers one capitalisation when it covers them all. Unique
--     per type: one name has one fate, and re-recording a rule replaces it. target_id is the row a name is
--     rewritten to, or NULL for "drop it"; an ID, not a name, so renaming the target keeps the rule on the
--     same row and chains cannot form. No FK: it points into one of six tables depending on `type`, and
--     MetadataService keeps it honest instead.
create table metadata_rule (id integer primary key autoincrement, target_id integer, source_name_lower varchar(255) not null, type varchar(20) not null);

-- subscription - a saved search on a site whose galleries are queued for download on their own, first all of
--     them, then each new one.
--   source              - the search site's key (nhentai, e-hentai, exhentai). A key, not an ordinal, because the
--                         sites come from the sources registered at startup.
--   query               - as the site's search takes it, normalized by the source
--   poll_minutes        - how often the newest page is checked, at least 10
--   recheck_every_hours, recheck_depth_hours - how often galleries uploaded in the last recheck_depth_hours are
--                         listed again (tags are added after upload); 0 = never
--   compression_mode .. request_delay - what each queued row gets, as a paste on the Download page would
--   revision            - bumped whenever the walk starts over, so a page fetched for the old walk is never applied
--   newest_gallery_id, oldest_gallery_id - the walk's head and tail: every gallery the search listed between them
--                         has been handled
--   oldest_cursor       - the source's own way of continuing below the tail (nhentai: an id, an upload time and
--                         the galleries of that second already had, so it can run long)
--   reached_end         - the walk below the tail found nothing older
--   catch_up_*          - a walk from the newest page down to catch_up_stop (NULL = the end of the search) under way;
--                         catch_up_top becomes the head when it ends
--   *_count             - galleries listed for the first time, by what became of them
--   top_seen_at         - when a check last saw the top of the search (service/subscription/SubscriptionWalk): the
--                         time a catch-up's checkpoint is recorded with, and how a check tells that the walk lost
--                         sight of the search (the app was off, or the site failed, for longer than a re-check
--                         reaches back). NULL before the first check.
-- subscription_checkpoint - the head as it was at a time, so a re-check knows where "recheck_depth_hours ago" was
--   in the search; at most one an hour, pruned past the oldest one a re-check still needs.
create table subscription (id integer primary key autoincrement, name varchar(255), source varchar(32) not null, query varchar(1000) not null, poll_minutes integer not null, recheck_every_hours integer not null, recheck_depth_hours integer not null, compression_mode varchar(64) not null default 'NONE', avoid_duplicate_titles boolean not null default 0, cookies_browser varchar(32), download_originals boolean not null default 0, request_delay varchar(32) not null, enabled boolean not null default 1, created_at timestamp not null, revision integer not null default 0, newest_gallery_id varchar(255), oldest_gallery_id varchar(255), oldest_cursor varchar(2000), reached_end boolean not null default 0, catch_up_top varchar(255), catch_up_cursor varchar(2000), catch_up_stop varchar(255), last_checked_at timestamp, last_rechecked_at timestamp, result_total integer, queued_count integer not null default 0, in_library_count integer not null default 0, deleted_since_count integer not null default 0, already_queued_count integer not null default 0, blacklisted_count integer not null default 0, last_error varchar(2000), last_error_at timestamp, failed_steps integer not null default 0, top_seen_at timestamp);
create table subscription_checkpoint (id integer primary key autoincrement, subscription_id integer not null references subscription (id) on delete cascade, recorded_at timestamp not null, gallery_id varchar(255) not null);

-- download_queue - one row per pasted link, deleted only once the item has been carried all the way
--     through. No status column on purpose: an "in progress" flag
--     written before a power cut would be a lie, so pending is `error is null` and failed is
--     `error is not null`. ignore_image_errors, compression_mode, avoid_duplicate_titles and replace_pages
--     are the choices a paste or retry made, on the row because the download runs later, on the worker
--     thread, and has to survive a restart. Their defaults are an ordinary, strict, uncompressed download.
--   cookies_browser    - the browser gallery-dl reads cookies from (--cookies-from-browser); null = no cookies
--   download_originals - e-hentai's original files instead of the resampled ones
--   request_delay      - seconds between two image requests, "0.4-0.65" or "2"; '' is no delay gallery-dl reads,
--                        so a row holding it is refused rather than fetched without a delay
--     These three are the gallery-dl choices; only gallery-dl sources read them.
--   priority           - who asked: 0 the user (a paste, favourites, a retry of theirs), 1 a subscription. Lower
--                        runs first. Stored values: never renumbered.
--   subscription_id    - the subscription that queued the row; SET NULL when it is deleted, so its rows keep their
--                        priority and still download. A paste of the same gallery claims the row (priority 0, NULL).
-- downloaded_gallery - "this source gallery has been fetched", keyed by the namespaced gallery id
--     ("mock:12"). Kept when the queue row goes, and outlives the chapter it created.
create table download_queue (id integer primary key autoincrement, attempts integer not null, chapter_id integer, queued_at timestamp not null, error varchar(2000), gallery_id varchar(255), link varchar(1000) not null unique, ignore_image_errors boolean not null default 0, compression_mode varchar(64) not null default 'NONE', avoid_duplicate_titles boolean not null default 0, replace_pages boolean not null default 0, cookies_browser varchar(32), download_originals boolean not null default 0, request_delay varchar(32) not null default '', priority integer not null default 0, subscription_id integer references subscription (id) on delete set null);
create table downloaded_gallery (chapter_id integer not null, downloaded_at timestamp not null, gallery_id varchar(255) not null, primary key (gallery_id));

-- image_compression_mode - the modes the user defines. The three
--     built-in ones are not rows: they come from image-compression-modes.properties, so they cannot be
--     edited away and a fresh install has them without a seed. `name` is UNIQUE because the dropdown shows
--     it. `encoder` is ORDINAL (0 JXL / 1 AVIF).
create table image_compression_mode (id integer primary key autoincrement, name varchar(255) not null unique, encoder tinyint not null, encoder_args varchar(1000) not null, magick_args varchar(1000) not null, min_relative_reduction integer not null, min_absolute_reduction integer not null, formats varchar(255) not null);

-- ===== Indexes =====
-- Sort and filter indexes for search:
--   (<sort>, id, status) - walked in sort order, with several statuses filtered from the index alone
--       instead of reading every row to see its status. SQLite keeps the rowid after the key anyway.
--   (status, <sort>), (language, <sort>) - walked in sort order for one status or one chapter language.
--   (status), (language) - the DATE sort's composites: SQLite ends every index with the rowid and DATE
--       sorts by id. They are also streams of the native compound queries.
--   (id) - the ids alone, in order, a fraction of the table's size: the stream an exclusion-only search
--       subtracts from.
-- There is no plain chapter (series_id) index: ix_chapter__series_match_key starts with it. There is none
-- on chapter_num either: a chapter number is only ever ordered within one series, which series_id narrows
-- to a few rows first.
create index idx_chapter_status on chapter (status);
create index idx_chapter_upload_date on chapter (upload_date);
create index ix_chapter__status_score on chapter (status, score);
create index ix_chapter__status_page_num on chapter (status, page_num);
create index ix_chapter__status_disk_size on chapter (status, disk_size);
create index ix_chapter__score_id_status on chapter (score, id, status);
create index ix_chapter__page_num_id_status on chapter (page_num, id, status);
create index ix_chapter__disk_size_id_status on chapter (disk_size, id, status);
create index ix_chapter__language on chapter (language);
create index ix_chapter__language_score on chapter (language, score);
create index ix_chapter__language_page_num on chapter (language, page_num);
create index ix_chapter__language_disk_size on chapter (language, disk_size);
create index ix_chapter__id on chapter (id);
create index idx_series_status on series (status);
create index idx_series_created_date on series (created_date);
create index ix_series__status_score on series (status, score);
create index ix_series__status_page_num on series (status, page_num);
create index ix_series__status_disk_size on series (status, disk_size);
create index ix_series__score_id_status on series (score, id, status);
create index ix_series__page_num_id_status on series (page_num, id, status);
create index ix_series__disk_size_id_status on series (disk_size, id, status);
create index ix_series__id on series (id);

-- Matching (see service/match): (match_key, id) covers the exact and "extends this key" range seeks.
-- (series_id, match_key, id) lets the sweep walk the unlinked chapters
-- in key order, which makes each family arrive together without a sort over the whole table.
-- ix_chapter__condensed_match_key is match_key without spaces, for the series page's "Link chapters": the
-- chapters sharing a series' block, as one range walked outward from the series' own title. An expression
-- index rather than a column, so SQLite maintains it on every write and nothing has to backfill it. A query
-- uses it only when it spells the expression exactly as declared here, which is why ChapterCandidateFinder
-- seeks it in native SQL, pinned with INDEXED BY. ix_series__condensed_match_key is the same walk over series
-- (service/match/NearestTitles), so automatic matching finds what the series page offers.
-- (native_match_key, series_id) - matching seeks chapters by this key for the series they are in, so the series
-- comes off the index: a series' many chapters under one key cost index entries, not rows.
create index ix_chapter__match_key on chapter (match_key, id);
create index ix_chapter__series_match_key on chapter (series_id, match_key, id);
create index ix_chapter__condensed_match_key on chapter (replace(match_key, ' ', ''), id);
create index ix_series__match_key on series (match_key, id);
create index ix_series__condensed_match_key on series (replace(match_key, ' ', ''), id);
create index ix_chapter__native_match_key on chapter (native_match_key, series_id);

-- "Avoid duplicated titles from other sources": a chapter with exactly this title_full whose gallery id is
-- not from this source. title_full leads so the equality is a seek; gallery_id rides along so the source
-- comparison is answered from the index without reading a chapter row.
create index ix_chapter__title_full_gallery_id on chapter (title_full, gallery_id);

-- Metadata join tables, both directions: (meta_id, owner_id) is covering and drives the search semi-join,
-- (owner_id, meta_id) loading and rebuilding an owner's metadata. (language, series_id) gives the series of
-- one language in id order, so the native count needs no temporary B-tree and a language can be a stream of
-- the compound queries.
create index ix_chapter_artists__artist_chapter on chapter_artists (artist_id, chapter_id);
create index ix_chapter_artists__chapter_artist on chapter_artists (chapter_id, artist_id);
create index ix_chapter_categories__category_chapter on chapter_categories (category_id, chapter_id);
create index ix_chapter_categories__chapter_category on chapter_categories (chapter_id, category_id);
create index ix_chapter_characters__character_chapter on chapter_characters (character_id, chapter_id);
create index ix_chapter_characters__chapter_character on chapter_characters (chapter_id, character_id);
create index ix_chapter_groups__group_chapter on chapter_groups (group_id, chapter_id);
create index ix_chapter_groups__chapter_group on chapter_groups (chapter_id, group_id);
create index ix_chapter_parodies__parody_chapter on chapter_parodies (parody_id, chapter_id);
create index ix_chapter_parodies__chapter_parody on chapter_parodies (chapter_id, parody_id);
create index ix_chapter_tags__tag_chapter on chapter_tags (tag_id, chapter_id);
create index ix_chapter_tags__chapter_tag on chapter_tags (chapter_id, tag_id);
create index ix_series_artists__artist_series on series_artists (artist_id, series_id);
create index ix_series_artists__series_artist on series_artists (series_id, artist_id);
create index ix_series_categories__category_series on series_categories (category_id, series_id);
create index ix_series_categories__series_category on series_categories (series_id, category_id);
create index ix_series_characters__character_series on series_characters (character_id, series_id);
create index ix_series_characters__series_character on series_characters (series_id, character_id);
create index ix_series_eff_artists__artist_series on series_effective_artists (artist_id, series_id);
create index ix_series_eff_artists__series_artist on series_effective_artists (series_id, artist_id);
create index ix_series_eff_categories__category_series on series_effective_categories (category_id, series_id);
create index ix_series_eff_categories__series_category on series_effective_categories (series_id, category_id);
create index ix_series_eff_characters__character_series on series_effective_characters (character_id, series_id);
create index ix_series_eff_characters__series_character on series_effective_characters (series_id, character_id);
create index ix_series_eff_groups__group_series on series_effective_groups (group_id, series_id);
create index ix_series_eff_groups__series_group on series_effective_groups (series_id, group_id);
create index ix_series_eff_languages__language_series on series_effective_languages (language, series_id);
create index idx_series_eff_lang_series_id on series_effective_languages (series_id);
create index ix_series_eff_parodies__parody_series on series_effective_parodies (parody_id, series_id);
create index ix_series_eff_parodies__series_parody on series_effective_parodies (series_id, parody_id);
create index ix_series_eff_tags__tag_series on series_effective_tags (tag_id, series_id);
create index ix_series_eff_tags__series_tag on series_effective_tags (series_id, tag_id);
create index ix_series_groups__group_series on series_groups (group_id, series_id);
create index ix_series_groups__series_group on series_groups (series_id, group_id);
create index ix_series_parodies__parody_series on series_parodies (parody_id, series_id);
create index ix_series_parodies__series_parody on series_parodies (series_id, parody_id);
create index ix_series_tags__tag_series on series_tags (tag_id, series_id);
create index ix_series_tags__series_tag on series_tags (series_id, tag_id);

-- (type, id) serves the rules page ("newest first for this type"), (type, target_id) the fixups when a
-- target is removed or merged away.
create unique index ux_metadata_rule__type_source on metadata_rule (type, source_name_lower);
create index ix_metadata_rule__type_id on metadata_rule (type, id);
create index ix_metadata_rule__type_target on metadata_rule (type, target_id);

-- (error, priority, id) serves the worker's pending-in-order query and both lists on the queue page.
-- (gallery_id): every enqueue looks its gallery up.
create index ix_download_queue__error_priority_id on download_queue (error, priority, id);
create index ix_download_queue__gallery_id on download_queue (gallery_id);
create index ix_download_queue__subscription_error on download_queue (subscription_id, error);

create index ix_subscription_checkpoint__subscription_recorded on subscription_checkpoint (subscription_id, recorded_at);

-- ===== FTS5 trigram title index =====
-- Substring title search (LIKE '%term%') cannot use a B-tree index, so titles are also indexed as
-- overlapping 3-character grams. External content (content='chapter'): the virtual table stores only
-- the index, never a copy of the titles. tokenize='trigram case_sensitive 0' spells out the trigram
-- default because case-insensitive matching - including for non-ASCII titles, which SQLite lower()/LIKE
-- cannot fold - depends on it. See service/TitleSearchIndex and entity/link/TitleMatch.
create virtual table chapter_fts using fts5(title_full, native_title, content='chapter', content_rowid='id', tokenize='trigram case_sensitive 0');
create virtual table series_fts using fts5(title_full, native_title, content='series', content_rowid='id', tokenize='trigram case_sensitive 0');

-- AFTER-write triggers, the standard external-content FTS5 sync pattern: a delete is recorded by
-- re-supplying the OLD values (that is how FTS5 removes the row grams), an update is delete + insert.
-- Because they are SQL triggers, EVERY write keeps the index current - Hibernate, native SQL, or a
-- manual sqlite3 session - with no application code involved.
-- The update triggers re-index a title only when it (or the id) changes: UPDATE OF skips a statement that
-- sets none of these columns, WHEN one that sets them to the same values. Unconditional, they would delete
-- and re-insert the unchanged title's trigrams on every status, page count or series change (20k updates
-- of another column: 182 ms against 8 ms), and every recomputeDerived would pay it for its series.
create trigger chapter_fts_ai after insert on chapter begin
    insert into chapter_fts(rowid, title_full, native_title) values (new.id, new.title_full, new.native_title);
end;
create trigger chapter_fts_ad after delete on chapter begin
    insert into chapter_fts(chapter_fts, rowid, title_full, native_title) values ('delete', old.id, old.title_full, old.native_title);
end;
create trigger chapter_fts_au after update of id, title_full, native_title on chapter
when old.id is not new.id or old.title_full is not new.title_full or old.native_title is not new.native_title
begin
    insert into chapter_fts(chapter_fts, rowid, title_full, native_title) values ('delete', old.id, old.title_full, old.native_title);
    insert into chapter_fts(rowid, title_full, native_title) values (new.id, new.title_full, new.native_title);
end;

create trigger series_fts_ai after insert on series begin
    insert into series_fts(rowid, title_full, native_title) values (new.id, new.title_full, new.native_title);
end;
create trigger series_fts_ad after delete on series begin
    insert into series_fts(series_fts, rowid, title_full, native_title) values ('delete', old.id, old.title_full, old.native_title);
end;
create trigger series_fts_au after update of id, title_full, native_title on series
when old.id is not new.id or old.title_full is not new.title_full or old.native_title is not new.native_title
begin
    insert into series_fts(series_fts, rowid, title_full, native_title) values ('delete', old.id, old.title_full, old.native_title);
    insert into series_fts(rowid, title_full, native_title) values (new.id, new.title_full, new.native_title);
end;

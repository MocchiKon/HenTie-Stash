-- Subscriptions (see CLAUDE.md "Subscriptions"): a saved search on a site whose galleries are queued for download
-- on their own, first all of them, then each new one.
-- subscription
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
-- subscription_checkpoint - the head as it was at a time, so a re-check knows where "recheck_depth_hours ago" was
--   in the search; at most one an hour, pruned past the oldest one a re-check still needs.
create table subscription (id integer primary key autoincrement, name varchar(255), source varchar(32) not null, query varchar(1000) not null, poll_minutes integer not null, recheck_every_hours integer not null, recheck_depth_hours integer not null, compression_mode varchar(64) not null default 'NONE', avoid_duplicate_titles boolean not null default 0, cookies_browser varchar(32), download_originals boolean not null default 0, request_delay varchar(32) not null, enabled boolean not null default 1, created_at timestamp not null, revision integer not null default 0, newest_gallery_id varchar(255), oldest_gallery_id varchar(255), oldest_cursor varchar(2000), reached_end boolean not null default 0, catch_up_top varchar(255), catch_up_cursor varchar(2000), catch_up_stop varchar(255), last_checked_at timestamp, last_rechecked_at timestamp, result_total integer, queued_count integer not null default 0, in_library_count integer not null default 0, deleted_since_count integer not null default 0, already_queued_count integer not null default 0, blacklisted_count integer not null default 0, last_error varchar(2000), last_error_at timestamp, failed_steps integer not null default 0);
create table subscription_checkpoint (id integer primary key autoincrement, subscription_id integer not null references subscription (id) on delete cascade, recorded_at timestamp not null, gallery_id varchar(255) not null);
create index ix_subscription_checkpoint__subscription_recorded on subscription_checkpoint (subscription_id, recorded_at);

-- download_queue
--   priority        - who asked: 0 the user (a paste, favourites, a retry of theirs), 1 a subscription. Lower runs
--                     first. Stored values: never renumbered.
--   subscription_id - the subscription that queued the row; SET NULL when it is deleted, so its rows keep their
--                     priority and still download. A paste of the same gallery claims the row (priority 0, NULL).
-- (error, priority, id) serves the worker's pending-in-order query and both lists on the queue page.
-- (gallery_id): every enqueue looks its gallery up.
alter table download_queue add column priority integer not null default 0;
alter table download_queue add column subscription_id integer references subscription (id) on delete set null;
drop index ix_download_queue__error_id;
create index ix_download_queue__error_priority_id on download_queue (error, priority, id);
create index ix_download_queue__gallery_id on download_queue (gallery_id);
create index ix_download_queue__subscription_error on download_queue (subscription_id, error);

-- merge_duplicate_metadata - with the duplicate-title check on, a gallery whose title and language a chapter from
-- another source has gives that chapter its metadata instead of being refused. Chosen per paste and per
-- subscription, like the check itself.
alter table download_queue add column merge_duplicate_metadata boolean not null default 0;
alter table subscription add column merge_duplicate_metadata boolean not null default 0;

-- merged_duplicate - a row whose gallery was merged that way. Not a failure and not a refusal, so it has a list of
-- its own; the row is kept as the record of the merge, and while it is there nothing queues the gallery again.
alter table download_queue add column merged_duplicate boolean not null default 0;

-- The Failed list leaves merged rows out, so its partial index must too, or the list would read the merged rows'
-- entries; the Merged duplicates list gets one of its own, as the Rejected duplicates list has (V4).
drop index ix_download_queue__failed_priority_id;
create index ix_download_queue__failed_priority_id on download_queue (priority, id)
    where error is not null and rejected_duplicate = 0 and merged_duplicate = 0;
create index ix_download_queue__merged_priority_id on download_queue (priority, id)
    where error is not null and merged_duplicate = 1;

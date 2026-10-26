-- rejected_duplicate - a row the duplicate-title check refused. It is not a download failure and is listed
-- apart from the Failed list, so the two lists are counted and read on their own. Partial indexes keep each
-- list in (priority, id) order without visiting the other's rows.
alter table download_queue add column rejected_duplicate boolean not null default 0;
-- Only a refusal carries this sentence, so the row of one moved from the old Failed list: an image failure of a
-- checked row does not.
update download_queue
set rejected_duplicate = 1
where error is not null and avoid_duplicate_titles = 1
  and instr(error, 'was queued to avoid duplicated titles from other sources.') > 0;
create index ix_download_queue__failed_priority_id on download_queue (priority, id)
    where error is not null and rejected_duplicate = 0;
create index ix_download_queue__rejected_priority_id on download_queue (priority, id)
    where error is not null and rejected_duplicate = 1;

-- The title and language a gallery had when it was downloaded: the duplicate-title check also looks at past
-- downloads, so a chapter deleted since cannot come back through another source. Filled from the chapter for
-- rows that existed before this migration; a row whose chapter is gone keeps them null.
alter table downloaded_gallery add column title_full varchar(255);
alter table downloaded_gallery add column language varchar(255);
update downloaded_gallery
set title_full = (select chapter.title_full from chapter where chapter.id = downloaded_gallery.chapter_id),
    language = (select chapter.language from chapter where chapter.id = downloaded_gallery.chapter_id);

-- The lookup is an equality seek on title_full, as the chapter check is; gallery_id rides along.
create index ix_downloaded_gallery__title_full_gallery_id on downloaded_gallery (title_full, gallery_id);

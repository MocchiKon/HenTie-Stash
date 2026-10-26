-- The chapter whose title refused a gallery, so the Rejected duplicates list can link to it. Null when the clash
-- was a past download whose chapter is gone, or when the row's failure was not a refusal.
alter table download_queue add column duplicate_chapter_id integer;

-- Rows refused before the link column existed still name their chapter at the start of the error message,
-- "Chapter 12 (from ...)". Fill the column from it, but only when that chapter still exists: one deleted since
-- would be a link to nothing. A past download's message starts with "Gallery", so it gets no chapter.
update download_queue
set duplicate_chapter_id = (
    select chapter.id from chapter
    where chapter.id = cast(substr(download_queue.error, 9, instr(download_queue.error, ' (') - 9) as integer)
)
where rejected_duplicate = 1
  and duplicate_chapter_id is null
  and error glob 'Chapter [0-9]* (*';

-- favourite - the user's mark on a chapter or series, apart from its status: a favourite can be new, reviewed or
-- (a series) updated. It was status 2; those rows become reviewed favourites, and status 2 is UPDATED from here on,
-- a series that a new chapter joined after it was reviewed. The status CHECK (0 to 2) stays as it is.
alter table chapter add column favourite boolean not null default 0;
alter table series add column favourite boolean not null default 0;
update chapter set favourite = 1, status = 1 where status = 2;
update series set favourite = 1, status = 1 where status = 2;

-- (favourite, id), since SQLite ends every index with the rowid: the favourites in id order, which search reads like
-- a tag's list - a stream to merge, a list to start from when it is short, the page itself by date.
create index ix_chapter__favourite on chapter (favourite);
create index ix_series__favourite on series (favourite);

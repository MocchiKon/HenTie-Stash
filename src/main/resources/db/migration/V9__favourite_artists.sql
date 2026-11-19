-- artist.favourite - the user's mark on an artist (the heart in its chip).
-- chapter.favourite_artist / series.favourite_artist - whether the chapter, or the series through its effective
--     artists, carries an artist marked favourite. Search filters on it like on favourite, so it is materialized on
--     the owner row; the triggers below keep it, never the app. No row needs a value now: no artist is a favourite yet.
alter table artist add column favourite boolean not null default 0;
alter table chapter add column favourite_artist boolean not null default 0;
alter table series add column favourite_artist boolean not null default 0;

-- (favourite_artist, id), since SQLite ends every index with the rowid: read by search like the favourite index.
create index ix_chapter__favourite_artist on chapter (favourite_artist);
create index ix_series__favourite_artist on series (favourite_artist);

-- Triggers, not the app: links change through Hibernate's collections, the native merge and removal in
-- MetadataService, foreign-key cascades and a manual sqlite3 session alike, and a trigger sees every one of them.
-- Each one rewrites an owner row only when its flag is wrong ("set x = not x where x <> <truth>"), so an unchanged
-- flag costs no write and no index update. A missing artist counts as a possible favourite (coalesce ... 1): when a
-- cascade from an artist's delete removes the link, the artist row is already gone, and the flag must be checked.
create trigger chapter_artists_favourite_ai after insert on chapter_artists
when coalesce((select favourite from artist where id = new.artist_id), 1) = 1
begin
    update chapter set favourite_artist = 1 where id = new.chapter_id and favourite_artist = 0;
end;

create trigger chapter_artists_favourite_ad after delete on chapter_artists
when coalesce((select favourite from artist where id = old.artist_id), 1) = 1
begin
    update chapter set favourite_artist = not favourite_artist
    where id = old.chapter_id and favourite_artist <> exists (select 1 from chapter_artists l
        join artist a on a.id = l.artist_id where l.chapter_id = old.chapter_id and a.favourite = 1);
end;

create trigger chapter_artists_favourite_au after update of artist_id, chapter_id on chapter_artists
when old.chapter_id is not new.chapter_id
    or coalesce((select favourite from artist where id = old.artist_id), 1) = 1
    or coalesce((select favourite from artist where id = new.artist_id), 1) = 1
begin
    update chapter set favourite_artist = not favourite_artist
    where id in (old.chapter_id, new.chapter_id) and favourite_artist <> exists (select 1 from chapter_artists l
        join artist a on a.id = l.artist_id where l.chapter_id = chapter.id and a.favourite = 1);
end;

create trigger series_eff_artists_favourite_ai after insert on series_effective_artists
when coalesce((select favourite from artist where id = new.artist_id), 1) = 1
begin
    update series set favourite_artist = 1 where id = new.series_id and favourite_artist = 0;
end;

create trigger series_eff_artists_favourite_ad after delete on series_effective_artists
when coalesce((select favourite from artist where id = old.artist_id), 1) = 1
begin
    update series set favourite_artist = not favourite_artist
    where id = old.series_id and favourite_artist <> exists (select 1 from series_effective_artists l
        join artist a on a.id = l.artist_id where l.series_id = old.series_id and a.favourite = 1);
end;

create trigger series_eff_artists_favourite_au after update of artist_id, series_id on series_effective_artists
when old.series_id is not new.series_id
    or coalesce((select favourite from artist where id = old.artist_id), 1) = 1
    or coalesce((select favourite from artist where id = new.artist_id), 1) = 1
begin
    update series set favourite_artist = not favourite_artist
    where id in (old.series_id, new.series_id) and favourite_artist <> exists (select 1 from series_effective_artists l
        join artist a on a.id = l.artist_id where l.series_id = series.id and a.favourite = 1);
end;

-- Marking or unmarking an artist rechecks only its own chapters and series, found through the (artist_id, owner_id)
-- indexes; the check reads each one's few artists through (owner_id, artist_id). The WHEN skips Hibernate's updates
-- that name the column without changing it (Artist is not @DynamicUpdate, so a rename writes it too).
create trigger artist_favourite_au after update of favourite on artist
when old.favourite is not new.favourite
begin
    update chapter set favourite_artist = not favourite_artist
    where id in (select chapter_id from chapter_artists where artist_id = new.id)
        and favourite_artist <> exists (select 1 from chapter_artists l join artist a on a.id = l.artist_id
            where l.chapter_id = chapter.id and a.favourite = 1);
    update series set favourite_artist = not favourite_artist
    where id in (select series_id from series_effective_artists where artist_id = new.id)
        and favourite_artist <> exists (select 1 from series_effective_artists l join artist a on a.id = l.artist_id
            where l.series_id = series.id and a.favourite = 1);
end;

-- Matching by native title (see service/match): a translated chapter finds its original, and two romanizations of
-- one work find each other, through the Japanese title both carry.
-- chapter.native_match_key - native_title normalized like match_key (TitleKey.nativeKey); for a chapter without a
--     native title whose own title is written in Japanese, Chinese or Korean, that title's key. '' when there is
--     nothing to compare.
-- (native_match_key, series_id) - matching seeks chapters by this key for the series they are in, so the series comes
--     off the index: a series' many chapters under one key cost index entries, not rows.
alter table chapter add column native_match_key varchar(255) not null default '';
create index ix_chapter__native_match_key on chapter (native_match_key, series_id);

-- The nearest-title walk over series (service/match/NearestTitles), as "Link chapters" walks chapters through
-- ix_chapter__condensed_match_key: the same seek in both directions, so automatic matching finds what the series page
-- offers. Not a block column (series.match_block, dropped here): capped by id, a crowded block would yield its oldest
-- series rather than the nearest titles.
create index ix_series__condensed_match_key on series (replace(match_key, ' ', ''), id);
drop index ix_series__match_block;
alter table series drop column match_block;

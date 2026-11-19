-- quality - how much of the source's image a mode keeps, 0 the best, as priorities count. Image compression rules
-- pick the best quality among the modes they choose between, and only a strictly better one is worth downloading a
-- chapter again for. Modes that existed before get 1, what a new mode starts at: below None and Lossless (0), above
-- the built-in reductions (100 and 1000, image-compression-modes.properties).
alter table image_compression_mode add column quality integer not null default 1;

-- allow_upgrade - a paste with a mode picked by hand may download a gallery already in the library again, when its
-- chapter's pages were compressed with a worse mode. A row queued "By rules" (compression_mode 'RULES') never sets
-- it: the rules decide that too.
alter table download_queue add column allow_upgrade boolean not null default 0;

-- Subscriptions no longer choose a mode: what they queue is downloaded "By rules". Rows they queued before keep the
-- mode they were queued with.
alter table subscription drop column compression_mode;
-- upgrade_count - galleries in the library the subscription queued again, because a rule gives them a better mode
alter table subscription add column upgrade_count integer not null default 0;

-- image_compression_rule - which mode a gallery queued "By rules" gets, by the metadata of its chapter.
--   priority         - 0 is the highest; only the matching rules with the lowest number count
--   compression_mode - a mode key (LOSSLESS, custom:7, ...); a key that no longer resolves means None
--   allow_upgrade    - a chapter already in the library whose pages a worse mode compressed is downloaded again
--   condition_count  - how many values the rule was saved with; a gallery must carry every one. Not lowered when a
--                      value is removed (its condition goes with it, by the cascade), so the rule never matches
--                      again instead of matching galleries the user never named. 0 = every gallery.
-- image_compression_rule_<kind> - a rule's conditions, one table per metadata kind like chapter_<kind>, so a value is
--     an id: renaming it keeps the rule, merging it moves the rule along (MetadataService), and the foreign keys
--     cascade both ways.
create table image_compression_rule (id integer primary key autoincrement, priority integer not null default 1, compression_mode varchar(64) not null, allow_upgrade boolean not null default 0, condition_count integer not null default 0);
create table image_compression_rule_tags (rule_id integer not null references image_compression_rule (id) on delete cascade, tag_id integer not null references tag (id) on delete cascade);
create table image_compression_rule_artists (rule_id integer not null references image_compression_rule (id) on delete cascade, artist_id integer not null references artist (id) on delete cascade);
create table image_compression_rule_characters (rule_id integer not null references image_compression_rule (id) on delete cascade, character_id integer not null references character (id) on delete cascade);
create table image_compression_rule_parodies (rule_id integer not null references image_compression_rule (id) on delete cascade, parody_id integer not null references parody (id) on delete cascade);
create table image_compression_rule_groups (rule_id integer not null references image_compression_rule (id) on delete cascade, group_id integer not null references group_artists (id) on delete cascade);
create table image_compression_rule_categories (rule_id integer not null references image_compression_rule (id) on delete cascade, category_id integer not null references category (id) on delete cascade);

-- (meta_id, rule_id) is covering for matching (a chapter's values to the rules naming them) and serves merges and the
-- cascade when a value is removed. (rule_id, meta_id) loads a rule's conditions and serves the cascade when a rule is
-- deleted; UNIQUE, so a rule cannot count one value twice (a merge drops the duplicate first).
create index ix_compression_rule_tags__tag_rule on image_compression_rule_tags (tag_id, rule_id);
create unique index ux_compression_rule_tags__rule_tag on image_compression_rule_tags (rule_id, tag_id);
create index ix_compression_rule_artists__artist_rule on image_compression_rule_artists (artist_id, rule_id);
create unique index ux_compression_rule_artists__rule_artist on image_compression_rule_artists (rule_id, artist_id);
create index ix_compression_rule_characters__character_rule on image_compression_rule_characters (character_id, rule_id);
create unique index ux_compression_rule_characters__rule_character on image_compression_rule_characters (rule_id, character_id);
create index ix_compression_rule_parodies__parody_rule on image_compression_rule_parodies (parody_id, rule_id);
create unique index ux_compression_rule_parodies__rule_parody on image_compression_rule_parodies (rule_id, parody_id);
create index ix_compression_rule_groups__group_rule on image_compression_rule_groups (group_id, rule_id);
create unique index ux_compression_rule_groups__rule_group on image_compression_rule_groups (rule_id, group_id);
create index ix_compression_rule_categories__category_rule on image_compression_rule_categories (category_id, rule_id);
create unique index ux_compression_rule_categories__rule_category on image_compression_rule_categories (rule_id, category_id);

-- The rules page lists rules in (priority, id) order; condition_count = 0 finds the rules for every gallery, which
-- each decision reads.
create index ix_image_compression_rule__priority_id on image_compression_rule (priority, id);
create index ix_image_compression_rule__condition_count on image_compression_rule (condition_count);

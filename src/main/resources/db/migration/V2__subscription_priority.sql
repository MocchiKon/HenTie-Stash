-- A subscription's priority: lower numbers are handled first, 0 is the highest. The default keeps every
-- subscription that existed before this migration below one the user promotes, and gives new ones the same
-- starting point. NOT NULL is safe here because the default fills the existing rows.
alter table subscription add column priority integer not null default 1;

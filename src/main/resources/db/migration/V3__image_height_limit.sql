-- ImageMagick's arguments are applied only to images taller than this. Null (the default) means every image
-- the mode selects goes through ImageMagick.
alter table image_compression_mode add column image_height_limit integer;

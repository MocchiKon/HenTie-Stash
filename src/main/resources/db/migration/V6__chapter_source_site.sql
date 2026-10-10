-- chapter.source_site - which of its source's websites a chapter's pages last came from, for a source with more
--     than one (exhentai for e-hentai's galleries); null for the source's usual one. The chapter page links there:
--     exhentai has galleries e-hentai hides.
alter table chapter add column source_site varchar(32);

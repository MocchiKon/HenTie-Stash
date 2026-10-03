-- The gallery-dl choices of a paste (see CLAUDE.md "gallery-dl"), on the row like compression_mode because the
-- download runs later, on the worker, and has to survive a restart. Re-pasting a link replaces them, a retry
-- keeps them. Only gallery-dl sources read them.
--   cookies_browser    - the browser gallery-dl reads cookies from (--cookies-from-browser); null = no cookies
--   download_originals - e-hentai's original files instead of the resampled ones
--   request_delay      - seconds between two image requests, "0.4-0.65" or "2". SQLite adds a NOT NULL
--                        column only with a default; '' is no delay gallery-dl reads, so a row holding it is
--                        refused rather than fetched without a delay
alter table download_queue add column cookies_browser varchar(32);
alter table download_queue add column download_originals boolean not null default 0;
alter table download_queue add column request_delay varchar(32) not null default '';

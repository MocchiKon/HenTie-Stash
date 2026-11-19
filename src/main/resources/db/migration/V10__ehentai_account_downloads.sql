-- e-hentai downloads send the e-hentai account from Settings, so the browser a row or a subscription read cookies
-- from, and Settings' default browser, are gone.
alter table download_queue drop column cookies_browser;
alter table subscription drop column cookies_browser;
delete from app_setting where setting_key = 'gallery-dl.cookies-browser';

-- needs_login - the row failed because the site shows the gallery only to an account it accepts. It goes back to
--     waiting by itself once the site accepts the account. Read only on a failed row.
alter table download_queue add column needs_login boolean not null default 0;

-- Rows that failed that way before: with browser cookies that held no exhentai login, gallery-dl quietly switched
-- to e-hentai.org, where a gallery it hides is "not available" or "not found". Only the app's own messages are
-- read, and only an e-hentai row whose message names exhentai.org (it was tried there with cookies) or says the
-- gallery needs a login. A ban, an image limit or missing GP is left alone: a login fixes none of them.
update download_queue
set needs_login = 1
where error is not null
  and rejected_duplicate = 0
  and substr(gallery_id, 1, 8) = 'ehentai:'
  and instr(error, 'Temporarily Banned') = 0
  and instr(error, 'temporarily banned') = 0
  and instr(error, 'mage limit') = 0
  and instr(error, 'Not enough GP') = 0
  and (instr(error, 'only to a logged-in account') > 0
    or instr(error, 'exhentai.org refused the cookies') > 0
    or (instr(error, 'https://exhentai.org/g/') > 0
        and (instr(error, 'could not be found') > 0 or instr(error, 'NotFoundError') > 0
             or instr(error, 'AuthorizationError') > 0)));

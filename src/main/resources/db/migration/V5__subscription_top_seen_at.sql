-- subscription.top_seen_at - when a check last saw the top of the search (service/subscription/SubscriptionWalk):
--     the time a catch-up's checkpoint is recorded with, and how a check tells that the walk lost sight of the search
--     (the app was off, or the site failed, for longer than a re-check reaches back). NULL before the first check.
alter table subscription add column top_seen_at timestamp;

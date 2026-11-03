-- Folds the FAVORITE deal state into pinned_at, so the app has one way to mark a row.
--
-- Feed rows were favorited (`state = FAVORITE`, V14); Tracked and grocery rows were pinned
-- (`pinned_at`). The two never met on one row, but they were the same gesture -- "keep this, I
-- care about it" -- under two names, two buttons and two storage columns. pinned_at is the one
-- kept: it is a timestamp rather than a value of a single-valued column, so pinning no longer
-- competes with NEW/SEEN/DISMISSED, and HistoryPruner already keeps every reading of a pinned row.
--
-- A favorite comes off as SEEN, the state un-favoriting always wrote: you have seen it.
--
-- ## Ordering is load-bearing
--
-- As in V14: the rows must leave FAVORITE before a constraint without it can be added.
UPDATE deals SET pinned_at = COALESCE(pinned_at, now()), state = 'SEEN' WHERE state = 'FAVORITE';

ALTER TABLE deals DROP CONSTRAINT deals_state_check;
ALTER TABLE deals ADD CONSTRAINT deals_state_check CHECK (state IN ('NEW', 'SEEN', 'DISMISSED'));

-- The pinned feed screen reads deals_pinned_idx instead.
DROP INDEX IF EXISTS deals_favorite_idx;

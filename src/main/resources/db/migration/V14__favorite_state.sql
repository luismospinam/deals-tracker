-- Renames the SAVED deal state to FAVORITE, and indexes it.
--
-- ## Why rename rather than add a column
--
-- `SAVED` already did most of what "favorite" means: it survives every later poll, because
-- `upsertDeal` deliberately never touches `state` on conflict. What it lacked was a way back
-- (the button only ever wrote SAVED, so a second click was a no-op) and a screen of its own.
-- Those are UI gaps, not schema gaps, so a second boolean column would have left two
-- overlapping ways to mark the same row and no answer for which one the State filter means.
--
-- The name is the part that had to change. `state` is exposed as a value in three places --
-- the feed's State dropdown, the Excel export's State column, and the query string -- so a
-- column storing SAVED under a UI saying "favorite" would be a discrepancy visible to the
-- operator rather than an internal detail.
--
-- ## Ordering is load-bearing
--
-- Existing SAVED rows violate the new constraint, so it cannot be added first: DROP, then
-- UPDATE, then ADD. `deals_state_check` is the name V1 gave the constraint explicitly, which
-- is what makes it droppable by name rather than by hunting Postgres's generated identifier.
ALTER TABLE deals DROP CONSTRAINT deals_state_check;

UPDATE deals SET state = 'FAVORITE' WHERE state = 'SAVED';

ALTER TABLE deals ADD CONSTRAINT deals_state_check
    CHECK (state IN ('NEW', 'SEEN', 'DISMISSED', 'FAVORITE'));

-- The favorites screen is the one query in the app that does NOT filter on is_active: a
-- favorite whose offer ended is still worth showing, marked expired, because it vanishing
-- silently reads as "it was never saved" rather than "the deal is gone". That means it cannot
-- use `deals_feed_idx`, which is partial on `WHERE is_active`.
--
-- Partial on the value, following V4's category_group and V12's brand: favorites are a small
-- minority of a table where most rows are still NEW, so indexing the whole column would be
-- mostly wasted pages.
CREATE INDEX deals_favorite_idx ON deals (state) WHERE state = 'FAVORITE';

-- Provenance and retry state for the Colombia shipping verdict.
--
-- WHY: co_shipping alone is UNKNOWN/YES/NO, which cannot express "we checked and could not
-- tell". A failed check was therefore indistinguishable from one never attempted, so a
-- batch would re-check the same broken deals on every single cycle, forever.
--
-- Two properties these columns exist to guarantee:
--   1. A verdict set BY HAND is never overwritten by automation. Manual verification is
--      expensive human effort and must survive every later poll.
--   2. Repeated failures back off instead of being retried immediately.

-- Who decided the current verdict. NULL means nobody has: the row is still at its
-- ingest-time default and has never been touched.
ALTER TABLE deals ADD COLUMN co_shipping_source TEXT
    CONSTRAINT deals_co_shipping_source_check
    CHECK (co_shipping_source IN ('MANUAL', 'AUTO'));

-- When the last check ran, successful or not. NULL = never attempted, which is what makes
-- "not checked" distinguishable from "checked, no verdict".
ALTER TABLE deals ADD COLUMN co_shipping_checked_at TIMESTAMPTZ;

-- Consecutive inconclusive/failed attempts. Drives exponential backoff and is reset to 0
-- the moment a definite verdict is reached.
ALTER TABLE deals ADD COLUMN co_shipping_attempts INTEGER NOT NULL DEFAULT 0;

-- The page text the verdict rests on, so any stored result can be audited later without
-- re-fetching. Capped by the writer, not here.
ALTER TABLE deals ADD COLUMN co_shipping_evidence TEXT;

-- Whether Amazon stated that import charges are already covered. Bears directly on whether
-- a deal is actually worth it once landed in Colombia. NULL = Amazon said nothing.
ALTER TABLE deals ADD COLUMN co_shipping_import_free BOOLEAN;

-- Backfill: existing YES/NO verdicts came from Luis clicking the buttons in the UI, so they
-- are MANUAL and must be protected from automation on the very first batch run.
UPDATE deals
   SET co_shipping_source = 'MANUAL',
       co_shipping_checked_at = now()
 WHERE co_shipping <> 'UNKNOWN';

-- Candidate selection scans for unchecked/retryable rows, so index the columns it filters on.
CREATE INDEX deals_co_shipping_retry_idx
    ON deals (co_shipping_checked_at NULLS FIRST, co_shipping_attempts)
    WHERE co_shipping = 'UNKNOWN';

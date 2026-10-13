-- What a visit to an item's own product page established, for rows found by search.
--
-- WHY: the `amazon` provider reads size, price and stock from search TILES. Amazon's size filter
-- returns a representative of the product family, not strictly the matching child, so a tile can
-- carry the wrong size (and that size's price). The only time the app opens a product page is the
-- shipping verifier, and that page states the ASIN's own size and buy-box price. These columns
-- record that one authoritative reading.
--
-- page_checked_at   when a page last confirmed (or refuted) the size. NULL = never visited, which
--                   is what makes a row a candidate. A size is a fact about an ASIN and never
--                   changes, so one visit settles it; this is not a refresh schedule.
-- page_size         the size the page itself reported, kept so a rejection can be audited.
-- page_attempts     consecutive visits that read nothing usable, for the same exponential backoff
--                   the shipping check uses. page_attempted_at is the last of them.
-- size_rejected     the page said this ASIN is NOT the size being tracked. Permanent for the row:
--                   the next poll's search will return the same ASIN again, and upsertDeal would
--                   otherwise set is_active back to TRUE every hour.
ALTER TABLE deals
    ADD COLUMN page_checked_at    TIMESTAMPTZ,
    ADD COLUMN page_size          TEXT,
    ADD COLUMN page_attempts      INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN page_attempted_at  TIMESTAMPTZ,
    ADD COLUMN size_rejected      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN size_reject_reason TEXT;

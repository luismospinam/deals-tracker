-- The price a row's own product page last showed. A search tile's price for the same ASIN often differs
-- (median 11% over 305 rows in 14 days) and the page is the one that matches the item, but the next
-- hourly tile reading used to put the tile price straight back (222 of 316 corrections). Ingest now
-- ignores a tile price that disagrees with this until the page is read again.
ALTER TABLE deals
    ADD COLUMN page_price          numeric(14,2),
    ADD COLUMN page_price_currency text;

-- Backfill from the corrections already in the history: a page read that changed the price leaves an
-- observation within 6 minutes of the reading before it. Rows whose page read agreed with the tile left
-- no mark, and get their page price at the next read.
UPDATE deals d
   SET page_price = c.price, page_price_currency = c.currency
  FROM (
        SELECT DISTINCT ON (deal_id) deal_id, price, currency
          FROM (
                SELECT deal_id, observed_at, price, currency,
                       lag(price) OVER w AS prev_price, lag(observed_at) OVER w AS prev_at
                  FROM price_observations
                WINDOW w AS (PARTITION BY deal_id ORDER BY observed_at)
               ) o
         WHERE prev_price IS NOT NULL AND price <> prev_price AND observed_at - prev_at < interval '6 minutes'
         ORDER BY deal_id, observed_at DESC
       ) c
 WHERE d.id = c.deal_id AND d.source = 'amazon';

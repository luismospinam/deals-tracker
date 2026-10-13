-- A grocery item's "usual" price, kept on the item and rebuilt once a day.
--
-- WHY: /groceries computed each row's usual price (median of its daily cheapest readings over the 30
-- days before today) on every page load, three times over -- the table, the digest, and the digest's
-- row lookup -- about 2.5s together. The window is [today - 30, today), so the answer cannot change
-- until the date does. It is refreshed by GroceryRepository.refreshUsualPrices, which the scheduler
-- runs on its short tick and which does nothing once a row's usual_as_of is today.
--
-- usual_as_of marks which day a row was computed for. A row whose date is not today is still shown (a
-- day-old median is close) until the next tick rebuilds it.
ALTER TABLE deals
    ADD COLUMN usual_price NUMERIC(14,2),
    ADD COLUMN usual_low   NUMERIC(14,2),
    ADD COLUMN usual_days  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN usual_as_of DATE;

UPDATE deals d
   SET usual_price = h.usual,
       usual_low   = h.low,
       usual_days  = COALESCE(h.days, 0),
       usual_as_of = current_date
  FROM deals g
  LEFT JOIN LATERAL (
        SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY price) AS usual,
               MIN(price) AS low, COUNT(*) AS days
          FROM (SELECT observed_at::date, MIN(price) AS price
                  FROM price_observations
                 WHERE deal_id = g.id
                   AND observed_at >= current_date - 30
                   AND observed_at < current_date
                 GROUP BY 1) daily
       ) h ON TRUE
 WHERE g.id = d.id AND g.grocery_group IS NOT NULL;

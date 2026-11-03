-- Each deal's current card-only price and its lowest card price ever, kept on the deal itself, and a
-- third watch rule that may read them.
--
-- ## Why on the deal
--
-- V17 put card prices on the observation, which is still where they live: these promotions change
-- daily, and the history is the point. But the feed, the deal page's header and the Excel export
-- read `deals`, and asking the latest observation for every row the feed sorts is the cost V27 was
-- written to remove. So the latest reading's card columns, and the lowest card price recorded, ride
-- along on the deal -- maintained by the same trigger as obs_min_price, for the same reason: readings
-- are inserted from several places and a summary one of them forgot would be wrong silently.
--
-- "Current" means the latest INSERTED reading, which is the latest in time: every writer inserts
-- now(). The one writer that inserts no card columns is the product-page price correction
-- (DealRepository.recordPageCheck), which only runs on Amazon rows -- and Amazon publishes no card
-- prices, so its null overwrites nothing.
ALTER TABLE deals
    ADD COLUMN card_price          NUMERIC(14,2),
    ADD COLUMN card_percent        NUMERIC(5,2),
    ADD COLUMN card_scope          TEXT,
    ADD COLUMN obs_min_card_price  NUMERIC(14,2);

UPDATE deals d
   SET card_price = l.card_price, card_percent = l.card_percent, card_scope = l.card_scope
  FROM (SELECT DISTINCT ON (deal_id) deal_id, card_price, card_percent, card_scope
          FROM price_observations ORDER BY deal_id, observed_at DESC, id DESC) l
 WHERE l.deal_id = d.id AND l.card_price IS NOT NULL;

UPDATE deals d
   SET obs_min_card_price = a.m
  FROM (SELECT deal_id, MIN(card_price) AS m FROM price_observations
         WHERE card_price IS NOT NULL GROUP BY deal_id) a
 WHERE a.deal_id = d.id;

CREATE OR REPLACE FUNCTION price_observation_summary() RETURNS trigger AS $$
BEGIN
    UPDATE deals
       SET obs_min_price      = LEAST(COALESCE(obs_min_price, NEW.price), NEW.price),
           obs_max_price      = GREATEST(COALESCE(obs_max_price, NEW.price), NEW.price),
           obs_count          = obs_count + 1,
           card_price         = NEW.card_price,
           card_percent       = NEW.card_percent,
           card_scope         = NEW.card_scope,
           -- LEAST ignores NULLs, so a reading with no card price leaves the minimum alone.
           obs_min_card_price = LEAST(obs_min_card_price, NEW.card_price)
     WHERE id = NEW.deal_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- CARD_LOW: a new low at the price a card the operator holds pays (WatchEvaluator.cardLow). Opt-in
-- through the cards picked on /watches; with none picked it never fires.
ALTER TABLE watch_matches DROP CONSTRAINT watch_matches_reason_check;
ALTER TABLE watch_matches ADD CONSTRAINT watch_matches_reason_check
    CHECK (reason IN ('LOWEST_IN_WINDOW', 'CROSS_SITE_SPREAD', 'CARD_LOW'));

-- Which card a CARD_LOW alert's price is paid with, so the digest can say "new low with CMR" from the
-- ledger alone. Null on the cash rules.
ALTER TABLE watch_matches ADD COLUMN card_scope TEXT;

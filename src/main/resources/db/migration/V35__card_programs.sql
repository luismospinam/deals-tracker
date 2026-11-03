-- Every card offer on a reading, two more cards (Visa, Addi), and a list of the cards the crawls find.
--
-- ## Why
--
-- "Cards I hold" became a setting for every tab, and its choices must be the cards the stores actually
-- run offers on. Measured 2026-11-03, the data could not answer that:
--
--  - **Only the cheapest offer was kept.** VtexSource.cardOfferOf took the minimum across teasers, so
--    Exito's 423 L Electrolux (1,899,000 with Visa, 1,999,000 with Addi) recorded Visa's price only --
--    and someone holding Addi alone saw a price they cannot pay.
--  - **Three programmes were an anonymous "Card".** Exito's `*_aliado_tabladeprecios_*` price tables
--    name payment ids 2/209 (Visa), 4/210 (Mastercard) and 102 (Addi); Easy's `SW T. INT ADDI ...`
--    teasers name 102. The price-table path never read the ids, so 11,170 readings were OTHER.
--
-- ## What changes
--
-- `price_observations.card_offers` holds every offer, best per card: [{"scope","price","percent"}].
-- card_price/percent/scope stay the cheapest of them, so every reader of V17's columns is unchanged.
-- `deals.card_offers` (the latest reading's) and `deals.card_lows` ({"CMR": 2179900}, the lowest per
-- card) ride on the deal by the V27/V34 trigger, and `card_programs` records each (card, store) pair
-- any reading has carried -- the list the "My cards" page offers.
ALTER TABLE price_observations DROP CONSTRAINT price_observations_card_scope_check;
ALTER TABLE price_observations ADD CONSTRAINT price_observations_card_scope_check
    CHECK (card_scope IN ('MASTERCARD', 'STORE_CARD', 'OTHER', 'CMR', 'TMC', 'CODENSA', 'VISA', 'ADDI'));

ALTER TABLE price_observations ADD COLUMN card_offers JSONB;
ALTER TABLE deals ADD COLUMN card_offers JSONB, ADD COLUMN card_lows JSONB;

-- ## Naming the old OTHER readings, where the evidence is unambiguous
--
-- A reading links the promotions live on it (V18), and a CARD promotion keeps the payment ids it named.
-- Where exactly one CARD promotion is linked -- and, when it publishes a percentage, that percentage is
-- the reading's -- the card price can only be that promotion's, so its ids name the card. Measured:
-- 4,615 Easy readings are Addi, 467 Exito ones Addi or Mastercard, 23 grocery ones Visa. Readings with
-- two card promotions (Visa and Addi together) or none linked stay OTHER: guessing would be worse.
WITH single AS (
    SELECT po.id, MIN(p.card_ids) AS ids
      FROM price_observations po
      JOIN observation_promotions op ON op.observation_id = po.id
      JOIN promotions p ON p.id = op.promotion_id AND p.kind = 'CARD'
     WHERE po.card_scope = 'OTHER'
     GROUP BY po.id
    HAVING COUNT(*) = 1 AND bool_and(p.percent IS NULL OR abs(p.percent - po.card_percent) < 0.01)
)
UPDATE price_observations po
   SET card_scope = CASE
           WHEN s.ids ~ '(^|,)(4|210)(,|$)' THEN 'MASTERCARD'
           WHEN s.ids ~ '^(2|209)(,(2|209))*$' THEN 'VISA'
           WHEN s.ids = '102' THEN 'ADDI'
           ELSE 'OTHER'
       END
  FROM single s
 WHERE po.id = s.id;

-- The deal's current card columns (V34) again, now that some readings were renamed.
UPDATE deals d
   SET card_scope = l.card_scope,
       card_offers = jsonb_build_array(jsonb_build_object('scope', l.card_scope, 'price', l.card_price, 'percent', l.card_percent))
  FROM (SELECT DISTINCT ON (deal_id) deal_id, card_price, card_percent, card_scope
          FROM price_observations ORDER BY deal_id, observed_at DESC, id DESC) l
 WHERE l.deal_id = d.id AND l.card_price IS NOT NULL;

UPDATE deals d
   SET card_lows = a.lows
  FROM (SELECT deal_id, jsonb_object_agg(card_scope, m) AS lows
          FROM (SELECT deal_id, card_scope, MIN(card_price) AS m FROM price_observations
                 WHERE card_price IS NOT NULL GROUP BY deal_id, card_scope) per
         GROUP BY deal_id) a
 WHERE a.deal_id = d.id;

CREATE TABLE card_programs (
    scope         TEXT        NOT NULL,
    source        TEXT        NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    readings      BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (scope, source)
);

INSERT INTO card_programs (scope, source, first_seen_at, last_seen_at, readings)
SELECT po.card_scope, d.source, MIN(po.observed_at), MAX(po.observed_at), COUNT(*)
  FROM price_observations po JOIN deals d ON d.id = po.deal_id
 WHERE po.card_scope IS NOT NULL
 GROUP BY po.card_scope, d.source;

CREATE OR REPLACE FUNCTION price_observation_summary() RETURNS trigger AS $$
DECLARE
    offers JSONB;
    src    TEXT;
BEGIN
    -- A writer that sets only V17's columns (or a reading from before V35) still counts as one offer.
    offers := COALESCE(
        NEW.card_offers,
        CASE WHEN NEW.card_price IS NOT NULL THEN
            jsonb_build_array(jsonb_build_object('scope', NEW.card_scope, 'price', NEW.card_price, 'percent', NEW.card_percent))
        END
    );
    UPDATE deals
       SET obs_min_price      = LEAST(COALESCE(obs_min_price, NEW.price), NEW.price),
           obs_max_price      = GREATEST(COALESCE(obs_max_price, NEW.price), NEW.price),
           obs_count          = obs_count + 1,
           card_price         = NEW.card_price,
           card_percent       = NEW.card_percent,
           card_scope         = NEW.card_scope,
           obs_min_card_price = LEAST(obs_min_card_price, NEW.card_price),
           card_offers        = offers,
           card_lows          = CASE WHEN offers IS NULL THEN card_lows ELSE
               COALESCE(card_lows, '{}'::jsonb) || (
                   SELECT jsonb_object_agg(e->>'scope', LEAST((e->>'price')::numeric, (card_lows->>(e->>'scope'))::numeric))
                     FROM jsonb_array_elements(offers) e
               )
           END
     WHERE id = NEW.deal_id
    RETURNING source INTO src;

    IF offers IS NOT NULL AND src IS NOT NULL THEN
        INSERT INTO card_programs (scope, source, first_seen_at, last_seen_at, readings)
        SELECT e->>'scope', src, NEW.observed_at, NEW.observed_at, 1 FROM jsonb_array_elements(offers) e
        ON CONFLICT (scope, source) DO UPDATE
           SET last_seen_at = GREATEST(card_programs.last_seen_at, EXCLUDED.last_seen_at),
               readings     = card_programs.readings + 1;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

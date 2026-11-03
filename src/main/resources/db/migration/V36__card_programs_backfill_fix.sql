-- Undoes part of V35's renaming of old OTHER card prices: the Exito price-table readings.
--
-- V35 named a reading's card from its one linked CARD promotion. At Exito that was not enough: the
-- 423 L Electrolux carried `addi_aliado_tabladeprecios_*` (CARD, payment id 102) AND
-- `exito1_aliado_tabladeprecios_*`, a bank BIN table that names no payment id and so is not a CARD
-- promotion at all. Its 1,899,000 was the stored card price, and V35 called it Addi -- whose own price
-- is 1,999,000. A price-table promotion publishes no percentage, so nothing on the reading can say
-- which of the two it was.
--
-- So an Exito reading V35 renamed goes back to OTHER wherever any unnamed price table rode with it:
-- 419 of them. Easy's 4,623 Addi readings stand: there the reading's percentage equals the Addi
-- teaser's exactly, and Easy's BIN teasers carry percentages the parser never turns into a price.
-- Readings taken since V35 are not touched -- they keep every offer under its own card.
UPDATE price_observations po
   SET card_scope = 'OTHER'
  FROM deals d
 WHERE d.id = po.deal_id
   AND d.source = 'exito'
   AND po.card_offers IS NULL
   AND po.card_scope IN ('ADDI', 'VISA', 'MASTERCARD')
   AND EXISTS (
       SELECT 1 FROM observation_promotions op JOIN promotions p ON p.id = op.promotion_id
        WHERE op.observation_id = po.id AND p.kind = 'OTHER' AND p.name ILIKE '%tabladeprecios%'
   );

-- The Exito deals' summaries, rebuilt from their readings (V35's shapes).
UPDATE deals d
   SET card_scope = l.card_scope,
       card_offers = COALESCE(l.card_offers,
           jsonb_build_array(jsonb_build_object('scope', l.card_scope, 'price', l.card_price, 'percent', l.card_percent)))
  FROM (SELECT DISTINCT ON (deal_id) deal_id, card_price, card_percent, card_scope, card_offers
          FROM price_observations ORDER BY deal_id, observed_at DESC, id DESC) l
 WHERE l.deal_id = d.id AND d.source = 'exito' AND l.card_price IS NOT NULL;

UPDATE deals d
   SET card_lows = a.lows
  FROM (SELECT deal_id, jsonb_object_agg(scope, m) AS lows
          FROM (SELECT po.deal_id, COALESCE(e->>'scope', po.card_scope) AS scope,
                       MIN(COALESCE((e->>'price')::numeric, po.card_price)) AS m
                  FROM price_observations po
                  JOIN deals x ON x.id = po.deal_id AND x.source = 'exito'
                  LEFT JOIN LATERAL jsonb_array_elements(po.card_offers) e ON TRUE
                 WHERE po.card_price IS NOT NULL
                 GROUP BY 1, 2) per
         GROUP BY deal_id) a
 WHERE a.deal_id = d.id;

-- And Exito's rows in the card list, recounted.
DELETE FROM card_programs WHERE source = 'exito';
INSERT INTO card_programs (scope, source, first_seen_at, last_seen_at, readings)
SELECT COALESCE(e->>'scope', po.card_scope), 'exito', MIN(po.observed_at), MAX(po.observed_at), COUNT(*)
  FROM price_observations po
  JOIN deals d ON d.id = po.deal_id AND d.source = 'exito'
  LEFT JOIN LATERAL jsonb_array_elements(po.card_offers) e ON TRUE
 WHERE po.card_scope IS NOT NULL
 GROUP BY 1;

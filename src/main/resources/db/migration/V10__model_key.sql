-- Second identity key, so the two tiers can join each other.
--
-- WHY: V8 stored one key per deal, preferring a validated GTIN and falling back to
-- brand+model. That is fine within a retailer but breaks *between* them, because Colombian
-- retailers do not agree on which they publish:
--
--   exito / carulla / easy      publish real GS1 EANs  -> 'gtin:0757638435765'
--   falabella / homecenter      publish no barcode     -> 'mdl:mabe|msl480lmlps0'
--
-- The same MABE side-by-side is listed at all four, and with a single key it produced two
-- different values and never clustered. Measured on the live feed: 119 multi-source clusters
-- existed, and *not one* joined a Falabella-group store to an Exito-group store — which is
-- most of the value of having added them, since a comparison inside one corporate group is
-- weak evidence (one banner running a promotion the other is not).
--
-- So both keys are stored, and two listings are the same product if they share EITHER. The
-- GTIN key stays the stronger one; the model key is what lets a barcode-publishing retailer
-- be compared against one that publishes none.
ALTER TABLE deals ADD COLUMN model_key TEXT;

-- Backfill: match_key already holds the model-tier value wherever no valid GTIN was found,
-- so those rows are simply copied. Rows keyed by GTIN cannot be backfilled here -- brand is
-- not a column on deals -- and acquire a model_key on their next poll.
UPDATE deals SET model_key = match_key WHERE match_key LIKE 'mdl:%';

CREATE INDEX deals_model_key_idx ON deals (model_key) WHERE model_key IS NOT NULL;

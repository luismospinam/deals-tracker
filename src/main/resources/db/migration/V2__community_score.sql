-- Community deal-quality score from the source, where it publishes one.
--
-- Slickdeals carries a "Thumb Score" on every RSS item (observed range -14..+384,
-- median 20). It is the only signal that answers "is this actually a deal?" for that
-- source, because Slickdeals publishes no list price -- so no discount percentage can
-- be computed and the discount column is empty for every one of its rows.
--
-- Nullable on purpose: VTEX (Exito, Carulla) and MercadoLibre publish no equivalent,
-- and NULL must stay distinguishable from a genuine score of 0. A score of 0 or below
-- means the community actively voted the deal down.
ALTER TABLE deals ADD COLUMN community_score INTEGER;

-- Supports sorting and filtering by score without scanning the whole table.
CREATE INDEX deals_community_score_idx ON deals (community_score DESC NULLS LAST)
    WHERE community_score IS NOT NULL;

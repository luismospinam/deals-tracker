-- Groceries: pack size for unit pricing, and a pin for staples.
--
-- ## Pack size, not unit price
--
-- `unit_measure` is the base unit ('g', 'ml' or 'un') and `unit_quantity` how many of it the pack
-- holds -- `(500  gr)` is ('g', 500), a 0.5kg weighed cut is ('g', 500), `(12  und)` is ('un', 12).
-- The unit PRICE is derived on read from the current price, never stored: a stored one would be a
-- second copy of the price that goes stale the moment the next observation lands.
--
-- Null when the source published nothing parseable. Never guessed -- a wrong pack size makes a
-- wrong "cheaper per kilo", which is worse than showing no unit price at all.
ALTER TABLE deals ADD COLUMN unit_measure TEXT CHECK (unit_measure IN ('g', 'ml', 'un'));
ALTER TABLE deals ADD COLUMN unit_quantity NUMERIC(12, 3) CHECK (unit_quantity > 0);

-- The grocery tab's subcategory ("Meat & fish", "Pantry"), shared across both stores' taxonomies.
--
-- Its own column rather than `category_group`: that one is the Categorizer's cross-source bucket and
-- says "Groceries" for every one of these rows, which is right for the feed and useless for telling
-- dairy from meat. Set by the `vtex-grocery` adapter from config, null for every other source.
ALTER TABLE deals ADD COLUMN grocery_group TEXT;

-- ## Why a column and not a DealState
--
-- `state` is single-valued, which is exactly what a pin must not be: pinning a staple must not
-- erase a favorite or a hide, and a pinned staple should not appear on /favorites. A timestamp
-- rather than a boolean so the pinned list can show most recent first without another column.
--
-- `upsertDeal` never writes this column, so a pin survives every poll.
ALTER TABLE deals ADD COLUMN pinned_at TIMESTAMPTZ;

CREATE INDEX deals_pinned_idx ON deals (pinned_at) WHERE pinned_at IS NOT NULL;

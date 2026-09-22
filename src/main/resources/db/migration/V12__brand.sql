-- Store the brand a source publishes, instead of consuming it and throwing it away.
--
-- Every adapter that can already reads a brand -- Éxito, Carulla, Easy and Alkomprar publish one,
-- and the Amazon search tiles carry it in their own element -- but `upsertDeal` only ever used it
-- to DERIVE identity keys and then discarded the value. So the fact was known at ingest and
-- unavailable afterwards.
--
-- What forced the column: the tracked tab needs to filter by brand, and there was no honest way to
-- recover one. Two routes were tried and rejected before adding this:
--
--   * Parse it out of `model_key`, which encodes `mdl:<brand>|<model>`. Measured on the live
--     Amazon rows: only 10 of 188 have a model_key at all, because a key needs BOTH a brand and a
--     derivable model code. That is 5% coverage.
--   * Guess it from the title. Titles are frequently truncated ("Men's Slim", "Men's Modern") and
--     a brand inferred from prose is exactly the wrong-identity mistake this schema's comments warn
--     about repeatedly -- a wrong brand is worse than none.
--
-- The tiles themselves carry a real brand on 55 of 60, so storing what the source said is both the
-- most accurate option and the cheapest.
--
-- NULL stays a normal value, not a defect. A PrestaShop grid publishes no brand at all (see
-- PrestashopSource, which deliberately leaves it null rather than guessing from the title), and
-- Slickdeals publishes none either. The UI therefore has to treat "unknown brand" as a real state.
ALTER TABLE deals ADD COLUMN brand TEXT;

-- Partial, following V8's match_key and V2's community_score: most rows in the wider feed will
-- never have a brand, and an index over mostly-NULL is wasted pages. This supports the dropdown's
-- DISTINCT as well as filtering by a chosen brand.
CREATE INDEX deals_brand_idx ON deals (brand) WHERE brand IS NOT NULL;

-- Backfill what `model_key` already encodes, so existing rows are not stranded until their next
-- fetch. `mdl:adidas|ultimate365` -> `adidas`.
--
-- This recovers the brand for exactly the rows that had a derivable model code -- 10 of 188 on the
-- Amazon source -- and every other row fills in on its next poll, which for a tracked item is
-- within the hour. A backfill in the same migration as the DDL follows V10 and V11.
--
-- Deliberately NOT capitalised or otherwise normalised here: `ProductIdentity.modelKey` lower-cases
-- and accent-folds via Categorizer.normalize to build the key, so what is recoverable is the folded
-- form. The next fetch overwrites it with the source's own casing ("Nike", "adidas"), and the UI
-- groups case-insensitively so the two forms are never shown as different brands.
UPDATE deals
SET brand = nullif(split_part(replace(model_key, 'mdl:', ''), '|', 1), '')
WHERE model_key IS NOT NULL AND brand IS NULL;

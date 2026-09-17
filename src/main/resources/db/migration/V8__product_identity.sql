-- Cross-site product identity: what makes "the same fridge is cheaper at Homecenter" sayable.
--
-- WHY: a discount percentage is a claim by one retailer about its own past price. The
-- strongest signal that a price is actually good is a different retailer selling the
-- identical item for more -- but that requires knowing two listings are the same product,
-- which titles alone do not establish ("Nevera No Frost MABE 421 Lts. Brutos RMP410FZCC"
-- vs whatever Homecenter calls it).
--
-- Verified on live Exito data (2026-09-07): Colombian appliances do carry real GS1 EANs --
-- 7705191041773 (Challenger), 7704353404340 (Haceb), 7704712216294 (Mabe). The 770 prefix
-- is Colombia. Crucially Exito ALSO puts internal refs in the same `ean` field (14636,
-- IZ20000899908), and those fail the GS1 check-digit test -- so a checksum cleanly
-- separates a real cross-site key from junk with no heuristics.
--
-- Added now, ahead of the alerting rule that consumes it, because identity cannot be
-- backfilled onto price history we never recorded.

-- The raw value the source published, kept even when it fails validation so a bad feed is
-- diagnosable rather than silently dropped.
ALTER TABLE deals ADD COLUMN gtin TEXT;

-- Manufacturer model code lifted from the title (CR290, RMP410FZCC, H6164nt27). The
-- fallback identity when no valid GTIN is published.
ALTER TABLE deals ADD COLUMN model_code TEXT;

-- The derived cluster key two listings must share to be treated as the same product:
--   'gtin:7705191041773'  when the GTIN passes its check digit
--   'mdl:mabe|rmp410fzcc' from brand + model code otherwise
-- NULL when neither is available, which is the common case for groceries and most
-- Slickdeals rows -- and NULL must never cluster with NULL, hence the partial index.
ALTER TABLE deals ADD COLUMN match_key TEXT;

CREATE INDEX deals_match_key_idx ON deals (match_key) WHERE match_key IS NOT NULL;

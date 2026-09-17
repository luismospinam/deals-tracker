-- Normalise already-stored GTIN keys to the 14-digit form.
--
-- WHY: ProductIdentity now left-pads a validated barcode to GTIN-14 before using it as a key,
-- because Exito publishes the *same* MABE fridge as both "757638435765" (UPC-12) and
-- "0757638435765" (EAN-13). Unpadded those are two keys for one product, and the cross-site
-- comparison sees two fridges rather than six listings of one.
--
-- A deal's key is only recomputed when that deal is next fetched, and the search windows these
-- sources expose shift between polls, so some rows would keep the old key for days. This
-- backfills them, in the same spirit as V5's co_shipping_source backfill and the
-- `--recategorize` command.
--
-- Padding cannot invalidate a code: GS1's mod-10 weights are anchored at the rightmost digit
-- and leading zeros add nothing to the sum, which is exactly why GS1 permits zero-padding
-- between GTIN widths. Only rows whose key already passed validation are touched here.
--
-- EAN-8 is excluded: it is a separate numbering scheme, not a short GTIN-13, and padding it
-- would fabricate a code that means something else.
UPDATE deals
SET match_key = 'gtin:' || lpad(substring(match_key FROM 6), 14, '0')
WHERE match_key LIKE 'gtin:%'
  AND length(substring(match_key FROM 6)) < 14
  AND length(substring(match_key FROM 6)) <> 8;

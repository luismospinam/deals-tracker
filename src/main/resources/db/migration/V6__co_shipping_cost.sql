-- International shipping cost quoted for the destination country.
--
-- WHY: the eligibility verdict alone is misleading. Amazon's global-shipping widget reads
-- either
--     "No Import Charges & FREE Shipping to Colombia"
-- or
--     "No Import Charges & $60.66 Shipping to Colombia"
-- and both were being stored identically as "ships, no import charges". A $60.66 shipping
-- fee usually destroys whatever discount made the deal interesting, so the number matters
-- as much as the yes/no.
--
-- NUMERIC, never float, matching every other money column here.
-- 0 = free shipping. NULL = Amazon did not state a cost.
ALTER TABLE deals ADD COLUMN co_shipping_cost NUMERIC(14,2)
    CONSTRAINT deals_co_shipping_cost_check CHECK (co_shipping_cost IS NULL OR co_shipping_cost >= 0);

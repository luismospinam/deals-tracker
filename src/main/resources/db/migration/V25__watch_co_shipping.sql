-- Watches that only alert on things Luis can actually have delivered.
--
-- WHY: a watch scored every candidate on price alone, so an Amazon or Slickdeals listing that
-- does not ship to Colombia (co_shipping = 'NO'), or whose verdict is still unknown, could fire
-- an alert -- and could set the "floor" every other listing was then judged against. Bulky goods
-- are the worst case: most sofas, fridges and TVs on amazon.com do not ship here at all.
--
-- Defaults ON, deliberately. Colombian retailers are 'YES' by default, so existing watches lose
-- nothing from them; only listings that cannot reach Colombia drop out, which is the point.
ALTER TABLE watches
    ADD COLUMN require_co_shipping BOOLEAN NOT NULL DEFAULT TRUE,
    -- In US$, because that is what co_shipping_cost holds (Amazon quotes the fee in dollars).
    -- NULL means no cap; a listing whose fee Amazon never quoted passes either way.
    ADD COLUMN max_shipping_cost NUMERIC(14,2)
        CONSTRAINT watches_max_shipping_cost_check CHECK (max_shipping_cost IS NULL OR max_shipping_cost >= 0);

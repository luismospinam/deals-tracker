-- Card-only prices: what Exito and Carulla charge when paying with a particular card.
--
-- ## Why on the observation, not the deal
--
-- These promotions change daily. Their VTEX teaser names end in a date range -- `..._sep25_sep25` is
-- today only -- so a column on `deals` would hold whatever the latest poll saw and lose the rest.
-- Kept per observation, the history of card prices sits beside the history of shelf prices, and a
-- one-day "Mastercard -20%" can be told apart from a standing one.
--
-- ## What the numbers are
--
-- `card_price` is the price at the till with the card, as the product page shows it ("$3.280
-- Mastercard / $4.100 Otros"). It is computed from the price BEFORE any shelf promotion
-- (`PriceWithoutDiscount`), not from the shelf price: measured on the rendered pages, Arroz premium
-- 1 kg at shelf 5,193 / before-promotion 6,110 with a 25% card teaser shows $4,583 (6,110 x 0.75),
-- not 3,895 (5,193 x 0.75). The two discounts do not stack.
--
-- `card_percent` is the teaser's own percentage, against that pre-promotion price.
--
-- `card_scope` is who can pay it:
--   'MASTERCARD' -- any Mastercard, including the store's own (payment ids 4 and 501)
--   'STORE_CARD' -- only Tarjeta Exito y Carulla / Carulla Mastercard (payment id 501)
--   'OTHER'      -- a payment condition naming neither (unseen so far; kept rather than dropped,
--                   so a new card programme shows up instead of silently vanishing)
--
-- All three are null when no card promotion applies, or when the card price would not beat the shelf
-- price -- a "discount" that costs more than paying cash is not one.
ALTER TABLE price_observations ADD COLUMN card_price NUMERIC(14, 2) CHECK (card_price >= 0);
ALTER TABLE price_observations ADD COLUMN card_percent NUMERIC(5, 2) CHECK (card_percent > 0 AND card_percent < 100);
ALTER TABLE price_observations ADD COLUMN card_scope TEXT CHECK (card_scope IN ('MASTERCARD', 'STORE_CARD', 'OTHER'));

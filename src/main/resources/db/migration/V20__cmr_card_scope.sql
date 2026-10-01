-- CMR joins the card scopes: the Falabella group's own credit card.
--
-- WHY a fourth value rather than reusing STORE_CARD. STORE_CARD means "Tarjeta Exito y Carulla /
-- Carulla Mastercard" everywhere it is shown -- that is what the chip's tooltip says. CMR is a
-- different programme at a different retailer, and a chip reading "Store card only" on a Homecenter
-- row would name the wrong card. The operator asked for every card kept and labelled, so the label
-- has to be able to say which one.
--
-- WHY not simply 'OTHER'. OTHER means "a payment method this app does not recognise yet", which is
-- the honest answer for an unseen id and the wrong one here: CMR is recognised, published per
-- product, and the reason this migration exists.
--
-- The CHECK is replaced rather than extended in place, since Postgres has no ADD VALUE for a CHECK
-- the way it does for an enum. The constraint name is the one Postgres auto-assigned in V17, where
-- the check was written inline.
ALTER TABLE price_observations DROP CONSTRAINT price_observations_card_scope_check;
ALTER TABLE price_observations ADD CONSTRAINT price_observations_card_scope_check
    CHECK (card_scope IN ('MASTERCARD', 'STORE_CARD', 'OTHER', 'CMR'));

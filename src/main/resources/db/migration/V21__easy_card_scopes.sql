-- Easy's two card programmes: Tarjeta Mas Cencosud and Codensa.
--
-- WHY named rather than folded into an existing value. Every other scope names a card the
-- operator either holds or does not, and the chip's job is to say which one pays the price on
-- screen -- "Store card only" on an Easy row would name Tarjeta Exito y Carulla, which cannot pay
-- it. Same reasoning as CMR in V20.
--
-- WHY two and not one. Easy's teasers restrict by card BIN, and the BINs fall into exactly two
-- disjoint halves plus their union, measured 2026-09-29 over 185 teasers: six BINs whose names say
-- `TMC`, six whose names say `COD`, and the twelve together on teasers named `COD Y TMC`. Name and
-- BIN set agreed in all 185, so the split is the store's own and not an inference. A teaser good on
-- both cards is recorded as TMC, the card in hand; the teaser name is kept as evidence either way.
ALTER TABLE price_observations DROP CONSTRAINT price_observations_card_scope_check;
ALTER TABLE price_observations ADD CONSTRAINT price_observations_card_scope_check
    CHECK (card_scope IN ('MASTERCARD', 'STORE_CARD', 'OTHER', 'CMR', 'TMC', 'CODENSA'));

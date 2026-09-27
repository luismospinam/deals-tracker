-- Promotions and site banners, kept for later analysis of how Exito and Carulla price over time.
--
-- ## Why
--
-- Price observations say THAT a price moved, never WHY. On 2026-09-25 about 220 Exito items went
-- back up between the morning and afternoon crawls, and the cause -- a produce promotion ending
-- (`cmp_20_prov_mcdo_fruver_franja_...`) -- was found only because a probe happened to keep the raw
-- response. Both stores name every promotion in the data a crawl already reads, so recording them
-- costs no extra requests and turns "why did this move" into a query.
--
-- ## promotions: one row per distinct promotion name, per store
--
-- Few and long-lived: ~100 distinct names across 600 products at a time. `name` is the store's own
-- identifier, verbatim. Everything else is decoded from it and kept alongside, never instead:
--
--   cmp_20_prov_mercado_cat2_vie_id33945_10jul_01ene    kind SHELF,     percent 20, valid 10jul..01ene
--   mastercard_20_aliado_..._id40239_sep25_sep25        kind CARD,      percent 20, card ids 4,501
--   paguexllevey_2_3_fisi_1737193_..._17sep_18oct       kind MULTIBUY,  pay 2 take 3
--
-- `valid_from` / `valid_to` are the dates in the name as the store wrote them. They are a claim, like
-- a list price; `first_seen_at` / `last_seen_at` are what we observed, and the two disagreeing is
-- itself worth knowing. Dates are nullable because not every name carries a parseable range.
--
-- One row per (source, name): the same name at two stores is two promotions that can start and end
-- independently, and per-store is what the stores' own pricing is.
CREATE TABLE promotions (
    id            BIGSERIAL PRIMARY KEY,
    source        TEXT NOT NULL,
    name          TEXT NOT NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('SHELF', 'CARD', 'MULTIBUY', 'OTHER')),
    percent       NUMERIC(6, 2),
    -- Payment method ids for a CARD promotion, as the store lists them: '4,501'.
    card_ids      TEXT,
    -- A multi-buy's "pay X, take Y".
    pay_qty       INT,
    take_qty      INT,
    valid_from    DATE,
    valid_to      DATE,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, name)
);

-- Which promotions were live on which observation. The join that makes "which promotion ended at
-- 11:59?" answerable: an item's observations, each with the promotions it carried.
--
-- CASCADE both ways: a link is meaningless without either end, and price_observations already
-- cascades from deals.
CREATE TABLE observation_promotions (
    observation_id BIGINT NOT NULL REFERENCES price_observations(id) ON DELETE CASCADE,
    promotion_id   BIGINT NOT NULL REFERENCES promotions(id) ON DELETE CASCADE,
    PRIMARY KEY (observation_id, promotion_id)
);
CREATE INDEX observation_promotions_promotion_idx ON observation_promotions (promotion_id);

-- ## site_banners: the homepage announcement-bar configs, as the store left them
--
-- The only place time-of-day rules are written out: "30% con Mastercard en frutas y verduras 6:00 am
-- a 11:59 am". Read from the storefront homepage, which robots.txt allows, once per crawl.
--
-- CAUTION, measured 2026-09-25: the homepage embeds a LIBRARY of these bars (`SuperBar` blocks), not
-- only the live one -- 11 at Exito and 6 at Carulla, each with its own `initialDate` / `endDate`, and
-- every one of those dates was in 2025. So a row here is "the store has this banner configured", not
-- "this is on screen today". `shown` and the stated window are kept verbatim so that can be judged
-- later; `stated_from` / `stated_to` are the store's text, parsed, and may be long past.
--
-- One row per (host, text), with the span we saw it for -- a config repeats on every crawl, and
-- storing each sighting would be the same sentence thousands of times. `seen_count` keeps how many
-- crawls it appeared on, and a config that stops appearing is the store having removed it.
CREATE TABLE site_banners (
    id            BIGSERIAL PRIMARY KEY,
    host          TEXT NOT NULL,
    text          TEXT NOT NULL,
    shown         BOOLEAN,
    stated_from   TIMESTAMP,
    stated_to     TIMESTAMP,
    link          TEXT,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    seen_count    INT NOT NULL DEFAULT 1,
    UNIQUE (host, text)
);

-- ## Stock per observation
--
-- `AvailableQuantity` as the store reported it. Measured 2026-09-25: 608 of 611 grocery offers read
-- exactly 10,000 -- VTEX's cap, meaning "plenty" -- and 3 read 0. So on its own it says little. Kept
-- because it costs one integer per row, and a value under the cap is exactly the rare signal
-- (a promotion selling through) that would be invisible without the history.
ALTER TABLE price_observations ADD COLUMN stock_qty INT CHECK (stock_qty >= 0);

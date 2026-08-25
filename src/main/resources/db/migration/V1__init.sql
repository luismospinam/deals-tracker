-- Deals Tracker initial schema.
--
-- Design notes worth keeping in mind when editing:
--   * Prices are NUMERIC, never float. COP values reach ~9,700,900 while USD
--     needs cents; binary floating point would introduce rounding drift in
--     exactly the price comparisons this tool exists to make.
--   * All timestamps are TIMESTAMPTZ. Deals arrive from US (Slickdeals) and
--     Colombian (Exito/Carulla/MercadoLibre) sources.
--   * state/co_shipping use TEXT + CHECK rather than native enums, which are
--     awkward to evolve once rows exist.

-- Trigram index support for the UI's free-text filter.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE deals (
    id              BIGSERIAL PRIMARY KEY,

    -- provider id from config/sources.yaml (e.g. 'exito', 'slickdeals-frontpage')
    source          TEXT        NOT NULL,
    -- stable id within that provider: Slickdeals thread id, VTEX productId, ML item id
    source_deal_id  TEXT        NOT NULL,

    title           TEXT        NOT NULL,
    url             TEXT        NOT NULL,
    -- retailer actually selling it. For Slickdeals this is resolved from the
    -- feed and is the basis of the Amazon-only filter.
    merchant        TEXT,
    -- Amazon ASIN when known; comes free from the Slickdeals feed.
    asin            TEXT,
    category        TEXT,
    image_url       TEXT,
    currency        TEXT        NOT NULL,

    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- false once a fetch stops returning it, so expired deals drop out of the
    -- feed without losing their price history.
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,

    state           TEXT        NOT NULL DEFAULT 'NEW'
                    CONSTRAINT deals_state_check
                    CHECK (state IN ('NEW', 'SEEN', 'DISMISSED', 'SAVED')),

    -- Colombia shippability. UNKNOWN for Amazon (Luis verifies manually and the
    -- verdict persists); YES for Colombian retailers.
    co_shipping     TEXT        NOT NULL DEFAULT 'UNKNOWN'
                    CONSTRAINT deals_co_shipping_check
                    CHECK (co_shipping IN ('UNKNOWN', 'YES', 'NO')),

    -- True once the deal has met a promotion rule (discount floor or real drop).
    promoted        BOOLEAN     NOT NULL DEFAULT FALSE,
    promoted_at     TIMESTAMPTZ,

    -- The dedupe key. Makes ingest idempotent via ON CONFLICT DO UPDATE.
    CONSTRAINT deals_source_unique UNIQUE (source, source_deal_id)
);

-- Default feed query: active, not dismissed, newest/most-discounted first.
CREATE INDEX deals_feed_idx ON deals (is_active, state) WHERE is_active;
CREATE INDEX deals_source_idx ON deals (source);
CREATE INDEX deals_asin_idx ON deals (asin) WHERE asin IS NOT NULL;
-- Trigram GIN so partial-word search stays fast as the table grows.
CREATE INDEX deals_title_trgm_idx ON deals USING GIN (title gin_trgm_ops);

-- Append-only price log. Never UPDATE or DELETE rows here: this table is the
-- evidence base for distinguishing a real discount from an inflated list price.
CREATE TABLE price_observations (
    id          BIGSERIAL PRIMARY KEY,
    deal_id     BIGINT        NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    observed_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    price       NUMERIC(14,2) NOT NULL CHECK (price >= 0),
    -- the retailer's claimed "before" price; often inflated, so kept separate
    list_price  NUMERIC(14,2) CHECK (list_price IS NULL OR list_price >= 0),
    currency    TEXT          NOT NULL
);

CREATE INDEX price_obs_deal_time_idx ON price_observations (deal_id, observed_at DESC);

-- One row per source per poll. Without this, a broken adapter fails silently
-- and the feed just quietly goes stale.
CREATE TABLE fetch_runs (
    id          BIGSERIAL PRIMARY KEY,
    source      TEXT        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    item_count  INTEGER     NOT NULL DEFAULT 0,
    new_count   INTEGER     NOT NULL DEFAULT 0,
    error       TEXT
);

CREATE INDEX fetch_runs_source_time_idx ON fetch_runs (source, started_at DESC);

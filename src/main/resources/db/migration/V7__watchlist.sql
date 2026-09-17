-- Watches: the things Luis is actually trying to buy, and the alerts they produced.
--
-- WHY: until now the app was push-shaped -- it polled fixed categories and showed whatever
-- looked discounted. That suits browsing but not the real job, which is "I want a fridge,
-- keep hunting it for the next few weeks and tell me when a good one appears". A watch is
-- that standing intent, and it does two jobs: it steers fetching where a site supports
-- keyword search, and it filters/scores everything ingested everywhere else.
--
-- Deliberately a table rather than a YAML file, unlike categories.yaml and sources.yaml:
-- what you are shopping for changes far more often than how a protocol is parsed, and it
-- should not need a restart.

CREATE TABLE watches (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT NOT NULL,

    -- Pipe-separated, exactly like the feed's search box, and parsed by the same
    -- parseSearchTerms(). Reusing that syntax means "nevera | refrigerador" behaves
    -- identically here and there, and there is only one thing to learn.
    terms        TEXT NOT NULL,

    -- Terms that disqualify a match. Load-bearing, not polish: a search for "nevera"
    -- returns "Organizador De Nevera" -- a ~20k COP plastic fridge organiser -- ranked
    -- above actual fridges. Without a way to say "not that", a lowest-price rule fires on
    -- accessories forever.
    exclude_terms TEXT,

    -- Optional narrowing. category_group is the normalized bucket Categorizer already
    -- computes, so a watch can say "appliances only" without re-implementing taxonomy.
    category_group TEXT,
    sources        TEXT[] NOT NULL DEFAULT '{}',

    -- min_price is the junk floor (see exclude_terms), max_price is the actual budget.
    -- NUMERIC, never float, matching every other money column here.
    min_price    NUMERIC(14,2) CONSTRAINT watches_min_price_check CHECK (min_price IS NULL OR min_price >= 0),
    max_price    NUMERIC(14,2) CONSTRAINT watches_max_price_check CHECK (max_price IS NULL OR max_price >= 0),
    currency     TEXT NOT NULL DEFAULT 'COP',

    -- The N in "cheapest seen in N days". A fridge hunt runs for weeks, so the window is
    -- the point: it is what distinguishes a real floor from an opening price.
    lookback_days INTEGER NOT NULL DEFAULT 30
        CONSTRAINT watches_lookback_days_check CHECK (lookback_days > 0),

    -- How much cheaper this site must be than the cheapest other site carrying the same
    -- item before that counts as news. Percent.
    cross_site_min_spread NUMERIC(5,2) NOT NULL DEFAULT 15.00
        CONSTRAINT watches_spread_check CHECK (cross_site_min_spread >= 0),

    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only enabled watches are ever evaluated or contribute search terms.
CREATE INDEX watches_enabled_idx ON watches (id) WHERE enabled;

-- The alert ledger. Exists so a notification fires once and thereafter only when the news
-- actually improves -- without it, every poll cycle re-announces the same standing price.
CREATE TABLE watch_matches (
    id          BIGSERIAL PRIMARY KEY,
    watch_id    BIGINT NOT NULL REFERENCES watches(id) ON DELETE CASCADE,
    -- CASCADE because a match is meaningless without the deal it points at, the same
    -- reasoning as price_observations.
    deal_id     BIGINT NOT NULL REFERENCES deals(id) ON DELETE CASCADE,

    -- TEXT + CHECK rather than a native enum, matching state and co_shipping: enums are
    -- awkward to evolve and a third reason is plausible.
    reason      TEXT NOT NULL
        CONSTRAINT watch_matches_reason_check CHECK (reason IN ('LOWEST_IN_WINDOW', 'CROSS_SITE_SPREAD')),

    matched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    price_at_match NUMERIC(14,2) NOT NULL
        CONSTRAINT watch_matches_price_check CHECK (price_at_match >= 0),

    -- NULL until a notification actually went out, so a failed or suppressed send is
    -- distinguishable from one that was never attempted -- the same distinction
    -- co_shipping_checked_at draws for verification.
    notified_at    TIMESTAMPTZ,
    notified_price NUMERIC(14,2),

    -- The sentence the alert rested on, e.g. "beat 30d floor 3.024.900 seen at exito".
    -- Auditable after the fact without recomputing, like co_shipping_evidence.
    evidence    TEXT,

    -- One row per (watch, deal, reason): the ledger is updated in place rather than
    -- appended to, because "has this already been announced" is the question it answers.
    CONSTRAINT watch_matches_unique UNIQUE (watch_id, deal_id, reason)
);

CREATE INDEX watch_matches_watch_idx ON watch_matches (watch_id, matched_at DESC);

-- Drives "what still needs announcing" on every cycle.
CREATE INDEX watch_matches_pending_idx ON watch_matches (watch_id) WHERE notified_at IS NULL;

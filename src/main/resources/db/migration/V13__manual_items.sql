-- Items tracked because Luis asked for them by URL, rather than because a search returned them.
--
-- The `amazon` provider finds items by keyword and lets Amazon filter by size. That is good for
-- discovery and bad for precision: Amazon's size filter returns a REPRESENTATIVE of the product
-- family, not strictly the matching child. A size-8 search returned the 8.5 ASIN at $130.26 while
-- size 8 sold for $107.41, and the stored row carried the wrong variant at the wrong price.
--
-- A pasted URL has no such ambiguity. The ASIN is exact, so the price is the price of the thing
-- actually wanted, and the size can be read from that ASIN's own variation entry instead of being
-- inferred from a filter. So this is not merely a convenience -- it is the more trustworthy of the
-- two routes, and the one to reach for when a specific item matters.
--
-- ## Why a table rather than config
--
-- `config/sources.yaml` is read once at boot, so a URL pasted at runtime has nowhere to live. A
-- `trackUrls:` list there would work but needs a file edit and a restart per item, and offers no
-- clean place for the per-item brand or type override.
--
-- ## Why this does not duplicate `deals`
--
-- This table is the INTENT ("keep watching this URL"); `deals` and `price_observations` remain the
-- only record of what was observed. The two are joined by `(source, source_deal_id)` — the same
-- dedupe key `upsertDeal` uses — so a manual item's price history is indistinguishable from a
-- searched one's, and the tracked tab needs no special case to show it.
--
-- Deleting a row here stops the refresh; it does not delete the deal or its history. That is the
-- same soft-delete reasoning as the tab's hide button, which sets `state = DISMISSED` rather than
-- removing anything.
CREATE TABLE manual_items (
    id          BIGSERIAL PRIMARY KEY,

    -- The URL as pasted, kept verbatim for provenance: it is what the operator saw, and a stored
    -- URL that does not match what they entered is unauditable.
    url         TEXT NOT NULL,

    -- Extracted from the URL at insert. This, with `source`, is the join to `deals` and the thing
    -- the refresh actually fetches. UNIQUE because adding the same item twice is a mistake, not a
    -- second intent -- and without it a double-click would double the request cost forever.
    asin        TEXT NOT NULL
        CONSTRAINT manual_items_asin_unique UNIQUE
        CONSTRAINT manual_items_asin_shape CHECK (asin ~ '^[A-Z0-9]{10}$'),

    -- Which provider owns the resulting deal row, so the tracked tab and `deactivateStale` treat
    -- these exactly like searched rows. Defaulted rather than hardcoded in Kotlin: a second manual
    -- source (a different retailer) then needs no migration.
    source      TEXT NOT NULL DEFAULT 'amazon',

    -- Operator OVERRIDES, both nullable and both normally null.
    --
    -- The product page publishes a brand ("Visit the New Balance Store"), a breadcrumb category and
    -- the ASIN's own size, so these are only for the cases where the page does not say or says
    -- something unhelpful. Null means "use whatever the page publishes", which is the common path.
    brand       TEXT,
    product_type TEXT,

    added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- When the refresh last fetched this URL, and what happened. Null = never fetched.
    --
    -- Recorded because a manual item that silently stops refreshing looks identical to one whose
    -- price genuinely has not moved, and those are very different facts. The tab can then say
    -- "checked 3 days ago" rather than implying a stable price.
    last_fetch_at    TIMESTAMPTZ,
    last_fetch_error TEXT
);

-- The refresh reads every row each poll, oldest-checked first, so an item that fails repeatedly
-- does not starve the rest. Nulls first: never-fetched items are the most urgent.
CREATE INDEX manual_items_refresh_idx ON manual_items (last_fetch_at NULLS FIRST);

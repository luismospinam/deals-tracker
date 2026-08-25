-- When the deal was published by the source, as opposed to when we first saw it.
--
-- first_seen_at only records our own discovery time, so it cannot distinguish a deal
-- posted today from one posted a year ago that we happened to reach on a deep search
-- page. Slickdeals' paginated q= search reaches back over 400 days, so without this the
-- feed accumulated stale 2025 deals.
--
-- Nullable: VTEX and MercadoLibre publish no posting date for catalogue items, and rows
-- ingested before this column existed have no value to backfill from.
ALTER TABLE deals ADD COLUMN posted_at TIMESTAMPTZ;

-- Supports the age cutoff applied on every feed query.
CREATE INDEX deals_posted_at_idx ON deals (posted_at DESC NULLS LAST)
    WHERE posted_at IS NOT NULL;

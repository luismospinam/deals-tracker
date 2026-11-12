-- Tracked items already announced on Discord (2026-11-11). The tracked post used to go out at fixed
-- times of day listing everything on sale; it now goes out when an item is first seen on sale, so
-- something has to remember what was said. One row per item: the price it was announced at.
--
-- An item is announced again only at a lower price than this, or after it has been off sale and the
-- row was dropped (a day after the announcement at the earliest, so a price hovering on the
-- threshold does not post every hour).
CREATE TABLE tracked_alerts (
    deal_id BIGINT PRIMARY KEY REFERENCES deals (id) ON DELETE CASCADE,
    price   NUMERIC(14, 2) NOT NULL,
    sent_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

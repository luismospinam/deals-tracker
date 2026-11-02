-- Listings hidden from one watch with its "hide" button. Exact, where an exclusion term is a guess:
-- the Nevera watch's cheapest match was a 1.3 cu ft drinks cooler, and the only word unique to it
-- was its brand. A hidden listing is no longer a candidate, so it neither shows nor alerts, and
-- it is hidden from that watch only -- the feed and other watches still see it.
CREATE TABLE watch_hidden_deals (
    watch_id  BIGINT NOT NULL REFERENCES watches (id) ON DELETE CASCADE,
    deal_id   BIGINT NOT NULL REFERENCES deals (id) ON DELETE CASCADE,
    hidden_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (watch_id, deal_id)
);

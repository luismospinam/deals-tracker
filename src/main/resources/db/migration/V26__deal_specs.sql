-- What each retailer says a product IS: its specification table and its description.
--
-- WHY: the app knew prices and titles, which is enough to say a sofa is cheap and not enough to say
-- it is the right one -- left or right chaise, bed size, seat depth, upholstery. Those facts sit in
-- the same API responses the adapters already read, so they cost nothing to keep. They exist so the
-- Ask panel can compare products on specification, not only on price.
--
-- Its own table rather than columns on deals: specs are large, rarely change, and are read only when
-- a question needs them, while deals is scanned by every page. One row per deal; spec_hash lets an
-- unchanged poll skip the write.
CREATE TABLE deal_specs (
    deal_id     BIGINT PRIMARY KEY REFERENCES deals(id) ON DELETE CASCADE,
    -- {"Lado Chaise": "Izquierdo", "Dimensiones": "265 x 146 x 80 cm", ...}
    specs       JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Marketing copy with the HTML stripped. Often where dimensions live when there is no spec table.
    description TEXT,
    spec_hash   TEXT NOT NULL,
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

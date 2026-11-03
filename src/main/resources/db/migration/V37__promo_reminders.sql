-- Recurring payment promotions worth remembering: a bank's code that takes money off on a fixed day of
-- the month. Not something any crawl can see -- the code is applied at checkout, never on the product
-- page -- so the operator enters them on the Config tab, and the app reminds on the day (a banner on
-- every page, a chip on qualifying rows, a desktop banner and a Discord post).
--
-- `nth` is which `weekday` of the month (1-5), or -1 for the last one. `weekday` is ISO: 1 = Monday.
-- `source_prefix` picks the rows a chip goes on: 'amazon' matches amazon and amazon-watches.
CREATE TABLE promo_reminders (
    id             BIGSERIAL PRIMARY KEY,
    name           TEXT        NOT NULL,
    code           TEXT,
    payment        TEXT,
    source_prefix  TEXT,
    nth            SMALLINT    NOT NULL CHECK (nth BETWEEN 1 AND 5 OR nth = -1),
    weekday        SMALLINT    NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    min_amount     NUMERIC(14, 2),
    discount       NUMERIC(14, 2) NOT NULL CHECK (discount > 0),
    currency       TEXT        NOT NULL,
    notify_at      TEXT        NOT NULL DEFAULT '08:00',
    remind_day_before BOOLEAN  NOT NULL DEFAULT TRUE,
    enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    notes          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The one that prompted this, 2026-11-03: Bancolombia's first-Tuesday code, US$10 off from US$60.
INSERT INTO promo_reminders (name, code, payment, source_prefix, nth, weekday, min_amount, discount, currency)
VALUES ('Bancolombia first Tuesday', 'BANCOLMARTES', 'Bancolombia', 'amazon', 1, 2, 60, 10, 'USD');

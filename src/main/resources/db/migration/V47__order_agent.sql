-- The order agent: a cart it fills each morning at one Exito, from past orders and the day's promotions.
--
-- One row per cart it started (at most one per run). A run that found nothing worth buying has no row
-- here; its explanation is the run's own summary in agent_runs.
CREATE TABLE agent_orders (
    id         bigserial PRIMARY KEY,
    run_id     bigint REFERENCES agent_runs(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- exito.CartStore key: 'laureles' or 'colombia'. One cart has one pickup store.
    store      text NOT NULL,
    -- The card the cart was built for (a CardScope name), or 'cash'. Card promotions do not stack.
    pay_with   text NOT NULL DEFAULT 'cash',
    -- Exito's orderFormId, set on the first add. Matched against exito_orders.cart_id to tell an ordered cart.
    cart_id    text
);
CREATE INDEX agent_orders_created_idx ON agent_orders (created_at DESC);
CREATE UNIQUE INDEX agent_orders_run_idx ON agent_orders (run_id);

-- What it put in the cart, with the prices it judged by and why.
CREATE TABLE agent_order_items (
    id         bigserial PRIMARY KEY,
    order_id   bigint NOT NULL REFERENCES agent_orders(id) ON DELETE CASCADE,
    deal_id    bigint REFERENCES deals(id) ON DELETE SET NULL,
    product_id text NOT NULL,
    title      text NOT NULL,
    price      numeric(14,2),
    usual      numeric(14,2),
    card_price numeric(14,2),
    reason     text NOT NULL,
    added_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_order_items_order_idx ON agent_order_items (order_id);

-- The operator's note on a run, read by the runs after it. On the run, not the cart: a day with no
-- cart can be wrong too ("milk was 30% off, why nothing?").
ALTER TABLE agent_runs ADD COLUMN feedback text;
ALTER TABLE agent_runs ADD COLUMN feedback_at timestamptz;

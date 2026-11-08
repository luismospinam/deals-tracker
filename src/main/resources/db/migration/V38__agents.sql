-- Groundwork for the agents (2026-11-03): what was bought, what an agent suggested, and each run.
--
-- The read-only database role the agents query through (`deals_agent`) is NOT created here. Roles are
-- cluster-wide and its password is generated per boot, so `agent.AgentDb` creates it and applies its
-- grants at every start, and a restore into a fresh server gets it back on the next boot.

-- What was bought, as best the app can tell. Adding to the Exito cart is when the operator buys, so the
-- main source is the tracker's own Exito cart, read a few times a day: a line that appears or grows is
-- a purchase of the difference. `deal_id` is the row it matched, kept nullable so pruning a deal never
-- loses the purchase; `product_id` is the store's own id, which is what the cart reports.
CREATE TABLE purchases (
    id          BIGSERIAL PRIMARY KEY,
    deal_id     BIGINT REFERENCES deals (id) ON DELETE SET NULL,
    source      TEXT        NOT NULL,
    product_id  TEXT        NOT NULL,
    title       TEXT        NOT NULL,
    quantity    INTEGER     NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(14, 2),
    currency    TEXT        NOT NULL,
    bought_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    origin      TEXT        NOT NULL CHECK (origin IN ('exito_cart', 'manual')),
    cart_id     TEXT
);
CREATE INDEX purchases_deal_idx ON purchases (deal_id, bought_at DESC);
CREATE INDEX purchases_product_idx ON purchases (source, product_id, bought_at DESC);

-- One agent run: when, how it ended, and what it cost the plan.
CREATE TABLE agent_runs (
    id           BIGSERIAL PRIMARY KEY,
    agent        TEXT        NOT NULL,
    trigger      TEXT        NOT NULL CHECK (trigger IN ('schedule', 'manual')),
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    status       TEXT        NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'ok', 'failed')),
    cost_usd     NUMERIC(8, 4),
    error        TEXT,
    summary      TEXT
);
CREATE INDEX agent_runs_agent_idx ON agent_runs (agent, started_at DESC);

-- What an agent suggested, and what the operator answered. The answers are the agent's memory: the next
-- run reads them, so a dismissed idea with a reason ("I don't drink soda") is not suggested again.
CREATE TABLE suggestions (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT REFERENCES agent_runs (id) ON DELETE SET NULL,
    agent        TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind         TEXT        NOT NULL CHECK (kind IN ('buy_now', 'stock_up', 'wait', 'switch', 'pin', 'other')),
    deal_id      BIGINT REFERENCES deals (id) ON DELETE SET NULL,
    title        TEXT        NOT NULL,
    reason       TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'accepted', 'dismissed', 'expired')),
    answered_at  TIMESTAMPTZ,
    note         TEXT
);
CREATE INDEX suggestions_open_idx ON suggestions (status, created_at DESC);
CREATE INDEX suggestions_deal_idx ON suggestions (deal_id, created_at DESC);

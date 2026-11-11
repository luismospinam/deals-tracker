-- Orders placed on the Exito account (2026-11-11), read from the store's own order history
-- (`/api/oms/user/orders` on the checkout host, with the login the cart button already holds).
--
-- Orders belong to the account, not to a browser, so this sees what the cart-based purchase log
-- cannot: orders placed from the phone app's cart. It is also the only record of what was really
-- paid -- a cart add is an intention, and the order's lines carry the price after every discount.
--
-- Only what the analysis needs is kept. An order's detail also carries the delivery address, the
-- name and document on the account, and the payment's card digits; none of that is stored.

CREATE TABLE exito_orders (
    order_id           TEXT PRIMARY KEY,
    placed_at          TIMESTAMPTZ NOT NULL,
    -- VTEX's own status ('payment-approved', 'invoiced', 'canceled', ...) and its wording for it.
    status             TEXT        NOT NULL,
    status_description TEXT,
    -- The list's `lastChange`. An order is read again when this moves: a grocery order's lines can
    -- change after it is placed (a substitution, an item out of stock), and a status always does.
    last_change        TIMESTAMPTZ,
    -- The cart the order came from. Equal to app_settings.exito_cart_id for one placed from the
    -- tracker's cart; anything else came from another browser or the phone app.
    cart_id            TEXT,
    hostname           TEXT,
    currency           TEXT        NOT NULL DEFAULT 'COP',
    total              NUMERIC(14, 2),
    items_total        NUMERIC(14, 2),
    -- Negative: what the order's promotions took off items_total.
    discounts_total    NUMERIC(14, 2),
    shipping_total     NUMERIC(14, 2),
    -- "Mastercard", "Tarjeta Éxito": which card paid, so a card price can be checked against it.
    payment_names      TEXT,
    fetched_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX exito_orders_placed_idx ON exito_orders (placed_at DESC);

CREATE TABLE exito_order_items (
    id               BIGSERIAL PRIMARY KEY,
    order_id         TEXT    NOT NULL REFERENCES exito_orders (order_id) ON DELETE CASCADE,
    line             INTEGER NOT NULL,
    product_id       TEXT    NOT NULL,
    sku_id           TEXT,
    ean              TEXT,
    title            TEXT    NOT NULL,
    quantity         INTEGER NOT NULL CHECK (quantity > 0),
    -- What one unit cost in this order, after its discounts (VTEX `sellingPrice`).
    unit_price       NUMERIC(14, 2),
    -- The shelf price the order started from (`price`) and the store's "before" price (`listPrice`).
    store_price      NUMERIC(14, 2),
    list_price       NUMERIC(14, 2),
    seller           TEXT,
    measurement_unit TEXT,
    unit_multiplier  NUMERIC(10, 4),
    -- The tracked row this line is, and what the tracker had recorded for it when the order was
    -- placed: its latest reading at that moment, and its usual price (the median of its daily prices
    -- over the 30 days before, null with fewer than 3 days). Null when the product is not tracked.
    deal_id            BIGINT REFERENCES deals (id) ON DELETE SET NULL,
    tracked_price      NUMERIC(14, 2),
    tracked_list_price NUMERIC(14, 2),
    tracked_card_price NUMERIC(14, 2),
    tracked_usual      NUMERIC(14, 2),
    UNIQUE (order_id, line)
);
CREATE INDEX exito_order_items_deal_idx ON exito_order_items (deal_id);
CREATE INDEX exito_order_items_product_idx ON exito_order_items (product_id);

-- Who put a line in an order-agent cart: the agent, or the operator with the 🛒 button while that cart was
-- the day's cart for the store. The operator's additions have no reason; the next runs read them as a signal.
ALTER TABLE agent_order_items ADD COLUMN added_by text NOT NULL DEFAULT 'agent';

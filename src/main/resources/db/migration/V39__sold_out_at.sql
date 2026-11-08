-- When Exito refused a cart add because the store has none (2026-11-03: Tajín 45 g sold out at Laureles
-- between the 17:20 crawl and an 20:24 add). Until the store's next crawl starts after this time, the
-- row counts as off the shelf, so the page stops offering a price nobody can pay. A crawl that finds it
-- again puts it back on its own: its start is later than this mark and it refreshes last_seen_at.
ALTER TABLE deals ADD COLUMN sold_out_at TIMESTAMPTZ;

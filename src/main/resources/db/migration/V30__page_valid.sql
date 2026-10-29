-- When the row's own page last proved it still sells: a real product page with a buy-box price, the right
-- size, and no refusal to ship to Colombia. A row the search stopped listing is kept while this is recent,
-- because absence from a 45-tile search is not evidence the item is gone. Null until the next page read.
ALTER TABLE deals ADD COLUMN page_valid_at timestamptz;

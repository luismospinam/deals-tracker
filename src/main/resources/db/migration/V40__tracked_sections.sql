-- Which part of the Tracked tab an item added by URL belongs to (2026-11-04). The tab was all clothes,
-- found by size-filtered searches; electronics are added by hand only and get their own section, so
-- they never land among the sneakers with a blank size. Every existing item was clothes.
ALTER TABLE manual_items ADD COLUMN section TEXT NOT NULL DEFAULT 'clothes'
    CONSTRAINT manual_items_section_check CHECK (section IN ('clothes', 'electronics'));

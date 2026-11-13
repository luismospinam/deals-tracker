-- The aisle chosen for a pinned grocery by the classifier (ask.AisleClassifier), kept apart from
-- grocery_group because every crawl rewrites that one from the store's own category.
-- Null means "not asked yet"; the screens read COALESCE(aisle_ai, grocery_group).
ALTER TABLE deals ADD COLUMN aisle_ai TEXT;

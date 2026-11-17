-- The garment type chosen by Claude for a tracked row whose title names none (ask.TypeClassifier):
-- "Men's Tech 2.0 1/2 Zip" and "Fleece Hoody" were both filed under `other` on the Tracked tab.
-- A ProductType label; `other` when it was asked and nothing fits; null means "not asked yet".
ALTER TABLE deals ADD COLUMN type_ai TEXT;

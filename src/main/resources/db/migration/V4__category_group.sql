-- Normalized product category, for the UI's Category filter.
--
-- The raw `category` column is kept as the source published it. It cannot be filtered on
-- directly because the sources disagree entirely:
--   * Exito and Carulla publish 125 combined 3-level Spanish paths from two different
--     taxonomies, and even spell the same top level differently ("Tecnología" vs
--     "Tecnologia").
--   * Slickdeals publishes only its forum name -- "Hot Deals" on 212 of 220 deals -- so
--     its rows carry no product information at all.
--
-- category_group holds one of ~13 broad English buckets derived by Categorizer from
-- config/categories.yaml, so a single dropdown works across every source.
--
-- Nullable so existing rows stay valid until backfilled by `--recategorize`.
ALTER TABLE deals ADD COLUMN category_group TEXT;

CREATE INDEX deals_category_group_idx ON deals (category_group)
    WHERE category_group IS NOT NULL;

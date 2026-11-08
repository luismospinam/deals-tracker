-- Smallest screen a watch wants, in inches (2026-11-05): the TV watch was alerting on 32" and 43" sets
-- when only 55" and up were wanted. Read from the listing's title; a title that names no size passes.
ALTER TABLE watches ADD COLUMN min_screen_inches INT
    CONSTRAINT watches_min_screen_check CHECK (min_screen_inches IS NULL OR min_screen_inches BETWEEN 10 AND 150);

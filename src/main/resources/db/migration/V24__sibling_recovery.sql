-- Whether a retired wrong-size row has been looked at for a right-size sibling (V23 retires the row;
-- this records the follow-up).
--
-- Amazon's size filter surfaces a product FAMILY and often shows the wrong child. When the page says so
-- the row is retired, and the same page lists every child, so the verifier looks for the one in the
-- wanted size and tracks that instead. This column set makes that follow-up automatic and repeatable:
--
-- sibling_checked_at  when the family was last searched for the size. NULL on a retired row = not yet,
--                     which is how rows retired before the follow-up existed get picked up.
-- sibling_added       whether a replacement was started. A family with nothing in the size today may
--                     restock, so a miss is retried weekly for a month, not forever.
ALTER TABLE deals
    ADD COLUMN sibling_checked_at TIMESTAMPTZ,
    ADD COLUMN sibling_added      BOOLEAN;

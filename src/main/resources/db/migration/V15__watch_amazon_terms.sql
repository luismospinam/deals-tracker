-- Watches can now search amazon.com directly, through the `amazon-watches` provider.
--
-- WHY a separate field rather than reusing `terms`: watch terms are written for Colombian
-- retailers, in Spanish ("televisor | smart tv"), and amazon.com answers those poorly. Every Amazon
-- search is also one of a small request budget -- the host starts refusing after a few -- so the
-- terms sent there should be chosen for it. Null or blank means the watch does not search Amazon,
-- which keeps that the opt-in it should be.
ALTER TABLE watches ADD COLUMN amazon_terms TEXT;

-- Which watch's Amazon search found which deal.
--
-- WHY a link rather than matching on title: a watch's own terms are Spanish and an Amazon title is
-- English ("SAMSUNG 55-Inch Class Crystal UHD 4K"), so neither `televisor` nor the Amazon search
-- phrase itself reliably matches the rows that search returned. The rows were fetched FOR this
-- watch, and that fact is recorded here so they are its candidates by construction. Exclusions,
-- the price band and the category still apply to them like any other row.
--
-- CASCADE on both sides: a link is meaningless without either end.
CREATE TABLE watch_sourced_deals (
    watch_id  BIGINT NOT NULL REFERENCES watches(id) ON DELETE CASCADE,
    deal_id   BIGINT NOT NULL REFERENCES deals(id) ON DELETE CASCADE,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (watch_id, deal_id)
);

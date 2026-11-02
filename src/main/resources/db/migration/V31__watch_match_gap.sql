-- How far below the comparison an alert's price is, as a percentage: the rival's price for
-- CROSS_SITE_SPREAD, the watch's prior floor for LOWEST_IN_WINDOW. The daily watch digest ranks
-- alerts by it, so the one worth reading leads. Before this it lived only inside `evidence` text.
ALTER TABLE watch_matches ADD COLUMN gap_percent NUMERIC(5, 1);

-- Cross-site evidence has always started "<spread>% cheaper than ...", so those rows can be filled
-- in. A LOWEST_IN_WINDOW row states its floor and saving as formatted money, which is not parsed
-- back; those stay NULL and rank after every known gap until they fire again.
UPDATE watch_matches
SET gap_percent = substring(evidence FROM '^([0-9]+(\.[0-9]+)?)% cheaper')::numeric
WHERE reason = 'CROSS_SITE_SPREAD' AND evidence ~ '^[0-9]+(\.[0-9]+)?% cheaper';

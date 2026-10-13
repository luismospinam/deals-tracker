-- Each deal's lowest price, highest price and reading count, kept on the deal itself.
--
-- WHY: the feed, the grocery tab and every deal lookup computed MIN/MAX/COUNT over a deal's whole
-- price history for every row they showed -- and the feed's default sort needs it for every matching
-- row before it can take the top 1000. On 3.2M readings that was ~4 of the feed's 6 seconds. The
-- numbers only change when a reading is inserted, so they are maintained there instead of rebuilt on
-- every page load.
--
-- A trigger rather than ingest code: observations are inserted from several places (the poll, the
-- manual refresh, the page-check cleanup), and a summary that one of them forgot to update would be
-- wrong silently. Min and max are exact as long as readings are only added or thinned by the history
-- pruner (which keeps every change). obs_count counts stored readings, so thinning lowers it.
ALTER TABLE deals
    ADD COLUMN obs_min_price NUMERIC(14,2),
    ADD COLUMN obs_max_price NUMERIC(14,2),
    ADD COLUMN obs_count     INTEGER NOT NULL DEFAULT 0;

UPDATE deals d
   SET obs_min_price = a.min_price,
       obs_max_price = a.max_price,
       obs_count     = a.n
  FROM (SELECT deal_id, MIN(price) AS min_price, MAX(price) AS max_price, COUNT(*) AS n
          FROM price_observations GROUP BY deal_id) a
 WHERE a.deal_id = d.id;

CREATE FUNCTION price_observation_summary() RETURNS trigger AS $$
BEGIN
    UPDATE deals
       SET obs_min_price = LEAST(COALESCE(obs_min_price, NEW.price), NEW.price),
           obs_max_price = GREATEST(COALESCE(obs_max_price, NEW.price), NEW.price),
           obs_count     = obs_count + 1
     WHERE id = NEW.deal_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER price_observation_summary_trg
    AFTER INSERT ON price_observations
    FOR EACH ROW EXECUTE FUNCTION price_observation_summary();

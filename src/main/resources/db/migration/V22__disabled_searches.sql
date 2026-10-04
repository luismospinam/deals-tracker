-- Tracked searches the operator has switched off.
--
-- WHY a table of the DISABLED ones, not an enabled flag per term: the terms themselves live in
-- config/sources.yaml (`trackSearches`), which stays the source of truth for what exists. This only
-- records an override on top, so a term added to the YAML is on by default and nothing needs
-- seeding. Deleting the row turns the search back on.
CREATE TABLE disabled_searches (
    provider    TEXT        NOT NULL,
    term        TEXT        NOT NULL,
    disabled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, term)
);

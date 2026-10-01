-- App-wide settings that are not configuration: things the operator sets from the UI and that
-- must survive a restart.
--
-- First use is the notification language. The poller's banners have no browser request to read a
-- language from, so the one last chosen with the masthead switch is kept here (see AppLanguage).
--
-- Key/value rather than one column per setting: each setting is written by one screen and read by
-- one subsystem, and a new one should not need a migration of its own.
CREATE TABLE app_settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

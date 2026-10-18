# Pinned-items-on-sale Discord posts

Twice a day the app posts the **pinned** items that are currently on sale to Discord, one message per
section (Groceries, Tracked), each to its own channel. Code: `notify/PinnedAlerts.kt`, `notify/Discord.kt`.

## What counts as "on sale"

**Groceries** -- a pinned item (chain-wide, as on the Pinned tab) that is
- on the shelf today (not an out-of-stock pin), and
- at least **10%** under its **usual** price, with at least **3** earlier days of history.

This is exactly the Deals tab's rule (`GroceryRow.isDeal`; thresholds `DEAL_PERCENT` and `MIN_DAYS` in
`GroceryRepository.kt`). *Usual* is the median of each day's cheapest reading over the 30 days before
today. Shown at the chain's cheaper store.

**Tracked** -- a pinned item or an Added (manual) item, one row per product, not refused for shipping to
Colombia, and either
- at least **10%** under its own usual price (same median, same 3-day minimum, computed by
  `GroceryRepository.usualPrices`), **or**
- at its **lowest recorded price** after having been higher (`Deal.isAtLowestEver`).

The store's advertised "% off" is deliberately not used: list prices can be inflated.

Sorted deepest discount first. Nothing is sent when nothing qualifies.

## When

`defaults.pinnedAlertTimes` in `config/sources.yaml`, default `["08:00", "19:00"]`, local time. 19:00 sits
after the last grocery crawl window ends (18:45, `pricesmart-poblado-grocery`); **move it if a
`runWindows` entry moves**. The scheduler checks every 5 minutes. If the Mac slept through a time, the
latest passed time is sent on wake (not each missed one). The last handled time is kept in `app_settings`
(`pinned_alert_*_slot`), so a restart neither repeats nor skips a post. A failed send is retried on the
next tick.

## Changing the rules

- Threshold / history: `GroceryRow.DEAL_PERCENT`, `GroceryRow.MIN_DAYS` (also changes the Deals tab).
- Tracked rule: `trackedDealsOnSale` in `PinnedAlerts.kt`.
- Groceries rule: `pinnedGroceryDeals` in `PinnedAlerts.kt`.

## Setup

Create a webhook per channel (Channel settings > Integrations > Webhooks) and set in `.env`:
`DISCORD_GROCERIES_WEBHOOK_URL`, `DISCORD_TRACKED_WEBHOOK_URL`. A section without a URL is not posted.
Restart the app afterwards.

## Trying it

    ./dt run --args="--notify-pinned --dry-run"   print what would be posted, send nothing
    ./dt run --args="--notify-pinned"             post now, ignoring the daily times

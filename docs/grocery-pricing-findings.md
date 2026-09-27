# Grocery pricing at Exito and Carulla — findings

Running notes for the analysis the `promotions`, `observation_promotions`, `stock_qty` and
`site_banners` tables exist to support (V18). Add to this as data accumulates; review after a week of
three-a-day crawls, i.e. from **2026-10-03**.

## Established so far

- **Exito reprices during the day; Carulla barely does.** 2026-09-25, morning vs afternoon shelf
  price on items read in both: Exito Laureles 220 of 7,202 changed (204 up), Exito Colombia 203 of
  7,562 (189 up), Carulla Laureles 7 of 4,736.
- **The Exito changes were promotions ending.** On 2026-09-24 a produce −20%
  (`cmp_20_prov_mcdo_fruver_franja_ID19665_1ene_31dic`) was live at 09:50 and gone by 14:00: 193
  produce items rose by exactly 25% (80 → 100). The homepage advertised time-limited promotions:
  "30% con Mastercard en frutas y verduras 6:00 am a 11:59 am".
- **Card prices don't stack on shelf promotions.** The card % comes off `PriceWithoutDiscount`,
  checked on five rendered product pages.
- **Card promotions are near-universal at Exito and daily.** 5,772 of 7,257 Exito Laureles items
  carried a Mastercard teaser on 2026-09-25, most named `…_sep25_sep25`.
- **Shelf prices at the two Exitos mostly agree.** Of 6,021 products at both, 5,815 had the same price
  on 2026-09-24. Part of the remaining gap was crawl timing (see the first point).

## Questions to answer from the data

1. Which weekdays does Exito run card promotions, and at what depth? Name tokens like `vie` hint at
   Fridays; the homepage mentions "Viernes de Mastercard".
2. How long does a shelf promotion actually run versus the dates in its name?
   (`valid_from`/`valid_to` versus `first_seen_at`/`last_seen_at`.)
3. Do morning-only promotions recur on a schedule, e.g. produce every Friday before noon?
4. Does stock ever drop under the 10,000 cap during a promotion?
5. Is the midday Exito crawl worth its cost? Compare 07:00, 11:00 and 17:00 readings.
6. Do the store-card-only (`501`) promotions exist in practice, and how deep do they go?

## Useful queries

Promotions that ended today, and how many items they covered:

```sql
SELECT p.source, p.name, p.kind, p.percent, p.last_seen_at, count(op.observation_id) AS readings
FROM promotions p JOIN observation_promotions op ON op.promotion_id = p.id
WHERE p.last_seen_at::date = current_date AND p.last_seen_at < now() - interval '3 hours'
GROUP BY 1,2,3,4,5 ORDER BY readings DESC;
```

An item's readings with the promotions live on each:

```sql
SELECT po.observed_at, po.price, po.card_price, string_agg(p.name, ', ')
FROM price_observations po
LEFT JOIN observation_promotions op ON op.observation_id = po.id
LEFT JOIN promotions p ON p.id = op.promotion_id
WHERE po.deal_id = :deal_id GROUP BY 1,2,3 ORDER BY 1;
```

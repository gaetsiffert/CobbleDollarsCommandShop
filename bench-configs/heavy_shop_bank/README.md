# Heavy shop+bank benchmark pack

This folder is intentionally separate from the runtime config tree.

How to use:
1. Copy the `perf_megastore` folder into `config/cobbledollarscommandshops/shops/`.
2. Reload with `/cdshops reload`.
3. Open the shop with `/cdshops open perf_megastore`.

What is inside:
- `shop.json`: 20 categories, 408 offers total.
  - 384 exact-stack offers with unique `custom_data`
  - 24 tag-based stress offers
  - finite stock, interval restocks, purchase bonuses, lightweight conditions
- `bank.json`: 9 categories, 271 offers total.
  - 1 `mod: minecraft` catch-all
  - 24 exact item overrides
  - 6 tag overrides
  - 240 exact-stack ticket variants with unique `custom_data`

The bank is deliberately large so the accepted-items modal has a lot to render and filter.

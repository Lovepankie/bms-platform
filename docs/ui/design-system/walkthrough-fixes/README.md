# Walk-through fixes: before and after (#103, #105, #112)

Taken on 2026-10-06 against the real API (the jar from `mvn package` with the `dev` profile, on the compose
PostgreSQL migrated to V22), headless Chromium driven by Playwright. `before-*` is the bundle built from
`main`, `after-*` the bundle from this branch, both served against the same API and data. Everything shown is
fabricated: the tenant "Sample Shop (fabricated)", its branches HQ Head Office, SECOND Second Shop, TOWN Town
and MKT Market Kiosk, its products and its one stock move. The head office holds no stock.

Each capture is full page at 360px or 390px wide, so the retail bottom bar shows where it sat when the page
was captured.

| File | Shows |
|---|---|
| `*-branch-picker-stock` | The branch the stock screen opens on in a fresh browser, and how branches are named |
| `after-*-no-stock-here` | The head office chosen: "This branch holds no stock. Switch branch?" with the branches that do |
| `*-stock-moves` | The stock moves list row |
| `*-move-stock-over` | Move stock with 50 of an item the branch holds 12 of: the hint and the disabled button |

`walk-log.txt` is the script's step log (43 of 43 passed). The script is not committed.

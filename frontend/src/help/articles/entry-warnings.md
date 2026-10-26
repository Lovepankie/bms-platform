---
title: What does "Is that right?" under a number mean?
job: count-stock
order: 20
module: retail
needs_any: retail.sale.create, retail.purchase.create, retail.stock.transfer, retail.stocktake.commit
screens:
words: warning, is that right, unusual, confirm, change, usually, mistake, typo, entry warning
---
BMS compares what you type with the item's own history. When a number looks unusual, a note asks you to check it before saving. It never stops you.

1. The note says what is usual, such as "You usually buy about 24 pieces of this at a time; you entered 240 pieces. Is that right?".
   ![A note asks Is that right?](entry-warnings-1.webp)
2. If the number is right, tap **Confirm** and it is saved. If it is a mistake, tap **Change**: you go back to the number to fix it.
   ![Confirm or Change](entry-warnings-2.webp)

> These notes show on Sell, Buy stock, Move stock and Count stock. Your manager can see which ones were confirmed.

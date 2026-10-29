---
title: How do I count stock?
job: count-stock
order: 10
module: retail
needs: retail.stocktake.commit, retail.stock.read
screens: /staff/retail/stocktake
words: count, counting, shelf, check stock, missing, stock check, difference, commit the count, not counted, empty, cartons and pieces, carton, pieces, saved, lost my count, reload, offline, stock updated
---
Count what is on the shelf and enter it. BMS shows the difference before anything changes.

1. Choose the branch you are counting with **Switch branch**, then open **Count stock** under Stock.
   ![Open Count stock](count-stock-1.webp)
2. Find each item by typing part of its name, or scan it, or work down the list **Not counted yet**. Type how many you counted, or tap 0 for an item that is gone. Leave an item empty if you did not count it: it stays as it is.
   For an item kept in cartons and pieces, type the full cartons and the pieces left, or all of it in pieces. BMS shows the total in pieces under the boxes, and saves the count in pieces.
   Each count is saved as you type it: **Saved** shows under its box, and the top of the page says "Saved on this phone". If the page reloads or the connection drops, your counts are still there when you open Count stock again on this phone, with the day and time they were saved. Counts from an earlier day are only filled in when you tap **Use them**. To throw them away and count afresh, tap **Start again**.
   ![Type how many you counted](count-stock-2.webp)
3. Tap **Review the difference**. You see each item where the count and the records differ.
   ![Review the difference](count-stock-3.webp)
4. When it looks right, tap **Commit the count**. Nothing changes until you do. A line that takes an item to 0, or removes more than half of it, says how many it removes: tick it if that is right, or tap **Back to the count** to fix it.
   A screen then says "Stock updated" for the items you counted, with what went up and what went down. Tap **Start another count** or **Back to My stock**.
   ![Commit the count](count-stock-4.webp)

> A count far from what the records hold is asked about first: tap **Confirm** if it is right, or **Change** to fix it.

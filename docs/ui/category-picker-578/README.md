# The category picker (issue #578, FR-UXN-10)

Taken on 25 Oct 2026 with the Vite dev server (`VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1`, fabricated categories and the
fabricated new category "Rice") and headless Chromium, on a phone at 412x900 and a laptop at 1280x800.

| File | What it shows |
|---|---|
| `01-add-item-field-412.png` | Add an item: the Category field, drawn as a select, before a choice |
| `02-sheet-search-add-list-412.png` | The full height sheet: the search with the cursor, Add a new category, the list, Done |
| `03-search-any-part-412.png` | "li" finds Cables, Lighting and Solar: any part of the name, whatever the case |
| `04-no-category-called-add-it-412.png` | `No category called "Rice". Add it?` with Add Rice |
| `05-add-inline-code-start-412.png` | The inline form: the name and the code start the app will make, no code to type |
| `06-added-chosen-dark-toast-412.png` | Rice chosen at once, its next item code shown, and the dark toast |
| `07-chosen-row-tinted-412.png` | The chosen row tinted with a tick, no stripe |
| `08-buy-stock-new-item-sheet-412.png` | The same sheet from Buy stock's new item |
| `09-sheet-dark-412.png` | The sheet in the dark theme |
| `10-laptop-combobox-keyboard-1280.png` | The laptop list under the field, narrowed by "i", the row the arrow keys reached ringed |
| `11-laptop-chosen-1280.png` | After Enter: Fittings chosen and the focus back on the field |
| `12-laptop-items-filter-1280.png` | The Items filter with All categories first and no Add |

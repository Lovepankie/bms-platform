// The glossary (issue #547, D24): the words a person sees for the things of the shop, in one place. Every label is a word a
// shop attendant in Kampala uses (docs/specs/units-packs-and-costing.md section 5). Screens, menus, tours and help take their
// words from here instead of typing their own. Routes, permission names and API fields keep their old names: only words a
// person sees change. glossary.test.ts fails when a banned or retired word shows in the copy.

export const WORDS = {
  /** Getting new goods in from a supplier and paying for them; was "Restock". */
  buyStock: 'Buy stock',
  /** One record of buying stock, with its number (P-900001); was "a restock". */
  purchase: 'purchase',
  purchases: 'purchases',
  pastPurchases: 'Past purchases',
  /** Counting what is on the shelf and fixing the list to match; was "Stock-take". */
  countStock: 'Count stock',
  /** One count of a branch; was "a stock-take". */
  stockCount: 'stock count',
  stockCounts: 'stock counts',
  /** Counted less expected, or billed less received; was "Variance". */
  difference: 'Difference',
  /** Stock that went missing or was spoilt; was "Shrinkage". */
  lostOrDamaged: 'Lost or damaged stock',
  /** People who buy now and pay later; was "Credit buyers". */
  customersWhoOwe: 'Customers who owe',
  customerWhoOwes: 'customer who owes',
  /** The smallest unit an item is kept and counted in; was "Unit". */
  countedIn: 'Counted in',
  /** The packs an item also comes in, each a number of the smallest unit; was "Pack sizes". */
  packs: 'Cartons, boxes and other packs',
  moveStock: 'Move stock',
  suppliers: 'Suppliers',
  buyingPrice: 'Buying price',
  sellingPrice: 'Selling price',
  itemCode: 'Item code',
} as const;

/** Words that never show to a person, in any case: the test fails on each. */
export const BANNED_WORDS = ['SKU', 'variance', 'shrinkage', 'UOM', 'restock'] as const;

/** Old labels the glossary replaced, with the word that took their place. They never show to a person either. */
export const RETIRED_WORDS: Record<string, string> = {
  'stock-take': WORDS.countStock,
  stocktake: WORDS.countStock,
  'credit buyer': WORDS.customersWhoOwe,
  'pack size': WORDS.packs,
};

/** The banned and retired words a piece of copy contains, lower case; empty when it is clean. */
export function offendingWords(text: string): string[] {
  const lower = text.toLowerCase();
  return [
    ...BANNED_WORDS.map((w) => w.toLowerCase()).filter((w) => (w.length <= 3 ? new RegExp(`\\b${w}s?\\b`).test(lower) : lower.includes(w))),
    ...Object.keys(RETIRED_WORDS).filter((w) => lower.includes(w)),
  ];
}

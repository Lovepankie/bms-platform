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
  /** How a purchase was paid, on Buy stock (issue #557, D15). */
  paidHow: 'Paid how',
  /** Bought now and owed to the supplier; was "Credit (pay the supplier later)". */
  onCredit: 'On credit',
  /** The balance of every supplier the shop owes, under the Money tab; was "What you owe suppliers". */
  whatIOweSuppliers: 'What I owe suppliers',
  payASupplier: 'Pay a supplier',
  supplierStatement: 'Supplier statement',
} as const;

/** "piece" to "pieces", "box" to "boxes": the plural of a unit's name, for the questions below. */
export function plural(unit: string): string {
  if (/(s|x|z|ch|sh)$/i.test(unit)) return `${unit}es`;
  if (/[^aeiou]y$/i.test(unit)) return `${unit.slice(0, -1)}ies`;
  return `${unit}s`;
}

/** D23 (#554): what a line bought in a pack asks on Buy stock and Goods received: "How many pieces in each carton?". */
export function howManyInEach(unit: string, pack: string): string {
  return `How many ${plural(unit)} in each ${pack}?`;
}

/** D23 (#554): the tick that makes this delivery's size the pack's usual one: "Use 30 from now on". */
export function useFromNowOn(size: string): string {
  return `Use ${size} from now on`;
}

/** Words that never show to a person, in any case: the test fails on each. */
export const BANNED_WORDS = ['SKU', 'variance', 'shrinkage', 'UOM', 'restock'] as const;

/** Old labels the glossary replaced, with the word that took their place. They never show to a person either. */
export const RETIRED_WORDS: Record<string, string> = {
  'stock-take': WORDS.countStock,
  stocktake: WORDS.countStock,
  'credit buyer': WORDS.customersWhoOwe,
  'pack size': WORDS.packs,
  // Issue #680: the two prices of an item are always the buying price and the selling price.
  'average cost': `Average ${WORDS.buyingPrice.toLowerCase()}`,
  'cost price': WORDS.buyingPrice,
  'unit cost': WORDS.buyingPrice,
};

/** The banned and retired words a piece of copy contains, lower case; empty when it is clean. */
export function offendingWords(text: string): string[] {
  const lower = text.toLowerCase();
  return [
    ...BANNED_WORDS.map((w) => w.toLowerCase()).filter((w) => (w.length <= 3 ? new RegExp(`\\b${w}s?\\b`).test(lower) : lower.includes(w))),
    ...Object.keys(RETIRED_WORDS).filter((w) => lower.includes(w)),
  ];
}

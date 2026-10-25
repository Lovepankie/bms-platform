import type { Me } from '../../api/client';
import { foldText } from '../../app/ui/fold';
import { mayCreateMember } from './lending/member-permissions';
import { staffAreas } from './menu';
import { CATALOGUE_LINKS, canUse, screensFor, showRetail } from './retail/permissions';
import { moneyLinks } from './tabs';
import { canManageUsers } from './users/state';

// Feature search (issue #551, owner decision D17): type what you want to do and go straight to the screen. The entries come from
// the same sources as the menu (staffAreas, the retail SCREENS and CATALOGUE_LINKS through canUse, the Money page's links), so a
// screen the session may not open, or whose module or capability is off, is never in the list: there is no second permission
// list here. Each entry carries its glossary name (#548) and the everyday words people type for it, in English and Luganda.
// The synonyms are only matched, never shown, so a word the glossary bans ('restock') may stand here: glossary.test.ts reads
// SYNONYMS and every `synonyms` property of this file, and of no other, as match-only.

export interface Feature {
  to: string;
  /** The name shown, from the menu or the glossary. */
  label: string;
  /** The line under the name, from the menu's own hint. */
  hint?: string;
  /** Words that find it but never show. */
  synonyms: readonly string[];
}

/** The everyday words of each screen, by its path. A screen with none is still found by its name and hint. */
export const SYNONYMS: Record<string, readonly string[]> = {
  '/staff': ['home', 'dashboard', 'start', 'today'],
  '/staff/approvals': ['approve', 'approval', 'inbox', 'pending', 'second person'],
  '/staff/lending': ['loan', 'borrow', 'borrower', 'application', 'repayment', 'disburse'],
  '/staff/lending/members': ['member', 'client', 'borrower', 'register'],
  '/staff/lending/collateral': ['collateral', 'security', 'pledge'],
  '/staff/lending/savings': ['save', 'savings', 'deposit', 'tereka', 'okutereka'],
  '/staff/lending/investments': ['invest', 'investment', 'investor'],
  '/staff/insights': ['insights', 'charts', 'trends', 'performance'],
  '/staff/assistant': ['ai', 'assistant', 'advisor', 'brief'],
  '/staff/reports': ['report', 'export', 'download'],
  '/staff/retail': ['retail', 'shop', 'dduuka'],
  '/staff/sms': ['sms', 'text message', 'message', 'wording'],
  '/staff/payments': ['mobile money', 'momo', 'airtel', 'mtn', 'card', 'payment', 'ssente'],
  '/staff/users': ['staff', 'users', 'user', 'people', 'employee', 'worker', 'team', 'role', 'abakozi'],
  '/staff/notifications': ['notification', 'alert', 'install', 'app'],
  '/staff/feedback': ['feedback', 'idea', 'problem', 'question', 'complain', 'support'],
  '/staff/setup': ['setup', 'set up', 'settings', 'business name', 'logo', 'colour', 'color'],
  '/staff/retail/sale': ['sell', 'sale', 'till', 'checkout', 'receipt', 'tunda', 'okutunda'],
  '/staff/retail/credit-sales': ['credit', 'owe', 'debt', 'debtor', 'unpaid', 'pay later', 'bbanja', 'ebbanja'],
  '/staff/retail/sales': ['sales', 'history', 'sold', 'receipts', 'void', 'cancel a sale'],
  '/staff/retail/orders': ['order', 'whatsapp', 'instagram', 'facebook', 'delivery', 'deliver', 'phone order'],
  '/staff/retail/orders-report': ['delivery', 'fee', 'cancelled', 'cancellation'],
  '/staff/retail/restock': ['buy', 'purchase', 'restock', 'supplier', 'goods in', 'new stock', 'gula', 'okugula'],
  '/staff/retail/usage': ['use', 'used', 'damage', 'broken', 'spoilt', 'expired', 'lost', 'write off'],
  '/staff/retail/stock': ['stock', 'shelf', 'inventory', 'quantity', 'how many', 'low stock', 'ebintu'],
  '/staff/retail/stocktake': ['count', 'stock take', 'stocktake', 'check stock', 'audit', 'bala', 'okubala'],
  '/staff/retail/transfer': ['move', 'transfer', 'send stock', 'branch', 'store'],
  '/staff/retail/stock-by-location': ['store', 'shop', 'location', 'where', 'warehouse'],
  '/staff/retail/transfers': ['move', 'transfer', 'sent', 'moved'],
  '/staff/retail/in-transit': ['transit', 'dispatch', 'receive', 'arriving'],
  '/staff/retail/valuation': ['value', 'worth', 'cost'],
  '/staff/retail/profit': ['profit', 'gain', 'earnings', 'amagoba', 'magoba'],
  '/staff/retail/sales-analysis': ['analysis', 'best sellers', 'report', 'seller'],
  '/staff/retail/margins': ['margin', 'markup', 'price change', 'profit'],
  '/staff/retail/stock-health': ['reorder', 'dead stock', 'slow', 'running out', 'low stock'],
  '/staff/retail/credit-control': ['credit', 'owe', 'late', 'overdue', 'debt', 'collect', 'bbanja'],
  '/staff/retail/evaluation': ['evaluation', 'worth', 'net worth'],
  '/staff/retail/controls': ['controls', 'void', 'fraud', 'theft', 'cancelled', 'price changed'],
  '/staff/retail/catalogue': ['catalogue', 'catalog', 'item', 'product', 'list'],
  '/staff/retail/banking': ['bank', 'deposit', 'banked'],
  '/staff/retail/savings': ['save', 'savings', 'set aside', 'tereka', 'okutereka'],
  '/staff/retail/expenses': ['expense', 'spend', 'spent', 'spending', 'costs', 'bills', 'rent', 'transport'],
  '/staff/retail/withdrawals': ['withdraw', 'withdrawal', 'cash out', 'take out'],
  '/staff/retail/advances': ['advance', 'salary advance', 'repay'],
  '/staff/retail/cash-summary': ['cash', 'till', 'close the day', 'end of day', 'takings', 'ssente', 'sente'],
  '/staff/retail/banking-report': ['bank', 'unbanked', 'expected'],
  '/staff/retail/expenses-report': ['expense', 'spending', 'spend'],
  '/staff/retail/expense-lists': ['expense', 'category', 'setup'],
  '/staff/retail/price-lists': ['price', 'wholesale', 'discount'],
  '/staff/retail/customer-groups': ['group', 'wholesale', 'customer type'],
  '/staff/retail/accounts': ['account', 'credit limit', 'statement', 'terms', 'owe'],
  '/staff/retail/customer-ageing': ['ageing', 'aging', 'late', 'overdue', 'owe', 'debt'],
  '/staff/retail/documents': ['invoice', 'delivery note', 'print', 'pdf'],
  '/staff/retail/quotations': ['quote', 'quotation', 'estimate', 'proforma'],
  '/staff/retail/returns': ['return', 'refund', 'take back', 'credit note', 'exchange'],
  '/staff/retail/purchase-orders': ['order from supplier', 'purchase order', 'buy'],
  '/staff/retail/goods-received': ['received', 'delivery', 'goods in', 'grn'],
  '/staff/retail/supplier-bills': ['bill', 'supplier invoice'],
  '/staff/retail/supplier-payments': ['pay supplier', 'supplier payment', 'paid'],
  '/staff/retail/supplier-payables': ['owe suppliers', 'payables', 'creditors', 'debt', 'supplier balance', 'credit supplier', 'what i owe'],
  '/staff/retail/landed-costs': ['freight', 'duty', 'clearing', 'transport', 'import'],
  '/staff/retail/supplier-credits': ['send back', 'return to supplier', 'credit note'],
  '/staff/retail/branch-roles': ['store', 'shop', 'branch', 'warehouse'],
  '/staff/retail/settings': ['settings', 'features', 'switch on', 'options'],
  '/staff/retail/catalogue/products': ['item', 'product', 'price', 'change price', 'ebintu'],
  '/staff/retail/catalogue/categories': ['category', 'group'],
  '/staff/retail/catalogue/units': ['unit', 'piece', 'kg', 'kilogram', 'measure'],
  '/staff/retail/catalogue/suppliers': ['supplier', 'vendor', 'wholesaler'],
  '/staff/retail/catalogue/buyers': ['customer', 'credit', 'owe', 'debt', 'debtor', 'bbanja'],
  '/staff/retail/catalogue/labels': ['barcode', 'label', 'sticker', 'print'],
};

/**
 * The main actions that are a step inside a screen, each opened by the check its screen already makes. Add an item needs the
 * categories screen, whose check includes retail.catalogue.manage, the permission the Add an item button in a category asks for.
 */
export const ACTIONS: { to: string; label: string; hint: string; may: (me: Me) => boolean; synonyms: readonly string[] }[] = [
  {
    to: '/staff/retail/catalogue/categories',
    label: 'Add an item',
    hint: 'Open a category, then tap Add an item',
    may: (me) => showRetail(me) && canUse(me, 'categories'),
    synonyms: ['new item', 'add product', 'new product', 'create item'],
  },
  { to: '/staff/users/new', label: 'Invite someone', hint: 'Add a member of staff', may: canManageUsers, synonyms: ['invite', 'add staff', 'new user', 'add user', 'abakozi'] },
  { to: '/staff/lending/members/new', label: 'Add a member', hint: 'Register a new member', may: (me) => mayCreateMember(me), synonyms: ['new member', 'register', 'join'] },
];

/**
 * Every screen and main action the session may open, each once. `sells` is useSells, as for the menu. The retail screens come
 * first with their menu names (Buy stock, Count stock, Customers who owe), then the Money page's links, then the menu's areas.
 */
export function featuresFor(me: Me, { sells }: { sells: boolean }): Feature[] {
  const retail = showRetail(me);
  const screens = retail ? screensFor(me, { sells }) : [];
  const catalogue = retail ? CATALOGUE_LINKS.filter((l) => canUse(me, l.screen)) : [];
  const found: Omit<Feature, 'synonyms'>[] = [
    ...screens.map((s) => ({ to: s.path, label: s.label, hint: s.hint })),
    ...catalogue.map((l) => ({ to: l.path, label: l.label, hint: l.hint })),
    ...moneyLinks(me).map((l) => ({ to: l.to, label: l.label, hint: l.hint })),
    ...staffAreas(me, { sells }).map((a) => ({ to: a.to, label: a.label })),
  ];
  const seen = new Set<string>();
  const features: Feature[] = [];
  for (const f of found) {
    if (seen.has(f.to)) continue;
    seen.add(f.to);
    features.push({ ...f, synonyms: SYNONYMS[f.to] ?? [] });
  }
  for (const a of ACTIONS) if (a.may(me)) features.push({ to: a.to, label: a.label, hint: a.hint, synonyms: a.synonyms });
  return features;
}

/** The words of a text, folded (no case, no accents), split at anything that is not a letter or a digit. */
const wordsOf = (text: string): string[] => foldText(text).split(/[^\p{L}\p{N}]+/u).filter(Boolean);

/** True when every typed word starts some word of `words`. */
const covers = (words: string[], typed: string[]) => typed.every((t) => words.some((w) => w.startsWith(t)));

/**
 * The features that match what was typed, best first: every typed word must start a word of the name, the hint or a synonym.
 * A name that starts with the whole term comes first, then names that hold every word, then synonyms, then hints; ties keep the
 * order of featuresFor. Nothing typed matches nothing, as results show only while typing.
 */
export function findFeatures(features: Feature[], term: string): Feature[] {
  const typed = wordsOf(term);
  if (typed.length === 0) return [];
  const whole = typed.join(' ');
  const ranked: { feature: Feature; rank: number; at: number }[] = [];
  features.forEach((feature, at) => {
    const label = wordsOf(feature.label);
    const synonyms = [...label, ...feature.synonyms.flatMap(wordsOf)];
    const all = [...synonyms, ...wordsOf(feature.hint ?? '')];
    const rank = label.join(' ').startsWith(whole) ? 0 : covers(label, typed) ? 1 : covers(synonyms, typed) ? 2 : covers(all, typed) ? 3 : -1;
    if (rank >= 0) ranked.push({ feature, rank, at });
  });
  return ranked.sort((a, b) => a.rank - b.rank || a.at - b.at).map((r) => r.feature);
}

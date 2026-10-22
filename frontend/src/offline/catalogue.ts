import type { Me } from '../api/client';
import type { Customer, Product, SaleRequest } from '../api/retail';
import { qtyString } from '../areas/staff/retail/maths';
import { branchesWhere, sellingBranches } from '../areas/staff/retail/permissions';
import { baseQtyMilli, chosenPack } from '../areas/staff/retail/units-state';
import { defaultPrice, lineFigures, type Draft } from '../areas/staff/retail/sale-state';
import { provisionalNumber, type OutboxItem } from './outbox';
import type { Catalogue, OfflineStore } from './store';

// The catalogue copy and the offline sale (issue #458, ADR-067 decisions 2 and 3): what is fetched for the person's selling
// branches, how the sale screen searches it with no network, what stops an offline sale, and the request it queues. The
// price on every line is sent as charged, so the server records what the customer paid.

/** Refreshed at most this often while online. */
export const REFRESH_EVERY_MS = 15 * 60 * 1000;

/** The branches the person may sell at, from the profile (a store or a branch that keeps no goods never sells). */
export function sellingBranchesOf(me: Me): { id: string; name: string }[] {
  return sellingBranches(branchesWhere(me, 'retail.sale.create'), undefined)
    .filter((b): b is typeof b & { id: string } => Boolean(b.id))
    .map((b) => ({ id: b.id, name: b.name ?? '' }));
}

export interface CatalogueSource {
  /** One page of the active items of a branch, with their stock there, and the server's Date header when it has one. */
  products(branchId: string, cursor?: string): Promise<{ items?: Product[]; next_cursor?: string; serverTime?: string }>;
  customers(): Promise<Customer[]>;
}

/**
 * Fetches the whole copy, then writes it at once, so a refresh cut half way leaves the last full copy in place. The prices
 * are as of the server's time (its Date header on the first page), not the phone's, so the server can tell a phone clock
 * that is behind (ADR-067 decision 6); the phone's time is used only when the server sent none.
 */
export async function refreshCatalogue(source: CatalogueSource, me: Me, scope: string, store: OfflineStore, now: Date): Promise<Catalogue> {
  const products: Record<string, Product[]> = {};
  let serverTime: string | undefined;
  for (const b of sellingBranchesOf(me)) {
    const rows: Product[] = [];
    let cursor: string | undefined;
    do {
      const page = await source.products(b.id, cursor);
      serverTime ??= serverIso(page.serverTime);
      rows.push(...(page.items ?? []));
      cursor = page.next_cursor;
    } while (cursor);
    products[b.id] = rows;
  }
  let customers: Customer[] = [];
  try {
    customers = await source.customers();
  } catch {
    // A person who may not read buyers sells without naming one.
  }
  const catalogue: Catalogue = { pricesAsOf: serverTime ?? now.toISOString(), products, customers };
  await store.putCatalogue(scope, catalogue);
  await store.putProfile(scope, me);
  return catalogue;
}

/** An HTTP Date header as an ISO time, or undefined when absent or unreadable. */
export function serverIso(date: string | null | undefined): string | undefined {
  if (!date) return undefined;
  const at = Date.parse(date);
  return Number.isNaN(at) ? undefined : new Date(at).toISOString();
}

/** The items of a branch matching the words, as the server's search does: code, name or category, any case. */
export function searchCatalogue(catalogue: Catalogue | null, branchId: string, term: string): Product[] {
  const rows = catalogue?.products[branchId] ?? [];
  const words = term.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return rows.slice(0, 50);
  return rows
    .filter((p) => {
      const text = `${p.code ?? ''} ${p.description ?? ''} ${p.category ?? ''}`.toLowerCase();
      return words.every((w) => text.includes(w));
    })
    .slice(0, 50);
}

/** "Prices as of 22 Oct, 09:14", in the device's own words for the time. */
export function pricesAsOfWords(pricesAsOf: string | undefined): string {
  if (!pricesAsOf) return 'No prices saved on this phone yet. Go online once to save them.';
  const at = new Date(pricesAsOf);
  return `Prices as of ${at.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}, ${at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}`;
}

/**
 * What stops an offline sale, or null. Stock on the phone is only as fresh as the copy, so too little stock does not stop
 * it: the server records the sale and flags the stock. A credit sale needs the server. The price rules are those of an
 * online sale (ADR-067 decision 11): the price saved on this phone, or another typed by a holder of retail.price.override
 * (`canType`) with a reason.
 */
export function offlineProblem(draft: Draft, canType = false): string | null {
  if (draft.lines.length === 0) return 'Add at least one item.';
  if (draft.method === 'credit') return 'A credit sale needs the internet. Choose cash, mobile money or bank.';
  for (const l of draft.lines) {
    const f = lineFigures(l);
    if (f === null) return 'Check the quantity and price on each item.';
    const pack = chosenPack(l.product, l.unitId);
    if (pack && baseQtyMilli(f.qtyMilli, pack.factor) === null) return 'A quantity does not convert exactly to single items. Change it.';
    if (f.priceMinor !== defaultPrice(l.product, l.unitId)) {
      if (!canType) return `Use the price saved on this phone for ${l.product.description ?? 'each item'}: you may not change prices.`;
      if ((l.reason ?? '').trim().length < 5) return `Say why the price of ${l.product.description ?? 'an item'} is changed (at least 5 characters).`;
    }
  }
  return null;
}

/** The outbox item of an offline sale: every line priced as charged, the client sale id and the time it happened. */
export function offlineSale(args: {
  draft: Draft;
  clientSaleId: string;
  key: string;
  scope: string;
  branchName: string;
  pricesAsOf?: string;
  buyerName?: string;
  /** An online request whose answer was lost: it goes as it was sent, dated when it was sent (ADR-067 decision 5). */
  sent?: { body: SaleRequest; at: string };
  now: Date;
}): OutboxItem {
  const { draft, clientSaleId, key, scope, branchName, pricesAsOf, now, sent } = args;
  const occurredAt = sent?.at ?? now.toISOString();
  const lines = draft.lines.map((l) => {
    const f = lineFigures(l);
    if (!f) throw new Error('invalid line');
    const pack = chosenPack(l.product, l.unitId);
    const typed = f.priceMinor !== defaultPrice(l.product, l.unitId) && (l.reason ?? '').trim() !== '';
    return {
      request: {
        product_id: l.product.id ?? '',
        qty: qtyString(f.qtyMilli),
        unit_price_minor: f.priceMinor,
        ...(pack ? { unit_id: pack.unit_id } : {}),
        ...(typed ? { price_override_reason: (l.reason ?? '').trim() } : {}),
      },
      shown: { description: `${l.product.description ?? ''}${pack ? ` (${pack.unit ?? ''})` : ''}`, qty: qtyString(f.qtyMilli), unitPriceMinor: f.priceMinor, totalMinor: f.totalMinor },
    };
  });
  const buyer = args.buyerName?.trim();
  const delivery = { occurred_at: occurredAt, recorded_offline: true, ...(pricesAsOf ? { prices_as_of: pricesAsOf } : {}) };
  const request: SaleRequest = sent ? { ...sent.body, client_sale_id: clientSaleId, ...delivery } : {
    branch_id: draft.branchId,
    payment_method: draft.method,
    ...(draft.customerId ? { customer_id: draft.customerId } : {}),
    ...(!draft.customerId && buyer ? { buyer_name: buyer } : {}),
    lines: lines.map((l) => l.request),
    client_sale_id: clientSaleId,
    ...delivery,
  };
  return {
    id: clientSaleId,
    scope,
    key,
    request,
    occurredAt,
    branchId: draft.branchId,
    branchName,
    method: draft.method,
    totalMinor: lines.reduce((sum, l) => sum + l.shown.totalMinor, 0),
    lines: lines.map((l) => l.shown),
    provisionalNo: provisionalNumber(clientSaleId),
    state: 'queued',
    attempts: 0,
    nextAttemptAt: now.getTime(),
  };
}

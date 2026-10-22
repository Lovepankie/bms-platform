import type { Me } from '../api/client';
import type { Customer, Product, SaleRequest } from '../api/retail';
import { qtyString } from '../areas/staff/retail/maths';
import { branchesWhere, sellingBranches } from '../areas/staff/retail/permissions';
import { baseQtyMilli, chosenPack } from '../areas/staff/retail/units-state';
import { lineFigures, type Draft } from '../areas/staff/retail/sale-state';
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
  /** One page of the active items of a branch, with their stock there. */
  products(branchId: string, cursor?: string): Promise<{ items?: Product[]; next_cursor?: string }>;
  customers(): Promise<Customer[]>;
}

/** Fetches the whole copy, then writes it at once, so a refresh cut half way leaves the last full copy in place. */
export async function refreshCatalogue(source: CatalogueSource, me: Me, scope: string, store: OfflineStore, now: Date): Promise<Catalogue> {
  const products: Record<string, Product[]> = {};
  for (const b of sellingBranchesOf(me)) {
    const rows: Product[] = [];
    let cursor: string | undefined;
    do {
      const page = await source.products(b.id, cursor);
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
  const catalogue: Catalogue = { pricesAsOf: now.toISOString(), products, customers };
  await store.putCatalogue(scope, catalogue);
  await store.putProfile(scope, me);
  return catalogue;
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
 * it: the server records the sale and flags the stock. A credit sale needs the server.
 */
export function offlineProblem(draft: Draft): string | null {
  if (draft.lines.length === 0) return 'Add at least one item.';
  if (draft.method === 'credit') return 'A credit sale needs the internet. Choose cash, mobile money or bank.';
  for (const l of draft.lines) {
    const f = lineFigures(l);
    if (f === null) return 'Check the quantity and price on each item.';
    const pack = chosenPack(l.product, l.unitId);
    if (pack && baseQtyMilli(f.qtyMilli, pack.factor) === null) return 'A quantity does not convert exactly to single items. Change it.';
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
  now: Date;
}): OutboxItem {
  const { draft, clientSaleId, key, scope, branchName, pricesAsOf, now } = args;
  const occurredAt = now.toISOString();
  const lines = draft.lines.map((l) => {
    const f = lineFigures(l);
    if (!f) throw new Error('invalid line');
    const pack = chosenPack(l.product, l.unitId);
    return {
      request: {
        product_id: l.product.id ?? '',
        qty: qtyString(f.qtyMilli),
        unit_price_minor: f.priceMinor,
        ...(pack ? { unit_id: pack.unit_id } : {}),
      },
      shown: { description: `${l.product.description ?? ''}${pack ? ` (${pack.unit ?? ''})` : ''}`, qty: qtyString(f.qtyMilli), unitPriceMinor: f.priceMinor, totalMinor: f.totalMinor },
    };
  });
  const buyer = args.buyerName?.trim();
  const request: SaleRequest = {
    branch_id: draft.branchId,
    payment_method: draft.method,
    ...(draft.customerId ? { customer_id: draft.customerId } : {}),
    ...(!draft.customerId && buyer ? { buyer_name: buyer } : {}),
    lines: lines.map((l) => l.request),
    client_sale_id: clientSaleId,
    occurred_at: occurredAt,
    recorded_offline: true,
    ...(pricesAsOf ? { prices_as_of: pricesAsOf } : {}),
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

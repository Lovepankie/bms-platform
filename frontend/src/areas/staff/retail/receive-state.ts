import type { ReceiveLine, TransferLine } from '../../../api/retail';
import { milliOf, parseCount, qtyString, showQty } from './maths';

// The receive form's pure logic (ADR-033, capability transfer_receipt): what arrived against what was
// sent, line by line. Quantities are integer thousandths, never floats. The server decides: it refuses
// more than was sent (422 received_exceeds_dispatched) and a count that leaves an item out; the hints
// only save a round trip. Less than was sent is allowed and is written off as a shortage.

/** What arrived per product id, as typed; starts as everything that was sent. */
export type Arrived = Record<string, string>;

export function everythingArrived(lines: TransferLine[]): Arrived {
  return Object.fromEntries(lines.map((l) => [l.product_id ?? '', showQty(l.qty ?? '0')]));
}

/** A line's hint in plain words, or null when the entry is fine and nothing is short. */
export function receiveLineHint(sent: string, typed: string): string | null {
  const got = parseCount(typed);
  if (got === null) return 'Enter how many arrived, or 0 if none did.';
  const wanted = milliOf(sent);
  if (got > wanted) return `Only ${showQty(sent)} were sent.`;
  if (got < wanted) return `${showQty(qtyString(wanted - got))} short. The difference is written off as a loss.`;
  return null;
}

/** True when the entry is a number that cannot be sent: not a quantity, or more than was sent. */
export function receiveLineInvalid(sent: string, typed: string): boolean {
  const got = parseCount(typed);
  return got === null || got > milliOf(sent);
}

/** What stops the receipt being saved, in plain words, or null. */
export function receiveProblem(lines: TransferLine[], arrived: Arrived): string | null {
  return lines.some((l) => receiveLineInvalid(l.qty ?? '0', arrived[l.product_id ?? ''] ?? '')) ? 'Fix the items marked above.' : null;
}

/** Everything arrived as sent: the request can then leave the lines out. */
export function allArrived(lines: TransferLine[], arrived: Arrived): boolean {
  return lines.every((l) => parseCount(arrived[l.product_id ?? ''] ?? '') === milliOf(l.qty ?? '0'));
}

export function buildReceive(lines: TransferLine[], arrived: Arrived): ReceiveLine[] | undefined {
  if (allArrived(lines, arrived)) return undefined;
  return lines.map((l) => ({ product_id: l.product_id ?? '', qty: qtyString(parseCount(arrived[l.product_id ?? ''] ?? '') ?? 0) }));
}

/** The days a transfer has been on its way, in words. */
export function waiting(ageDays: number | undefined): string {
  const days = ageDays ?? 0;
  if (days <= 0) return 'sent today';
  return days === 1 ? 'sent yesterday' : `on its way for ${days} days`;
}

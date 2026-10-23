import { api, problemOf } from './client';
import type { components } from './schema';

// Subscription billing (issue #480, ADR-075): the Owner's Plan and billing page and the overdue banner on the business's own host,
// and the operator's grace period, words on how to pay and list of businesses due for suspension on the platform host. Typed by
// the generated schema (`npm run gen:api`).

type S = components['schemas'];

export type BillingView = S['BillingView'];
export type BillingPayment = S['BillingPayment'];
export type BillingNotice = S['BillingNotice'];
export type BillingSettings = S['BillingSettings'];
export type BillingSettingsRequest = S['BillingSettingsRequest'];
export type DueForSuspension = S['DueForSuspension'];
export type DueItem = S['DueForSuspensionItem'];

function fail(error: unknown, fallback: string): never {
  const problem = problemOf(error);
  const fields = problem.errors ?? [];
  throw new Error(fields.length > 0 ? fields.map((f) => f.message).join(' ') : (problem.detail ?? fallback));
}

export async function billingView(): Promise<BillingView> {
  const { data, error } = await api.GET('/api/v1/billing');
  if (error || !data) fail(error, 'Could not load the plan and billing.');
  return data;
}

export async function billingNotice(): Promise<BillingNotice> {
  const { data, error } = await api.GET('/api/v1/billing/notice');
  if (error || !data) fail(error, 'Could not load the subscription notice.');
  return data;
}

/** Saves the receipt of a payment as a PDF on this device. */
export async function downloadReceipt(paymentId: string, receiptNo: string): Promise<void> {
  const { data, error } = await api.GET('/api/v1/billing/payments/{payment_id}/receipt', {
    params: { path: { payment_id: paymentId } },
    parseAs: 'blob',
  });
  if (error || !data) fail(error, 'Could not download the receipt. Try again.');
  const url = URL.createObjectURL(data as Blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `receipt-${receiptNo}.pdf`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoked later: revoking right after the click can cancel the download in Safari and Firefox.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function billingSettings(): Promise<BillingSettings> {
  const { data, error } = await api.GET('/api/v1/platform/billing/settings');
  if (error || !data) fail(error, 'Could not load the billing settings.');
  return data;
}

export async function saveBillingSettings(body: BillingSettingsRequest): Promise<BillingSettings> {
  const { data, error } = await api.PUT('/api/v1/platform/billing/settings', { body });
  if (error || !data) fail(error, 'Could not save the billing settings.');
  return data;
}

export async function dueForSuspension(): Promise<DueForSuspension> {
  const { data, error } = await api.GET('/api/v1/platform/billing/due-for-suspension');
  if (error || !data) fail(error, 'Could not load the businesses due for suspension.');
  return data;
}

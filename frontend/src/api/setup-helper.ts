import { api } from './client';
import { postFile } from './lending-forms';
import type { components } from './schema';

// The setup helper (issue #626, ADR-102): the app reads a list the owner gives (typed, a photo, or a CSV, Excel or PDF file)
// into draft rows of an import. The server saves nothing: the rows go into the import's own check, and only the owner's tap
// there adds them. The generated schema marks every field optional; these are the shapes the server always sends
// (core/help/internal/SetupHelperController.java, retail ItemDraftApi.java, lending ExistingLoanApi.java).

export interface SetupHelperStatus {
  /** The helper may read a list for this business; while false the box is not drawn. */
  enabled: boolean;
  /** A photo can be read too. */
  photos: boolean;
}

/** Why there are no rows: no provider set, every one failed, the day's drafts used up, or nothing in the file to read. */
export type DraftReason = 'no_provider' | 'all_failed' | 'daily_cap' | 'unreadable';

export interface ItemDraftRow {
  description: string;
  category: string;
  unit: string;
  sell_price_minor: number | null;
  cost_price_minor: number | null;
  pack_unit: string | null;
  pack_factor: string | null;
}

export interface ItemDraft {
  reason: DraftReason | null;
  rows: ItemDraftRow[];
  currency_exponent: number;
}

export interface LoanDraftPayment {
  date: string | null;
  amount_minor: number | null;
}

export interface LoanDraftRow {
  ref: string;
  member_no: string | null;
  member_full_name: string | null;
  member_name: string | null;
  member_phone: string | null;
  principal_minor: number | null;
  disbursement_date: string | null;
  term_months: number | null;
  agreed_total_minor: number | null;
  rate_percent: string | null;
  total_worked_out: boolean;
  payments: LoanDraftPayment[];
}

export interface LoanDraft {
  reason: DraftReason | null;
  loans: LoanDraftRow[];
  currency_exponent: number;
}

// Keeps the generated types honest: a change to the contract that drops one of these breaks this line, not a screen.
export type GeneratedSetupHelperStatus = components['schemas']['SetupHelperStatus'];
export type GeneratedItemDraft = components['schemas']['RetailItemDraft'];
export type GeneratedLoanDraft = components['schemas']['LoanRegisterDraft'];

/** What the owner gave: the list as typed, or one photo or file. */
export type Given = { text: string } | { file: Blob; name: string };

function send<T>(path: string, given: Given, fields: Record<string, string> = {}): Promise<T> {
  if ('text' in given) return postFile<T>(path, null, '', { ...fields, text: given.text });
  return postFile<T>(path, given.file, given.name, fields);
}

export const setupHelper = {
  async status(): Promise<SetupHelperStatus> {
    const r = await api.GET('/api/v1/help/setup');
    // Off when it cannot be read: the import screens work as before without it.
    return r.data && r.response.ok ? (r.data as SetupHelperStatus) : { enabled: false, photos: false };
  },

  draftItems(given: Given, shopType?: string): Promise<ItemDraft> {
    return send<ItemDraft>('/api/v1/retail/products/import/draft', given, shopType ? { shop_type: shopType } : {});
  },

  draftLoans(given: Given): Promise<LoanDraft> {
    return send<LoanDraft>('/api/v1/lending/existing-loans/import/draft', given);
  },
};

export type SetupHelperApi = typeof setupHelper;

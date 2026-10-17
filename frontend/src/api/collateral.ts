import { api } from './client';
import { guarded, postFile, unwrapForm, type UploadProgress } from './lending-forms';
import type { components } from './schema';

// The collateral client for the staff collateral register (issue #280, chapter 7 section 7.11.14), typed
// by the generated schema. Screens use only `CollateralApi`, so the in-memory mock of the tests can stand in
// for it. Every change carries the item's `version` as If-Match.

type S = components['schemas'];

export type CollateralItem = S['Collateral'];
export type CollateralDetail = S['CollateralDetail'];
export type CollateralPage = S['CollateralPage'];
export type CollateralValuation = S['CollateralValuation'];
export type CollateralEvent = S['CollateralEvent'];
export type CollateralDocument = S['CollateralDocument'];
export type CreateCollateralBody = S['CreateCollateralRequest'];
export type UpdateCollateralBody = S['UpdateCollateralRequest'];
export type ValuationBody = S['CollateralValuationRequest'];
export type EventBody = S['CollateralEventRequest'];

export interface CollateralQuery {
  memberId?: string;
  type?: string;
  custody?: string;
  branchIds?: string[];
  cursor?: string;
  signal?: AbortSignal;
}

export interface CollateralApi {
  list(q: CollateralQuery): Promise<CollateralPage>;
  get(id: string): Promise<CollateralDetail>;
  create(body: CreateCollateralBody, idempotencyKey: string): Promise<CollateralItem>;
  update(id: string, version: number, body: UpdateCollateralBody): Promise<CollateralItem>;
  addValuation(id: string, body: ValuationBody): Promise<CollateralValuation>;
  recordEvent(id: string, version: number, body: EventBody): Promise<CollateralDetail>;
  /** Asks for the item to be released; `executed` is false while a second person must agree. */
  requestRelease(id: string, version: number, body: { collected_by: string; note?: string }): Promise<{ executed: boolean }>;
  listDocuments(id: string): Promise<CollateralDocument[]>;
  uploadDocument(id: string, file: Blob, fileName: string, opts?: { onProgress?: UploadProgress; signal?: AbortSignal }): Promise<CollateralDocument>;
  /** A 5 minute signed address for a stored photo or scan. */
  documentUrl(documentId: string): Promise<string>;
}

const ifMatch = (version: number) => ({ 'If-Match': String(version) });

const rawCollateral: CollateralApi = {
  async list(q) {
    return unwrapForm(
      await api.GET('/api/v1/lending/collateral', {
        params: {
          query: {
            member_id: q.memberId,
            type: q.type ? [q.type] : undefined,
            custody_status: q.custody ? [q.custody] : undefined,
            branch_id: q.branchIds,
            limit: 50,
            cursor: q.cursor,
          },
        },
        signal: q.signal,
      }),
    );
  },

  async get(id) {
    return unwrapForm(await api.GET('/api/v1/lending/collateral/{collateral_id}', { params: { path: { collateral_id: id } } }));
  },

  async create(body, idempotencyKey) {
    // The route does not read Idempotency-Key today (chapter 7 section 7.8), so the key is only sent for the day it does. An item
    // with a reference number cannot register twice (the server refuses it); for any item the screen lists the member's
    // collateral after an answer that may have been lost, before it allows another try.
    return unwrapForm(await api.POST('/api/v1/lending/collateral', { body, headers: { 'Idempotency-Key': idempotencyKey } }));
  },

  async update(id, version, body) {
    return unwrapForm(
      await api.PATCH('/api/v1/lending/collateral/{collateral_id}', { params: { path: { collateral_id: id }, header: ifMatch(version) }, body }),
    );
  },

  async addValuation(id, body) {
    return unwrapForm(await api.POST('/api/v1/lending/collateral/{collateral_id}/valuations', { params: { path: { collateral_id: id } }, body }));
  },

  async recordEvent(id, version, body) {
    return unwrapForm(
      await api.POST('/api/v1/lending/collateral/{collateral_id}/events', { params: { path: { collateral_id: id }, header: ifMatch(version) }, body }),
    );
  },

  async requestRelease(id, version, body) {
    const out = unwrapForm(
      await api.POST('/api/v1/lending/collateral/{collateral_id}/release', { params: { path: { collateral_id: id }, header: ifMatch(version) }, body }),
    );
    return { executed: out.executed ?? false };
  },

  async listDocuments(id) {
    return unwrapForm(await api.GET('/api/v1/lending/collateral/{collateral_id}/documents', { params: { path: { collateral_id: id } } })).items ?? [];
  },

  uploadDocument(id, file, fileName, opts) {
    return postFile<CollateralDocument>(`/api/v1/lending/collateral/${encodeURIComponent(id)}/documents`, file, fileName, {}, opts);
  },

  async documentUrl(documentId) {
    const out = unwrapForm(await api.POST('/api/v1/documents/{document_id}/download-url', { params: { path: { document_id: documentId } } }));
    return out.url ?? '';
  },
};

export const realCollateral: CollateralApi = guarded(rawCollateral);

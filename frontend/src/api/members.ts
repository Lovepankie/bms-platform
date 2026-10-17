import { api } from './client';
import { guarded, postFile, unwrapForm, type UploadProgress } from './lending-forms';
import type { components } from './schema';

// The members client for the staff member screens (issue #280, chapter 7 section 7.11.11), typed by the
// generated schema like lending.ts. Screens use only `MembersApi`, so the in-memory mock of the tests
// (members-mock.ts) can stand in for the real client. Edits carry the member's `version` as If-Match; a
// registration carries the Idempotency-Key its form generated (one per form, stable across retries).

type S = components['schemas'];

export type MemberRecord = S['Member'];
export type MemberListItem = S['MemberListItem'];
export type MemberPage = S['MemberPage'];
export type NextOfKin = S['NextOfKin'];
export type MemberDocument = S['MemberDocument'];
export type DuplicateCandidate = S['DuplicateCandidate'];
export type CreateMemberBody = S['CreateMemberRequest'];
export type UpdateMemberBody = S['UpdateMemberRequest'];
export type KinBody = S['CreateNextOfKinRequest'];
export type KinPatch = S['UpdateNextOfKinRequest'];
export type MemberRelationships = S['MemberRelationships'];
export type DocKind = 'id_front' | 'id_back' | 'photo' | 'other';

export interface MemberQuery {
  q?: string;
  status?: string;
  branchIds?: string[];
  cursor?: string;
  signal?: AbortSignal;
}

export interface MembersApi {
  list(q: MemberQuery): Promise<MemberPage>;
  get(id: string): Promise<MemberRecord>;
  create(body: CreateMemberBody, idempotencyKey: string): Promise<MemberRecord>;
  update(id: string, version: number, body: UpdateMemberBody): Promise<MemberRecord>;
  duplicateCheck(body: { full_name?: string; phone?: string; national_id?: string }, signal?: AbortSignal): Promise<DuplicateCandidate[]>;
  decideKyc(id: string, version: number, decision: 'verified' | 'rejected', note?: string): Promise<MemberRecord>;
  listKin(memberId: string): Promise<NextOfKin[]>;
  addKin(memberId: string, body: KinBody): Promise<NextOfKin>;
  updateKin(id: string, version: number, body: KinPatch): Promise<NextOfKin>;
  removeKin(id: string, version: number): Promise<void>;
  decideKinLink(id: string, version: number, decision: 'confirm' | 'reject'): Promise<NextOfKin>;
  relationships(memberId: string): Promise<MemberRelationships>;
  listDocuments(memberId: string): Promise<MemberDocument[]>;
  uploadDocument(memberId: string, kind: DocKind, file: Blob, fileName: string, opts?: { onProgress?: UploadProgress; signal?: AbortSignal }): Promise<MemberDocument>;
  /** A 5 minute signed address for a stored document. */
  documentUrl(documentId: string): Promise<string>;
}

const ifMatch = (version: number) => ({ 'If-Match': String(version) });

const rawMembers: MembersApi = {
  async list(q) {
    return unwrapForm(
      await api.GET('/api/v1/lending/members', {
        params: { query: { q: q.q || undefined, status: q.status ? [q.status] : undefined, branch_id: q.branchIds, limit: 25, cursor: q.cursor } },
        signal: q.signal,
      }),
    );
  },

  async get(id) {
    return unwrapForm(await api.GET('/api/v1/lending/members/{member_id}', { params: { path: { member_id: id } } }));
  },

  async create(body, idempotencyKey) {
    // The route does not read Idempotency-Key today (it is not a money route, chapter 7 section 7.8), so the key is only sent for
    // the day it does. What stops a second registration is the phone and national ID checks plus the screen: after an answer that
    // may have been lost, it looks the member up (duplicateCheck) before it allows another try.
    return unwrapForm(await api.POST('/api/v1/lending/members', { body, headers: { 'Idempotency-Key': idempotencyKey } }));
  },

  async update(id, version, body) {
    return unwrapForm(await api.PATCH('/api/v1/lending/members/{member_id}', { params: { path: { member_id: id }, header: ifMatch(version) }, body }));
  },

  async duplicateCheck(body, signal) {
    const out = unwrapForm(await api.POST('/api/v1/lending/members/duplicate-check', { body, signal }));
    return out.candidates ?? [];
  },

  async decideKyc(id, version, decision, note) {
    return unwrapForm(
      await api.POST('/api/v1/lending/members/{member_id}/kyc/verify', {
        params: { path: { member_id: id }, header: ifMatch(version) },
        body: { decision, note: note || undefined },
      }),
    );
  },

  async listKin(memberId) {
    return unwrapForm(await api.GET('/api/v1/lending/members/{member_id}/next-of-kin', { params: { path: { member_id: memberId } } })).items ?? [];
  },

  async addKin(memberId, body) {
    return unwrapForm(await api.POST('/api/v1/lending/members/{member_id}/next-of-kin', { params: { path: { member_id: memberId } }, body }));
  },

  async updateKin(id, version, body) {
    return unwrapForm(await api.PATCH('/api/v1/lending/next-of-kin/{kin_id}', { params: { path: { kin_id: id }, header: ifMatch(version) }, body }));
  },

  async removeKin(id, version) {
    const r = await api.DELETE('/api/v1/lending/next-of-kin/{kin_id}', { params: { path: { kin_id: id }, header: ifMatch(version) } });
    if (!r.response.ok) unwrapForm({ error: r.error ?? {}, response: r.response });
  },

  async decideKinLink(id, version, decision) {
    return unwrapForm(
      await api.POST('/api/v1/lending/next-of-kin/{kin_id}/link', { params: { path: { kin_id: id }, header: ifMatch(version) }, body: { decision } }),
    );
  },

  async relationships(memberId) {
    return unwrapForm(await api.GET('/api/v1/lending/members/{member_id}/relationships', { params: { path: { member_id: memberId } } }));
  },

  async listDocuments(memberId) {
    return unwrapForm(await api.GET('/api/v1/lending/members/{member_id}/documents', { params: { path: { member_id: memberId } } })).items ?? [];
  },

  uploadDocument(memberId, kind, file, fileName, opts) {
    return postFile<MemberDocument>(`/api/v1/lending/members/${encodeURIComponent(memberId)}/documents`, file, fileName, { doc_kind: kind }, opts);
  },

  async documentUrl(documentId) {
    const out = unwrapForm(await api.POST('/api/v1/documents/{document_id}/download-url', { params: { path: { document_id: documentId } } }));
    return out.url ?? '';
  },
};

export const realMembers: MembersApi = guarded(rawMembers);

import type { CollateralApi, CollateralItem } from './collateral';
import { FormError } from './lending-forms';
import type { DuplicateCandidate, MemberDocument, MemberRecord, MembersApi, NextOfKin } from './members';

// Fabricated, in-memory stand-ins for the members and collateral clients, with the server's rules that the screens depend on
// (a national ID already registered is refused with its member number, a stale version is a 409, a phone is stored as E.164).
// Used by the tests through LendingApiContext. Nothing here is real data. `calls` lets a test see exactly what was sent.

export interface MembersMock {
  api: MembersApi;
  members: MemberRecord[];
  calls: { create: { body: unknown; key: string }[]; update: { id: string; version: number; body: unknown }[]; uploads: { kind: string; size: number }[] };
  /** The next create fails once with this error (after saving, to model a lost answer, when `saved` is true). */
  failNextCreate(error: FormError, saved?: boolean): void;
  failNextUpload(error: FormError): void;
}

const phoneOf = (raw: string | undefined): string => {
  const d = (raw ?? '').replace(/[\s-]/g, '').replace(/^\+?256/, '').replace(/^0/, '');
  return `+256${d}`;
};

export function createMembersMock(seed: MemberRecord[] = []): MembersMock {
  const members = seed.map((m) => ({ ...m }));
  const kin: NextOfKin[] = [];
  const docs: MemberDocument[] = [];
  const calls: MembersMock['calls'] = { create: [], update: [], uploads: [] };
  let failCreate: { error: FormError; saved: boolean } | null = null;
  let failUpload: FormError | null = null;
  let n = members.length;

  const mask = (s: string | undefined) => (s ? `***${s.slice(-4)}` : undefined);
  const find = (id: string) => {
    const m = members.find((x) => x.id === id);
    if (!m) throw new FormError('Not found.', 404, 'not_found');
    return m;
  };

  const api: MembersApi = {
    async list(q) {
      const t = (q.q ?? '').toLowerCase();
      const items = members
        .filter((m) => (!q.status || m.status === q.status) && (t === '' || [m.full_name, m.member_no].some((v) => v?.toLowerCase().includes(t))))
        .map((m) => ({ id: m.id, branch_id: m.branch_id, member_no: m.member_no, full_name: m.full_name, phone_e164_masked: mask(m.phone_e164), national_id_masked: mask(m.national_id), kyc_status: m.kyc_status, status: m.status, is_blacklisted: m.is_blacklisted }));
      return { items };
    },
    async get(id) {
      return { ...find(id) };
    },
    async create(body, key) {
      calls.create.push({ body, key });
      const phone = phoneOf(body.phone);
      const holder = members.find((m) => body.national_id && m.national_id === body.national_id.replace(/\s/g, '').toUpperCase());
      if (holder) throw new FormError(`Member ${holder.member_no} already has this NIN.`, 409, 'duplicate_nin', { national_id: `Member ${holder.member_no} already has this NIN.` }, `Member ${holder.member_no} already has this NIN.`);
      if (members.some((m) => m.phone_e164 === phone) && !body.confirmed_not_duplicate) throw new FormError('Another member has this phone.', 409, 'duplicate_phone', { phone: 'Another member has this phone number.' });
      const record: MemberRecord = {
        id: `00000000-0000-4000-8000-0000000001${String(++n).padStart(2, '0')}`, branch_id: body.branch_id, member_no: `M${String(n).padStart(6, '0')}`, full_name: body.full_name,
        phone_e164: phone, id_type: body.id_type, national_id: body.national_id?.replace(/\s/g, '').toUpperCase(), kyc_status: 'incomplete', status: 'active', version: 1,
      };
      if (failCreate) {
        const { error, saved } = failCreate;
        failCreate = null;
        if (saved) members.push(record);
        throw error;
      }
      members.push(record);
      return { ...record };
    },
    async update(id, version, body) {
      calls.update.push({ id, version, body });
      const m = find(id);
      if (m.version !== version) throw new FormError('Someone else changed this since you opened it.', 409, 'version_conflict');
      Object.assign(m, body, { version: (m.version ?? 0) + 1 });
      return { ...m };
    },
    async duplicateCheck(body) {
      const nin = body.national_id?.toUpperCase();
      const out: DuplicateCandidate[] = [];
      for (const m of members) {
        const reasons = [
          ...(nin && m.national_id === nin ? ['nin'] : []),
          ...(body.phone && m.phone_e164 === body.phone ? ['phone'] : []),
          ...(body.full_name && m.full_name?.toLowerCase() === body.full_name.toLowerCase() ? ['name'] : []),
        ];
        if (reasons.length) out.push({ id: m.id, member_no: m.member_no, full_name: m.full_name, phone_e164_masked: mask(m.phone_e164), match_reasons: reasons, in_scope: true });
      }
      return out;
    },
    async decideKyc(id, version, decision) {
      const m = find(id);
      if (m.version !== version) throw new FormError('Someone else changed this.', 409, 'version_conflict');
      m.kyc_status = decision === 'verified' ? 'verified' : 'rejected';
      m.version = (m.version ?? 0) + 1;
      return { ...m };
    },
    async listKin(memberId) {
      return kin.filter((k) => k.member_id === memberId);
    },
    async addKin(memberId, body) {
      const k: NextOfKin = { id: `k${kin.length + 1}`, member_id: memberId, full_name: body.full_name, phone_e164: phoneOf(body.phone), relationship: body.relationship, is_primary: body.is_primary, link_status: 'none', version: 1 };
      kin.push(k);
      return k;
    },
    async updateKin(id, version, body) {
      const k = kin.find((x) => x.id === id);
      if (!k || k.version !== version) throw new FormError('Someone else changed this.', 409, 'version_conflict');
      Object.assign(k, { full_name: body.full_name ?? k.full_name, version: (k.version ?? 0) + 1 });
      return k;
    },
    async removeKin(id) {
      kin.splice(kin.findIndex((x) => x.id === id), 1);
    },
    async decideKinLink(id) {
      return kin.find((x) => x.id === id) ?? {};
    },
    async relationships(memberId) {
      return { member_id: memberId, named_as_kin_by: [], names_as_kin: [] };
    },
    async listDocuments(memberId) {
      return docs.filter((d) => d.uploaded_by === memberId);
    },
    async uploadDocument(memberId, kind, file, _name, opts) {
      calls.uploads.push({ kind, size: file.size });
      if (failUpload) {
        const error = failUpload;
        failUpload = null;
        throw error;
      }
      opts?.onProgress?.(file.size, file.size);
      const d: MemberDocument = { document_id: `d${docs.length + 1}`, doc_kind: kind, content_type: file.type, size_bytes: file.size, uploaded_by: memberId };
      docs.push(d);
      return d;
    },
    async documentUrl(id) {
      return `https://files.example.test/${id}`;
    },
  };
  return { api, members, calls, failNextCreate: (error, saved = false) => { failCreate = { error, saved }; }, failNextUpload: (error) => { failUpload = error; } };
}

export interface CollateralMock {
  api: CollateralApi;
  items: CollateralItem[];
  calls: { create: { body: unknown; key: string }[] };
}

export function createCollateralMock(seed: CollateralItem[] = []): CollateralMock {
  const items = seed.map((c) => ({ ...c }));
  const calls: CollateralMock['calls'] = { create: [] };
  const api: CollateralApi = {
    async list(q) {
      return { items: items.filter((c) => (!q.memberId || c.member_id === q.memberId) && (!q.type || c.collateral_type === q.type) && (!q.custody || c.custody_status === q.custody)) };
    },
    async get(id) {
      const item = items.find((c) => c.id === id);
      if (!item) throw new FormError('Not found.', 404, 'not_found');
      return { item, valuations: [], events: [] };
    },
    async create(body, key) {
      calls.create.push({ body, key });
      const ref = body.reference_no?.replace(/\s/g, '').toUpperCase();
      if (ref && items.some((c) => c.collateral_type === body.collateral_type && c.reference_no_normalised === ref && c.custody_status !== 'released')) {
        throw new FormError('An item of this type with this reference is already registered.', 409, 'collateral_already_pledged', { reference_no: 'An item of this type with this reference is already registered.' });
      }
      const item: CollateralItem = {
        id: `c${items.length + 1}`, member_id: body.member_id, collateral_type: body.collateral_type, description: body.description, reference_no: body.reference_no,
        reference_no_normalised: ref, custody_status: body.custody_status ?? 'pledged', estimated_value_minor: body.estimated_value_minor, currency: 'UGX', version: 1,
      };
      items.push(item);
      return item;
    },
    async update(id, version, body) {
      const item = items.find((c) => c.id === id);
      if (!item || item.version !== version) throw new FormError('Someone else changed this.', 409, 'version_conflict');
      Object.assign(item, body, { version: (item.version ?? 0) + 1 });
      return item;
    },
    async addValuation(_id, body) {
      return { id: 'v1', valued_on: body.valued_on, market_value_minor: body.market_value_minor };
    },
    async recordEvent(id) {
      return api.get(id);
    },
    async requestRelease() {
      return { executed: false };
    },
    async listDocuments() {
      return [];
    },
    async uploadDocument(_id, file) {
      return { document_id: 'd1', content_type: file.type, size_bytes: file.size };
    },
    async documentUrl(id) {
      return `https://files.example.test/${id}`;
    },
  };
  return { api, items, calls };
}

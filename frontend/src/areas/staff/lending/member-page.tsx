import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { lending } from '../../../api/lending';
import { useCollateralApi, useMembersApi } from '../../../api/lending-context';
import type { MemberRecord } from '../../../api/members';
import { branchLabel } from '../../../auth/branch';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { LoanRows } from './loans';
import { money, words } from './loan-state';
import { MemberSmsControl } from './member-sms';
import { DocumentsSection } from './member-documents';
import { NextOfKinSection } from './member-kin';
import { holds, mayEditMember, mayVerifyKyc, showCollateral, MEMBERS_VERIFY } from './member-permissions';
import { kycBadge, kycMissing, kycWords, relationshipWords, showPhone } from './member-state';
import { showInvestments } from './investment-state';
import { showSavings } from './savings-permissions';
import { MembersGate } from './member-ui';
import { BusyButton } from '../../../app/ui/BusyButton';

// A member's page (FR-MEM-05, FR-MEM-08, FR-MEM-09, FR-MEM-10): who they are, what is missing before their details can be
// checked, the check itself for staff who may do it, next of kin, documents, the relationship panel, their loans, savings and
// collateral, and the text-message choice. Everything shown is the server's, in the member's branch scope.

/** The ID number is shown in full only to staff who change or check members; others see the last four characters. */
const maskUnless = (may: boolean, value: string | undefined): string | undefined => (may || !value ? value : `${'*'.repeat(Math.max(0, value.length - 4))}${value.slice(-4)}`);

function Line({ label, value }: { label: string; value?: string | number | null }) {
  if (value === undefined || value === null || value === '') return null;
  return (
    <>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </>
  );
}

function KycPanel({ member, kinCount, hasIdFront }: { member: MemberRecord; kinCount: number; hasIdFront: boolean }) {
  const { me } = useStaff();
  const api = useMembersApi();
  const queryClient = useQueryClient();
  const [rejecting, setRejecting] = useState(false);
  const [note, setNote] = useState('');
  const decide = useMutation({
    mutationFn: (v: { decision: 'verified' | 'rejected'; note?: string }) => api.decideKyc(member.id ?? '', member.version ?? 0, v.decision, v.note),
    onSuccess: () => {
      setRejecting(false);
      void queryClient.invalidateQueries({ queryKey: ['members'] });
    },
  });
  const missing = kycMissing(member, kinCount, hasIdFront);
  const canCheck = mayVerifyKyc(me, member.branch_id) && member.kyc_status === 'pending_verification';
  return (
    <section className="rt-card stack" aria-labelledby="kyc-title" data-tour="member-kyc">
      <h2 id="kyc-title">Checking the member's details</h2>
      <p><span className={kycBadge(member.kyc_status)}>{kycWords(member.kyc_status)}</span></p>
      {member.kyc_status === 'incomplete' && (
        <p>{missing.length > 0 ? `Still needed: ${missing.join(', ')}.` : 'The details are complete and will be ready to check shortly.'}</p>
      )}
      {member.kyc_status === 'pending_verification' && !canCheck && <p>Waiting for a branch manager to check the details and the ID.</p>}
      {canCheck && (
        <>
          <p>Compare the details with the photo of the ID, then accept or refuse them.</p>
          {!rejecting ? (
            <p className="cluster">
              <BusyButton busy={decide.isPending} type="button" className="rt-primary" onClick={() => decide.mutate({ decision: 'verified' })}>Details are correct</BusyButton>
              <button type="button" className="btn-ghost" onClick={() => setRejecting(true)}>Do not accept</button>
            </p>
          ) : (
            <div className="stack">
              <label htmlFor="kyc-note">Why are they not accepted</label>
              <textarea id="kyc-note" rows={3} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
              <p className="cluster">
                <BusyButton busy={decide.isPending} type="button" className="rt-primary" disabled={note.trim() === ''} onClick={() => decide.mutate({ decision: 'rejected', note: note.trim() })}>Not accepted</BusyButton>
                <button type="button" className="btn-ghost" onClick={() => setRejecting(false)}>Cancel</button>
              </p>
            </div>
          )}
        </>
      )}
      {member.kyc_status === 'rejected' && <p>The details were not accepted. Change them, and they go back to be checked.</p>}
      <Problem error={decide.error} />
    </section>
  );
}

function Relationships({ memberId }: { memberId: string }) {
  const api = useMembersApi();
  const rel = useQuery({ queryKey: ['members', 'relationships', memberId], queryFn: () => api.relationships(memberId) });
  const named = rel.data?.named_as_kin_by ?? [];
  if (!rel.data || named.length === 0) return null;
  return (
    <section className="rt-card stack" aria-labelledby="rel-title">
      <h2 id="rel-title">Members who name this member as next of kin</h2>
      <ul className="ln-list">
        {named.map((r) => (
          <li key={r.id ?? r.member_no}>
            {r.in_scope && r.id ? <Link to="/staff/lending/members/$memberId" params={{ memberId: r.id }}>{r.full_name ?? r.member_no}</Link> : <span>{r.member_no}</span>}
            {r.relationship ? `, ${relationshipWords(r.relationship).toLowerCase()}` : ''}
          </li>
        ))}
      </ul>
    </section>
  );
}

function MemberLoans({ memberId }: { memberId: string }) {
  const loans = useQuery({ queryKey: ['lending', 'loans', 'member', memberId], queryFn: () => lending.listLoans({ memberId }) });
  return (
    <section className="rt-card stack" aria-labelledby="loans-title" data-tour="member-loans">
      <h2 id="loans-title">Loans</h2>
      {loans.isPending && <p className="loading">Loading loans</p>}
      <Problem error={loans.error} />
      {loans.data && ((loans.data.items ?? []).length === 0 ? <p className="empty-state">No loans yet.</p> : <LoanRows items={loans.data.items ?? []} />)}
    </section>
  );
}

function MemberCollateral({ memberId, canAdd }: { memberId: string; canAdd: boolean }) {
  const api = useCollateralApi();
  const items = useQuery({ queryKey: ['collateral', 'member', memberId], queryFn: () => api.list({ memberId }) });
  return (
    <section className="rt-card stack" aria-labelledby="col-title">
      <h2 id="col-title">Collateral</h2>
      {items.isPending && <p className="loading">Loading collateral</p>}
      <Problem error={items.error} />
      {items.data && (items.data.items ?? []).length === 0 && <p className="empty-state">No collateral registered for this member.</p>}
      <ul className="ln-list">
        {(items.data?.items ?? []).map((c) => (
          <li key={c.id}>
            <Link className="ln-item" to="/staff/lending/collateral/$collateralId" params={{ collateralId: c.id ?? '' }}>
              <strong>{c.description}</strong>
              <span>{words(c.collateral_type)}, worth about {money(c.collateral_value_minor ?? c.estimated_value_minor, c.currency)}</span>
            </Link>
          </li>
        ))}
      </ul>
      {canAdd && <Link to="/staff/lending/collateral/new" search={{ memberId } as never} className="btn btn-block">Register collateral for this member</Link>}
    </section>
  );
}

export function MemberView({ member }: { member: MemberRecord }) {
  const { me } = useStaff();
  const api = useMembersApi();
  const id = member.id ?? '';
  const kin = useQuery({ queryKey: ['members', 'kin', id], queryFn: () => api.listKin(id) });
  const docs = useQuery({ queryKey: ['members', 'documents', id], queryFn: () => api.listDocuments(id) });
  const edit = mayEditMember(me, member.branch_id);
  const seesIds = holds(me, MEMBERS_VERIFY, member.branch_id);
  const branch = branchLabel((me.branches ?? []).find((b) => b.id === member.branch_id));
  return (
    <>
      <section className="rt-card ln-head" aria-label="Member" data-tour="member-header">
        <p className="ln-item-head">
          <strong>{member.full_name}</strong>
          <span className={kycBadge(member.kyc_status)}>{kycWords(member.kyc_status)}</span>
        </p>
        <p>{member.member_no}{member.status && member.status !== 'active' ? `, ${words(member.status).toLowerCase()}` : ''}{member.is_blacklisted ? ', blacklisted' : ''}</p>
        {member.is_blacklisted && member.blacklist_reason && <p className="alert alert-danger">Blacklisted: {member.blacklist_reason}</p>}
        <dl className="ln-facts">
          <Line label="Phone" value={showPhone(member.phone_e164)} />
          <Line label="Other phone" value={member.alt_phone_e164 ? showPhone(member.alt_phone_e164) : undefined} />
          <Line label="ID" value={maskUnless(edit || mayVerifyKyc(me, member.branch_id), member.id_type === 'nin' ? member.national_id : member.other_id_number)} />
          <Line label="Date of birth" value={member.date_of_birth} />
          <Line label="Work" value={member.occupation} />
          <Line label="Usual income a month" value={member.monthly_income_minor != null ? money(member.monthly_income_minor, member.currency) : undefined} />
          <Line label="Lives in" value={[member.village, member.sub_county, member.district, member.location].filter(Boolean).join(', ')} />
          <Line label="Branch" value={branch} />
        </dl>
        <p className="cluster" data-tour="member-actions">
          {edit && <Link to="/staff/lending/members/$memberId/edit" params={{ memberId: id }} className="btn rt-primary">Change details</Link>}
          {showSavings(me) && <Link to="/staff/lending/members/$memberId/savings" params={{ memberId: id }} className="btn btn-ghost">Savings</Link>}
          {showInvestments(me) && <Link to="/staff/lending/investments/member/$memberId" params={{ memberId: id }} className="btn btn-ghost">Investments</Link>}
        </p>
      </section>
      <KycPanel member={member} kinCount={(kin.data ?? []).length} hasIdFront={(docs.data ?? []).some((d) => d.doc_kind === 'id_front') || !seesIds} />
      <NextOfKinSection memberId={id} canChange={edit} />
      <DocumentsSection memberId={id} canAdd={edit} seesIds={seesIds} />
      <Relationships memberId={id} />
      {(me.permissions ?? []).includes('lending.loans.read') && <MemberLoans memberId={id} />}
      {showCollateral(me) && <MemberCollateral memberId={id} canAdd={holds(me, 'lending.collateral.manage', member.branch_id)} />}
      <MemberSmsControl memberId={id} />
    </>
  );
}

const route = getRouteApi('/staff/lending/members/$memberId');

function MemberPage() {
  const { memberId } = route.useParams();
  const api = useMembersApi();
  const member = useQuery({ queryKey: ['members', 'one', memberId], queryFn: () => api.get(memberId) });
  useCrumbLabel(member.data?.member_no);
  return (
    <MembersGate title={member.data?.full_name ?? 'Member'} tourId="member-page">
      <Link to="/staff/lending/members" className="btn btn-ghost">Back to members</Link>
      {member.isPending && <p className="loading">Loading the member</p>}
      <Problem error={member.error} />
      {member.data && <MemberView member={member.data} />}
    </MembersGate>
  );
}

export const Route = createLazyRoute('/staff/lending/members/$memberId')({ component: MemberPage });

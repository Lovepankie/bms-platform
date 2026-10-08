import { useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { investments, type InvestmentCertificate, type InvestmentStatement } from '../../../api/investments';
import { Problem } from '../retail/ui';
import { instructionWords, investmentTxnWords, payoutWords, percent } from './investment-state';
import { InvestmentsGate } from './investments';
import { money } from './loan-state';

// The investment certificate and statement (FR-INV-10), laid out to print from the phone's
// browser: the certificate's terms and the statement of every money event with the balances after
// it. Printing hides the staff navigation (lending.css, @media print). A PDF copy kept in document
// storage is a documents follow-up.

export function CertificateCard({ c }: { c: InvestmentCertificate }) {
  const cur = c.currency;
  return (
    <section aria-labelledby="iv-cert" className="rt-card iv-print">
      <p className="ln-muted">{c.tenant_name}, {c.branch_name}</p>
      <h2 id="iv-cert">Investment certificate {c.certificate_no}</h2>
      <p>
        This certifies that <strong>{c.member_name}</strong> (member {c.member_no}) holds investment {c.account_no} in{' '}
        {c.product_name}.
      </p>
      <dl className="ln-facts">
        <dt>Amount invested</dt>
        <dd>{money(c.principal_minor, cur)}</dd>
        <dt>Return rate</dt>
        <dd>{percent(c.return_rate_bp)} a year, {c.return_method === 'compound' ? 'compounding monthly' : 'flat'}</dd>
        <dt>Return paid</dt>
        <dd>{payoutWords(c.payout_frequency)}</dd>
        <dt>Term</dt>
        <dd>{c.term_months} months</dd>
        <dt>Start date</dt>
        <dd>{c.start_date}</dd>
        <dt>Maturity date</dt>
        <dd>{c.maturity_date}</dd>
        <dt>Agreed return</dt>
        <dd>{money(c.agreed_return_minor, cur)}</dd>
        <dt className="ln-strong">Value at maturity</dt>
        <dd className="ln-strong">{money(c.maturity_value_minor, cur)}</dd>
        <dt>At maturity</dt>
        <dd>{instructionWords(c.maturity_instruction)}</dd>
        <dt>Early withdrawal</dt>
        <dd>
          {c.early_withdrawal_allowed
            ? `${c.early_withdrawal_rule === 'reduced_rate' ? `earns ${percent(c.early_withdrawal_rate_bp)} a year` : 'the return is forfeited'}, penalty ${percent(c.early_withdrawal_penalty_bp)} of principal`
            : 'not allowed'}
        </dd>
      </dl>
      <p className="ln-muted">Issued {c.issued_on}.</p>
    </section>
  );
}

export function StatementTable({ statement }: { statement: InvestmentStatement }) {
  const cur = statement.investment?.currency;
  const lines = statement.lines ?? [];
  return (
    <section aria-labelledby="iv-stmt" className="iv-print">
      <h2 id="iv-stmt">Statement</h2>
      <div className="table-wrap" tabIndex={0} role="region" aria-labelledby="iv-stmt">
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>What</th>
              <th className="num">Amount</th>
              <th className="num">Principal after</th>
              <th className="num">Return payable after</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((l) => (
              <tr key={l.transaction?.id}>
                <td>{l.transaction?.value_date}</td>
                <td>
                  {investmentTxnWords(l.transaction?.txn_type)}
                  {l.transaction?.receipt_no ? ` ${l.transaction.receipt_no}` : ''}
                </td>
                <td className="num">{money(l.transaction?.amount_minor, cur)}</td>
                <td className="num">{money(l.principal_balance_minor, cur)}</td>
                <td className="num">{money(l.return_payable_minor, cur)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

const route = getRouteApi('/staff/lending/investments/$investmentId/certificate');

function CertificatePage() {
  const { investmentId } = route.useParams();
  const cert = useQuery({ queryKey: ['lending', 'investment', investmentId, 'certificate'], queryFn: () => investments.certificate(investmentId) });
  const statement = useQuery({ queryKey: ['lending', 'investment', investmentId, 'statement'], queryFn: () => investments.statement(investmentId) });
  return (
    <InvestmentsGate title="Certificate">
      <div className="ln-actions iv-no-print">
        <Link className="btn btn-ghost" to="/staff/lending/investments/$investmentId" params={{ investmentId }}>Back to the investment</Link>
        <button type="button" className="rt-primary" onClick={() => window.print()}>Print</button>
      </div>
      {cert.isPending && <p className="loading">Loading the certificate</p>}
      <Problem error={cert.error ?? statement.error} />
      {cert.data && <CertificateCard c={cert.data} />}
      {statement.data && <StatementTable statement={statement.data} />}
    </InvestmentsGate>
  );
}

export const Route = createLazyRoute('/staff/lending/investments/$investmentId/certificate')({ component: CertificatePage });

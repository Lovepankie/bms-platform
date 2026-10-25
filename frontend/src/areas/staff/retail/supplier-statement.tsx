import { useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type SupplierStatement } from '../../../api/retail';
import { WORDS } from '../../../app/glossary';
import { FieldsNeededNote } from '../../../app/ui/Field';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { defaultStatementRange } from './account-state';
import { canUse } from './permissions';
import { statementLineWords, statementText } from './payables-state';
import { Star } from './purchasing-ui';
import { showDate } from './transfers';
import { Gate, Problem, Money } from './ui';

// A supplier's statement (issue #557, D15): what was owed at the start, each purchase on credit, invoice, payment, void and
// reversal with the balance after it, and what is owed at the end. Like a customer statement it can be sent: Share uses the
// phone's own share options with the statement as text, and falls back to WhatsApp where the phone has none. Nothing is stored.

/** The statement itself: the opening balance, a card for each line with the running balance, the closing balance. */
export function SupplierStatementView({ statement }: { statement: SupplierStatement }) {
  const lines = statement.lines ?? [];
  return (
    <section aria-label="The statement" data-testid="supplier-statement" data-tour="supplier-statement-lines">
      <p>Owed at the start of {showDate(statement.from)}: <strong data-testid="statement-opening"><Money minor={statement.opening_minor ?? 0} /></strong></p>
      {lines.length === 0 ? (
        <p className="empty-state">Nothing happened with this supplier between these dates.</p>
      ) : (
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {lines.map((l, i) => (
            <li key={`${l.source_id ?? ''}-${l.kind ?? ''}-${i}`} className="rt-card" data-testid="statement-line">
              <p>{showDate(l.date)}: {statementLineWords(l)}</p>
              <p>
                {(l.increase_minor ?? 0) > 0 ? 'Adds ' : 'Takes off '}<Money minor={(l.increase_minor ?? 0) > 0 ? (l.increase_minor ?? 0) : (l.decrease_minor ?? 0)} />. Owed after it{' '}
                <strong data-testid="statement-balance"><Money minor={l.balance_minor ?? 0} /></strong>
              </p>
            </li>
          ))}
        </ul>
      )}
      <p className="rt-total">Owed at the end of {showDate(statement.to)}: <span data-testid="statement-closing"><Money minor={statement.closing_minor ?? 0} /></span></p>
    </section>
  );
}

/** Sends the statement as text: the phone's share options when it has them, otherwise WhatsApp with the text typed in. */
async function shareText(title: string, text: string): Promise<void> {
  const nav = navigator as Navigator & { share?: (data: ShareData) => Promise<void> };
  if (typeof nav.share === 'function') {
    try {
      await nav.share({ title, text });
      return;
    } catch (e) {
      // The person closed the share options: nothing to do.
      if (e instanceof DOMException && e.name === 'AbortError') return;
    }
  }
  window.open(`https://wa.me/?text=${encodeURIComponent(text)}`, '_blank', 'noopener');
}

const route = getRouteApi('/staff/retail/suppliers/$supplierId/statement');

export function SupplierStatementPage() {
  const { me } = useStaff();
  const { supplierId } = route.useParams();
  const range = defaultStatementRange();
  const [from, setFrom] = useState(range.from);
  const [to, setTo] = useState(range.to);
  const ranged = from !== '' && to !== '' && from <= to;
  const statement = useQuery({
    queryKey: ['retail', 'supplier-statement', supplierId, from, to],
    queryFn: () => retail.supplierStatement(supplierId, { from, to }),
    enabled: canUse(me, 'supplierPayables') && ranged,
  });
  useCrumbLabel(statement.data?.supplier_name);
  const name = statement.data?.supplier_name ?? '';
  return (
    <Gate screen="supplierPayables" title={name ? `${WORDS.supplierStatement}: ${name}` : WORDS.supplierStatement}>
      <p className="cluster" data-tour="supplier-statement-links">
        <Link className="btn btn-sm" to="/staff/retail/supplier-payments/new" search={{ supplier: supplierId } as never}>Pay this supplier</Link>
        <Link className="btn btn-sm" to="/staff/retail/supplier-payables">{WORDS.whatIOweSuppliers}</Link>
      </p>
      <FieldsNeededNote />
      <div className="rt-row" data-tour="supplier-statement-dates">
        <div><label htmlFor="sst-from">From<Star /></label><input id="sst-from" type="date" aria-required value={from} onChange={(e) => setFrom(e.target.value)} /></div>
        <div><label htmlFor="sst-to">To<Star /></label><input id="sst-to" type="date" aria-required value={to} onChange={(e) => setTo(e.target.value)} /></div>
      </div>
      {!ranged && <p role="alert" className="rt-flag">The start date must not be after the end date.</p>}
      {statement.isPending && ranged && <SkeletonList label="Loading the statement" />}
      <Problem error={statement.error} />
      {statement.data && (
        <>
          <SupplierStatementView statement={statement.data} />
          <p data-tour="supplier-statement-share">
            <button type="button" className="rt-primary" onClick={() => void shareText(`${WORDS.supplierStatement}: ${name}`, statementText(statement.data))}>
              Share the statement
            </button>
          </p>
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/suppliers/$supplierId/statement')({ component: SupplierStatementPage });

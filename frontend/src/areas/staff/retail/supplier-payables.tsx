import { useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { retail, type SupplierPayables, type SupplierPayablesRow } from '../../../api/retail';
import { WORDS } from '../../../app/glossary';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { canUse, capabilityOn } from './permissions';
import { AGEING_COLUMNS } from './payables-state';
import { showDate } from './transfers';
import { Gate, Problem, Money, useIsPhone } from './ui';
import { Hint } from '../../../app/ui/Hint';

// What I owe suppliers (ADR-033, issue #198; under the Money tab without any capability since issue #557, ADR-084): one balance per
// supplier, from what was bought on credit in Buy stock (and, with the purchasing capability, supplier invoices) less what was
// paid, with the total. Each supplier has Pay and Statement. With the purchasing capability the page also splits what is owed by
// how late it is (not yet due, 1 to 30, 31 to 60, 61 to 90 and over 90 days past the due date) and shows the goods received and
// not yet invoiced beside it.

const NOT_INVOICED_HINT = 'Goods you have received and not yet been billed for (in accounts, goods received not invoiced).';

const NotInvoiced = () => <span className="hint"> {NOT_INVOICED_HINT}</span>;

function PayLink({ row }: { row: SupplierPayablesRow }) {
  return (
    <Link to="/staff/retail/supplier-payments/new" search={{ supplier: row.supplier_id } as never} aria-label={`Pay ${row.supplier_name}`}>
      {row.supplier_name}
    </Link>
  );
}

/** Pay and Statement for one supplier, as buttons a thumb can hit. */
function SupplierActions({ row }: { row: SupplierPayablesRow }) {
  return (
    <p className="cluster">
      <Link className="btn btn-sm" to="/staff/retail/supplier-payments/new" search={{ supplier: row.supplier_id } as never} aria-label={`Pay ${row.supplier_name}`}>Pay</Link>
      <Link className="btn btn-sm" to="/staff/retail/suppliers/$supplierId/statement" params={{ supplierId: row.supplier_id ?? '' }} aria-label={`Statement of ${row.supplier_name}`}>Statement</Link>
    </p>
  );
}

/** Without the purchasing capability: each supplier owed something with the balance, Pay and Statement, and the total first. */
export function OwedList({ report }: { report: SupplierPayables }) {
  const suppliers = (report.suppliers ?? []).filter((r) => (r.owed_minor ?? 0) > 0);
  return (
    <section aria-label={WORDS.whatIOweSuppliers} data-testid="payables-report" data-tour="payables-report">
      <p className="rt-total" data-testid="payables-total">You owe suppliers <Money minor={report.totals?.owed_minor ?? 0} /> in all.</p>
      <p className="hint">As of {showDate(report.as_of)}.</p>
      {suppliers.length === 0 ? (
        <p className="empty-state">You owe no supplier anything. Stock bought {WORDS.onCredit.toLowerCase()} in {WORDS.buyStock} shows here.</p>
      ) : (
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {suppliers.map((row) => (
            <li key={row.supplier_id} className="rt-card" data-testid={`payable-${row.supplier_id}`}>
              <p><strong>{row.supplier_name}</strong></p>
              <p>Owed <strong><Money minor={row.owed_minor ?? 0} /></strong></p>
              <SupplierActions row={row} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The report: a table on a wide screen, a card for each supplier on a phone. */
export function PayablesReport({ report, phone = false }: { report: SupplierPayables; phone?: boolean }) {
  const suppliers = report.suppliers ?? [];
  const totals = report.totals ?? {};
  return (
    <section aria-label="What you owe suppliers" data-testid="payables-report" data-tour="payables-report">
      <p className="hint">As of {showDate(report.as_of)}. Tap a supplier&apos;s name to pay them.</p>
      {suppliers.length === 0 && <p className="empty-state">You owe no supplier anything, and no goods are waiting for an invoice.</p>}
      {suppliers.length > 0 && phone && (
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {[...suppliers, { ...totals, supplier_name: 'All suppliers' } as SupplierPayablesRow].map((row, i) => (
            <li key={row.supplier_id ?? `total-${i}`} className="rt-card" data-testid={row.supplier_id ? `payable-${row.supplier_id}` : 'payables-totals'}>
              <p><strong>{row.supplier_id ? <PayLink row={row} /> : row.supplier_name}</strong></p>
              <p>Owed in all <strong><Money minor={row.owed_minor ?? 0} /></strong></p>
              <ul>
                {AGEING_COLUMNS.map((c) => <li key={c.key}>{c.label}: <Money minor={row[c.key] ?? 0} /></li>)}
              </ul>
              <p>Received, not invoiced <strong><Money minor={row.unbilled_receipts_minor ?? 0} /></strong>{i === 0 && <NotInvoiced />}</p>
              {row.supplier_id && <SupplierActions row={row} />}
            </li>
          ))}
        </ul>
      )}
      {suppliers.length > 0 && !phone && (
        <div className="table-wrap" tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th>Supplier</th>
                {AGEING_COLUMNS.map((c) => <th key={c.key} className="num">{c.label}</th>)}
                <th className="num">Owed in all</th>
                <th className="num">Received, not invoiced</th>
              </tr>
            </thead>
            <tbody>
              {suppliers.map((row) => (
                <tr key={row.supplier_id} data-testid={`payable-${row.supplier_id}`}>
                  <td><PayLink row={row} /> <Link to="/staff/retail/suppliers/$supplierId/statement" params={{ supplierId: row.supplier_id ?? '' }} aria-label={`Statement of ${row.supplier_name}`}>Statement</Link></td>
                  {AGEING_COLUMNS.map((c) => <td key={c.key} className="num"><Money minor={row[c.key] ?? 0} /></td>)}
                  <td className="num"><strong><Money minor={row.owed_minor ?? 0} /></strong></td>
                  <td className="num"><Money minor={row.unbilled_receipts_minor ?? 0} /></td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr data-testid="payables-totals">
                <td><strong>All suppliers</strong></td>
                {AGEING_COLUMNS.map((c) => <td key={c.key} className="num"><strong><Money minor={totals[c.key] ?? 0} /></strong></td>)}
                <td className="num"><strong><Money minor={totals.owed_minor ?? 0} /></strong></td>
                <td className="num"><strong><Money minor={totals.unbilled_receipts_minor ?? 0} /></strong></td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
      {suppliers.length > 0 && !phone && <p className="hint">Received, not invoiced: {NOT_INVOICED_HINT}</p>}
    </section>
  );
}

export function SupplierPayablesPage() {
  const { me } = useStaff();
  const phone = useIsPhone();
  const purchasing = capabilityOn(me, 'retail', 'purchasing');
  const report = useQuery({ queryKey: ['retail', 'supplier-payables'], queryFn: () => retail.supplierPayables(), enabled: canUse(me, 'supplierPayables') });
  return (
    <Gate screen="supplierPayables" title={WORDS.whatIOweSuppliers} wide={purchasing}>
      <Hint tour="payables-help">
        {purchasing
          ? `Everything you owe suppliers, by how late it is. It counts supplier invoices and stock bought ${WORDS.onCredit.toLowerCase()} in ${WORDS.buyStock}.`
          : `What each supplier is owed for stock bought ${WORDS.onCredit.toLowerCase()} in ${WORDS.buyStock}, less what you paid them.`}
      </Hint>
      <p className="cluster" data-tour="payables-links">
        <Link className="btn btn-sm" to="/staff/retail/supplier-payments/new">{WORDS.payASupplier}</Link>
        <Link className="btn btn-sm" to="/staff/retail/supplier-payments">Supplier payments</Link>
        {purchasing && <Link className="btn btn-sm" to="/staff/retail/supplier-bills">Supplier invoices</Link>}
      </p>
      {report.isPending && <SkeletonList label="Loading what you owe" />}
      <Problem error={report.error} />
      {report.data && (purchasing ? <PayablesReport report={report.data} phone={phone} /> : <OwedList report={report.data} />)}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/supplier-payables')({ component: SupplierPayablesPage });

import { useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { retail, type SupplierPayables, type SupplierPayablesRow } from '../../../api/retail';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { canUse } from './permissions';
import { AGEING_COLUMNS } from './payables-state';
import { showDate } from './transfers';
import { Gate, Problem, money, useIsPhone } from './ui';

// What you owe suppliers (the purchasing capability, ADR-033, issue #198): one line for each supplier with what is owed split by how
// late it is (not yet due, 1 to 30, 31 to 60, 61 to 90 and over 90 days past the due date), the total owed, and the goods received
// and not yet invoiced beside it. A supplier's name starts a payment for them. A supplier invoice and a credit restock both count.

const NOT_INVOICED_HINT = 'Goods you have received and not yet been billed for (in accounts, goods received not invoiced).';

const NotInvoiced = () => <span className="hint"> {NOT_INVOICED_HINT}</span>;

function PayLink({ row }: { row: SupplierPayablesRow }) {
  return (
    <Link to="/staff/retail/supplier-payments/new" search={{ supplier: row.supplier_id } as never} aria-label={`Pay ${row.supplier_name}`}>
      {row.supplier_name}
    </Link>
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
              <p>Owed in all <strong>{money(row.owed_minor ?? 0)}</strong></p>
              <ul>
                {AGEING_COLUMNS.map((c) => <li key={c.key}>{c.label}: {money(row[c.key] ?? 0)}</li>)}
              </ul>
              <p>Received, not invoiced <strong>{money(row.unbilled_receipts_minor ?? 0)}</strong>{i === 0 && <NotInvoiced />}</p>
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
                  <td><PayLink row={row} /></td>
                  {AGEING_COLUMNS.map((c) => <td key={c.key} className="num">{money(row[c.key] ?? 0)}</td>)}
                  <td className="num"><strong>{money(row.owed_minor ?? 0)}</strong></td>
                  <td className="num">{money(row.unbilled_receipts_minor ?? 0)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr data-testid="payables-totals">
                <td><strong>All suppliers</strong></td>
                {AGEING_COLUMNS.map((c) => <td key={c.key} className="num"><strong>{money(totals[c.key] ?? 0)}</strong></td>)}
                <td className="num"><strong>{money(totals.owed_minor ?? 0)}</strong></td>
                <td className="num"><strong>{money(totals.unbilled_receipts_minor ?? 0)}</strong></td>
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
  const report = useQuery({ queryKey: ['retail', 'supplier-payables'], queryFn: () => retail.supplierPayables(), enabled: canUse(me, 'supplierPayables') });
  return (
    <Gate screen="supplierPayables" title="What you owe suppliers" wide>
      <p className="hint" data-tour="payables-help">
        Everything you owe suppliers, by how late it is. It counts supplier invoices and stock bought on credit in Restock.
      </p>
      <p className="cluster" data-tour="payables-links">
        <Link className="btn-sm" to="/staff/retail/supplier-payments">Supplier payments</Link>
        <Link className="btn-sm" to="/staff/retail/supplier-bills">Supplier invoices</Link>
      </p>
      {report.isPending && <SkeletonList label="Loading what you owe" />}
      <Problem error={report.error} />
      {report.data && <PayablesReport report={report.data} phone={phone} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/supplier-payables')({ component: SupplierPayablesPage });

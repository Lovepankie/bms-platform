import { useMutation } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type Barcode, type Product } from '../../../api/retail';
import { BarcodeSvg } from './catalogue-barcodes';
import { Gate, Problem, ProductPicker, money } from './ui';
import { ActionBar } from '../../../app/ui/ActionBar';

// Barcode labels (ADR-070, issue #345; FR-RET-68): choose items and how many labels of each, make a shop code for an
// item that has none (an EAN-13 from 210 since issue #524), and print an A4 sheet of 3 by 8 labels with the name, the
// price and the barcode.

export interface LabelRow {
  product: Product;
  count: string;
}

/** The barcode a label prints: the item's own made code (not a pack's); or none yet. */
export function labelBarcode(product: Product, made: Barcode[] = []): Barcode | undefined {
  // Always the system's own label code, drawn in its own format: an EAN-13 from 210 (issue #524), or the Code 128 IN
  // code of an item that got one before. An item without one gets one made.
  return [...(product.barcodes ?? []), ...made.filter((b) => b.product_id === product.id)].find((b) => b.generated && !b.unit_id);
}

export function labelCode(product: Product, made: Barcode[] = []): string | undefined {
  return labelBarcode(product, made)?.code;
}

/** The labels to print, in the order chosen: each row as many times as asked (1 to 99, an unreadable count is 1). */
export function sheetOf(rows: LabelRow[], made: Barcode[]): { product: Product; code: string; format?: string }[] {
  return rows.flatMap((r) => {
    const own = labelBarcode(r.product, made);
    const n = Math.min(99, Math.max(1, Number.parseInt(r.count, 10) || 1));
    const code = own?.code;
    return code ? Array.from({ length: n }, () => ({ product: r.product, code, ...(own?.format ? { format: own.format } : {}) })) : [];
  });
}

export function LabelsPage() {
  const [rows, setRows] = useState<LabelRow[]>([]);
  const [made, setMade] = useState<Barcode[]>([]);
  const [ready, setReady] = useState(false);
  const [safe, setSafe] = useState(false);
  const missing = rows.filter((r) => !labelCode(r.product, made)).map((r) => r.product.id ?? '');
  const make = useMutation({
    mutationFn: () => (missing.length > 0 ? retail.generateBarcodes(missing) : Promise.resolve([])),
    onSuccess: (codes) => {
      setMade((m) => [...m, ...codes]);
      setReady(true);
    },
  });
  const sheet = ready ? sheetOf(rows, made) : [];
  return (
    <Gate screen="labels" title="Barcode labels">
      <div data-tour="labels-pick">
        <ProductPicker id="labels-search" showPrice showStock={false} onAdd={(p) => { setReady(false); setRows((rs) => (rs.some((r) => r.product.id === p.id) ? rs : [...rs, { product: p, count: '1' }])); }} />
      </div>
      <h2>Labels to print</h2>
      {rows.length === 0 && <p className="empty-state">No items yet. Type an item above and tap it in the list.</p>}
      {rows.map((r, i) => (
        <div key={r.product.id} className="rt-card rt-row">
          <span>
            <strong>{r.product.description}</strong>
            <br />
            {labelCode(r.product, made) ?? 'No label code yet: one is made for it'}
          </span>
          <label htmlFor={`labels-count-${i}`}>Labels</label>
          <input id={`labels-count-${i}`} inputMode="numeric" style={{ width: '5em' }} value={r.count} onChange={(e) => { setReady(false); setRows((rs) => rs.map((x, n) => (n === i ? { ...x, count: e.target.value } : x))); }} />
          <button type="button" className="btn-ghost" onClick={() => { setReady(false); setRows((rs) => rs.filter((_, n) => n !== i)); }} aria-label={`Remove ${r.product.description}`}>Remove</button>
        </div>
      ))}
      <Problem error={make.error} />
      <p className="hint label-hint" data-testid="label-stock">
        Print on A4 label sheets of 3 by 8 labels, each 70 by 37 mm, with no margin around the sheet. In the print dialog choose no
        margins (zero margin) and actual size (100%).
      </p>
      <label className="label-hint">
        <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={safe} onChange={(e) => setSafe(e.target.checked)} />
        My printer cannot print to the edge: keep 4 mm clear inside each label
      </label>
      <ActionBar>
        <BusyButton type="button" className="rt-primary" data-tour="labels-make" busy={make.isPending} notReady={rows.length === 0} onClick={() => make.mutate()}>
          Make the labels
        </BusyButton>
        <button type="button" data-tour="labels-print" disabled={sheet.length === 0} onClick={() => window.print()}>
          Print
        </button>
      </ActionBar>
      {sheet.length > 0 && (
        <div className={safe ? 'label-sheet label-safe' : 'label-sheet'} data-testid="label-sheet" aria-label="Label sheet">
          {sheet.map((l, i) => (
            <div key={`${l.product.id}-${i}`} className="label">
              <span className="label-name">{l.product.description}</span>
              {l.product.sell_minor !== undefined && <strong>{money(l.product.sell_minor)}</strong>}
              <BarcodeSvg text={l.code} format={l.format} />
              {l.format !== 'ean13' && <span className="label-code">{l.code}</span>}
            </div>
          ))}
        </div>
      )}
    </Gate>
  );
}

export const LabelsRoute = createLazyRoute('/staff/retail/catalogue/labels')({ component: LabelsPage });

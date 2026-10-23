import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type Product } from '../../../api/retail';
import { useStaff } from '../context';
import { checkBarcode, code128Widths, formatName } from './barcode-state';
import { UNITS, capabilityOn, holds } from './permissions';
import { Problem } from './ui';
import { activePacks } from './units-state';

// The barcodes of an item on its edit form (ADR-070, issue #345; FR-RET-68): several per item, each for the item's own
// unit or one of its pack sizes. The format is read from the code and its check digit checked here, as the server does.

/** A Code 128 barcode of `text` as bars, black on white whatever the theme; read back by any scanner or the camera. */
export function BarcodeSvg({ text }: { text: string }) {
  const widths = code128Widths(text);
  const quiet = 10;
  const total = widths.reduce((a, b) => a + b, 0) + quiet * 2;
  let x = quiet;
  const bars = widths.map((w, i) => {
    const at = x;
    x += w;
    return i % 2 === 0 ? <rect key={i} x={at} y={0} width={w} height={50} /> : null;
  });
  return (
    <svg viewBox={`0 0 ${total} 50`} preserveAspectRatio="none" shapeRendering="crispEdges" role="img" aria-label={`Barcode ${text}`} fill="currentColor">
      {bars}
    </svg>
  );
}

export function BarcodeEditor({ product }: { product: Product }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const canManage = holds(me, 'retail.catalogue.manage');
  const id = product.id ?? '';
  const packs = capabilityOn(me, 'retail', UNITS) ? activePacks(product) : [];
  const codes = useQuery({ queryKey: ['retail', 'barcodes', id], queryFn: () => retail.getProduct(id).then((p) => p.barcodes ?? []), initialData: product.barcodes ?? [] });
  const [code, setCode] = useState('');
  const [unitId, setUnitId] = useState('');
  // A code a spreadsheet may have damaged is taken only when the person says it is exactly as printed (Code 128).
  const [asPrinted, setAsPrinted] = useState(false);
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['retail'] });
  const add = useMutation({
    mutationFn: () => retail.addBarcode(id, { code: code.trim(), ...(asPrinted ? { format: 'code128' } : {}), ...(unitId ? { unit_id: unitId } : {}) }),
    onSuccess: () => {
      setCode('');
      setAsPrinted(false);
      refresh();
    },
  });
  const remove = useMutation({ mutationFn: (barcodeId: string) => retail.removeBarcode(id, barcodeId), onSuccess: refresh });
  const make = useMutation({ mutationFn: () => retail.generateBarcodes([id]), onSuccess: refresh });
  const inferred = code.trim() === '' ? null : checkBarcode(code);
  const checked = inferred && !inferred.ok && inferred.spreadsheet && asPrinted ? checkBarcode(code, 'code128') : inferred;
  const list = codes.data ?? [];
  return (
    <fieldset data-testid="barcode-editor" data-tour="item-barcodes" onKeyDown={(e) => { if (e.key === 'Enter') e.preventDefault(); }}>
      <legend>Barcodes</legend>
      <p className="hint">Scan the barcode on the item into the box, or type it. A scan when selling, restocking or counting then finds this item.</p>
      {list.length === 0 && <p className="empty-state">No barcode yet.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {list.map((b) => (
          <li key={b.id} className="rt-card rt-row">
            <span>
              <strong>{b.code}</strong> {formatName(b.format)}
              {b.unit ? `, on the ${b.unit}` : ''}
              {b.generated ? ', made for labels' : ''}
            </span>
            {canManage && (
              <BusyButton type="button" className="btn-ghost" busy={remove.isPending && remove.variables === b.id} disabled={remove.isPending} onClick={() => remove.mutate(b.id ?? '')} aria-label={`Remove barcode ${b.code}`}>
                Remove
              </BusyButton>
            )}
          </li>
        ))}
      </ul>
      <Problem error={remove.error ?? make.error} />
      {canManage && (
        <>
          <label htmlFor="barcode-new">Add a barcode</label>
          <input
            id="barcode-new"
            autoComplete="off"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            aria-invalid={checked !== null && !checked.ok}
            aria-describedby={checked !== null && !checked.ok ? 'barcode-problem' : undefined}
          />
          {checked !== null && !checked.ok && <p id="barcode-problem" className="rt-flag">{checked.problem}</p>}
          {inferred !== null && !inferred.ok && inferred.spreadsheet && (
            <label>
              <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={asPrinted} onChange={(e) => setAsPrinted(e.target.checked)} /> This code is exactly as printed (Code 128)
            </label>
          )}
          {checked?.ok && <p className="hint">{formatName(checked.format)}</p>}
          {packs.length > 0 && (
            <>
              <label htmlFor="barcode-unit">This barcode is on</label>
              <select id="barcode-unit" value={unitId} onChange={(e) => setUnitId(e.target.value)}>
                <option value="">One {product.unit}</option>
                {packs.map((k) => <option key={k.unit_id} value={k.unit_id}>One {k.unit}</option>)}
              </select>
            </>
          )}
          <Problem error={add.error} />
          <BusyButton type="button" busy={add.isPending} disabled={!checked?.ok} onClick={() => add.mutate()}>
            Add barcode
          </BusyButton>
          {!list.some((b) => b.generated) && (
            <BusyButton type="button" className="btn-ghost" busy={make.isPending} onClick={() => make.mutate()}>
              Make a barcode for labels
            </BusyButton>
          )}
        </>
      )}
    </fieldset>
  );
}

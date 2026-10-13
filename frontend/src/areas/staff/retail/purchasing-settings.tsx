import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { retail, type PurchasingSettings as Settings } from '../../../api/retail';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { PURCHASING, SETTINGS_MANAGE, capabilityOn, holds } from './permissions';
import { bpToPercentText, percentTextToBp } from './purchasing-state';
import { Problem, Success, changeFailureText, money } from './ui';

// The purchasing settings block of the Retail settings screen (the purchasing capability, ADR-033, issue #198): the two tolerances,
// in percent with two decimals and kept as basis points (100 is 1 per cent), saved under If-Match with the settings version. The
// order approval limit is read only here: it is set in the business settings, and the server sends it only when one is set. The
// block shows only while purchasing is on and the person may change settings.

/** The two boxes and the save button for the settings as read. Keyed by the version, so a fresh read starts from what is saved. */
export function PurchasingSettingsForm({ settings, busy, error, saved, onSave }: {
  settings: Settings;
  busy?: boolean;
  error?: unknown;
  saved?: boolean;
  onSave: (body: { bill_tolerance_bp: number; over_receipt_bp: number }) => void;
}) {
  const [bill, setBill] = useState(bpToPercentText(settings.bill_tolerance_bp));
  const [over, setOver] = useState(bpToPercentText(settings.over_receipt_bp));
  const billBp = percentTextToBp(bill);
  const overBp = percentTextToBp(over);
  const unchanged = billBp === settings.bill_tolerance_bp && overBp === settings.over_receipt_bp;
  const bad = billBp === null || overBp === null;
  return (
    <form
      className="stack"
      aria-label="Purchasing settings"
      onSubmit={(e) => {
        e.preventDefault();
        if (billBp !== null && overBp !== null && !busy) onSave({ bill_tolerance_bp: billBp, over_receipt_bp: overBp });
      }}
    >
      <h2>Purchasing</h2>
      {saved && <Success>Saved.</Success>}
      <label htmlFor="tolerance-bill">How far a supplier invoice may differ from the goods received, in percent</label>
      <input id="tolerance-bill" inputMode="decimal" value={bill} onChange={(e) => setBill(e.target.value)} aria-invalid={billBp === null} aria-describedby="tolerance-bill-hint" />
      <p id="tolerance-bill-hint" className="hint">Above this, the person recording the invoice must say why it differs. 0 means any difference needs a reason.</p>
      <label htmlFor="tolerance-over">How much more than the order may be received, in percent</label>
      <input id="tolerance-over" inputMode="decimal" value={over} onChange={(e) => setOver(e.target.value)} aria-invalid={overBp === null} aria-describedby="tolerance-over-hint" />
      <p id="tolerance-over-hint" className="hint">0 means a delivery can never be more than was ordered.</p>
      {bad && <p role="alert" className="rt-flag">Enter each as a percent from 0 to 100, with at most two decimals, for example 1.5.</p>}
      {settings.order_approval_threshold_minor !== undefined ? (
        <p data-testid="order-threshold">A purchase order worth {money(settings.order_approval_threshold_minor)} or more needs a second person to approve it before it is sent. This limit is set in the business settings.</p>
      ) : (
        <p className="hint" data-testid="order-threshold">No approval limit is set for purchase orders, so a single person can send any order.</p>
      )}
      <Problem error={error} />
      <button type="submit" className="rt-primary" disabled={bad || unchanged || busy}>{busy ? 'Saving' : 'Save purchasing settings'}</button>
    </form>
  );
}

export function PurchasingSettings() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [saved, setSaved] = useState(false);
  const on = capabilityOn(me, 'retail', PURCHASING) && holds(me, SETTINGS_MANAGE);
  const read = useQuery({ queryKey: ['retail', 'purchasing-settings'], queryFn: () => retail.purchasingSettings(), enabled: on });
  const save = useMutation({
    mutationFn: (body: { bill_tolerance_bp: number; over_receipt_bp: number }) => retail.changePurchasingSettings(read.data?.version ?? 0, body),
    onSuccess: (next) => {
      queryClient.setQueryData(['retail', 'purchasing-settings'], next);
      setSaved(true);
    },
  });
  if (!on) return null;
  return (
    <section className="rt-card" aria-label="Purchasing" data-tour="purchasing-settings">
      {read.isPending && <SkeletonList label="Loading purchasing settings" />}
      <Problem error={read.error} />
      {read.data && (
        <PurchasingSettingsForm
          key={read.data.version}
          settings={read.data}
          busy={save.isPending}
          error={save.error && new Error(changeFailureText(save.error, queryClient))}
          saved={saved && !save.isPending}
          onSave={(body) => { setSaved(false); save.mutate(body); }}
        />
      )}
    </section>
  );
}

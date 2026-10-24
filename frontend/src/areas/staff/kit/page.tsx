import { createLazyRoute } from '@tanstack/react-router';
import { useEffect, useState, type ReactNode } from 'react';
import { Card, ListRow, RowList } from '../../../app/ui/ListRow';
import { ActionBar } from '../../../app/ui/ActionBar';
import { EmptyState, NoAccess } from '../../../app/ui/EmptyState';
import { Hint } from '../../../app/ui/Hint';
import { KeyFigure, KeyFigureRow } from '../../../app/ui/KeyFigure';
import { PeriodPicker } from '../../../app/ui/PeriodPicker';
import { Field, FieldsNeededNote, focusFirstMissing } from '../../../app/ui/Field';
import { MoneyInput } from '../../../app/ui/MoneyInput';
import { Segmented } from '../../../app/ui/Segmented';
import { Sheet } from '../../../app/ui/Sheet';
import { Skeleton } from '../../../app/ui/Skeleton';
import { Stepper } from '../../../app/ui/Stepper';
import { SuccessScreen } from '../../../app/ui/SuccessScreen';
import { formatMinor } from '../../../components/money';
import { useStaff } from '../context';

// The component kit page (#520): every control of src/app/ui/kit.css on one staff page, with made-up figures, so a change to the
// kit is reviewed on a phone in one place before phase 3 moves the screens onto it. Administrators only, and not in the menu:
// it is reached by typing /staff/kit. It calls no API.

function Example({ title, about, children }: { title: string; about: string; children: ReactNode }) {
  return (
    <section className="kit-example" aria-labelledby={`kit-${title}`}>
      <h2 id={`kit-${title}`}>{title}</h2>
      <p className="hint">{about}</p>
      {children}
    </section>
  );
}

const SHOW = [
  { value: 'all', label: 'All items' },
  { value: 'out', label: 'Out of stock' },
  { value: 'low', label: 'Low stock' },
] as const;

const ugx = (minor: number) => formatMinor(minor, 'UGX');

/** A pretend load of `ms`, started again by each new `round`, so the skeleton can be seen on the page. */
function usePretendLoad(ms: number, round: number): boolean {
  const [done, setDone] = useState(0);
  useEffect(() => {
    if (round === 0) return;
    const timer = window.setTimeout(() => setDone(round), ms);
    return () => window.clearTimeout(timer);
  }, [ms, round]);
  return round > done;
}

function SkeletonExample() {
  const [round, setRound] = useState(0);
  const [fresh, setFresh] = useState(0);
  const loading = usePretendLoad(2500, round);
  return (
    <>
      <div className="cluster">
        <button type="button" onClick={() => { setFresh((n) => n + 1); setRound((n) => n + 1); }}>
          Load a new list
        </button>
        <button type="button" disabled={round === 0} onClick={() => setRound((n) => n + 1)}>
          Reload this list
        </button>
      </div>
      <Card>
        <Skeleton key={fresh} loading={loading} rows={3}>
          {round > 0 ? (
            <RowList label="Loaded sales">
              <ListRow title="Sale S-000103" meta="12:10, walk-in buyer" amount={ugx(7000)} />
              <ListRow title="Sale S-000104" meta="12:31, walk-in buyer" amount={ugx(45500)} />
            </RowList>
          ) : (
            <EmptyState text="Press Load a new list. It takes 2.5 seconds." />
          )}
        </Skeleton>
      </Card>
    </>
  );
}

export function KitPage() {
  const { me } = useStaff();
  const [show, setShow] = useState<string>('all');
  const [qty, setQty] = useState('2');
  const [phone, setPhone] = useState('');
  const [buyer, setBuyer] = useState('');
  const [sent, setSent] = useState(false);
  const [paid, setPaid] = useState<number | null>(null);
  const [days, setDays] = useState({ from: '2026-10-01', to: '2026-10-24' });
  const [picking, setPicking] = useState(false);
  const [method, setMethod] = useState('Cash');
  const [done, setDone] = useState(0);
  if (!(me.roles ?? []).some((r) => r.role_key === 'tenant_admin')) {
    return (
      <main>
        <h1>Component kit</h1>
        <p className="lead">This page is for administrators.</p>
      </main>
    );
  }
  return (
    <main className="kit-page">
      <h1>Component kit</h1>
      <p className="lead">The shared controls every screen will use. The figures are made up.</p>

      <Example title="Segmented" about="Pick one of 2 to 5. Replaces rows of separate buttons.">
        <Segmented label="Show" options={SHOW} value={show} onChange={setShow} />
      </Example>

      <Example title="Stepper" about="A quantity, from 1 to 99. Tap minus or plus, type a number, or use the arrow keys.">
        <Stepper id="kit-qty" label="Quantity (piece)" name="Sample soap" value={qty} onChange={setQty} min={1} max={99} />
      </Example>

      <Example title="Field" about="The label above, a hint under it, and the error only once you leave the field. Save stays on and takes you to what is missing.">
        <form
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            setSent(true);
            focusFirstMissing(e.currentTarget);
          }}
        >
          <FieldsNeededNote />
          <Field label="Buyer's name" required autoComplete="name" value={buyer} onChange={(e) => setBuyer(e.target.value)} error={buyer.trim() ? null : "Type the buyer's name."} showError={sent} />
          <Field
            label="Buyer's phone number"
            required
            hint="The receipt is sent here."
            inputMode="tel"
            autoComplete="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            error={/^0\d{9}$/.test(phone) ? null : 'Type the 10 digits of the number, starting with 0.'}
            showError={sent}
          />
          <button type="submit" className="btn-primary">
            Save the buyer
          </button>
        </form>
      </Example>

      <Example title="MoneyInput" about="Whole shillings on the number keypad, with the commas drawn as you type.">
        <MoneyInput label="Amount paid" hint="What the buyer handed over." valueMinor={paid} onChange={setPaid} error={paid === null ? 'Type the amount paid.' : null} />
      </Example>

      <Example title="ListRow and Card" about="One row style for items, sales and people. The whole row is the target.">
        <Card title="Today's sales">
          <RowList label="Today's sales">
            <ListRow title="Sale S-000101" meta="10:42, walk-in buyer" amount={ugx(12500)} to="/staff/kit" />
            <ListRow title="Sale S-000102" meta="11:05, Sample Buyer, on credit" amount={ugx(184000)} to="/staff/kit" />
            <ListRow title="Sample soap" meta="12 left in Main shop" amount={ugx(3000)} />
            <ListRow title="Sample Person" meta="Seller, Main shop" onClick={() => undefined} />
          </RowList>
        </Card>
      </Example>

      <Example title="Sheet" about="A bottom sheet on a phone, a dialog on a wider screen. Swipe it down, press Escape or tap outside to close.">
        <p>
          Paid by <strong>{method}</strong>
        </p>
        <button type="button" onClick={() => setPicking(true)}>
          Change how the buyer paid
        </button>
        <Sheet
          open={picking}
          onClose={() => setPicking(false)}
          title="How did the buyer pay?"
          actions={
            <button type="button" onClick={() => setPicking(false)}>
              Cancel
            </button>
          }
        >
          <RowList label="Ways to pay">
            {['Cash', 'Mobile money', 'Bank'].map((m) => (
              <ListRow key={m} title={m} chevron={false} onClick={() => { setMethod(m); setPicking(false); }} />
            ))}
          </RowList>
        </Sheet>
      </Example>

      <Example title="EmptyState" about="One line and one action, where the list would be.">
        <Card>
          <EmptyState text="No sales today yet." action={{ label: 'Record a sale', to: '/staff/retail/sale' }} />
        </Card>
      </Example>

      <Example title="NoAccess" about="A page you may not open: who can give access, and a way Home.">
        <Card>
          <NoAccess />
        </Card>
      </Example>

      <Example title="KeyFigure" about="The one key amount of a screen, large, with its label above and its parts under it.">
        <KeyFigure label="Takings today" value={ugx(232500)}>
          <KeyFigureRow label="Cash" value={ugx(150000)} />
          <KeyFigureRow label="Mobile money" value={ugx(82500)} />
        </KeyFigure>
      </Example>

      <Example title="PeriodPicker" about="Today, This week, This month or Custom. Custom opens a sheet with the two days.">
        <PeriodPicker from={days.from} to={days.to} onChange={setDays} />
      </Example>

      <Example title="Hint" about="A help text on one line. The info button shows the rest when it does not fit.">
        <Hint>Every purchase is kept. If you entered one wrongly, open Correct this purchase, then enter it again with the right quantity.</Hint>
      </Example>

      <Example title="ActionBar" about="The main action: brand filled, and on a phone stuck full width to the bottom of the form.">
        <ActionBar>
          <button type="button" className="rt-primary">Save expense</button>
        </ActionBar>
      </Example>

      <Example title="Skeleton" about="Nothing for the first second, then grey rows. A reload keeps the list on the screen.">
        <SkeletonExample />
      </Example>

      <Example title="SuccessScreen" about="After a sale or a payment: the check, the amount, then what to do next.">
        <button type="button" onClick={() => setDone((n) => n + 1)}>
          Draw the check again
        </button>
        <Card>
          <SuccessScreen
            key={done}
            focus={done > 0}
            level={2}
            title="Sale recorded"
            amount={ugx(57500)}
            detail="Paid by mobile money. Receipt S-000105."
            actions={[
              { label: 'New sale', onClick: () => setDone((n) => n + 1) },
              { label: 'Send the receipt', onClick: () => setDone((n) => n + 1) },
            ]}
          />
        </Card>
      </Example>
    </main>
  );
}

export const Route = createLazyRoute('/staff/kit')({ component: KitPage });

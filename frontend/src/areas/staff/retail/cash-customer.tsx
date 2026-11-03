import { useState } from 'react';

// The customer's name on a sale paid now (issue #760): a pilot shop asked to print it on the receipt when the buyer wants
// one. It sits behind Add customer name, so the fast sale is unchanged; left empty, the receipt says Cash customer. It is
// kept on the sale for reprints and never makes a customer record, a credit account or any change to stock or money.

export const CASH_NAME_HINT = 'Printed on the receipt. Left empty, the receipt says Cash customer.';

export function CashCustomerName({ name, phone, onChange }: {
  name: string;
  phone: string;
  onChange: (patch: { cashName?: string; cashPhone?: string }) => void;
}) {
  const [open, setOpen] = useState(name !== '' || phone !== '');
  if (!open) {
    return (
      <p>
        <button type="button" onClick={() => setOpen(true)}>Add customer name</button>
      </p>
    );
  }
  return (
    <fieldset className="cash-customer">
      <legend>Customer on the receipt</legend>
      <label htmlFor="cash-name">Customer name (optional)</label>
      <input id="cash-name" value={name} maxLength={200} autoComplete="off" onChange={(e) => onChange({ cashName: e.target.value })} />
      <label htmlFor="cash-phone">Phone (optional)</label>
      <input id="cash-phone" type="tel" inputMode="tel" value={phone} maxLength={100} autoComplete="off" placeholder="0700 000 000"
        onChange={(e) => onChange({ cashPhone: e.target.value })} />
      <p className="hint">{CASH_NAME_HINT}</p>
      <p>
        <button type="button" className="btn-ghost" onClick={() => { onChange({ cashName: '', cashPhone: '' }); setOpen(false); }}>
          No name on the receipt
        </button>
      </p>
    </fieldset>
  );
}

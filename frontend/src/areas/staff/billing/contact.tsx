import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useRef, useState } from 'react';
import { type BillingView, clearBillingContact, setBillingContact } from '../../../api/billing';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field, FieldsNeededNote, focusFirstMissing } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';

// Who gets the bills (issue #571, ADR-090, FR-SUB-13): the Owner may name someone else, an accountant say, for the reminders,
// invoices and receipts. Blank means the Owner, as before. Only the Owner changes it; the administrators see who it is. It is never
// a sign-in contact: sign-in codes and password links keep going where they went.

/** What is wrong with the name, or null. */
export function nameProblem(name: string): string | null {
  return name.trim() === '' ? 'Type the name of the person who gets the bills.' : null;
}

/** What is wrong with the email, or null. */
export function emailProblem(email: string): string | null {
  const value = email.trim();
  if (value === '') return 'Type the email the bills go to.';
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value) ? null : 'This does not look like an email address.';
}

/** What is wrong with the phone, or null: optional, a Ugandan mobile number when given. */
export function phoneProblem(phone: string): string | null {
  const digits = phone.replace(/[\s().-]/g, '');
  if (digits === '') return null;
  return /^(\+256|256|0)7\d{8}$/.test(digits) ? null : 'Type a Ugandan mobile number, for example 0700 000 000.';
}

/** The sentence saying who gets the bills now. */
export function goesToSentence(view: Pick<BillingView, 'billing_contact' | 'bills_go_to'>): string {
  if (view.billing_contact) return `Reminders, invoices and receipts go to ${view.billing_contact.full_name} (${view.billing_contact.email}).`;
  if (view.bills_go_to) return `Reminders, invoices and receipts go to the owner, ${view.bills_go_to}.`;
  return 'Nobody gets the reminders yet: the business has no owner. Rincol Tech can set one.';
}

export function BillingContactCard({ view }: { view: BillingView }) {
  const queryClient = useQueryClient();
  const contact = view.billing_contact ?? null;
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(contact?.full_name ?? '');
  const [email, setEmail] = useState(contact?.email ?? '');
  const [phone, setPhone] = useState(contact?.phone ?? '');
  const [sent, setSent] = useState(false);
  const form = useRef<HTMLFormElement>(null);

  const save = useMutation({
    mutationFn: () => setBillingContact({ full_name: name.trim(), email: email.trim(), phone: phone.trim() === '' ? undefined : phone.trim() }),
    onSuccess: (saved) => {
      queryClient.setQueryData(['billing'], saved);
      setEditing(false);
    },
    meta: { success: 'Billing contact saved' },
  });
  const remove = useMutation({
    mutationFn: clearBillingContact,
    onSuccess: (saved) => {
      queryClient.setQueryData(['billing'], saved);
      setName('');
      setEmail('');
      setPhone('');
    },
    meta: { success: 'Bills go to the owner again' },
  });

  const problems = { name: nameProblem(name), email: emailProblem(email), phone: phoneProblem(phone) };
  const ready = !problems.name && !problems.email && !problems.phone;

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSent(true);
    if (!ready) {
      if (form.current) focusFirstMissing(form.current);
      return;
    }
    save.mutate();
  }

  const failure = save.error ?? remove.error;

  return (
    <section className="rt-card stack" aria-labelledby="billing-contact" data-tour="billing-contact">
      <h2 id="billing-contact">Who gets the bills</h2>
      <p>{goesToSentence(view)}</p>
      {contact?.phone && <p className="hint">Text reminders go to {contact.phone} when Rincol Tech sends them by SMS.</p>}
      <Hint full>Sign-in codes and password links still go to each person's own email or phone. This changes only where the bills go.</Hint>
      {!view.can_change_contact && <p className="hint">Only the owner of the business can change this.</p>}
      {failure && (
        <p role="alert" className="alert alert-danger">
          {failure instanceof Error ? failure.message : 'That did not work. Try again.'}
        </p>
      )}

      {view.can_change_contact && !editing && (
        <div className="cluster">
          <button
            type="button" /* busy-exempt: opens the form, sends nothing */
            onClick={() => {
              setEditing(true);
              setSent(false);
            }}
          >
            {contact ? 'Change who gets the bills' : 'Send the bills to someone else'}
          </button>
          {contact && (
            <BusyButton type="button" className="btn-ghost" busy={remove.isPending} onClick={() => remove.mutate()}>
              Send them to me again
            </BusyButton>
          )}
        </div>
      )}

      {view.can_change_contact && editing && (
        <form ref={form} className="form-stack" onSubmit={onSubmit} noValidate aria-label="Who gets the bills">
          <FieldsNeededNote />
          <Field label="Name" required value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" maxLength={120} error={problems.name} showError={sent} />
          <Field
            label="Email"
            required
            type="email"
            inputMode="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            maxLength={254}
            error={problems.email}
            showError={sent}
          />
          <Field
            label="Mobile number for text reminders"
            inputMode="tel"
            autoComplete="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            maxLength={20}
            hint="Leave it empty for email only."
            error={problems.phone}
            showError={sent}
          />
          <div className="cluster">
            <BusyButton type="submit" className="btn-primary" busy={save.isPending} notReady={!ready}>
              Save
            </BusyButton>
            <button type="button" /* busy-exempt: closes the form, sends nothing */ className="btn-ghost" disabled={save.isPending} onClick={() => setEditing(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

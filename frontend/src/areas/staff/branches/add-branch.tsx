import { Link } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { type AdminBranch, BranchesError, branches } from '../../../api/branches';
import { ActionBar } from '../../../app/ui/ActionBar';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field, FieldsNeededNote, focusFirstMissing } from '../../../app/ui/Field';
import { Segmented } from '../../../app/ui/Segmented';
import { BRANCH_KINDS, type BranchKind, KIND_FLAGS, madeCode } from './state';

// Add a branch (issue #581, ADR-089): the name, what the branch is (a shop, a store or a head office that keeps no goods, sent as
// sells_goods and stocks_goods, ADR-041 and ADR-048) and where it is. The code is never typed (the owner's rule of #562): the server
// makes it from the name, and the form shows the one it expects. The shop and store rules apply on the server from the moment it
// exists. A full plan says so in plain words, with a link to Plan and billing for someone who may open it. The screen closes the
// form, shows the toast and refreshes every branch list.

const KIND_HINT: Record<BranchKind, string> = {
  shop: 'Sells, keeps stock and takes purchases.',
  store: 'Keeps stock and takes purchases and transfers. Nothing is sold here.',
  office: 'Keeps no goods. Cash records and expenses still work.',
};

export function AddBranchForm({ takenCodes, canOpenBilling, onAdded, onCancel }: {
  /** The codes of the branches already listed, so the code shown is the one the server will make. */
  takenCodes: readonly string[];
  canOpenBilling: boolean;
  onAdded: (branch: AdminBranch) => void | Promise<void>;
  onCancel: () => void;
}) {
  const [name, setName] = useState('');
  const [kind, setKind] = useState<BranchKind>('shop');
  const [location, setLocation] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<{ code?: string; text: string } | null>(null);

  const nameProblem = name.trim() === '' ? 'Type the branch name.' : null;
  // An element, not a render function, so the chosen option keeps its node and its focus across renders.
  const kindPicker = <Segmented label="What is it?" options={BRANCH_KINDS} value={kind} onChange={setKind} />;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setSent(true);
    if (nameProblem) {
      focusFirstMissing(e.currentTarget as HTMLFormElement);
      return;
    }
    if (busy) return;
    setBusy(true);
    setRefusal(null);
    try {
      const added = await branches.create({
        name: name.trim(),
        location: location.trim() || undefined,
        ...KIND_FLAGS[kind],
      });
      await onAdded(added);
    } catch (err) {
      setRefusal({ code: err instanceof BranchesError ? err.code : undefined, text: err instanceof Error ? err.message : 'Could not add the branch.' });
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="rt-card stack" aria-label="Add a branch" noValidate onSubmit={(e) => void submit(e)}>
      <h2>Add a branch</h2>
      <FieldsNeededNote />
      <Field
        label="Name"
        required
        hint={name.trim() ? `Code ${madeCode(name, takenCodes)}, made by the app from the name.` : 'The app makes the branch code from the name.'}
        value={name}
        maxLength={200}
        autoComplete="off"
        error={nameProblem}
        showError={sent}
        onChange={(e) => setName(e.target.value)}
      />
      <div className="field">
        {/* The group is named by its aria-label; the visible words are the same, so a screen reader hears them once. */}
        <span className="field-label" aria-hidden="true">
          What is it?
        </span>
        {kindPicker}
        <span className="field-hint">{KIND_HINT[kind]}</span>
      </div>
      <Field label="Where is it?" hint="A street, a market or a town." value={location} maxLength={200} onChange={(e) => setLocation(e.target.value)} />
      {refusal &&
        (refusal.code === 'plan_limit_reached' ? (
          <p role="alert" className="alert alert-warning">
            {canOpenBilling ? (
              <span>
                Your plan has no room for another branch. Change your plan, or close a branch you no longer use.{' '}
                <Link to="/staff/setup/billing">Open Plan and billing</Link>
              </span>
            ) : (
              <span>Your plan has no room for another branch. Ask the owner to change the plan, or close a branch you no longer use.</span>
            )}
          </p>
        ) : (
          <p role="alert" className="alert alert-danger">{refusal.text}</p>
        ))}
      <ActionBar>
        <BusyButton type="submit" className="rt-primary" busy={busy}>
          Add the branch
        </BusyButton>
        <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </ActionBar>
    </form>
  );
}

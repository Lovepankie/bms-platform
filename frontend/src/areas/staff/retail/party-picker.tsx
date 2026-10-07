import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { retail, type CashParty, type CashPartyKind } from '../../../api/retail';
import { Problem } from './ui';

// One list of cash parties serves beneficiaries and advance parties (FR-RET-17). The picker offers the
// active parties of the allowed kinds and lets a permitted user add one on the spot. Adding is not a
// money move, so it needs no idempotency key; a name already in the list is refused in plain words.

export const KIND_LABELS: Record<string, string> = {
  owner: 'Owner', staff: 'Staff member', related_entity: 'Related company', supplier: 'Supplier', other: 'Other',
};

export function useCashParties() {
  return useQuery({ queryKey: ['retail', 'cash-parties'], queryFn: () => retail.listCashParties() });
}

export function PartyPicker({ id, label, parties, kinds, listed, value, onChange, noneLabel, addLabel, optional = false }: {
  id: string;
  label: string;
  parties: CashParty[];
  /** The kinds a party added here may be; one means the kind is fixed, several means the person chooses. */
  kinds: CashPartyKind[];
  /** The kinds the list shows; every kind when absent (a beneficiary may be anyone in the list). */
  listed?: CashPartyKind[];
  value: string;
  onChange: (partyId: string) => void;
  noneLabel: string;
  addLabel: string;
  optional?: boolean;
}) {
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [kind, setKind] = useState<CashPartyKind>(kinds[0] ?? 'other');
  const nameId = useId();
  const kindId = useId();
  const add = useMutation({
    mutationFn: () => retail.createCashParty({ name: name.trim(), kind }),
    onSuccess: (party) => {
      void queryClient.invalidateQueries({ queryKey: ['retail', 'cash-parties'] });
      onChange(party.id ?? '');
      setAdding(false);
      setName('');
    },
  });
  const choices = parties.filter((p) => p.active !== false && (!listed || listed.includes(p.kind as CashPartyKind)));
  return (
    <>
      {!adding && (
        <>
          <label htmlFor={id}>{label}</label>
          <select id={id} value={value} onChange={(e) => onChange(e.target.value)} aria-required={!optional}>
            <option value="">{noneLabel}</option>
            {choices.map((p) => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
          <button type="button" className="btn-sm" onClick={() => { setAdding(true); add.reset(); }}>
            {addLabel}
          </button>
        </>
      )}
      {adding && (
        <fieldset>
          <legend>{addLabel}</legend>
          <label htmlFor={nameId}>Name</label>
          <input id={nameId} value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
          {kinds.length > 1 && (
            <>
              <label htmlFor={kindId}>Who is this?</label>
              <select id={kindId} value={kind} onChange={(e) => setKind(e.target.value as CashPartyKind)}>
                {kinds.map((k) => (
                  <option key={k} value={k}>{KIND_LABELS[k] ?? k}</option>
                ))}
              </select>
            </>
          )}
          <Problem error={add.error} />
          <div className="cluster">
            <button type="button" className="btn-sm" disabled={name.trim() === '' || add.isPending} onClick={() => add.mutate()}>
              {add.isPending ? 'Adding' : 'Add to the list'}
            </button>
            <button type="button" className="btn-sm" onClick={() => setAdding(false)}>Cancel</button>
          </div>
        </fieldset>
      )}
    </>
  );
}

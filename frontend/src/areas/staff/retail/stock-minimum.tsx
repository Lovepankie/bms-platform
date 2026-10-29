import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { type StockMinimum, type StockMinimumBranch, parseLevel, showLevel, stockMinimumApi } from '../../../api/stock-minimum';
import { Problem } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';

// "Warn me when stock is below" on an item's page (issue #412, ADR-057). One level for every shop and store, and a different one for a
// chosen shop or store. With no level an item is still watched by the pace it sells at (lead days of cover), so nothing goes silent;
// the low-stock notification and the Stock health page use both rules, whichever trips first. Each size or colour of an item is its
// own product and has its own level. Not a nested form: the item form around it saves separately.

/** The toast of a level saved or taken off (issue #682): the level when one is set. */
export function levelWords(_next: unknown, qty: string | null): string {
  return qty === null ? 'Level removed' : `Level set to ${showLevel(qty)}`;
}

export function StockMinimumEditor({ productId, unit }: { productId: string; unit?: string }) {
  const queryClient = useQueryClient();
  const key = ['retail', 'stock-minimum', productId];
  const level = useQuery({ queryKey: key, queryFn: () => stockMinimumApi.get(productId) });
  const [every, setEvery] = useState<string | null>(null);
  const done = (next: StockMinimum) => {
    queryClient.setQueryData(key, next);
  };
  const saveEvery = useMutation({
    mutationFn: (qty: string | null) => stockMinimumApi.setEveryBranch(productId, qty),
    onSuccess: (next) => {
      setEvery(null);
      done(next);
    },
    meta: { success: levelWords },
  });
  if (level.isPending) return <p className="loading">Loading</p>;
  if (!level.data) return <Problem error={level.error} />;
  const data = level.data;
  const typed = every ?? showLevel(data.min_qty);
  const parsed = parseLevel(typed);
  const per = unit ? ` ${unit}` : '';
  return (
    <fieldset className="stack" data-tour="stock-minimum">
      <legend>Warn me when stock is below</legend>
      <p className="hint">
        You are told when this item falls below the level at a shop or store. Without a level, you are still told when it would run out within{' '}
        {data.lead_days ?? 14} days at the pace it sells.
      </p>
      {data.can_set_every_branch ? (
        <div className="cluster">
          <label htmlFor={`min-every-${productId}`}>Every shop and store{per}</label>
          <input
            id={`min-every-${productId}`}
            inputMode="decimal"
            value={typed}
            onChange={(e) => setEvery(e.target.value)}
            aria-invalid={typed.trim() !== '' && parsed === null}
            autoComplete="off"
          />
          <BusyButton type="button" className="btn-sm" busy={saveEvery.isPending && saveEvery.variables !== null} disabled={saveEvery.isPending || parsed === null || parsed === showLevel(data.min_qty)} onClick={() => saveEvery.mutate(parsed)}>
            Save
          </BusyButton>
          {data.min_qty && (
            <BusyButton type="button" className="btn-ghost btn-sm" busy={saveEvery.isPending && saveEvery.variables === null} disabled={saveEvery.isPending} onClick={() => saveEvery.mutate(null)}>
              Remove
            </BusyButton>
          )}
        </div>
      ) : (
        <p>Every shop and store: {data.min_qty ? `${showLevel(data.min_qty)}${per}` : 'no level set'}.</p>
      )}
      <Problem error={saveEvery.error} />
      {(data.branches ?? []).length > 0 && (
        <ul className="stack" style={{ listStyle: 'none', padding: 0 }}>
          {(data.branches ?? []).map((b) => (
            <BranchLevel key={b.branch_id} productId={productId} branch={b} per={per} onSaved={done} />
          ))}
        </ul>
      )}
    </fieldset>
  );
}

function BranchLevel({ productId, branch, per, onSaved }: { productId: string; branch: StockMinimumBranch; per: string; onSaved: (next: StockMinimum) => void }) {
  const [typed, setTyped] = useState<string | null>(null);
  const shown = typed ?? showLevel(branch.min_qty);
  const parsed = parseLevel(shown);
  const name = `${branch.branch_name ?? ''}${branch.store ? ' (store)' : ''}`;
  const save = useMutation({
    mutationFn: (qty: string | null) => stockMinimumApi.setBranch(productId, branch.branch_id ?? '', qty),
    onSuccess: (next) => {
      setTyped(null);
      onSaved(next);
    },
    meta: { success: levelWords },
  });
  const applies = branch.applies_qty ? `warns below ${showLevel(branch.applies_qty)}${per}` : 'no level, watched by its pace';
  return (
    <li className="rt-card">
      <p>
        <strong>{name}</strong>: {applies}
        {!branch.min_qty && branch.applies_qty ? ' (the level for every branch)' : ''}.
      </p>
      {branch.can_set && (
        <div className="cluster">
          <label htmlFor={`min-${branch.branch_id}`}>Its own level{per}</label>
          <input
            id={`min-${branch.branch_id}`}
            inputMode="decimal"
            value={shown}
            onChange={(e) => setTyped(e.target.value)}
            aria-invalid={shown.trim() !== '' && parsed === null}
            autoComplete="off"
          />
          <BusyButton type="button" className="btn-sm" busy={save.isPending && save.variables !== null} disabled={save.isPending || parsed === null || parsed === showLevel(branch.min_qty)} onClick={() => save.mutate(parsed)}>
            Save
          </BusyButton>
          {branch.min_qty && (
            <BusyButton type="button" className="btn-ghost btn-sm" busy={save.isPending && save.variables === null} disabled={save.isPending} onClick={() => save.mutate(null)}>
              Use the level for every branch
            </BusyButton>

          )}
        </div>
      )}
      <Problem error={save.error} />
    </li>
  );
}

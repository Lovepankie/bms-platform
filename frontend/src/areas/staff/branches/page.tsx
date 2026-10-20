import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { type AdminBranch, BranchesError, branches } from '../../../api/branches';
import { branchLabel } from '../../../auth/branch';
import { useStaff } from '../context';
import { money } from '../retail/ui';
import { branchLine, canManageBranches, closedCount, closedOn, isClosed, keepsGoods, showBranches, visibleBranches } from './state';

// Branches (issue #329; ADR-041): which branches are open, close one without losing its history, reopen it, and say that a branch
// keeps no goods (a head office). Everyone who may read branches sees the list; an administrator also closes, reopens and switches.
// Closed branches are hidden until asked for. Closing over stock or transfers on the road shows the server's warning with the stock
// value and asks for a reason; nothing is moved or written off.

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');

type Panel = { id: string; kind: 'close' | 'nostock'; warning: BranchesError | null; reason: string } | null;

export function BranchesScreen() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const allowed = showBranches(me);
  const canManage = canManageBranches(me);
  const list = useQuery({ queryKey: ['staff-branches'], queryFn: branches.list, enabled: allowed });
  const [showClosed, setShowClosed] = useState(false);
  const [panel, setPanel] = useState<Panel>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<{ id: string; text: string } | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  // One action at a time: a second tap before the first answer must not send a second request.
  const working = useRef(false);

  if (!allowed) {
    return (
      <main className="rt ln">
        <h1>Branches</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }

  async function act(branch: AdminBranch, run: () => Promise<string>, onRefusal?: (e: BranchesError) => boolean) {
    if (working.current) return;
    working.current = true;
    setBusyId(branch.id ?? null);
    setError(null);
    setNotice(null);
    try {
      setNotice(await run());
      setPanel(null);
      await queryClient.invalidateQueries({ queryKey: ['staff-branches'] });
      // Pickers, defaults and stock lists follow the branch list.
      await queryClient.invalidateQueries({ queryKey: ['me'] });
      void queryClient.invalidateQueries({ queryKey: ['setup-branches'] });
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    } catch (e) {
      if (!(e instanceof BranchesError && onRefusal?.(e))) setError({ id: branch.id ?? '', text: messageOf(e) });
    } finally {
      working.current = false;
      setBusyId(null);
    }
  }

  const close = (branch: AdminBranch, reason: string) =>
    act(
      branch,
      async () => {
        await branches.close(branch, reason.trim() || undefined);
        return `${branchLabel(branch)} is closed. Its history is kept.`;
      },
      (e) => {
        // The server found stock or transfers on the way: show its words and ask for a reason.
        if (e.code !== 'branch_close_needs_reason') return false;
        setPanel({ id: branch.id ?? '', kind: 'close', warning: e, reason });
        return true;
      },
    );

  const reopen = (branch: AdminBranch) =>
    act(branch, async () => {
      await branches.reopen(branch);
      return `${branchLabel(branch)} is open again.`;
    });

  const stocking = (branch: AdminBranch, on: boolean) =>
    act(branch, async () => {
      await branches.setStocking(branch, on);
      return on ? `${branchLabel(branch)} keeps goods again.` : `${branchLabel(branch)} now keeps no goods.`;
    });

  const all = list.data ?? [];
  const shown = visibleBranches(all, showClosed);
  const hidden = closedCount(all);

  return (
    <main className="rt ln stack" data-tour="branches-list">
      <h1>Branches</h1>
      <p data-tour="branches-intro">
        Your shops, stores and head office. Close a branch you no longer use: its sales and stock records stay for your reports,
        and it leaves the branch lists and the sale, restock and move-stock screens.
      </p>
      {!canManage && <p className="hint">Only an administrator can close or reopen a branch.</p>}
      {notice && <p role="status" className="alert alert-success">{notice}</p>}
      {list.isPending && <p className="loading">Loading your branches</p>}
      {list.isError && <p role="alert" className="alert alert-danger">{messageOf(list.error)}</p>}
      {list.data && (
        <>
          {hidden > 0 && (
            <label className="hint">
              <input type="checkbox" checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} /> Show closed branches ({hidden})
            </label>
          )}
          {shown.length === 0 ? (
            <p className="empty-state">No branches to show.</p>
          ) : (
            <ul className="ln-list stack">
              {shown.map((b) => {
                const closed = isClosed(b);
                const open = panel?.id === b.id;
                return (
                  <li key={b.id} className="rt-card stack">
                    <p>
                      <strong>{branchLabel(b)}</strong>{' '}
                      {b.is_head_office && <span className="badge">Head office</span>}{' '}
                      {closed && <span className="badge badge-warning">Closed</span>}{' '}
                      {!closed && !keepsGoods(b) && <span className="badge">Keeps no goods</span>}
                    </p>
                    <p className="hint">{branchLine(b)}</p>
                    {closed && closedOn(b) && <p className="hint">{closedOn(b)}</p>}
                    {closed && b.closed_reason && <p className="hint">Reason: {b.closed_reason}</p>}
                    {closed && (b.stock_items ?? 0) > 0 && (
                      <p className="hint">
                        Still holds {b.stock_items} {b.stock_items === 1 ? 'item' : 'items'} worth {money(b.stock_value_minor ?? 0)}, kept on record. It is left out of the
                        stock list unless you ask for closed branches there.
                      </p>
                    )}
                    {error?.id === b.id && <p role="alert" className="alert alert-danger">{error?.text}</p>}
                    {canManage && !open && (
                      <p className="cluster">
                        {closed ? (
                          <button type="button" className="btn-sm" disabled={busyId !== null} onClick={() => void reopen(b)}>
                            {busyId === b.id ? 'Reopening' : 'Reopen this branch'}
                          </button>
                        ) : (
                          <>
                            <button
                              type="button"
                              className="btn-sm"
                              disabled={busyId !== null}
                              onClick={() => {
                                if (keepsGoods(b)) {
                                  setPanel({ id: b.id ?? '', kind: 'nostock', warning: null, reason: '' });
                                  setError(null);
                                } else {
                                  void stocking(b, true);
                                }
                              }}
                            >
                              {keepsGoods(b) ? 'Mark as keeping no goods' : 'Keeps goods again'}
                            </button>
                            {!b.is_head_office && (
                              <button type="button" className="btn-ghost btn-sm" disabled={busyId !== null} onClick={() => { setPanel({ id: b.id ?? '', kind: 'close', warning: null, reason: '' }); setError(null); }}>
                                Close this branch
                              </button>
                            )}
                          </>
                        )}
                      </p>
                    )}
                    {open && panel && panel.kind === 'nostock' && (
                      <div className="alert alert-warning stack" role="group" aria-label={`Mark ${branchLabel(b)} as keeping no goods`}>
                        <p>
                          Mark <strong>{branchLabel(b)}</strong> as keeping no goods? Nothing can be sold, restocked, received or counted there afterwards.
                          Cash records still work, and goods left there can still be moved out. You can switch it back at any time.
                        </p>
                        <p className="cluster">
                          <button type="button" className="rt-primary" disabled={busyId !== null} onClick={() => void stocking(b, false)}>
                            {busyId === b.id ? 'Saving' : 'Yes, mark it'}
                          </button>
                          <button type="button" className="btn-ghost" disabled={busyId !== null} onClick={() => setPanel(null)}>Keep it as it is</button>
                        </p>
                      </div>
                    )}
                    {open && panel && panel.kind === 'close' && (
                      <div className="alert alert-warning stack" role="group" aria-label={`Close ${branchLabel(b)}`}>
                        {panel.warning ? (
                          <>
                            <p>{panel.warning.message}</p>
                            <label>
                              Why are you closing it?
                              <textarea
                                value={panel.reason}
                                maxLength={500}
                                rows={2}
                                onChange={(e) => setPanel({ ...panel, reason: e.target.value })}
                              />
                            </label>
                          </>
                        ) : (
                          <p>
                            Close <strong>{branchLabel(b)}</strong>? Its sales, stock records and history are kept and still show in your reports. It will no
                            longer appear in branch lists, sales, restocks or stock moves. You can reopen it later.
                          </p>
                        )}
                        <p className="cluster">
                          <button
                            type="button"
                            className="btn-danger"
                            disabled={busyId !== null || (panel.warning !== null && panel.reason.trim() === '')}
                            onClick={() => void close(b, panel.reason)}
                          >
                            {busyId === b.id ? 'Closing' : panel.warning ? 'Close it anyway' : 'Yes, close it'}
                          </button>
                          <button type="button" className="btn-ghost" disabled={busyId !== null} onClick={() => setPanel(null)}>Keep it open</button>
                        </p>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/setup/branches')({ component: BranchesScreen });

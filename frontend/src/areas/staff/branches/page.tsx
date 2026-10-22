import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { type ChangeEvent, useRef, useState } from 'react';
import { type AdminBranch, BranchesError, branches } from '../../../api/branches';
import { branchLabel } from '../../../auth/branch';
import { ShopLogo } from '../branch-picker';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import { money, useBranchRoles } from '../retail/ui';
import { branchLine, canManageBranches, closedCount, closedOn, isClosed, keepsGoods, sellsGoods, showBranches, visibleBranches } from './state';

// Branches (issue #329; ADR-041): which branches are open, close one without losing its history, reopen it, and say that a branch
// keeps no goods (a head office). Everyone who may read branches sees the list; an administrator also closes, reopens and switches.
// Closed branches are hidden until asked for. Closing over stock or transfers on the road shows the server's warning with the stock
// value and asks for a reason; nothing is moved or written off. An administrator also gives each open branch its own logo, with the
// rules of the business logo (issue #461, ADR-068); a branch without one shows the business logo on its receipts.

// The ADR-033 branch role, when roles are on, shown beside the Store flag so the two are never read as one (ADR-048).
const roleWords = (roles: { branch_id?: string; role?: string }[] | undefined, id: string | undefined): string | null => {
  const role = roles?.find((r) => r.branch_id === id)?.role;
  return role && role !== 'both' ? `Branch role: ${role}` : null;
};

/** The types the server takes for a logo, as on Business set-up; it checks the content and re-encodes it anyway. */
const LOGO_ACCEPT = 'image/png,image/jpeg,image/webp';

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');

type Panel = { id: string; kind: 'close' | 'nostock' | 'store'; warning: BranchesError | null; reason: string } | null;

export function BranchesScreen() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const allowed = showBranches(me);
  const canManage = canManageBranches(me);
  const roles = useBranchRoles(me);
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

  const selling = (branch: AdminBranch, on: boolean) =>
    act(branch, async () => {
      await branches.setSelling(branch, on);
      return on ? `${branchLabel(branch)} is a shop again and sells.` : `${branchLabel(branch)} is now a store. It holds stock but does not sell.`;
    });

  const uploadLogo = (branch: AdminBranch, event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    void act(branch, async () => {
      await branches.uploadLogo(branch, file);
      return `Logo saved for ${branchLabel(branch)}. Its receipts and documents now carry it.`;
    });
  };

  const removeLogo = (branch: AdminBranch) =>
    act(branch, async () => {
      await branches.removeLogo(branch);
      return `Logo removed from ${branchLabel(branch)}. It shows the business logo again.`;
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
                      <ShopLogo url={b.logo_url} name={branchLabel(b)} /> <strong>{branchLabel(b)}</strong>{' '}
                      {b.is_head_office && <span className="badge">Head office</span>}{' '}
                      {closed && <span className="badge badge-warning">Closed</span>}{' '}
                      {!closed && !keepsGoods(b) && <span className="badge">Keeps no goods</span>}{' '}
                      {!closed && !sellsGoods(b) && <span className="badge">Store (does not sell)</span>}{' '}
                      {!closed && roleWords(roles, b.id) && <span className="badge">{roleWords(roles, b.id)}</span>}
                    </p>
                    <p className="hint">{branchLine(b)}</p>
                    {canManage && !closed && (
                      <fieldset className="stack" aria-label={`Logo of ${branchLabel(b)}`}>
                        <legend>Logo</legend>
                        {!b.logo_url && <p className="hint">No logo of its own: its receipts and documents carry the business logo.</p>}
                        <label>
                          {b.logo_url ? 'Replace the logo' : 'Upload a logo'} (PNG, JPEG or WebP, up to 1 MB, at least 128 px)
                          <input type="file" accept={LOGO_ACCEPT} disabled={busyId !== null} onChange={(e) => uploadLogo(b, e)} style={{ display: 'block' }} />
                        </label>
                        {b.logo_url && (
                          <p>
                            <button type="button" className="btn-sm" disabled={busyId !== null} onClick={() => void removeLogo(b)}>
                              Remove the logo
                            </button>
                          </p>
                        )}
                      </fieldset>
                    )}
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
                          <BusyButton type="button" className="btn-sm" busy={busyId === b.id} disabled={busyId !== null} onClick={() => void reopen(b)}>
                            Reopen this branch
                          </BusyButton>
                        ) : (
                          <>
                            {sellsGoods(b) && (
                            <BusyButton
                              type="button"
                              className="btn-sm"
                              busy={busyId === b.id && !keepsGoods(b)}
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
                            </BusyButton>
                            )}
                            {keepsGoods(b) && (
                              <BusyButton
                                type="button"
                                className="btn-sm"
                                busy={busyId === b.id && !sellsGoods(b)}
                                disabled={busyId !== null}
                                onClick={() => {
                                  if (sellsGoods(b)) {
                                    setPanel({ id: b.id ?? '', kind: 'store', warning: null, reason: '' });
                                    setError(null);
                                  } else {
                                    void selling(b, true);
                                  }
                                }}
                              >
                                {sellsGoods(b) ? 'Mark as a store (does not sell)' : 'Sells again (make it a shop)'}
                              </BusyButton>
                            )}
                            {!b.is_head_office && (
                              <button type="button" /* busy-exempt: opens the close panel */ className="btn-ghost btn-sm" disabled={busyId !== null} onClick={() => { setPanel({ id: b.id ?? '', kind: 'close', warning: null, reason: '' }); setError(null); }}>
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
                          <BusyButton type="button" className="rt-primary" busy={busyId === b.id} disabled={busyId !== null} onClick={() => void stocking(b, false)}>
                            Yes, mark it
                          </BusyButton>
                          <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busyId !== null} onClick={() => setPanel(null)}>Keep it as it is</button>
                        </p>
                      </div>
                    )}
                    {open && panel && panel.kind === 'store' && (
                      <div className="alert alert-warning stack" role="group" aria-label={`Mark ${branchLabel(b)} as a store`}>
                        <p>
                          Mark <strong>{branchLabel(b)}</strong> as a store? It keeps its stock and still takes purchases, restocks and transfers in and out, but
                          nothing can be sold there afterwards. No takings, savings, banking or repayments here; expenses can still be paid from it. It
                          leaves the sale screen and the till. You can make it a shop again at any time.
                        </p>
                        <p className="cluster">
                          <BusyButton type="button" className="rt-primary" busy={busyId === b.id} disabled={busyId !== null} onClick={() => void selling(b, false)}>
                            Yes, make it a store
                          </BusyButton>
                          <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busyId !== null} onClick={() => setPanel(null)}>Keep it as it is</button>
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
                          <BusyButton
                            type="button"
                            className="btn-danger"
                            busy={busyId === b.id}
                            disabled={busyId !== null || (panel.warning !== null && panel.reason.trim() === '')}
                            onClick={() => void close(b, panel.reason)}
                          >
                            {panel.warning ? 'Close it anyway' : 'Yes, close it'}
                          </BusyButton>
                          <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busyId !== null} onClick={() => setPanel(null)}>Keep it open</button>
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

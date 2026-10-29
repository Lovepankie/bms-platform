import { useQuery } from '@tanstack/react-query';
import { type ReactNode, useEffect, useRef, useState } from 'react';
import type { Me } from '../../api/client';
import { PLATFORM_BRAND, useBusinessName, useLogo, useLogoSrc, useShellBrand } from '../../app/branding';
import { icons } from '../../components/icons';
import { retail } from '../../api/retail';
import { ALL_BRANCHES, branchLabel, initialBranch, loadBranch, saveBranch } from '../../auth/branch';
import { useToast } from './retail/toast';

// The branch picker in the staff bar (FR-BR-03) and the branch it starts on (#103). A user with a
// stored choice gets it back; anyone else starts on a branch that trades rather than an empty head
// office, which needs one stock read, so the staff area waits for it before showing a screen.

/** The branches holding stock, for a session that may read stock; shared with the retail screens. */
export function useStockedBranches(me: Me | undefined) {
  return useQuery({
    queryKey: ['retail', 'stocked-branches'],
    queryFn: () => retail.stockedBranches(),
    enabled: (me?.permissions ?? []).includes('retail.stock.read'),
    staleTime: 60_000,
    retry: 1,
  });
}

// Issue #656: a screen that must not change branch yet (Sell, offline, holding a count it cannot discard) is asked first, and says why.
const branchHolds = new Set<() => boolean>();
// Issue #664: told after each render of a holding screen and when it closes, since its hold may have lifted then. A hold must
// only lift on a render: one lifted by a ref change alone is not seen until the screen next renders.
const holdLifts = new Set<() => void>();

/** While the calling screen is open, `holds` is asked before every change of branch: true keeps the branch, and the screen says why. */
export function useBranchHold(holds: () => boolean): void {
  const latest = useRef(holds);
  latest.current = holds;
  useEffect(() => {
    const ask = () => latest.current();
    branchHolds.add(ask);
    return () => {
      branchHolds.delete(ask);
      for (const lift of holdLifts) lift();
    };
  }, []);
  useEffect(() => {
    for (const lift of holdLifts) lift();
  });
}

/** What the toast says once the branch in use changes (issue #680): "You are now in Test Shop One." */
export function switchedTo(me: Me, selection: string): string {
  if (selection === ALL_BRANCHES) return 'You now see all branches.';
  return `You are now in ${branchLabel((me.branches ?? []).find((b) => b.id === selection)) || 'that branch'}.`;
}

/** Issue #680: every change of branch, from the bar, the menu or a page, says where the person now is in the house toast. */
export function useBranchSwitch(me: Me | undefined, branch: string | null, choose: (selection: string) => boolean): { choose: (selection: string) => void; toast: ReactNode } {
  const { show, toast } = useToast();
  const switchTo = (selection: string) => {
    if (me && selection !== branch && choose(selection)) show(switchedTo(me, selection), 'info');
  };
  return { choose: switchTo, toast };
}

/** The active branch, a way to choose it (remembered per tenant and user; false when a screen holds the branch), and whether it is known yet. */
export function useActiveBranch(me: Me | undefined): { branch: string | null; choose: (selection: string) => boolean; ready: boolean } {
  const [branch, setBranch] = useState<string | null>(null);
  const shown = useRef(branch);
  shown.current = branch;
  const [ready, setReady] = useState(false);
  const stocked = useStockedBranches(me);
  // Waits for the stock read only while it is on its way (a failure or no stock read starts as before).
  const waiting = (me?.permissions ?? []).includes('retail.stock.read') && stocked.isPending;
  // Issue #664: a change of branch a hold kept waiting is tried again once the hold lifts.
  const [retry, setRetry] = useState(0);
  const held = useRef(false);
  useEffect(() => {
    const lift = () => {
      if (!held.current || [...branchHolds].some((holds) => holds())) return;
      held.current = false;
      setRetry((n) => n + 1);
    };
    holdLifts.add(lift);
    return () => void holdLifts.delete(lift);
  }, []);

  useEffect(() => {
    if (!me || waiting) return;
    const valid = (s: string | null) => (s === ALL_BRANCHES ? Boolean(me.all_branches) : (me.branches ?? []).some((b) => b.id === s));
    // A profile refetch (every token refresh) keeps the branch already in use, and a screen that holds the branch keeps it too.
    const current = shown.current;
    if (current === null || !valid(current)) {
      const next = initialBranch(me, loadBranch(me.user_id ?? ''), stocked.data ? new Set(stocked.data) : null);
      if (current === null || next === current || ![...branchHolds].some((holds) => holds())) setBranch(next);
      else held.current = true;
    } else held.current = false;
    setReady(true);
  }, [me, waiting, stocked.data, retry]);

  const choose = (selection: string) => {
    if ([...branchHolds].some((holds) => holds())) return false;
    setBranch(selection);
    if (me) saveBranch(me.user_id ?? '', selection);
    return true;
  };
  return { branch, choose, ready };
}

/** A shop's own logo beside its name (issue #461, ADR-068); nothing for a shop without one, which goes by the business logo. */
export function ShopLogo({ url, name }: { url?: string | null; name: string }) {
  const src = useLogoSrc(url ?? null);
  return src ? <img className="shop-logo" src={src} alt={`Logo of ${name}`} /> : null;
}

/**
 * The picture of the shop in use (issue #461, ADR-068): its own logo; a shop without one, or whose logo cannot be read, shows the
 * business logo, and without that none. While the shop's logo loads `loading` holds its place, so the business logo never shows
 * for a moment first. In production the image loads straight from its URL, so an image that fails to load (`broke`) is passed
 * over for the next one.
 */
function useShopImage(me: Me, branch: string | null) {
  const active = (me.branches ?? []).find((b) => b.id === branch);
  const shop = useLogo(active?.logo_url ?? null);
  const brand = useShellBrand();
  const [broken, setBroken] = useState<string[]>([]);
  const src = [shop.src, brand === PLATFORM_BRAND ? null : brand.logoSrc].find((s) => s && !broken.includes(s)) ?? null;
  return { active, loading: shop.loading, src, fromShop: src !== null && src === shop.src, broke: (s: string) => setBroken((b) => [...b, s]), brand };
}

/** The shop in use, its logo beside its name, on the More page (issues #461, #539). */
export function CurrentShop({ me, branch }: { me: Me; branch: string | null }) {
  const { active, loading, src, broke } = useShopImage(me, branch);
  if (!active && branch !== ALL_BRANCHES) return null;
  const name = active ? branchLabel(active) : 'All branches';
  return (
    <div className="current-shop">
      {loading ? <span className="current-shop-logo" aria-hidden="true" /> : src && <img className="current-shop-logo" src={src} alt={`Logo of ${name}`} onError={() => broke(src)} />}
      <span className="current-shop-name">{name}</span>
    </div>
  );
}

/** The choices of a branch switcher: All branches when the session may see them, then each branch with what it does not do. */
export function BranchOptions({ me }: { me: Me }) {
  return (
    <>
      {me.all_branches && <option value={ALL_BRANCHES}>All branches</option>}
      {(me.branches ?? []).map((b) => (
        <option key={b.id} value={b.id}>
          {branchLabel(b)}
          {b.stocks_goods === false ? ' (no stock)' : b.sells_goods === false ? ' (store)' : ''}
        </option>
      ))}
    </>
  );
}

/** Up to two capitals from a name, for a business with no logo: "Test Shop 01" gives "TS". */
export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter((w) => /^[a-z]/i.test(w))
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase())
    .join('') || ([...name.trim()][0]?.toUpperCase() ?? '');
}

/**
 * The top of the staff menu (issue #564): the shop's logo, else the business's own logo (ADR-068), else the business's initials;
 * never the platform's logo or name, which the shell shows only off a business's host or before its branding loads: then a plain
 * tile holds the logo's place and no name shows until the business's own arrives. Then the business name and the person's role,
 * and under them, on a row of
 * its own so nothing is cut off, the branch in use, which switches branch when there is more than one to choose (a native select
 * laid over it, as in the top bar's title). From 720px the sidebar is the only place the business and the branch show; `tour` is
 * then the branch step's anchor, on the branch row, or on the whole top for a session with no branch to show.
 */
export function MenuShop({ me, branch, role, onChoose, tour }: { me: Me; branch: string | null; role: string; onChoose: (selection: string) => void; tour?: string }) {
  const { active, loading, src, fromShop, broke } = useShopImage(me, branch);
  // The business's own name: its loaded branding's non-empty display_name, never the shell brand's fallback (ADR-086 decision 8).
  const business = useBusinessName();
  const branches = me.branches ?? [];
  const choices = branches.length + (me.all_branches ? 1 : 0);
  const name = active ? branchLabel(active) : branch === ALL_BRANCHES ? 'All branches' : choices > 1 ? 'Choose a branch' : '';
  return (
    <div className="menu-shop" data-tour={name ? undefined : tour}>
      {loading ? (
        <span className="menu-shop-logo" aria-hidden="true" />
      ) : src ? (
        <img className="menu-shop-logo" src={src} alt={`Logo of ${fromShop && active ? branchLabel(active) : (business ?? 'the business')}`} onError={() => broke(src)} />
      ) : (
        <span className={business ? 'menu-shop-logo menu-shop-initials' : 'menu-shop-logo menu-shop-blank'} aria-hidden="true">
          {business ? initials(business) : null}
        </span>
      )}
      <span className="menu-shop-text">
        {business && <span className="menu-shop-name">{business}</span>}
        {role && <span className="menu-shop-role">{role}</span>}
      </span>
      {name && (
        <span className="menu-shop-branch" data-tour={tour}>
          <span className="menu-shop-branch-name">{name}</span>
          {choices > 1 && <span className="menu-shop-chevron" aria-hidden="true">{icons.chevron}</span>}
          {choices > 1 && (
            <select className="menu-shop-select" aria-label="Switch branch" value={branch ?? ''} onChange={(e) => onChoose(e.target.value)}>
              <BranchOptions me={me} />
            </select>
          )}
        </span>
      )}
    </div>
  );
}

import type { ReactNode } from 'react';
import { RETAIL_CURRENCY } from '../../../api/retail';
import { formatMinor } from '../../../components/money';
import { useStaff } from '../context';
import { canSeeProfit, canUse, type RetailScreen } from './permissions';

// Shared pieces of the retail screens. Phone first: one column, 44px tap targets, labels on every
// input, a visible focus ring, and state shown in words as well as colour.

export const money = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

// The look lives in src/app/ui/retail.css with the shared design tokens (#95). The screens used to
// carry an inline <style> here, which the production CSP (style-src 'self') refuses; this stays as
// a no-op so a screen that still renders it keeps compiling.
export function RetailStyles() {
  return null;
}

export function Note({ children }: { children: ReactNode }) {
  return (
    <p role="note" className="alert alert-info">
      {children}
    </p>
  );
}

export function Problem({ error }: { error: unknown }) {
  if (!error) return null;
  return <p role="alert" className="alert alert-danger">{error instanceof Error ? error.message : 'Something went wrong.'}</p>;
}

/** Shows its children only when the session may use the screen; otherwise nothing of the screen. */
export function Gate({ screen, title, children }: { screen: RetailScreen; title: string; children: ReactNode }) {
  const { me } = useStaff();
  if (!canUse(me, screen)) {
    return (
      <main className="rt">
        <h1>{title}</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  return (
    <main className="rt">
      <h1>{title}</h1>
      {children}
    </main>
  );
}

/** The active branch as one concrete branch, or a prompt to choose one. */
export function useSingleBranch(): { branchId: string | null; branchName: string } {
  const { me, branch } = useStaff();
  const found = (me.branches ?? []).find((b) => b.id === branch);
  return { branchId: found?.id ?? null, branchName: found ? `${found.code ?? ''} ${found.name ?? ''}`.trim() : '' };
}

export function BranchRequired() {
  return <Note>Choose one branch in the Branch box at the top of the page first.</Note>;
}

export function useProfitAccess(): boolean {
  return canSeeProfit(useStaff().me);
}

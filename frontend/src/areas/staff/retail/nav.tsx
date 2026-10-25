import type { Me } from '../../../api/client';
import { branchesWhere, canUse, sellingBranches, showRetail } from './permissions';
import { useBranchRolesRead } from './ui';

// Whether the session sells (#95). Sell is the first row of Today in the staff menu (menu.tsx) and the first bottom tab on a phone.
// The retail shortcuts of #95 and their cap of five went with issue #564: the menu lists every shop page under Today, Stock and Money.

/**
 * True when the session may record a sale somewhere: retail is on, the sale screen is allowed and, with branch roles, some branch it
 * sells at is not a store. The branch roles are read only when the first two hold, and until they are read it is false, so Sell
 * never shows and then goes for a session whose every branch is a store.
 */
export function useSells(me: Me): boolean {
  const may = showRetail(me) && canUse(me, 'sale');
  const { roles, loading } = useBranchRolesRead(me, may);
  return may && !loading && sellingBranches(branchesWhere(me, 'retail.sale.create'), roles).length > 0;
}

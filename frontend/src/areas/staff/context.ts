import { createContext, useContext } from 'react';
import type { Me } from '../../api/client';

/** The signed-in user and the active branch, provided by the staff layout (FR-BR-03). */
export interface StaffContextValue {
  me: Me;
  branch: string | null;
  /** Switches the active branch, as the picker in the staff bar does (#103). */
  chooseBranch?: (selection: string) => void;
  /** Signs out, asking first while sales wait on this phone; More offers it on a phone (issue #539). */
  signOut?: () => void;
  /** True while signing out runs. */
  signingOut?: boolean;
}

export const StaffContext = createContext<StaffContextValue | null>(null);

/** The session when there is one: a leaf view drawn outside the staff layout (a test, a print view) shows no permission-gated link. */
export function useOptionalStaff(): StaffContextValue | null {
  return useContext(StaffContext);
}

export function useStaff(): StaffContextValue {
  const value = useContext(StaffContext);
  if (!value) throw new Error('useStaff outside the staff layout');
  return value;
}

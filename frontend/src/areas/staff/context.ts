import { createContext, useContext } from 'react';
import type { Me } from '../../api/client';

/** The signed-in user and the active branch, provided by the staff layout (FR-BR-03). */
export interface StaffContextValue {
  me: Me;
  branch: string | null;
}

export const StaffContext = createContext<StaffContextValue | null>(null);

export function useStaff(): StaffContextValue {
  const value = useContext(StaffContext);
  if (!value) throw new Error('useStaff outside the staff layout');
  return value;
}

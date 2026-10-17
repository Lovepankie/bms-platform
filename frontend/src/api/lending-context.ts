import { createContext, useContext } from 'react';
import { realCollateral, type CollateralApi } from './collateral';
import { realMembers, type MembersApi } from './members';

// Which implementation the member and collateral screens talk to: the real client unless a test (or a
// preview with fabricated data) provides another through LendingApiContext.Provider.

export interface LendingApis {
  members: MembersApi;
  collateral: CollateralApi;
}

export const LendingApiContext = createContext<LendingApis>({ members: realMembers, collateral: realCollateral });

export const useMembersApi = (): MembersApi => useContext(LendingApiContext).members;
export const useCollateralApi = (): CollateralApi => useContext(LendingApiContext).collateral;

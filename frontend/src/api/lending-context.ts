import { createContext, useContext } from 'react';
import { realCollateral, type CollateralApi } from './collateral';
import { realApplications, type ApplicationsApi } from './loan-applications';
import { realMembers, type MembersApi } from './members';

// Which implementation the member, collateral and loan application screens talk to: the real client unless a test (or a
// preview with fabricated data) provides another through LendingApiContext.Provider.

export interface LendingApis {
  members: MembersApi;
  collateral: CollateralApi;
  /** The loan application, appraisal and decision client; the real one when a provider leaves it out. */
  applications?: ApplicationsApi;
}

export const LendingApiContext = createContext<LendingApis>({ members: realMembers, collateral: realCollateral });

export const useMembersApi = (): MembersApi => useContext(LendingApiContext).members;
export const useCollateralApi = (): CollateralApi => useContext(LendingApiContext).collateral;
export const useApplicationsApi = (): ApplicationsApi => useContext(LendingApiContext).applications ?? realApplications;

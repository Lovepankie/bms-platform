import type { Me } from '../api/client';
import type { Tour, TourStep } from './model';

// Page tours for the loan application, appraisal and decision screens (issue #280 slices 3 and 4). Each is a page tour from Help
// for whoever can open the page. Plain words, short sentences, say what to tap. tours-applications.test.tsx renders each page and
// fails if an anchor is missing.

const MEMBERS_READ = 'lending.members.read';
const LOANS_CREATE = 'lending.loans.create';
const LOANS_APPRAISE = 'lending.loans.appraise';
const LOANS_APPROVE = 'lending.loans.approve';

/** The tenant has the lending module (a /me without a module list counts as having it, as member-permissions.ts reads it). */
const lendingTenant = (me: Me) => { const modules = (me as Me & { modules?: string[] }).modules; return !modules || modules.includes('lending'); };
const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);

const applicationSteps = (first: string): TourStep[] => [
  {
    id: 'loan',
    target: 'application-loan',
    module: 'lending',
    title: 'The loan',
    body: first,
  },
  {
    id: 'guarantors',
    target: 'application-guarantors',
    module: 'lending',
    title: 'Guarantors',
    body: 'Tap Add a guarantor and find another member. Type how much they guarantee. Some products need at least one.',
  },
  {
    id: 'collateral',
    target: 'application-collateral',
    module: 'lending',
    title: 'Collateral',
    body: 'Tick the member\'s items that secure this loan, and how much of the loan each one covers. Only items we hold and have a value for show here.',
  },
  {
    id: 'send',
    target: 'application-send',
    module: 'lending',
    title: 'Send for appraisal',
    body: 'Tap Send for appraisal. If something stops it, the application stays saved as a draft and the page says what to fix.',
  },
];

export const newApplicationPage: Tour = {
  id: 'page-application-new',
  version: 1,
  title: 'This page: new loan application',
  audience: (me) => lendingTenant(me) && held(me, LOANS_CREATE) && held(me, MEMBERS_READ),
  autoStart: false,
  page: '/staff/lending/applications/new',
  steps: applicationSteps('Find the member, choose the product, and type how much and for how long. The product\'s limits show under it.'),
};

export const editApplicationPage: Tour = {
  id: 'page-application-edit',
  version: 1,
  title: 'This page: change the application',
  audience: (me) => lendingTenant(me) && held(me, LOANS_CREATE),
  autoStart: false,
  page: '/staff/lending/loans/$loanId/edit',
  steps: applicationSteps('The member and the product stay. Change the amount, the time or what the loan is for.'),
};

export const appraisePage: Tour = {
  id: 'page-loan-appraise',
  version: 1,
  title: 'This page: appraise',
  audience: (me) => lendingTenant(me) && held(me, LOANS_APPRAISE),
  autoStart: false,
  page: '/staff/lending/loans/$loanId/appraise',
  steps: [
    {
      id: 'loan',
      target: 'appraise-loan',
      module: 'lending',
      title: 'The application',
      body: 'What the member asked for, and the largest payment it would mean.',
    },
    {
      id: 'money',
      target: 'appraise-money',
      module: 'lending',
      title: 'Income and payments',
      body: 'Type what the member earns and already pays each month, and what you saw on the visit. The score uses these.',
    },
    {
      id: 'save',
      target: 'appraise-save',
      module: 'lending',
      title: 'Appraise',
      body: 'Tap Appraise. The score, its warnings and the member\'s other loans show on the loan page.',
    },
  ],
};

export const decidePage: Tour = {
  id: 'page-loan-decide',
  version: 1,
  title: 'This page: decide',
  audience: (me) => lendingTenant(me) && held(me, LOANS_APPROVE),
  autoStart: false,
  page: '/staff/lending/loans/$loanId/decide',
  steps: [
    {
      id: 'loan',
      target: 'decide-loan',
      module: 'lending',
      title: 'The application',
      body: 'Read what was asked for and the appraisal under it. The score only advises.',
    },
    {
      id: 'choice',
      target: 'decide-choice',
      module: 'lending',
      title: 'Approve or decline',
      body: 'Approve the amount and time asked for, or less. Declining needs a reason. Whoever sent or appraised the application cannot approve it.',
    },
    {
      id: 'save',
      target: 'decide-save',
      module: 'lending',
      title: 'Record it',
      body: 'Tap the button at the bottom. An approved loan is then ready to pay out.',
    },
  ],
};

export const APPLICATION_TOURS: readonly Tour[] = [newApplicationPage, editApplicationPage, appraisePage, decidePage];

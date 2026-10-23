import type { Me } from '../api/client';
import type { Tour } from './model';

// Page tours of the operator portal (issue #407), on the platform host. An operator is not a tenant user: the portal hands the
// tour host this one profile, with tours switched off for starting by themselves, so a tour runs only from the page's own
// "Show me this page" button and no staff tour ever starts here. Nothing about it is saved. tours-operator.test.tsx renders each
// page and fails if an anchor is missing.

export const OPERATOR: Me = { tours: { 'tours-off': { status: 'dismissed', version: 1 } } } as Me;

const forOperator = (me: Me) => me === OPERATOR;

export const businessesPage: Tour = {
  id: 'page-operator-businesses',
  version: 1,
  title: 'This page: businesses',
  audience: forOperator,
  autoStart: false,
  page: '/platform/businesses',
  steps: [
    {
      id: 'search',
      target: 'biz-search',
      title: 'Find a business',
      body: 'Type part of its name, its address or the owner email. The list narrows as you type.',
    },
    {
      id: 'filters',
      target: 'biz-filters',
      title: 'Filter by status or module',
      body: 'Free month, Paying, Payment late, Suspended or Cancelled; and Shops and stock or Lending.',
    },
    {
      id: 'list',
      target: 'biz-list',
      title: 'One card per business',
      body: 'Each card shows its plan, its modules, where its subscription stands, its owner and when its staff last signed in.',
      optional: true,
    },
    {
      id: 'open',
      target: 'biz-open',
      title: 'Open it',
      body: 'Tap the name for its page: change the plan, record a payment, suspend or resume, or help the owner sign in.',
      optional: true,
    },
  ],
};

export const businessPage: Tour = {
  id: 'page-operator-business',
  version: 2,
  title: 'This page: one business',
  audience: forOperator,
  autoStart: false,
  page: '/platform/businesses/$tenantId',
  steps: [
    {
      id: 'details',
      target: 'biz-details',
      title: 'Who and where',
      body: 'Its own address, its owner, and when anyone last signed in.',
    },
    {
      id: 'status',
      target: 'biz-status',
      title: 'Where the subscription stands',
      body: 'The status in plain words, the plan and its price, the end of the free month and the last day paid for.',
    },
    {
      id: 'payment',
      target: 'biz-payment',
      title: 'Record a payment',
      body: 'Money received by mobile money or bank. You check the sentence before it is saved, and pressing twice records it once.',
    },
    {
      id: 'plan',
      target: 'biz-plan',
      title: 'Change the plan',
      body: 'Choose another plan and say why. A plan the business does not fit is refused, with the reason.',
    },
    {
      id: 'suspend',
      target: 'biz-suspend',
      title: 'Suspend or resume',
      body: 'Suspended means read only: staff can still sign in, look and export. Nothing is ever deleted. A reason is kept.',
    },
    {
      id: 'modules',
      target: 'biz-modules',
      title: 'Modules',
      body: 'Switch Shops and stock or Lending on or off. Data of a module switched off is kept.',
    },
    {
      id: 'rescue',
      target: 'biz-rescue',
      title: 'Owner sign-in help',
      body: 'A one-time link for a forgotten password, or a reset of a lost two-step sign-in. The owner is marked. When the owner has lost access for good, Make the owner gives the role to another active administrator. Each needs a code from your own authenticator app.',
    },
    {
      id: 'messages',
      target: 'biz-messages',
      title: 'Emails sent',
      body: 'The activation link and other emails, and whether they went.',
    },
    {
      id: 'history',
      target: 'biz-history',
      title: 'What was done',
      body: 'Every change by an operator, who did it and why.',
    },
  ],
};

export const plansPage: Tour = {
  id: 'page-operator-plans',
  version: 1,
  title: 'This page: plans and prices',
  audience: forOperator,
  autoStart: false,
  page: '/platform/plans',
  steps: [
    {
      id: 'list',
      target: 'plans-list',
      title: 'The plans',
      body: 'Each plan with its price, its limits and how many businesses are on it.',
      optional: true,
    },
    {
      id: 'price',
      target: 'plans-set-price',
      title: 'Set the price',
      body: 'The price a month, the months charged for a year (fewer than 12 gives months free) and the day it starts. Earlier charges never change.',
      optional: true,
    },
    {
      id: 'new',
      target: 'plans-new',
      title: 'Add a plan',
      body: 'Its name, what it includes, its modules and its limits: shops and stores, staff, and lending members. Empty means unlimited.',
    },
  ],
};

// Due for suspension (issue #480, ADR-075): businesses past the grace period, and the grace period and how to pay.
export const dueForSuspensionPage: Tour = {
  id: 'page-operator-billing',
  version: 1,
  title: 'This page: due for suspension',
  audience: forOperator,
  autoStart: false,
  page: '/platform/billing',
  steps: [
    {
      id: 'intro',
      target: 'due-intro',
      title: 'Nothing is automatic',
      body: 'A business that pays late keeps working through the grace period. After it, the business is listed here for you to decide.',
    },
    {
      id: 'list',
      target: 'due-list',
      title: 'Overdue past the grace period',
      body: 'Each business overdue for longer than the grace period, most overdue first. Nothing is suspended for you: open the business and suspend it there.',
      optional: true,
    },
    {
      id: 'settings',
      target: 'billing-settings',
      title: 'Grace period and how to pay',
      body: 'How many days an overdue business keeps working before it is listed here. How to pay, in your words, goes in every reminder.',
    },
  ],
};

// Feedback from every business (issue #447, ADR-065).
export const feedbackInboxPage: Tour = {
  id: 'page-operator-feedback',
  version: 1,
  title: 'This page: feedback',
  audience: forOperator,
  autoStart: false,
  page: '/platform/feedback',
  steps: [
    {
      id: 'filter',
      target: 'feedback-filter',
      title: 'Show by status',
      body: 'New is what nobody has read yet. Seen is read and still open. Done is finished. All shows everything, newest first.',
    },
    {
      id: 'list',
      target: 'feedback-list',
      title: 'One card per message',
      body: 'Each card shows the business, who sent it, the page, the version and the browser. Show the screenshot opens the picture they added.',
    },
    {
      id: 'reply',
      target: 'feedback-reply',
      title: 'Reply',
      body: 'Write a reply here. The sender sees it on their own Feedback page.',
      optional: true,
    },
    {
      id: 'actions',
      target: 'feedback-actions',
      title: 'Mark it seen or done',
      body: 'A message moves forward only: new, then seen, then done. Save the reply keeps the status as it is.',
      optional: true,
    },
  ],
};

export const OPERATOR_TOURS: readonly Tour[] = [businessesPage, businessPage, plansPage, dueForSuspensionPage, feedbackInboxPage];

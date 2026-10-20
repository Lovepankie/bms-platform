import type { Me } from '../api/client';
import type { Tour } from './model';

// Page tours for lending savings and investments, and the remaining staff pages (issue #337): savings, a member's savings,
// the edit screens of members and collateral, investments, and the staff home, approvals, insights, receive payments,
// invite, reports and the SMS screens. Each is a page tour for whoever can open the page; a step that needs more carries its
// own permission. Plain words, short sentences, say what to tap. tours-lending-more.test.tsx renders each page and fails if an
// anchor is missing.

const SAVINGS_READ = 'lending.savings.read';
const SAVINGS_OPEN = 'lending.savings.open';
const SAVINGS_PRODUCTS = 'lending.savings_products.manage';
const SAVINGS_MONEY = ['lending.savings.deposit', 'lending.savings.withdraw'] as const;
const MEMBERS_UPDATE = 'lending.members.update';
const COLLATERAL_MANAGE = 'lending.collateral.manage';
const INVESTMENTS_READ = 'lending.investments.read';
const INVESTMENTS_OPEN = 'lending.investments.open';
const INVESTMENT_PRODUCTS = 'lending.investment_products.manage';
const INVESTMENT_MONEY = ['lending.investments.fund', 'lending.investments.payout'] as const;
const MEMBERS_READ = 'lending.members.read';
const INSIGHTS_READ = 'lending.insights.read';
const APPROVALS_READ = 'core.approvals.read';
const SETTINGS_READ = 'core.settings.read';
const SETTINGS_MANAGE = 'core.settings.manage';
const PAYMENT_ACCOUNT = 'core.payment_account.manage';
const USERS_MANAGE = 'core.users.manage';
const NOTIFICATIONS_READ = 'core.notifications.read';
const TEMPLATES_MANAGE = 'core.notification_templates.manage';
const REPORT_PERMISSIONS = ['lending.reports.portfolio', 'lending.reports.collections', 'lending.reports.members', 'lending.reports.compliance'];

/** The tenant has the lending module (a /me without a module list counts as having it, as member-permissions.ts reads it). */
const lendingTenant = (me: Me) => { const modules = (me as Me & { modules?: string[] }).modules; return !modules || modules.includes('lending'); };
const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);
const heldAny = (me: Me, permissions: readonly string[]) => permissions.some((p) => held(me, p));

export const savingsPage: Tour = {
  id: 'page-lending-savings',
  version: 1,
  title: 'This page: savings accounts',
  audience: (me) => lendingTenant(me) && held(me, SAVINGS_READ),
  autoStart: false,
  page: '/staff/lending/savings',
  steps: [
    {
      id: 'search',
      target: 'savings-search',
      module: 'lending',
      title: 'Find an account',
      body: 'Type an account number, a member number, a name or a phone number. The list narrows as you type. Tap an account to open it.',
    },
    {
      id: 'status',
      target: 'savings-status',
      module: 'lending',
      title: 'Active or not',
      body: 'Choose Dormant, Frozen or Closed to see accounts that cannot pay money out right now.',
    },
    {
      id: 'products',
      target: 'savings-nav',
      module: 'lending',
      needs: [SAVINGS_PRODUCTS],
      optional: true,
      title: 'Savings products',
      body: 'Tap Products to set up the kinds of savings account your business offers, and their interest.',
    },
  ],
};

export const savingsProductsPage: Tour = {
  id: 'page-savings-products',
  version: 1,
  title: 'This page: savings products',
  audience: (me) => lendingTenant(me) && held(me, SAVINGS_READ),
  autoStart: false,
  page: '/staff/lending/savings/products',
  steps: [
    {
      id: 'new',
      target: 'savings-product-new',
      module: 'lending',
      needs: [SAVINGS_PRODUCTS],
      optional: true,
      title: 'Add a product',
      body: 'Tap New product to set the interest, the smallest balance and the fee for taking money out.',
    },
    {
      id: 'list',
      target: 'savings-products-list',
      module: 'lending',
      title: 'Your products',
      body: 'Each product shows its interest and rules. Tap the number of accounts to open the list of all savings accounts.',
    },
  ],
};

export const savingsAccountPage: Tour = {
  id: 'page-savings-account',
  version: 1,
  title: 'This page: one savings account',
  audience: (me) => lendingTenant(me) && held(me, SAVINGS_READ),
  autoStart: false,
  page: '/staff/lending/savings/accounts/$accountId',
  steps: [
    {
      id: 'card',
      target: 'savings-account-card',
      module: 'lending',
      title: 'The account',
      body: 'The balance, what can be paid out now, and the interest earned. Tap the member\'s name to see all their accounts.',
    },
    {
      id: 'actions',
      target: 'savings-account-actions',
      module: 'lending',
      needsAny: [...SAVINGS_MONEY],
      optional: true,
      title: 'Put money in or take it out',
      body: 'Tap Deposit or Withdraw. You see only what your role allows and what the account\'s state allows.',
    },
    {
      id: 'statement',
      target: 'savings-statement-link',
      module: 'lending',
      title: 'Statement',
      body: 'Tap Statement for a printed-style list of every movement between two dates.',
    },
    {
      id: 'transactions',
      target: 'savings-transactions',
      module: 'lending',
      title: 'Every movement',
      body: 'Deposits, withdrawals and interest, newest first, each with the balance after it.',
    },
  ],
};

export const savingsStatementPage: Tour = {
  id: 'page-savings-statement',
  version: 1,
  title: 'This page: account statement',
  audience: (me) => lendingTenant(me) && held(me, SAVINGS_READ),
  autoStart: false,
  page: '/staff/lending/savings/accounts/$accountId/statement',
  steps: [
    {
      id: 'range',
      target: 'statement-range',
      module: 'lending',
      title: 'Pick the dates',
      body: 'It opens on the last three months. Change From and To to see another period.',
    },
    {
      id: 'back',
      target: 'statement-back',
      module: 'lending',
      title: 'Back to the account',
      body: 'Tap here to return to the account and its actions.',
    },
  ],
};

export const memberSavingsPage: Tour = {
  id: 'page-member-savings',
  version: 1,
  title: 'This page: a member\'s savings',
  audience: (me) => lendingTenant(me) && held(me, SAVINGS_READ),
  autoStart: false,
  page: '/staff/lending/members/$memberId/savings',
  steps: [
    {
      id: 'accounts',
      target: 'member-savings-accounts',
      module: 'lending',
      title: 'Their accounts',
      body: 'Every savings account the member has. Tap one to open it.',
    },
    {
      id: 'open',
      target: 'member-savings-open',
      module: 'lending',
      needs: [SAVINGS_OPEN],
      optional: true,
      title: 'Open another account',
      body: 'Tap here, choose a product and tap Open account. The first deposit is recorded on the new account.',
    },
    {
      id: 'back',
      target: 'member-savings-back',
      module: 'lending',
      title: 'Back to all savings',
      body: 'Tap here to go back to the list of every savings account.',
    },
  ],
};

export const memberEditPage: Tour = {
  id: 'page-member-edit',
  version: 1,
  title: 'This page: change a member',
  audience: (me) => lendingTenant(me) && held(me, MEMBERS_UPDATE),
  autoStart: false,
  page: '/staff/lending/members/$memberId/edit',
  steps: [
    {
      id: 'who',
      target: 'member-who',
      module: 'lending',
      title: 'Name and phone',
      body: 'Correct the name or the phone number. To move a member to another branch, ask an administrator.',
    },
    {
      id: 'status',
      target: 'member-edit-status',
      module: 'lending',
      title: 'Member status',
      body: 'Choose Not active or Left when a member stops borrowing.',
    },
    {
      id: 'save',
      target: 'member-save',
      module: 'lending',
      title: 'Save',
      body: 'Tap Save changes. Tap Cancel to leave without saving.',
    },
  ],
};

export const collateralEditPage: Tour = {
  id: 'page-collateral-edit',
  version: 1,
  title: 'This page: change collateral',
  audience: (me) => lendingTenant(me) && held(me, COLLATERAL_MANAGE),
  autoStart: false,
  page: '/staff/lending/collateral/$collateralId/edit',
  steps: [
    {
      id: 'item',
      target: 'collateral-item',
      module: 'lending',
      title: 'The item',
      body: 'Correct the description or the title number. The same number cannot be registered twice while the item is held.',
    },
    {
      id: 'owner',
      target: 'collateral-owner',
      module: 'lending',
      title: 'Owner and value',
      body: 'Change who owns it, or your estimate of what it is worth.',
    },
    {
      id: 'save',
      target: 'collateral-save',
      module: 'lending',
      title: 'Save',
      body: 'Tap Save changes. Tap Cancel to go back to the item.',
    },
  ],
};

export const investmentsPage: Tour = {
  id: 'page-investments',
  version: 1,
  title: 'This page: investments',
  audience: (me) => lendingTenant(me) && held(me, INVESTMENTS_READ),
  autoStart: false,
  page: '/staff/lending/investments',
  steps: [
    {
      id: 'nav',
      target: 'investments-nav',
      module: 'lending',
      title: 'The investments menu',
      body: 'All investments, what matures soon, a new investment, and the products. The page you are on is underlined.',
    },
    {
      id: 'search',
      target: 'investments-search',
      module: 'lending',
      title: 'Find an investment',
      body: 'Type an account number, a member number, a name or a phone number. Tap an investment to open it.',
    },
    {
      id: 'status',
      target: 'investments-status',
      module: 'lending',
      title: 'Where it stands',
      body: 'Choose Matured to see investments waiting to be paid out, or Paid out for those that are finished.',
    },
  ],
};

export const investmentNewPage: Tour = {
  id: 'page-investment-new',
  version: 1,
  title: 'This page: new investment',
  audience: (me) => lendingTenant(me) && held(me, INVESTMENTS_OPEN),
  autoStart: false,
  page: '/staff/lending/investments/new',
  steps: [
    {
      id: 'member',
      target: 'investment-member',
      module: 'lending',
      title: 'Who is investing',
      body: 'Type at least two letters of the member\'s name, number or phone, then tap the member.',
    },
    {
      id: 'form',
      module: 'lending',
      title: 'Then the details',
      body: 'Choose the product, type the amount and pick how many months. You see the return before you tap Open the investment.',
    },
  ],
};

export const investmentNewForMemberPage: Tour = {
  id: 'page-investment-new-member',
  version: 1,
  title: 'This page: new investment for a member',
  audience: (me) => lendingTenant(me) && held(me, INVESTMENTS_OPEN),
  autoStart: false,
  page: '/staff/lending/investments/member/$memberId/new',
  steps: [
    {
      id: 'product',
      target: 'investment-product',
      module: 'lending',
      optional: true,
      title: 'Choose the product',
      body: 'Each product shows its return a year and how often the return is paid.',
    },
    {
      id: 'amount',
      target: 'investment-amount',
      module: 'lending',
      optional: true,
      title: 'Amount and months',
      body: 'Type the amount, then pick the number of months. The return is worked out for you below.',
    },
    {
      id: 'open',
      target: 'investment-open',
      module: 'lending',
      optional: true,
      title: 'Open it',
      body: 'Tap Open the investment. It waits for the money; record the money received on the next screen.',
    },
  ],
};

export const maturitiesPage: Tour = {
  id: 'page-investment-maturities',
  version: 1,
  title: 'This page: maturities',
  audience: (me) => lendingTenant(me) && held(me, INVESTMENTS_READ),
  autoStart: false,
  page: '/staff/lending/investments/maturities',
  steps: [
    {
      id: 'ladder',
      target: 'maturities-ladder',
      module: 'lending',
      title: 'Cash needed for payouts',
      body: 'What falls due in the next 7, 30 and 90 days, and what has matured and is still waiting. Use it to plan cash. Tap an investment to open it.',
    },
    {
      id: 'metrics',
      target: 'maturities-metrics',
      module: 'lending',
      title: 'This month',
      body: 'The month\'s figures, the largest investors and the shares by product. Tap an investor to see their investments.',
    },
  ],
};

export const investmentProductsPage: Tour = {
  id: 'page-investment-products',
  version: 1,
  title: 'This page: investment products',
  audience: (me) => lendingTenant(me) && held(me, INVESTMENTS_READ),
  autoStart: false,
  page: '/staff/lending/investments/products',
  steps: [
    {
      id: 'new',
      target: 'investment-product-new',
      module: 'lending',
      needs: [INVESTMENT_PRODUCTS],
      optional: true,
      title: 'Add a product',
      body: 'Tap New product to set the return, the months allowed, the smallest amount and the early withdrawal rule.',
    },
    {
      id: 'list',
      target: 'investment-products-list',
      module: 'lending',
      title: 'Your products',
      body: 'Each product shows its return, its terms and what happens on an early withdrawal.',
    },
  ],
};

export const memberInvestmentsPage: Tour = {
  id: 'page-member-investments',
  version: 1,
  title: 'This page: a member\'s investments',
  audience: (me) => lendingTenant(me) && held(me, INVESTMENTS_READ),
  autoStart: false,
  page: '/staff/lending/investments/member/$memberId',
  steps: [
    {
      id: 'new',
      target: 'member-investments-new',
      module: 'lending',
      needs: [INVESTMENTS_OPEN],
      optional: true,
      title: 'A new investment',
      body: 'Tap here to open an investment for this member. You do not need to look them up again.',
    },
    {
      id: 'list',
      target: 'member-investments-list',
      module: 'lending',
      title: 'Their investments',
      body: 'Every investment the member holds, with what is due. Tap one to open it.',
    },
  ],
};

export const investmentPage: Tour = {
  id: 'page-investment',
  version: 1,
  title: 'This page: one investment',
  audience: (me) => lendingTenant(me) && held(me, INVESTMENTS_READ),
  autoStart: false,
  page: '/staff/lending/investments/$investmentId',
  steps: [
    {
      id: 'header',
      target: 'investment-header',
      module: 'lending',
      title: 'The investment',
      body: 'Who holds it, the product and the return. Tap the member\'s name to open the member.',
    },
    {
      id: 'actions',
      target: 'investment-actions',
      module: 'lending',
      needsAny: [INVESTMENTS_OPEN, ...INVESTMENT_MONEY],
      optional: true,
      title: 'What you can do',
      body: 'Record the money received, pay the return, pay out, roll over or withdraw early. You see only what your role and the state allow.',
    },
    {
      id: 'balances',
      target: 'investment-balances',
      module: 'lending',
      title: 'Balances',
      body: 'What was invested, the return earned and paid, and the return due now.',
    },
    {
      id: 'certificate',
      target: 'investment-certificate-link',
      module: 'lending',
      optional: true,
      title: 'Certificate',
      body: 'Tap here for the certificate and statement to print for the member.',
    },
    {
      id: 'schedule',
      target: 'investment-schedule',
      module: 'lending',
      title: 'Schedule and statement',
      body: 'Below are the dates the return is earned and paid, then every money movement with the balances after it.',
    },
  ],
};

export const certificatePage: Tour = {
  id: 'page-investment-certificate',
  version: 1,
  title: 'This page: certificate',
  audience: (me) => lendingTenant(me) && held(me, INVESTMENTS_READ),
  autoStart: false,
  page: '/staff/lending/investments/$investmentId/certificate',
  steps: [
    {
      id: 'print',
      target: 'certificate-print',
      module: 'lending',
      title: 'Print it',
      body: 'Tap Print. The menus are left out of the printout, so it is ready to hand to the member.',
    },
    {
      id: 'card',
      target: 'certificate-card',
      module: 'lending',
      optional: true,
      title: 'The certificate',
      body: 'The amount, the return, the term and the maturity date, as the member agreed them.',
    },
    {
      id: 'back',
      target: 'certificate-back',
      module: 'lending',
      title: 'Back to the investment',
      body: 'Tap here to return to the investment and its actions.',
    },
  ],
};

export const staffHomePage: Tour = {
  id: 'page-staff-home',
  version: 1,
  title: 'This page: staff home',
  audience: () => true,
  autoStart: false,
  page: '/staff',
  steps: [
    {
      id: 'header',
      target: 'staff-home-header',
      title: 'Your home page',
      body: 'This is where you land after you sign in. The badge says whether the system is up.',
    },
    {
      id: 'areas',
      target: 'nav-areas',
      title: 'The tabs',
      body: 'Each tab opens one area, such as members, loans, insights or users. You see the tabs your role allows.',
    },
    {
      id: 'members',
      target: 'staff-home-search',
      module: 'lending',
      needs: [MEMBERS_READ],
      optional: true,
      title: 'Find a member',
      body: 'Type a name, a member number, a phone number or a national ID number. Tap a name to open the member.',
    },
  ],
};

export const approvalsPage: Tour = {
  id: 'page-approvals',
  version: 1,
  title: 'This page: approvals',
  audience: (me) => held(me, APPROVALS_READ),
  autoStart: false,
  page: '/staff/approvals',
  steps: [
    {
      id: 'list',
      target: 'approvals-list',
      title: 'What is waiting',
      body: 'Requests that need a second person to agree, such as a refund or giving collateral back. When nothing is waiting, this stays empty.',
    },
    {
      id: 'who',
      target: 'approvals-who',
      optional: true,
      title: 'Who asked',
      body: 'You cannot approve your own request. Someone else must. You can cancel your own.',
    },
    {
      id: 'decision',
      target: 'approvals-decision',
      optional: true,
      title: 'Approve or reject',
      body: 'Tap Approve and the action happens at once. To reject, type a note first, then tap Reject.',
    },
  ],
};

export const insightsPage: Tour = {
  id: 'page-insights',
  version: 1,
  title: 'This page: insights',
  audience: (me) => lendingTenant(me) && held(me, INSIGHTS_READ),
  autoStart: false,
  page: '/staff/insights',
  steps: [
    {
      id: 'filters',
      target: 'insights-filters',
      module: 'lending',
      title: 'Choose the dates',
      body: 'Pick a range such as This month or Last 30 days. You can also narrow to one product or one officer.',
    },
    {
      id: 'tabs',
      target: 'insights-tabs',
      module: 'lending',
      title: 'The sections',
      body: 'Today, Portfolio, Revenue and Members. Tap one to change what you see.',
    },
    {
      id: 'brief',
      target: 'insights-brief',
      module: 'lending',
      title: 'Today at a glance',
      body: 'Tap any number to see the rows behind it. Tap the small i to read how the number is worked out.',
    },
    {
      id: 'links',
      target: 'insights-links',
      module: 'lending',
      optional: true,
      title: 'Go to the list',
      body: 'Tap here to open the loan list, or to see which investments mature soon.',
    },
  ],
};

export const paymentsPage: Tour = {
  id: 'page-payments',
  version: 1,
  title: 'This page: receive payments',
  audience: (me) => heldAny(me, [SETTINGS_READ, PAYMENT_ACCOUNT]),
  autoStart: false,
  page: '/staff/payments',
  steps: [
    {
      id: 'intro',
      target: 'payments-intro',
      title: 'Your money stays yours',
      body: 'Members pay by mobile money or card straight into your own Pesapal account. This system never holds your money.',
    },
    {
      id: 'status',
      target: 'payments-status',
      title: 'Your payment account',
      body: 'Members pay into your own Pesapal account. This says whether it is connected and working.',
    },
    {
      id: 'credentials',
      target: 'payments-credentials',
      needs: [PAYMENT_ACCOUNT],
      optional: true,
      title: 'The key and secret',
      body: 'Paste the consumer key and secret from your Pesapal account. You also type the 6 digits from your authenticator app. The secret cannot be shown again.',
    },
    {
      id: 'test',
      target: 'payments-test',
      needs: [PAYMENT_ACCOUNT],
      optional: true,
      title: 'Test the connection',
      body: 'Tap Test connection. Nothing is charged. When it works, collecting is on.',
    },
  ],
};

export const inviteUserPage: Tour = {
  id: 'page-invite-user',
  version: 1,
  title: 'This page: invite someone',
  audience: (me) => held(me, USERS_MANAGE),
  autoStart: false,
  page: '/staff/users/new',
  steps: [
    {
      id: 'who',
      target: 'invite-name',
      title: 'Who to invite',
      body: 'Type the full name, then a phone number such as 0712 345 678. An email is only needed when there is no phone.',
    },
    {
      id: 'role',
      target: 'invite-role',
      title: 'Their role',
      body: 'Pick the role. It decides what the person can see and do. Choose a branch if they work in only one.',
    },
    {
      id: 'send',
      target: 'invite-send',
      title: 'Send the invitation',
      body: 'Tap Invite. You get a link to send them on WhatsApp. They use it once to choose a password.',
    },
  ],
};

export const reportsPage: Tour = {
  id: 'page-reports',
  version: 1,
  title: 'This page: reports',
  audience: (me) => me.reports_enabled === true && heldAny(me, REPORT_PERMISSIONS),
  autoStart: false,
  page: '/staff/reports',
  steps: [
    {
      id: 'catalogue',
      target: 'reports-catalogue',
      module: 'lending',
      title: 'Choose a report',
      body: 'Each report has its own form. Fill in the date, pick the file type and tap Run report.',
    },
    {
      id: 'runs',
      target: 'reports-runs',
      module: 'lending',
      title: 'Your reports',
      body: 'A report takes a moment. When it says Ready, tap Download. Files are kept for 30 days.',
    },
  ],
};

export const smsSettingsPage: Tour = {
  id: 'page-sms-settings',
  version: 1,
  title: 'This page: text message settings',
  audience: (me) => held(me, SETTINGS_READ),
  autoStart: false,
  page: '/staff/sms',
  steps: [
    {
      id: 'switch',
      target: 'sms-switch',
      title: 'On or off',
      body: 'Text messages cost money, so they start off. A business administrator reads the notice and switches them on.',
    },
    {
      id: 'sender',
      target: 'sms-sender',
      title: 'Sender name',
      body: 'The name members see on the message. You choose from the names the platform approved for your business.',
    },
    {
      id: 'window',
      target: 'sms-window',
      title: 'Sending hours',
      body: 'Reminders and arrears notices are sent only between these times. Receipts are not held back.',
    },
    {
      id: 'limits',
      target: 'sms-limits',
      title: 'Limits',
      body: 'The most messages a day and a month. You can lower them but not raise them. A limit of 0 stops every message.',
    },
  ],
};

export const smsWordingPage: Tour = {
  id: 'page-sms-wording',
  version: 1,
  title: 'This page: text message wording',
  audience: (me) => held(me, NOTIFICATIONS_READ),
  autoStart: false,
  page: '/staff/sms/wording',
  steps: [
    {
      id: 'list',
      target: 'sms-wording',
      title: 'The messages',
      body: 'Each box is one kind of message. Without your own words, the default is sent.',
    },
    {
      id: 'message',
      target: 'sms-wording-message',
      needs: [TEMPLATES_MANAGE],
      optional: true,
      title: 'Write your own words',
      body: 'Type the message. Tap a placeholder such as amount to add it. It is filled in for each member.',
    },
    {
      id: 'save',
      target: 'sms-wording-save',
      needs: [TEMPLATES_MANAGE],
      optional: true,
      title: 'Save',
      body: 'Tap Save my words. A preview shows the message with sample figures and how many texts it uses.',
    },
  ],
};

export const smsOptOutsPage: Tour = {
  id: 'page-sms-opt-outs',
  version: 1,
  title: 'This page: people who get no texts',
  audience: (me) => held(me, NOTIFICATIONS_READ),
  autoStart: false,
  page: '/staff/sms/opt-outs',
  steps: [
    {
      id: 'search',
      target: 'sms-optouts-search',
      title: 'Find a number',
      body: 'Type the last digits of a number. Choose who, or what is stopped, to narrow the list.',
    },
    {
      id: 'add',
      target: 'sms-optouts-add',
      needs: [SETTINGS_MANAGE],
      optional: true,
      title: 'Stop texts to a number',
      body: 'Type the number and tap Add to the list. Choose whether to stop reminders only or every message.',
    },
    {
      id: 'paste',
      target: 'sms-optouts-paste',
      needs: [SETTINGS_MANAGE],
      optional: true,
      title: 'Add many at once',
      body: 'Paste numbers, one on each line, and tap Check the list. Nothing is added until you confirm.',
    },
  ],
};

// Your own two-step sign-in (issue #348): every signed-in staff member can open it; the steps show while it is still off.
export const ownTwoStepPage: Tour = {
  id: 'page-own-two-step',
  version: 1,
  title: 'This page: your two-step sign-in',
  audience: () => true,
  autoStart: false,
  page: '/staff/two-step',
  steps: [
    {
      id: 'password',
      target: 'twostep-password',
      title: 'Confirm it is you',
      body: 'Type your password here. This makes sure nobody else can turn it on for you.',
    },
    {
      id: 'turn-on',
      target: 'twostep-turn-on',
      title: 'Turn it on',
      body: 'Tap Turn on two-step sign-in. You scan a code with your phone, type the 6 digits it shows, and save your recovery codes.',
    },
  ],
};

export const LENDING_MORE_TOURS: readonly Tour[] = [
  savingsPage, savingsProductsPage, savingsAccountPage, savingsStatementPage, memberSavingsPage, memberEditPage, collateralEditPage,
  investmentsPage, investmentNewPage, investmentNewForMemberPage, maturitiesPage, investmentProductsPage, memberInvestmentsPage,
  investmentPage, certificatePage, staffHomePage, approvalsPage, insightsPage, paymentsPage, inviteUserPage, reportsPage,
  smsSettingsPage, smsWordingPage, smsOptOutsPage, ownTwoStepPage,
];

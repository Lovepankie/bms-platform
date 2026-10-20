import type { ReactElement } from 'react';
import type { Me } from '../api/client';
import { mockMe } from '../api/retail-mock';
import { AdvancesScreen } from '../areas/staff/retail/advances';
import { BankingReportScreen, BankingScreen } from '../areas/staff/retail/banking';
import { BranchRoles } from '../areas/staff/retail/branch-roles';
import { CashSummary } from '../areas/staff/retail/cash-summary';
import { ImportPage } from '../areas/staff/retail/catalogue-import';
import { CategoriesPage, UnitsPage } from '../areas/staff/retail/catalogue-lists';
import { BuyersPage, SuppliersPage } from '../areas/staff/retail/catalogue-people';
import { ProductsPage } from '../areas/staff/retail/catalogue-products';
import { FamilyScreen } from '../areas/staff/retail/catalogue-family';
import { JoinPage, NewFamilyPage } from '../areas/staff/retail/catalogue-variants';
import { CustomerAgeingPage } from '../areas/staff/retail/customer-ageing';
import { CustomerGroups } from '../areas/staff/retail/customer-groups';
import { ExpenseListsScreen } from '../areas/staff/retail/expense-lists';
import { ExpensesReportScreen, ExpensesScreen } from '../areas/staff/retail/expenses';
import { SavingsScreen } from '../areas/staff/retail/savings';
import { WithdrawalsScreen } from '../areas/staff/retail/withdrawals';
import { RETAIL_CASH_TOURS } from './tours-retail-cash';
import { describeTours } from './test-kit';

// One test per tour of the catalogue lists, the cash book and the buyers pages (issue #337): render the screen as the user the
// tour is for and prove every step's data-tour anchor is on it. Fabricated users only.

const ADMIN: Me = { ...mockMe('admin'), module_capabilities: { retail: ['branch_roles', 'price_lists', 'customer_accounts'] } };
// A shop user: records banking, expenses and savings and reads the cash book, but holds none of the administrator's permissions.
const SELLER: Me = { ...mockMe('sales'), full_name: 'Test Seller 02' };

function screen(path: string): ReactElement {
  switch (path) {
    case '/staff/retail/catalogue/products': return <ProductsPage />;
    case '/staff/retail/catalogue/products/sizes/new': return <NewFamilyPage />;
    case '/staff/retail/catalogue/products/sizes/join': return <JoinPage />;
    case '/staff/retail/catalogue/products/sizes/$familyId': return <FamilyScreen familyId="f0000000-0000-4000-8000-000000000001" />;
    case '/staff/retail/catalogue/categories': return <CategoriesPage />;
    case '/staff/retail/catalogue/units': return <UnitsPage />;
    case '/staff/retail/catalogue/suppliers': return <SuppliersPage />;
    case '/staff/retail/catalogue/buyers': return <BuyersPage />;
    case '/staff/retail/catalogue/import': return <ImportPage />;
    case '/staff/retail/banking': return <BankingScreen />;
    case '/staff/retail/banking-report': return <BankingReportScreen />;
    case '/staff/retail/expenses': return <ExpensesScreen />;
    case '/staff/retail/expenses-report': return <ExpensesReportScreen />;
    case '/staff/retail/expense-lists': return <ExpenseListsScreen />;
    case '/staff/retail/withdrawals': return <WithdrawalsScreen />;
    case '/staff/retail/advances': return <AdvancesScreen />;
    case '/staff/retail/cash-summary': return <CashSummary />;
    case '/staff/retail/savings': return <SavingsScreen />;
    case '/staff/retail/branch-roles': return <BranchRoles />;
    case '/staff/retail/customer-groups': return <CustomerGroups />;
    case '/staff/retail/customer-ageing': return <CustomerAgeingPage />;
    default: return <></>;
  }
}

describeTours(
  'retail catalogue lists, cash book and buyers',
  RETAIL_CASH_TOURS,
  {
    'page-products': [ADMIN],
    'page-sizes-new': [ADMIN],
    'page-sizes-join': [ADMIN],
    'page-sizes': [ADMIN],
    'page-categories': [ADMIN],
    'page-units': [ADMIN],
    'page-suppliers': [ADMIN],
    'page-buyers': [ADMIN],
    'page-import': [ADMIN],
    'page-banking': [ADMIN, SELLER],
    'page-banking-report': [ADMIN, SELLER],
    'page-expenses': [ADMIN, SELLER],
    'page-expenses-report': [ADMIN, SELLER],
    'page-expense-lists': [ADMIN],
    'page-withdrawals': [ADMIN],
    'page-advances': [ADMIN],
    'page-cash-summary': [ADMIN, SELLER],
    'page-savings': [ADMIN, SELLER],
    'page-branch-roles': [ADMIN],
    'page-customer-groups': [ADMIN],
    'page-customer-ageing': [ADMIN],
  },
  screen,
);

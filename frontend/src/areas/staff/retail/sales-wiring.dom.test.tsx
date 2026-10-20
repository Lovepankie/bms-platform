// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { retail, type Sale } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { ALL_BRANCHES } from '../../../auth/branch';
import { StaffContext } from '../context';
import { chainUser, writeChain } from './sales-chain-state';
import { AllSales, CreditSales, SaleList } from './sales';

// The record-payment and void actions are wired into the screens (issues #326 and #327): the lists show them on their rows, an opened sale
// shows both, and a payment makes the list read the sale again. These fail if a list stops passing `action`, if OpenSale stops mounting an
// action, or if the refresh after a payment is removed. The retail adapter is the fabricated mock; names and amounts are made up.

vi.setConfig({ testTimeout: 20000 });

let api: ReturnType<typeof createMockRetail>;
beforeEach(() => {
  api = createMockRetail();
  Object.assign(retail, api);
  // These lists are grouped by the chain the person chose; this file uses year, month, day so a day's sales are the bottom (issue #341).
  writeChain(chainUser(mockMe('admin')), 'all', ['year', 'month', 'day']);
  writeChain(chainUser(mockMe('admin')), 'credit', ['year', 'month', 'day']);
});
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.restoreAllMocks();
});

const admin = mockMe('admin');

function screenOf(node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: admin, branch: ALL_BRANCHES }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

// The lists are grouped by year, month and day (issue #328): a day's sales load when the day is opened. Opens every group until
// the sales themselves show, as a person would by tapping down the list.
async function openEveryGroup() {
  await waitFor(() => expect(document.querySelector('.rt-group-head')).not.toBeNull());
  await waitFor(() => {
    const shut = [...document.querySelectorAll('.rt-group-head[aria-expanded="false"]')];
    shut.forEach((b) => fireEvent.click(b));
    expect(shut).toHaveLength(0);
    expect(document.querySelector('.loading')).toBeNull();
  });
}

describe('SaleList passes each sale to its action', () => {
  const rows: Sale[] = [
    { id: 'a', sale_no: 'S-1', payment_method: 'credit', status: 'completed', sale_date: '2026-10-10', total_minor: 100, paid_minor: 0, balance_minor: 100 },
    { id: 'b', sale_no: 'S-2', payment_method: 'cash', status: 'completed', sale_date: '2026-10-11', total_minor: 200, paid_minor: 200, balance_minor: 0 },
  ];

  it('renders the action under every row, with that row\'s sale', () => {
    screenOf(<SaleList items={rows} today="2026-10-19" onOpen={() => undefined} action={(s) => <i data-testid="row-action">{s.id}</i>} />);
    expect(screen.getAllByTestId('row-action').map((n) => n.textContent)).toEqual(['a', 'b']);
  });

  it('renders no action when none is given', () => {
    screenOf(<SaleList items={rows} today="2026-10-19" onOpen={() => undefined} />);
    expect(screen.queryByTestId('row-action')).toBeNull();
  });
});

describe('the Credit sales list', () => {
  it('offers Record payment on the rows that owe, and refreshes the list after a payment', async () => {
    const list = vi.spyOn(retail, 'listSales');
    screenOf(<CreditSales />);
    await openEveryGroup();
    const buttons = await screen.findAllByRole('button', { name: 'Record payment' });
    expect(buttons.length).toBeGreaterThan(0);
    const calls = list.mock.calls.length;
    const row = buttons[0]?.closest('li') as HTMLElement;
    const owedBefore = within(row).getByText(/still owes/i).textContent;
    fireEvent.click(buttons[0] as HTMLElement);
    fireEvent.change(within(row).getByLabelText('Amount received'), { target: { value: '1000' } });
    fireEvent.click(within(row).getByRole('button', { name: 'Save payment' }));
    await waitFor(() => expect(list.mock.calls.length).toBeGreaterThan(calls));
    await waitFor(() => expect(screen.getAllByText(/still owes/i).map((n) => n.textContent)).not.toContain(owedBefore));
  });

  it('opens a sale with its payment buttons', async () => {
    screenOf(<CreditSales />);
    await openEveryGroup();
    const owing = (await screen.findAllByRole('button', { name: 'Record payment' }))[0]?.closest('li') as HTMLElement;
    fireEvent.click(within(owing).getByRole('button', { name: /^Open the sale/ }));
    expect(await screen.findByRole('button', { name: 'Mark as fully paid' })).toBeTruthy();
  });
});

describe('the All sales list', () => {
  it('offers Record payment on its credit rows', async () => {
    screenOf(<AllSales />);
    await openEveryGroup();
    expect((await screen.findAllByRole('button', { name: 'Record payment' })).length).toBeGreaterThan(0);
  });

  it('shows Void this sale on an opened cash sale, and Voided with the reason after it is voided', async () => {
    screenOf(<AllSales />);
    await openEveryGroup();
    const rows = (await screen.findAllByRole('button', { name: /^Open the sale/ })).map((b) => b.closest('li') as HTMLElement);
    const cashRow = rows.find((li) => within(li).queryByText('Cash', { selector: 'strong' })) as HTMLElement;
    fireEvent.click(within(cashRow).getByRole('button', { name: /^Open the sale/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Void this sale' }));
    fireEvent.change(screen.getByLabelText(/Why is this sale being voided/), { target: { value: 'Rung up twice' } });
    fireEvent.click(screen.getByRole('button', { name: 'Void this sale' }));
    expect(await screen.findByText('Voided because')).toBeTruthy();
    expect(screen.getByText('Rung up twice')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Void this sale' })).toBeNull();
  });
});

// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Advance, Customer, Sale, Supplier } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Call, Text and WhatsApp next to a phone number (issue #817), in each place a number shows: a credit sale, a credit buyer and a
// supplier, a customer who owes, a buyer's account page, an advance and the advances owed. The links are the phone's own
// dialler, messages and WhatsApp; the app sends nothing. No number draws no links, and a contact the normaliser cannot read stays
// text only. Fabricated names and numbers.

vi.setConfig({ testTimeout: 20000 });

const listCustomerPage = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      customerAccount: async () => ({ customer_id: 'c1', name: 'Test Buyer 01', currency: 'UGX', balance_minor: 0, version: 1 }),
      customerBalance: async () => ({ customer_id: 'c1', currency: 'UGX', balance_minor: 0, open_sales: [] }),
      listAccountPayments: async () => ({ items: [] }),
      customerStatement: async () => ({ customer_id: 'c1', name: 'Test Buyer 01', currency: 'UGX', lines: [], opening_minor: 0, closing_minor: 0, balance_minor: 0 }),
      listCustomerPage: (...a: unknown[]) => listCustomerPage(...a),
    },
  };
});

const { contactHrefs, ContactLinks } = await import('./contact-links');
const { PersonRow } = await import('./catalogue-people');
const { AccountRow } = await import('./accounts');
const { AccountScreen } = await import('./account');
const { SaleDetail } = await import('./sales');
const { AdvanceDetailView, OutstandingView } = await import('./advances');
const { QuotationCard } = await import('./quotations');
const { OrderSummary } = await import('./orders');

afterEach(() => {
  cleanup();
  listCustomerPage.mockReset();
});

const admin = { ...mockMe('admin'), module_capabilities: { retail: ['customer_accounts'] } };
const branch = admin.branches?.[0]?.id ?? '';
const NUMBER = '0700 000 017';
const UNREADABLE = '0700 00';

function screenOf(node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: admin, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

/** The three links for `who`, with their addresses. */
function expectLinks(who: string, scope: HTMLElement = document.body) {
  const call = within(scope).getByRole('link', { name: `Call ${who}` });
  const text = within(scope).getByRole('link', { name: `Text ${who}` });
  const chat = within(scope).getByRole('link', { name: `WhatsApp ${who}` });
  expect(call.getAttribute('href')).toBe('tel:+256700000017');
  expect(text.getAttribute('href')).toBe('sms:+256700000017');
  expect(chat.getAttribute('href')).toBe('https://wa.me/256700000017');
  expect(chat.getAttribute('target')).toBe('_blank');
  expect(chat.getAttribute('rel')).toContain('noopener');
  for (const link of [call, text, chat]) expect(link.querySelector('svg')).not.toBeNull();
}

const noLinks = () => {
  expect(screen.queryByRole('link', { name: /^Call / })).toBeNull();
  expect(screen.queryByRole('link', { name: /^Text / })).toBeNull();
  expect(screen.queryByRole('link', { name: /^WhatsApp / })).toBeNull();
};

describe('the addresses', () => {
  it('reads a Uganda number however it is typed into international form', () => {
    for (const typed of ['0700 000 017', '+256 700 000 017', '256700000017', '700000017', '0700-000-017']) {
      expect(contactHrefs(typed)).toEqual({ call: 'tel:+256700000017', text: 'sms:+256700000017', whatsApp: 'https://wa.me/256700000017' });
    }
  });

  it('keeps another country with its + and code', () => {
    expect(contactHrefs('+254 711 000 000')?.whatsApp).toBe('https://wa.me/254711000000');
  });

  it('gives nothing for no number, an email, a landline or a number with digits missing', () => {
    for (const typed of [undefined, null, '', '   ', 'buyer@example.test', '0414 000 000', UNREADABLE]) expect(contactHrefs(typed)).toBeNull();
  });

  it('draws nothing without a readable number', () => {
    const { container } = screenOf(<ContactLinks contact={UNREADABLE} name="Test Buyer 01" />);
    expect(container.innerHTML).toBe('');
  });
});

describe('a credit sale', () => {
  const sale: Sale = { id: 's1', sale_no: 'S-000001', branch_id: branch, sale_date: '2026-10-10', payment_method: 'credit', status: 'completed', buyer_name: 'Test Buyer 01', total_minor: 12000, paid_minor: 2000, balance_minor: 10000, lines: [] };

  it('offers Call, Text and WhatsApp for the buyer', () => {
    screenOf(<SaleDetail sale={{ ...sale, buyer_contact: NUMBER }} today="2026-10-20" />);
    expectLinks('Test Buyer 01');
  });

  it('has none without a number, and shows an unreadable one as text only', () => {
    screenOf(<SaleDetail sale={sale} today="2026-10-20" />);
    noLinks();
    cleanup();
    screenOf(<SaleDetail sale={{ ...sale, buyer_contact: UNREADABLE }} today="2026-10-20" />);
    expect(screen.getByText(UNREADABLE)).toBeTruthy();
    noLinks();
  });
});

describe('a credit buyer and a supplier', () => {
  it('offers the links on a credit buyer who owes', () => {
    const buyer: Customer = { id: 'c1', name: 'Test Buyer 01', contact: NUMBER, balance_minor: 5000, version: 1 };
    screenOf(<ul><PersonRow row={buyer} onSave={() => undefined} /></ul>);
    expectLinks('Test Buyer 01');
  });

  it('offers the links on a supplier, none without a number, and text only for one it cannot read', () => {
    const supplier: Supplier = { id: 'v1', name: 'Test Supplier 01', contact: NUMBER, active: true, version: 1 };
    screenOf(<ul><PersonRow row={supplier} onSave={() => undefined} /></ul>);
    expectLinks('Test Supplier 01');
    cleanup();
    screenOf(<ul><PersonRow row={{ ...supplier, contact: undefined }} onSave={() => undefined} /></ul>);
    expect(screen.getByText('No contact saved.')).toBeTruthy();
    noLinks();
    cleanup();
    screenOf(<ul><PersonRow row={{ ...supplier, contact: UNREADABLE }} onSave={() => undefined} /></ul>);
    expect(screen.getByText(UNREADABLE)).toBeTruthy();
    noLinks();
  });
});

describe('a customer who owes', () => {
  it('shows the number and its links on Customer accounts', () => {
    screenOf(<ul><AccountRow row={{ id: 'c1', name: 'Test Buyer 02', contact: NUMBER, balance_minor: 5000 }} /></ul>);
    expect(screen.getByText(NUMBER)).toBeTruthy();
    expectLinks('Test Buyer 02');
  });

  it('has none without a number', () => {
    screenOf(<ul><AccountRow row={{ id: 'c1', name: 'Test Buyer 02', balance_minor: 5000 }} /></ul>);
    noLinks();
  });
});

describe("a buyer's account page", () => {
  const page = () => screenOf(<AccountScreen customerId="c1" me={admin} branch={branch} />);

  it('finds the buyer by name and offers the links under the name', async () => {
    listCustomerPage.mockResolvedValue({ items: [{ id: 'c9', name: 'Test Buyer 01', contact: '0700 000 099' }, { id: 'c1', name: 'Test Buyer 01', contact: NUMBER }] });
    page();
    expect(await screen.findByRole('link', { name: 'Call Test Buyer 01' })).toBeTruthy();
    expectLinks('Test Buyer 01');
    expect(listCustomerPage.mock.calls[0]?.[0]).toMatchObject({ query: 'Test Buyer 01' });
  });

  it('pages through the search until it finds the buyer', async () => {
    listCustomerPage
      .mockResolvedValueOnce({ items: [{ id: 'c9', name: 'Test Buyer 01', contact: '0700 000 099' }], next_cursor: 'n1' })
      .mockResolvedValueOnce({ items: [{ id: 'c1', name: 'Test Buyer 01', contact: NUMBER }] });
    page();
    expect(await screen.findByRole('link', { name: 'Call Test Buyer 01' })).toBeTruthy();
    expectLinks('Test Buyer 01');
    expect(listCustomerPage.mock.calls[1]?.[0]).toMatchObject({ query: 'Test Buyer 01', cursor: 'n1' });
  });

  it('has none when the buyer has no number, once the search has answered', async () => {
    let answer: (v: unknown) => void = () => undefined;
    listCustomerPage.mockReturnValue(new Promise((done) => { answer = done; }));
    page();
    await screen.findByRole('heading', { name: 'Test Buyer 01' });
    await vi.waitFor(() => expect(listCustomerPage).toHaveBeenCalled());
    await act(async () => {
      answer({ items: [{ id: 'c1', name: 'Test Buyer 01' }] });
      await new Promise((r) => setTimeout(r, 0));
    });
    noLinks();
    expect(screen.queryByText(NUMBER)).toBeNull();
  });
});

describe('an advance', () => {
  const advance: Advance = { id: 'a1', advance_no: 'ADV-000001', branch_id: branch, business_date: '2026-10-10', party_id: 'p1', party_name: 'Test Owner 01', principal_minor: 500000, repaid_minor: 0, balance_minor: 500000, voided: false };

  it('offers the links for the party it is for', () => {
    screenOf(<AdvanceDetailView advance={advance} contact={NUMBER} />);
    expect(screen.getByText(NUMBER)).toBeTruthy();
    expectLinks('Test Owner 01');
  });

  it('has none without a number, and shows an unreadable one as text only', () => {
    screenOf(<AdvanceDetailView advance={advance} />);
    noLinks();
    cleanup();
    screenOf(<AdvanceDetailView advance={advance} contact={UNREADABLE} />);
    expect(screen.getByText(UNREADABLE)).toBeTruthy();
    noLinks();
  });

  it('offers the links on Who owes, for each party with a number', () => {
    const report = { items: [{ party_id: 'p1', party_name: 'Test Owner 01', balance_minor: 1, count: 1 }, { party_id: 'p2', party_name: 'Test Staff 01', balance_minor: 1, count: 1 }] };
    screenOf(<OutstandingView report={report} contactOf={(id) => (id === 'p1' ? NUMBER : undefined)} />);
    expectLinks('Test Owner 01');
    expect(screen.queryByRole('link', { name: 'Call Test Staff 01' })).toBeNull();
  });
});

// JUSTIFICATION-A3: two more places a number shows (review of #842); new test cases, not new mechanism.
describe('a quotation and a customer order', () => {
  it('offers the links for the buyer of a quotation, and none for one with no number', () => {
    const q = { id: 'q1', number: 'QT-HQ-000001', branch_id: branch, status: 'cancelled', buyer_name: 'Test Buyer 03', buyer_contact: NUMBER, quote_date: '2026-10-10', valid_until: '2026-10-20', total_minor: 1 };
    screenOf(<ul><QuotationCard q={q} /></ul>);
    expectLinks('Test Buyer 03');
    cleanup();
    screenOf(<ul><QuotationCard q={{ ...q, buyer_contact: undefined }} /></ul>);
    noLinks();
  });

  it('offers the links for the customer of an order, and text only for a number it cannot read', () => {
    const order = { id: 'o1', order_no: 'ORD-000001', branch_id: branch, status: 'new', source: 'phone', customer_name: 'Test Buyer 04', customer_phone: NUMBER, lines: [] };
    screenOf(<OrderSummary order={order} />);
    expectLinks('Test Buyer 04');
    cleanup();
    screenOf(<OrderSummary order={{ ...order, customer_phone: UNREADABLE }} />);
    expect(screen.getByText(UNREADABLE)).toBeTruthy();
    noLinks();
  });
});

// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState, type ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { retail, type DocumentSeries, type RetailDocument } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { PdfButton, SaleDocuments, SeriesRow, openMadePdf } from './documents-ui';
import { blankConvert, type ConvertForm } from './documents-state';
import { ConvertPanel } from './quotations';

// The documents forms as a person uses them (documents capability, ADR-033, issue #197): a prefix is changed only when it is
// valid and different, a credit conversion waits for its date, a PDF is rendered first when none was stored and opened in a tab,
// and a sale's panel makes the documents it does not have yet. Fabricated names and amounts.

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const branch = mockMe('admin').branches?.[0]?.id ?? '';
const admin = { ...mockMe('admin'), module_capabilities: { retail: ['documents'] } };
const withStaff = (node: ReactElement) => (
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <StaffContext.Provider value={{ me: admin, branch }}>{node}</StaffContext.Provider>
  </QueryClientProvider>
);

const series: DocumentSeries = { type: 'invoice', prefix: 'INV', default_prefix: 'INV', branches: [{ branch_id: 'b1', branch_code: 'HQ', issued: 3, next: 'INV-HQ-000004' }] };

describe('changing a prefix', () => {
  it('saves only a valid prefix that differs, in capitals', () => {
    const onSave = vi.fn();
    render(<SeriesRow series={series} canEdit onSave={onSave} />);
    const save = screen.getByRole('button', { name: 'Save the Invoice prefix' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    const input = screen.getByLabelText('Prefix') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'tax' } });
    expect(input.value).toBe('TAX');
    expect(save.disabled).toBe(false);
    fireEvent.change(input, { target: { value: '1x' } });
    expect(save.disabled).toBe(true);
    expect(screen.getByRole('alert').textContent).toContain('starting with a letter');
    fireEvent.change(input, { target: { value: 'TAX' } });
    fireEvent.click(save);
    expect(onSave).toHaveBeenCalledWith('invoice', 'TAX');
  });
});

describe('making a quotation into a sale', () => {
  function Panel({ hasTerms = false, onConvert = () => undefined }: { hasTerms?: boolean; onConvert?: (form: ConvertForm) => void }) {
    const [form, setForm] = useState(blankConvert());
    return <ConvertPanel form={form} hasTerms={hasTerms} onChange={setForm} onConvert={() => onConvert(form)} />;
  }
  const button = () => screen.getByRole('button', { name: 'Make the sale' }) as HTMLButtonElement;

  it('is ready for cash, and waits for a date on credit unless the buyer has terms', () => {
    const sent = vi.fn();
    render(<Panel onConvert={sent} />);
    expect(button().disabled).toBe(false);
    fireEvent.change(screen.getByLabelText('How is it paid?'), { target: { value: 'credit' } });
    expect(button().disabled).toBe(true);
    expect(screen.getByRole('alert').textContent).toContain('date');
    fireEvent.change(screen.getByLabelText(/Buyer will pay by/), { target: { value: '2026-11-01' } });
    expect(button().disabled).toBe(false);
    fireEvent.click(screen.getByLabelText(/Also make the invoice/));
    fireEvent.click(button());
    expect(sent).toHaveBeenCalledWith({ method: 'credit', dueDate: '2026-11-01', keepPrices: true, invoice: false });
  });

  it('does not wait for a date when the buyer has terms', () => {
    render(<Panel hasTerms />);
    fireEvent.change(screen.getByLabelText('How is it paid?'), { target: { value: 'credit' } });
    expect(button().disabled).toBe(false);
  });
});

describe('opening a PDF', () => {
  let tab: { location: { href: string }; close: () => void; opener: unknown };

  beforeEach(() => {
    tab = { location: { href: '' }, close: vi.fn(), opener: window };
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
  });

  it('opens a stored PDF in a new tab through its signed link', async () => {
    const url = vi.spyOn(retail, 'documentUrl').mockResolvedValue('/files/one.pdf');
    const renderIt = vi.spyOn(retail, 'renderDocument');
    render(withStaff(<PdfButton doc={{ id: 'd1', pdf_document_id: 'f1', number: 'INV-HQ-000001' }} />));
    fireEvent.click(screen.getByRole('button', { name: 'Open PDF of INV-HQ-000001' }));
    await waitFor(() => expect(tab.location.href).toBe('/files/one.pdf'));
    expect(url).toHaveBeenCalledWith('f1');
    expect(renderIt).not.toHaveBeenCalled();
    expect(tab.opener, 'the new tab cannot reach back into this page').toBeNull();
  });

  it('renders a document whose PDF was never stored before opening it', async () => {
    const renderIt = vi.spyOn(retail, 'renderDocument').mockResolvedValue({ id: 'd1', pdf_document_id: 'f9' });
    vi.spyOn(retail, 'documentUrl').mockResolvedValue('/files/nine.pdf');
    render(withStaff(<PdfButton doc={{ id: 'd1', number: 'INV-HQ-000001' }} />));
    fireEvent.click(screen.getByRole('button', { name: 'Open PDF of INV-HQ-000001' }));
    await waitFor(() => expect(tab.location.href).toBe('/files/nine.pdf'));
    expect(renderIt).toHaveBeenCalledWith('d1');
  });

  it('closes the tab and says so when the PDF cannot be made', async () => {
    vi.spyOn(retail, 'renderDocument').mockRejectedValue(new Error('Too many documents are being made right now.'));
    render(withStaff(<PdfButton doc={{ id: 'd1', number: 'INV-HQ-000001' }} />));
    fireEvent.click(screen.getByRole('button', { name: 'Open PDF of INV-HQ-000001' }));
    await waitFor(() => expect(screen.getByText(/Too many documents/)).toBeTruthy());
    expect(tab.close).toHaveBeenCalled();
  });
});

describe('the documents of a sale', () => {
  const invoice: RetailDocument = { id: 'd1', type: 'invoice', number: 'INV-HQ-000001', status: 'issued', issue_date: '2026-10-08', currency: 'UGX', total_minor: 6000, pdf_document_id: 'f1' };

  it('lists what exists and makes only what is missing, with prices on a delivery note only when asked', async () => {
    vi.spyOn(retail, 'listSaleDocuments').mockResolvedValue([invoice]);
    const issue = vi.spyOn(retail, 'issueSaleDocument').mockResolvedValue({ ...invoice, id: 'd2', type: 'delivery_note', number: 'DN-HQ-000001' });
    render(withStaff(<SaleDocuments saleId="s1" status="completed" branchId={branch} />));
    await waitFor(() => expect(screen.getByText('INV-HQ-000001')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Make invoice' })).toBeNull();
    fireEvent.click(screen.getByLabelText(/Show the prices on the delivery note/));
    fireEvent.click(screen.getByRole('button', { name: 'Make delivery note' }));
    await waitFor(() => expect(issue).toHaveBeenCalled());
    expect(issue.mock.calls[0]?.[0]).toBe('s1');
    expect(issue.mock.calls[0]?.[1]).toEqual({ type: 'delivery_note', show_prices: true });
    expect(typeof issue.mock.calls[0]?.[2]).toBe('string');
  });

  it('offers nothing for a voided sale and says why', async () => {
    vi.spyOn(retail, 'listSaleDocuments').mockResolvedValue([]);
    render(withStaff(<SaleDocuments saleId="s1" status="voided" branchId={branch} />));
    await waitFor(() => expect(screen.getByText('A voided sale takes no new document.')).toBeTruthy());
    expect(screen.queryByRole('button', { name: /^Make/ })).toBeNull();
  });
});

describe('opening a PDF that has to be made first', () => {
  it('opens the tab before the waiting, so a pop-up blocker does not stop it, and points it at the file', async () => {
    const tab = { location: { href: '' }, close: vi.fn() };
    const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:statement', revokeObjectURL: () => undefined }));
    let finish: (b: Blob) => void = () => undefined;
    const waiting = openMadePdf(() => new Promise<Blob>((resolve) => { finish = resolve; }));
    expect(open).toHaveBeenCalledTimes(1);
    expect(tab.location.href).toBe('');
    finish(new Blob(['%PDF']));
    await waiting;
    expect(tab.location.href).toBe('blob:statement');
    vi.unstubAllGlobals();
  });

  it('closes the tab when the PDF cannot be made', async () => {
    const tab = { location: { href: '' }, close: vi.fn() };
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    await expect(openMadePdf(() => Promise.reject(new Error('Choose a shorter range.')))).rejects.toThrow('shorter range');
    expect(tab.close).toHaveBeenCalled();
  });
});

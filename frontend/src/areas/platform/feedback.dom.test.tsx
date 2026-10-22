// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PlatformFeedbackItem } from '../../api/feedback';
import { FeedbackInbox, issueNumberOf } from './feedback';

// The operator's Feedback list (issue #447, ADR-065): the issue opened for an item is shown, set and cleared, and a number that is
// not a whole number above zero is never sent. The server is a fake; fabricated values only.

afterEach(cleanup);

function show(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{node}</QueryClientProvider>);
}

const ITEM: PlatformFeedbackItem = {
  id: 'f-1',
  tenant_id: 't-1',
  tenant_slug: 'test-shop-one',
  tenant_name: 'Test Shop One',
  sender_name: 'Test Staff 01',
  kind: 'problem',
  message: 'Test message: the till froze.',
  page_path: '/staff/retail/sale',
  app_version: '0.0.0 (test)',
  has_screenshot: false,
  status: 'new',
  created_at: '2026-10-22T08:00:00Z',
};

const issueBox = () => screen.getByLabelText('Issue number') as HTMLInputElement;

describe('the issue number', () => {
  it('reads a whole number above zero, an empty box as none, and anything else as wrong', () => {
    expect(issueNumberOf(' 42 ')).toBe(42);
    expect(issueNumberOf('#42')).toBe(42);
    expect(issueNumberOf('')).toBeNull();
    expect(issueNumberOf('0')).toBeUndefined();
    expect(issueNumberOf('-3')).toBeUndefined();
    expect(issueNumberOf('4.5')).toBeUndefined();
    expect(issueNumberOf('abc')).toBeUndefined();
  });

  it('sets the issue for an item', async () => {
    const link = vi.fn(() => Promise.resolve(ITEM));
    show(<FeedbackInbox load={() => Promise.resolve([ITEM])} save={vi.fn()} link={link} loadScreenshot={vi.fn()} />);
    await screen.findByText('Test message: the till froze.');
    fireEvent.change(issueBox(), { target: { value: '512' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the issue' }));
    await waitFor(() => expect(link).toHaveBeenCalledWith('f-1', 512));
  });

  it('shows the linked issue and clears it', async () => {
    const link = vi.fn(() => Promise.resolve(ITEM));
    const linked = { ...ITEM, linked_issue_number: 77 };
    show(<FeedbackInbox load={() => Promise.resolve([linked])} save={vi.fn()} link={link} loadScreenshot={vi.fn()} />);
    expect(await screen.findByText('Issue: #77.')).toBeTruthy();
    expect(issueBox().value).toBe('77');
    fireEvent.change(issueBox(), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Clear the issue' }));
    await waitFor(() => expect(link).toHaveBeenCalledWith('f-1', null));
  });

  it('never sends a number that is not a whole number above zero', async () => {
    const link = vi.fn();
    show(<FeedbackInbox load={() => Promise.resolve([ITEM])} save={vi.fn()} link={link} loadScreenshot={vi.fn()} />);
    await screen.findByText('Test message: the till froze.');
    fireEvent.change(issueBox(), { target: { value: '0' } });
    expect(screen.getByText('The issue number is a whole number above zero.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Save the issue' }) as HTMLButtonElement).disabled).toBe(true);
    expect(link).not.toHaveBeenCalled();
  });
});

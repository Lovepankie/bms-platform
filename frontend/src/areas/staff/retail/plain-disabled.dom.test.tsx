// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { LandedCostPreview } from '../../../api/retail';
import { ReviewPanel } from './landed-cost-new';
import { blankLanded } from './landed-state';
import { blankBill } from './payables-state';
import { BillFormView } from './supplier-bill-new';

// Review of #559: a block that is not about the fields is a plain disabled button with its own reason on the page, never the
// "Fill in or correct the fields above first." nudge. Payments' Test connection is held in payments.dom.test.tsx. Every name
// below is invented.

afterEach(cleanup);

const NUDGE = 'Fill in or correct the fields above first.';
const held = (name: string) => {
  const button = screen.getByRole('button', { name }) as HTMLButtonElement;
  expect(button.disabled).toBe(true);
  expect(button.getAttribute('data-not-ready')).toBeNull();
  fireEvent.click(button);
  expect(screen.queryByText(NUDGE)).toBeNull();
};

describe('blocks that are not about the fields', () => {
  it('keeps Save landed cost disabled while the shares are worked out, and while the total does not match', () => {
    const props = { form: blankLanded('2026-10-12'), payee: 'Test Forwarder 01', onBack: () => undefined, onSave: () => undefined };
    const { rerender } = render(<ReviewPanel {...props} preview={undefined} />);
    expect(screen.getByText('Working out the shares')).toBeTruthy();
    held('Save landed cost');
    rerender(<ReviewPanel {...props} preview={{ total_minor: 5000, total_mismatch: true, lines: [] } as unknown as LandedCostPreview} />);
    expect(screen.getByRole('alert').textContent).toContain('The total you typed is not the sum of the charges.');
    held('Save landed cost');
  });

  it('keeps Save supplier invoice disabled while it is checked against the goods received', () => {
    render(<BillFormView form={{ ...blankBill('2026-10-12'), supplierId: 's1' }} suppliers={[]} checking onChange={() => undefined} onSubmit={() => undefined} />);
    expect(screen.getByText('Checking against the goods received')).toBeTruthy();
    held('Save supplier invoice');
  });
});

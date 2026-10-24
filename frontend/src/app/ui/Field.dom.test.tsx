// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { Field, FieldsNeededNote, focusFirstMissing } from './Field';

// The one form field (#520): label above, hint, the error only once the field is left, described and marked invalid.

afterEach(cleanup);

function Phone({ showError = false }: { showError?: boolean }) {
  const [phone, setPhone] = useState('');
  const error = /^0\d{9}$/.test(phone) ? null : 'Type the 10 digits of the phone number, starting with 0.';
  return <Field label="Phone number" hint="The buyer gets the receipt here." inputMode="tel" autoComplete="tel" value={phone} onChange={(e) => setPhone(e.target.value)} error={error} showError={showError} />;
}

const input = () => screen.getByLabelText('Phone number') as HTMLInputElement;
const describedBy = () => document.getElementById(input().getAttribute('aria-describedby') ?? '')?.textContent;

describe('Field', () => {
  it('labels the input, passes its props, and is described by the hint', () => {
    render(<Phone />);
    expect(input().getAttribute('inputmode')).toBe('tel');
    expect(input().getAttribute('autocomplete')).toBe('tel');
    expect(input().getAttribute('aria-invalid')).toBe('false');
    expect(describedBy()).toBe('The buyer gets the receipt here.');
  });

  it('keeps the error back while the person types, and shows it in place of the hint once they leave', () => {
    render(<Phone />);
    input().focus();
    fireEvent.change(input(), { target: { value: '07' } });
    expect(screen.queryByText(/Type the 10 digits/)).toBeNull();
    fireEvent.blur(input());
    expect(input().getAttribute('aria-invalid')).toBe('true');
    expect(describedBy()).toBe('Type the 10 digits of the phone number, starting with 0.');
    expect(screen.queryByText('The buyer gets the receipt here.')).toBeNull();
    fireEvent.change(input(), { target: { value: '0700000001' } });
    expect(input().getAttribute('aria-invalid')).toBe('false');
    expect(describedBy()).toBe('The buyer gets the receipt here.');
  });

  it('shows the error at once after a send', () => {
    render(<Phone showError />);
    expect(input().getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByText(/Type the 10 digits/).className).toBe('field-error');
  });

  it('puts a red asterisk, hidden from a screen reader, after the label of a required field and marks it aria-required', () => {
    render(<Field label="Buyer's name" required value="" onChange={() => undefined} />);
    const input = screen.getByLabelText(/Buyer's name/) as HTMLInputElement;
    const star = input.closest('.field')?.querySelector('label .field-star');
    expect(star?.textContent).toBe(' *');
    expect(star?.getAttribute('aria-hidden')).toBe('true');
    expect(input.getAttribute('aria-required')).toBe('true');
    expect(input.required).toBe(false);
  });

  it('marks an optional field "(optional)" instead, on a form where most are needed', () => {
    render(<Field label="Note" optional value="" onChange={() => undefined} />);
    expect(screen.getByText('(optional)', { exact: false }).className).toBe('field-optional');
    expect(screen.getByLabelText(/Note/).hasAttribute('aria-required')).toBe(false);
  });

  it('leads the error with the error icon', () => {
    render(<Phone showError />);
    expect(document.querySelector('.field-error svg')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('gives a form the line "Fields marked * are needed." and focuses the first missing or wrong field on a send', () => {
    render(
      <form>
        <FieldsNeededNote />
        <Field label="Name" required defaultValue="Sample Buyer" />
        <Field label="Phone" required defaultValue="" />
        <Field label="Town" required defaultValue="" />
      </form>,
    );
    expect(document.querySelector('.fields-needed')?.textContent).toBe('Fields marked * are needed.');
    expect(focusFirstMissing(document.querySelector('form')!)).toBe(true);
    expect(document.activeElement).toBe(screen.getByLabelText(/^Phone/));
    fireEvent.change(screen.getByLabelText(/^Phone/), { target: { value: '0700000001' } });
    fireEvent.change(screen.getByLabelText(/^Town/), { target: { value: 'Sample Town' } });
    expect(focusFirstMissing(document.querySelector('form')!)).toBe(false);
  });

  it('wires another control through control', () => {
    render(
      <Field
        label="Branch"
        hint="Where the stock is."
        required
        control={(props) => (
          <select {...props}>
            <option>Main shop</option>
          </select>
        )}
      />,
    );
    const select = screen.getByLabelText(/^Branch/);
    expect(select.tagName).toBe('SELECT');
    expect(select.getAttribute('aria-required')).toBe('true');
    expect(document.getElementById(select.getAttribute('aria-describedby') ?? '')?.textContent).toBe('Where the stock is.');
  });
});

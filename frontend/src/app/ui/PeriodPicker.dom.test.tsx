// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { businessToday, daysBefore } from '../../api/retail';
import { PeriodPicker, customProblem, periodOf, presetRange, showDate, showRange } from './PeriodPicker';

// The one period picker (issue #526): Today, This week, This month and Custom as one Segmented, the days in words under it,
// and Custom in the kit's Sheet. Dates read "23 Oct 2026" through the one formatter.

afterEach(cleanup);

function Harness({ start }: { start: { from: string; to: string } }) {
  const [range, setRange] = useState(start);
  return <PeriodPicker from={range.from} to={range.to} onChange={setRange} />;
}

describe('the date words', () => {
  it('shows a day as "23 Oct 2026" and a range with the year once', () => {
    expect(showDate('2026-10-23')).toBe('23 Oct 2026');
    expect(showRange('2026-10-17', '2026-10-24')).toBe('17 Oct to 24 Oct 2026');
    expect(showRange('2025-12-30', '2026-01-02')).toBe('30 Dec 2025 to 2 Jan 2026');
    expect(showRange('2026-10-24', '2026-10-24')).toBe('24 Oct 2026');
  });

  it('knows the presets, Monday to today for the week, and refuses days it cannot use', () => {
    expect(presetRange('week', '2026-10-24')).toEqual({ from: '2026-10-19', to: '2026-10-24' });
    expect(presetRange('month', '2026-10-24')).toEqual({ from: '2026-10-01', to: '2026-10-24' });
    expect(periodOf('2026-10-24', '2026-10-24', '2026-10-24')).toBe('today');
    expect(periodOf('2026-09-24', '2026-10-24', '2026-10-24')).toBe('custom');
    expect(customProblem('2026-10-24', '2026-10-01', '2026-10-24')).toBe('The first day must not be after the last.');
    expect(customProblem('2026-10-01', '2026-10-25', '2026-10-24')).toBe('Choose days up to today.');
    expect(customProblem('2025-01-01', '2026-10-24', '2026-10-24', 366)).toBe('Choose at most 366 days.');
  });
});

describe('the picker', () => {
  it('shows a default window as Custom with its days, and a preset moves the days', () => {
    const today = businessToday();
    render(<Harness start={{ from: daysBefore(today, 29), to: today }} />);
    expect(screen.getByRole('radio', { name: 'Custom' }).getAttribute('aria-checked')).toBe('true');
    fireEvent.click(screen.getByRole('radio', { name: 'Today' }));
    expect(screen.getByRole('radio', { name: 'Today' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByText(showDate(today))).toBeTruthy();
  });

  it('opens the days in a sheet on Custom and applies them on Show these days', () => {
    const today = businessToday();
    render(<Harness start={{ from: today, to: today }} />);
    fireEvent.click(screen.getByRole('radio', { name: 'Custom' }));
    const from = screen.getByLabelText('From') as HTMLInputElement;
    fireEvent.change(from, { target: { value: daysBefore(today, 3) } });
    fireEvent.click(screen.getByRole('button', { name: 'Show these days' }));
    expect(screen.queryByLabelText('From')).toBeNull();
    expect(screen.getByRole('button', { name: `${showRange(daysBefore(today, 3), today)}. Change the days` })).toBeTruthy();
  });

  // Review of #559: All sales starts with every day, and Show every day goes back to it after a preset.
  it('shows an open start as every day and goes back to it from the sheet', () => {
    const today = businessToday();
    function Open() {
      const [range, setRange] = useState({ from: '', to: today });
      return <PeriodPicker from={range.from} to={range.to} onChange={setRange} openStart />;
    }
    render(<Open />);
    expect(screen.getByRole('button', { name: `Every day up to ${showDate(today)}. Change the days` })).toBeTruthy();
    fireEvent.click(screen.getByRole('radio', { name: 'Today' }));
    fireEvent.click(screen.getByRole('radio', { name: 'Custom' }));
    fireEvent.click(screen.getByRole('button', { name: 'Show every day' }));
    expect(screen.getByRole('radio', { name: 'Custom' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('button', { name: `Every day up to ${showDate(today)}. Change the days` })).toBeTruthy();
  });

  it('keeps This week lit when it covers the same days as Today', () => {
    render(<Harness start={{ from: '2000-01-01', to: '2000-01-02' }} />);
    fireEvent.click(screen.getByRole('radio', { name: 'This week' }));
    expect(screen.getByRole('radio', { name: 'This week' }).getAttribute('aria-checked')).toBe('true');
  });
});

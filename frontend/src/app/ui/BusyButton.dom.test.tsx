// @vitest-environment jsdom
import { QueryClient, QueryClientProvider, useMutation } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState, type FormEvent } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BusyAnnouncer, BusyButton } from './BusyButton';

function Save({ send }: { send: () => Promise<void> }) {
  const save = useMutation({ mutationFn: send });
  return (
    <BusyButton type="button" className="btn-primary" busy={save.isPending} onClick={() => save.mutate()}>
      Save
    </BusyButton>
  );
}

function withQueries(ui: JSX.Element) {
  return render(<QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>);
}

afterEach(cleanup);

describe('the busy button (#456)', () => {
  it('is a plain button while idle: no spinner, not busy, nothing announced', () => {
    render(<><BusyButton busy={false} className="btn-primary">Save</BusyButton><BusyAnnouncer /></>);
    const button = screen.getByRole('button', { name: 'Save' });
    expect(button).not.toHaveProperty('disabled', true);
    expect(button.getAttribute('aria-busy')).toBeNull();
    expect(button.getAttribute('aria-disabled')).toBeNull();
    expect(button.className).toBe('btn-primary busy-button');
    expect(button.querySelector('.busy-spinner')).toBeNull();
    expect(screen.getByRole('status').textContent).toBe('');
  });

  it('while busy keeps its label, shows the spinner, is aria-disabled and aria-busy, and says Working', () => {
    render(<><BusyButton busy className="btn-primary">Save</BusyButton><BusyAnnouncer /></>);
    const button = screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    expect(button.disabled).toBe(false);
    expect(button.getAttribute('aria-disabled')).toBe('true');
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.textContent).toBe('Save');
    expect(button.querySelector('.busy-spinner')?.getAttribute('aria-hidden')).toBe('true');
    expect(screen.getByRole('status').textContent).toBe('Working');
  });

  it('has no live region of its own: one announcer says Working once for many busy buttons, then clears', () => {
    function Rows({ busy }: { busy: boolean }) {
      return (
        <>
          {['A', 'B', 'C'].map((row) => <BusyButton key={row} type="button" busy={busy}>{`Remove ${row}`}</BusyButton>)}
          <BusyAnnouncer />
        </>
      );
    }
    const { rerender } = render(<Rows busy />);
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status').textContent).toBe('Working');
    rerender(<Rows busy={false} />);
    expect(screen.getByRole('status').textContent).toBe('');
  });

  it('keeps keyboard focus while busy, and a press while busy sends nothing', () => {
    const onClick = vi.fn();
    const { rerender } = render(<BusyButton type="button" busy={false} onClick={onClick}>Save</BusyButton>);
    const button = screen.getByRole('button', { name: 'Save' });
    button.focus();
    rerender(<BusyButton type="button" busy onClick={onClick}>Save</BusyButton>);
    expect(document.activeElement).toBe(button);
    fireEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('holds the press while an onClick that awaits is still running', async () => {
    let finish = () => {};
    const onClick = vi.fn(() => new Promise<void>((resolve) => { finish = resolve; }));
    render(<BusyButton type="button" busy={false} onClick={onClick}>Save</BusyButton>);
    const button = screen.getByRole('button', { name: 'Save' });
    fireEvent.click(button);
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
    await act(async () => { finish(); await new Promise((resolve) => setTimeout(resolve, 20)); });
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(2);
  });

  it('lets a press through after ten seconds when an onClick never settles and busy never shows', () => {
    vi.useFakeTimers();
    try {
      const onClick = vi.fn(() => new Promise<void>(() => undefined));
      render(<BusyButton type="button" busy={false} onClick={onClick}>Save</BusyButton>);
      const button = screen.getByRole('button', { name: 'Save' });
      fireEvent.click(button);
      act(() => vi.advanceTimersByTime(9_999));
      fireEvent.click(button);
      expect(onClick).toHaveBeenCalledTimes(1);
      act(() => vi.advanceTimersByTime(1));
      fireEvent.click(button);
      expect(onClick).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps holding past ten seconds while busy shows', () => {
    vi.useFakeTimers();
    try {
      const onClick = vi.fn();
      const { rerender } = render(<BusyButton type="button" busy={false} onClick={onClick}>Save</BusyButton>);
      const button = screen.getByRole('button', { name: 'Save' });
      fireEvent.click(button);
      rerender(<BusyButton type="button" busy onClick={onClick}>Save</BusyButton>);
      act(() => vi.advanceTimersByTime(10_000));
      fireEvent.click(button);
      expect(onClick).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('lets the next press through after an onClick that throws, but not in the same task', async () => {
    const onClick = vi.fn(() => { throw new Error('refused'); });
    render(<BusyButton type="button" busy={false} onClick={onClick}>Save</BusyButton>);
    const button = screen.getByRole('button', { name: 'Save' });
    const swallow = (e: ErrorEvent) => e.preventDefault();
    window.addEventListener('error', swallow);
    try {
      fireEvent.click(button);
      fireEvent.click(button);
      expect(onClick).toHaveBeenCalledTimes(1);
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
      fireEvent.click(button);
      expect(onClick).toHaveBeenCalledTimes(2);
    } finally {
      window.removeEventListener('error', swallow);
    }
  });

  it('keeps a disabled button disabled when not busy', () => {
    render(<BusyButton busy={false} disabled>Save</BusyButton>);
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('sends once on a double click, then can be pressed again when the request is over', async () => {
    let finish = () => {};
    const send = vi.fn(() => new Promise<void>((resolve) => { finish = resolve; }));
    withQueries(<Save send={send} />);
    const button = screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(button.getAttribute('aria-busy')).toBe('true'));
    fireEvent.click(button);
    expect(send).toHaveBeenCalledTimes(1);
    await act(async () => finish());
    await waitFor(() => expect(button.getAttribute('aria-busy')).toBeNull());
    fireEvent.click(button);
    await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
  });

  it('lets a press that started nothing be followed by another one', async () => {
    const onClick = vi.fn();
    render(<BusyButton type="button" busy={false} onClick={onClick}>Save</BusyButton>);
    const button = screen.getByRole('button', { name: 'Save' });
    fireEvent.click(button);
    await act(async () => { await new Promise((resolve) => setTimeout(resolve)); await new Promise((resolve) => setTimeout(resolve)); });
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(2);
  });

  it('drops an Enter re-submit of its form while busy', () => {
    const onSubmit = vi.fn((e: FormEvent) => e.preventDefault());
    function Form() {
      const [busy, setBusy] = useState(false);
      return (
        <form onSubmit={(e) => { onSubmit(e); setBusy(true); }}>
          <input aria-label="Amount" />
          <BusyButton type="submit" busy={busy}>Record</BusyButton>
        </form>
      );
    }
    render(<Form />);
    const form = screen.getByLabelText('Amount').closest('form') as HTMLFormElement;
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Record' }).getAttribute('aria-busy')).toBe('true');
  });

  it('sends once when its form is submitted twice from code in one task, and again two tasks later', async () => {
    const onSubmit = vi.fn((e: FormEvent) => e.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <input aria-label="Amount" />
        <BusyButton type="submit" busy={false}>Record</BusyButton>
      </form>,
    );
    const form = screen.getByLabelText('Amount').closest('form') as HTMLFormElement;
    form.requestSubmit();
    form.requestSubmit();
    expect(onSubmit).toHaveBeenCalledTimes(1);
    await act(async () => { await new Promise((resolve) => setTimeout(resolve)); await new Promise((resolve) => setTimeout(resolve)); });
    form.requestSubmit();
    expect(onSubmit).toHaveBeenCalledTimes(2);
  });

  it('submits its form once on a double click', () => {
    const onSubmit = vi.fn((e: FormEvent) => e.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <BusyButton type="submit" busy={false}>Record</BusyButton>
      </form>,
    );
    const button = screen.getByRole('button', { name: 'Record' });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});

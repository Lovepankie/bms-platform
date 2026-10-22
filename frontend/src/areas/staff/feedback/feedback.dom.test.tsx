// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FeedbackError, type FeedbackItem, type FeedbackSend } from '../../../api/feedback';
import { FeedbackForm, FeedbackHistory } from './page';

// The Feedback form and the list of what a person sent (issue #447, ADR-065): nothing is sent until the kind and the message are
// there, a wrong picture is refused at once, a sent form says so in plain words and empties itself, the server's refusal is shown as
// it is, and the list shows the status and the team's reply. The server is a fake; fabricated values only.

afterEach(cleanup);

function show(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{node}</QueryClientProvider>);
}

const SENT: FeedbackItem = { id: 'f-1', kind: 'idea', message: 'Test idea', status: 'new', has_screenshot: false, created_at: '2026-10-22T08:00:00Z' };

const message = () => screen.getByLabelText('Your message') as HTMLTextAreaElement;
const sendButton = () => screen.getByRole('button', { name: 'Send feedback' });

describe('the feedback form', () => {
  it('asks for the kind and the message before sending anything', () => {
    const send = vi.fn();
    show(<FeedbackForm send={send} from={() => '/staff/retail/sale'} />);
    fireEvent.click(sendButton());
    expect(send).not.toHaveBeenCalled();
    expect(screen.getByText('Choose what it is: an idea, a problem or a question.')).toBeTruthy();
    expect(screen.getByText('Write your message.')).toBeTruthy();
  });

  it('sends the kind, the message, the page the person came from and the picture, then says so and empties itself', async () => {
    const send = vi.fn((_: FeedbackSend) => Promise.resolve(SENT));
    show(<FeedbackForm send={send} from={() => '/staff/retail/sale'} />);
    fireEvent.click(screen.getByLabelText(/A problem/));
    fireEvent.change(message(), { target: { value: '  The till froze after the third item.  ' } });
    const picture = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'screen.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText(/A picture of the screen/), { target: { files: [picture] } });
    fireEvent.click(sendButton());
    await screen.findByText(/Thank you. Your feedback was sent to the BMS team./);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0]?.[0]).toEqual({ kind: 'problem', message: 'The till froze after the third item.', pagePath: '/staff/retail/sale', screenshot: picture });
    expect(message().value).toBe('');
  });

  it('refuses a picture that is not a PNG or JPEG, or larger than 2 MB, without sending', () => {
    const send = vi.fn();
    show(<FeedbackForm send={send} from={() => null} />);
    fireEvent.click(screen.getByLabelText(/An idea/));
    fireEvent.change(message(), { target: { value: 'Test idea' } });
    const gif = new File(['GIF89a'], 'screen.gif', { type: 'image/gif' });
    fireEvent.change(screen.getByLabelText(/A picture of the screen/), { target: { files: [gif] } });
    expect(screen.getByText('Choose a picture: a PNG or JPEG.')).toBeTruthy();
    fireEvent.click(sendButton());
    expect(send).not.toHaveBeenCalled();
    const big = new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'big.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText(/A picture of the screen/), { target: { files: [big] } });
    expect(screen.getByText('This picture is larger than 2 MB. Choose a smaller one.')).toBeTruthy();
  });

  it('refuses a message longer than 2000 characters', () => {
    const send = vi.fn();
    show(<FeedbackForm send={send} from={() => null} />);
    fireEvent.click(screen.getByLabelText(/A question/));
    fireEvent.change(message(), { target: { value: 'x'.repeat(2001) } });
    fireEvent.click(sendButton());
    expect(send).not.toHaveBeenCalled();
    expect(screen.getByText('Your message is 2001 characters. Keep it to 2000.')).toBeTruthy();
  });

  it('shows the server refusal as it is, and keeps what was typed', async () => {
    const send = vi.fn(() =>
      Promise.reject(new FeedbackError('You have sent 10 messages in the last hour. Please wait a little and send this one later.', 429, 'feedback_rate_limited')),
    );
    show(<FeedbackForm send={send} from={() => null} />);
    fireEvent.click(screen.getByLabelText(/An idea/));
    fireEvent.change(message(), { target: { value: 'Test idea eleven' } });
    fireEvent.click(sendButton());
    await screen.findByText('You have sent 10 messages in the last hour. Please wait a little and send this one later.');
    expect(message().value).toBe('Test idea eleven');
  });
});

describe('what the person sent', () => {
  it('shows each item with its status and the team reply', async () => {
    const load = () =>
      Promise.resolve<FeedbackItem[]>([
        { ...SENT, id: 'f-2', kind: 'problem', message: 'Test problem', status: 'seen', reply_note: 'Test reply: fixed tomorrow.' },
        SENT,
      ]);
    show(<FeedbackHistory load={load} />);
    await screen.findByText('Test problem');
    expect(screen.getByText('Read by the BMS team')).toBeTruthy();
    expect(screen.getByText('Sent, not read yet')).toBeTruthy();
    expect(screen.getByText('Test reply: fixed tomorrow.')).toBeTruthy();
  });

  it('says so when nothing was sent yet', async () => {
    show(<FeedbackHistory load={() => Promise.resolve([])} />);
    await waitFor(() => expect(screen.getByText('You have not sent any feedback yet.')).toBeTruthy());
  });
});

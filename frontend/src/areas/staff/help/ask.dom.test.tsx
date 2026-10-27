// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { AskAnswer } from '../../../api/help';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Ask BMS (issue #20, ADR-096) on How do I...?: the box shows only while the server says it is on; an answer shows with a
// link to the article it comes from; another role's job and the matching articles each say so; no answer is said plainly
// with Ask us under it and only related articles (issue #609); a failure leaves
// the articles; and "Not answered? Ask us" carries the question. The server is a mock: no network, no model. Every person is
// fabricated.

const ask = vi.hoisted(() => ({ status: vi.fn(), ask: vi.fn() }));

vi.mock('../../../api/help', async (original) => {
  const real = await original<typeof import('../../../api/help')>();
  return { ...real, askBms: ask };
});

const { HowDoIPage } = await import('./page');
const { NOT_FOUND } = await import('./ask');

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  window.history.replaceState({}, '', '/');
});

const owner = {
  ...(mockMe('admin') as Me),
  permissions: [...((mockMe('admin') as Me).permissions ?? []), 'core.users.read', 'core.users.manage'],
} as Me;

function show(node: ReactNode) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/help'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: owner, branch: owner.branches?.[0]?.id ?? null }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const reply = (over: Partial<AskAnswer>): AskAnswer => ({ source: 'articles', answer: null, cited: null, articles: [], revision: '0123456789abcdef', ...over });

async function askQuestion(text: string) {
  const box = await screen.findByLabelText(/Your question/);
  fireEvent.change(box, { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
}

describe('Ask BMS', () => {
  it('is not drawn while it is off for the business, and How do I...? works as before', async () => {
    ask.status.mockResolvedValue({ enabled: false, revision: 'r' });
    show(<HowDoIPage />);
    await waitFor(() => expect(ask.status).toHaveBeenCalled());
    expect(screen.queryByRole('heading', { name: 'Ask BMS' })).toBeNull();
    expect(screen.getByLabelText('What do you want to do?')).toBeTruthy();
  });

  it('is not drawn when the server cannot be reached', async () => {
    ask.status.mockRejectedValue(new Error('offline'));
    show(<HowDoIPage />);
    await waitFor(() => expect(ask.status).toHaveBeenCalled());
    expect(screen.queryByRole('heading', { name: 'Ask BMS' })).toBeNull();
  });

  it('shows the answer with a link to the article it comes from, and Ask us carries the question', async () => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    const cited = { id: 'invite-someone', title: 'How do I invite someone to use BMS?' };
    ask.ask.mockResolvedValue(reply({ source: 'model', answer: 'Open Staff, then tap Invite someone.', cited, articles: [cited] }));
    show(<HowDoIPage />);
    expect(await screen.findByRole('heading', { name: 'Ask BMS' })).toBeTruthy();
    await askQuestion('How do I invite a cashier?');
    expect(ask.ask).toHaveBeenCalledWith('How do I invite a cashier?');
    expect((await screen.findByTestId('ask-answer')).textContent).toBe('Open Staff, then tap Invite someone.');
    const from = within(screen.getByRole('list', { name: 'From the article' })).getByRole('link');
    expect(from.textContent).toContain('How do I invite someone to use BMS?');
    expect(from.getAttribute('href')).toBe('/staff/help/invite-someone');
    const askUs = screen.getByRole('link', { name: 'Ask us' });
    expect(new URL(askUs.getAttribute('href') ?? '', 'http://localhost').searchParams.get('ask')).toBe('How do I invite a cashier?');
    // A new search afterwards is what Ask us carries, not the older question.
    fireEvent.change(screen.getByLabelText('What do you want to do?'), { target: { value: 'count stock' } });
    await waitFor(() =>
      expect(new URL(screen.getByRole('link', { name: 'Ask us' }).getAttribute('href') ?? '', 'http://localhost').searchParams.get('ask')).toBe('count stock'),
    );
  });

  it('says when the job is another role\'s, lists the articles when no answer was written, and says when none matches', async () => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    ask.ask.mockResolvedValueOnce(
      reply({
        source: 'other_role',
        answer: '"How do I invite someone to use BMS?" is a job for another role. Ask your business administrator to do it, or to give you that permission.',
        articles: [{ id: 'switch-branch', title: 'How do I switch branch?' }],
      }),
    );
    show(<HowDoIPage />);
    await askQuestion('How do I invite a cashier?');
    expect((await screen.findByRole('note')).textContent).toContain('a job for another role');
    const mine = screen.getByRole('list', { name: 'Articles for what you can do' });
    expect(within(mine).getByRole('link').getAttribute('href')).toBe('/staff/help/switch-branch');

    ask.ask.mockResolvedValueOnce(reply({ source: 'articles', articles: [{ id: 'count-stock', title: 'How do I count my stock?' }] }));
    await askQuestion('count the shelves');
    const list = await screen.findByRole('list', { name: 'These articles may answer it' });
    expect(within(list).getByRole('link').getAttribute('href')).toBe('/staff/help/count-stock');

    ask.ask.mockResolvedValueOnce(reply({ source: 'no_match' }));
    await askQuestion('zzqx wvvy');
    expect(await screen.findByText(NOT_FOUND)).toBeTruthy();
  });

  it('says plainly when the articles do not answer, puts Ask us right under it, and lists only related articles (issue #609)', async () => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    // What the server listed for the owner's question on 27 Oct: the model found no answer, and the matches were unrelated.
    const unrelated = [
      { id: 'add-an-item', title: 'How do I add an item?' },
      { id: 'approve-a-request', title: 'How do I approve a request?' },
      { id: 'add-a-branch', title: 'How do I add a shop or a store?' },
    ];
    ask.ask.mockResolvedValueOnce(reply({ source: 'articles', reason: 'not_in_articles', articles: unrelated }));
    show(<HowDoIPage />);
    await askQuestion('How do I sign up to this app first time');
    const box = await screen.findByTestId('ask-not-found');
    const [line, button] = Array.from(box.children);
    expect(line?.textContent).toBe('I could not find this in the help yet. Tap Ask us and it comes to the team.');
    const askUs = within(button as HTMLElement).getByRole('link', { name: 'Ask us' });
    expect(new URL(askUs.getAttribute('href') ?? '', 'http://localhost').searchParams.get('ask')).toBe('How do I sign up to this app first time');
    expect(screen.queryByRole('list', { name: 'These articles may answer it' })).toBeNull();
    // One Ask us: the page's own "Not answered? Ask us" steps aside while this one shows, and comes back for a search.
    expect(screen.getAllByRole('link', { name: 'Ask us' })).toEqual([askUs]);
    fireEvent.change(screen.getByLabelText('What do you want to do?'), { target: { value: 'count stock' } });
    await waitFor(() => expect(screen.getAllByRole('link', { name: 'Ask us' })).toHaveLength(2));
    fireEvent.change(screen.getByLabelText('What do you want to do?'), { target: { value: '' } });
    await waitFor(() => expect(screen.getAllByRole('link', { name: 'Ask us' })).toHaveLength(1));

    // Every model failing reads the same; an article sharing a real word with the question is listed under Ask us.
    ask.ask.mockResolvedValueOnce(reply({ source: 'articles', reason: 'all_failed', articles: [...unrelated, { id: 'start-using-bms', title: 'How do I start using BMS for my business?' }] }));
    await askQuestion('How do I sign up to this app first time');
    const list = await screen.findByRole('list', { name: 'These articles may answer it' });
    expect(within(list).getAllByRole('link').map((a) => a.getAttribute('href'))).toEqual(['/staff/help/start-using-bms']);
    expect(screen.getByTestId('ask-not-found').firstElementChild?.textContent).toBe(NOT_FOUND);
  });

  it('lists the matching articles without saying the help has none when no model was asked (the day\'s questions used up)', async () => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    ask.ask.mockResolvedValueOnce(reply({ source: 'articles', reason: 'daily_cap', articles: [{ id: 'count-stock', title: 'How do I count my stock?' }] }));
    show(<HowDoIPage />);
    await askQuestion('How do I count the shelves?');
    const list = await screen.findByRole('list', { name: 'These articles may answer it' });
    expect(within(list).getByRole('link').getAttribute('href')).toBe('/staff/help/count-stock');
    expect(screen.queryByText(NOT_FOUND)).toBeNull();
    expect(screen.getAllByRole('link', { name: 'Ask us' })).toHaveLength(1);
  });

  it('says so when the question cannot be sent, and asks for a question first', async () => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    ask.ask.mockRejectedValue(new Error('Could not ask now. The articles below still answer.'));
    show(<HowDoIPage />);
    await askQuestion('hi');
    expect(await screen.findByText('Type your question, in your own words.')).toBeTruthy();
    expect(ask.ask).not.toHaveBeenCalled();
    await askQuestion('How do I move stock?');
    expect((await screen.findByRole('alert')).textContent).toBe('Could not ask now. The articles below still answer.');
  });
});

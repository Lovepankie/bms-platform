import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { AdvisorBrief, AdvisorSettings } from '../../../api/advisor';
import { AssistantCaps, AssistantSwitch } from '../assistant/page';
import { AdvisorBriefView, AdvisorCardState } from './advisor';
import { canConfigureAdvisor, canUseAdvisor } from './permissions';

const renderToString = (node: ReactElement) =>
  render(<QueryClientProvider client={new QueryClient()}>{node}</QueryClientProvider>).replaceAll('<!-- -->', '');

// Static markup tests (the test setup has no DOM): every AI text carries the advisory label and the line under it; the
// automatic summary says so and says why; the figures are chips; nothing is drawn as markup from the server's text.

const model: AdvisorBrief = {
  advisory: true,
  source: 'model',
  reason: null,
  label: 'AI assistant (advisory)',
  as_of: '2026-10-17T06:15:00Z',
  facts_as_of: '2026-10-17',
  summary: 'Summary for 17 October 2026 (month so far from 1 October 2026 to 17 October 2026).',
  points: [{ text: 'Medium priority. Active loans (120). Suggested action: Review the spread.', facts: ['portfolio.active_loans'] }],
  facts: [
    { key: 'portfolio.active_loans', label: 'Active loans', display: '120', metric: 'portfolio.active_loans' },
    { key: 'branch_1.loans', label: 'Branch 1: Active loans', display: '60', metric: null },
  ],
  note: 'Generated from your portfolio figures. Check before acting. It does not change any record.',
};

const automatic: AdvisorBrief = {
  ...model,
  source: 'fallback',
  reason: 'provider_down',
  label: 'Automatic summary',
  note: 'Written from your portfolio figures by fixed rules. No AI was used.',
};

const settings: AdvisorSettings = {
  enabled: false,
  allowed: true,
  provider_configured: true,
  daily_token_cap: 200000,
  daily_cost_cap_micro: 1000000,
  cost_currency: 'USD',
  tokens_used_today: 1200,
  cost_used_today_micro: 100,
  acknowledged_by: null,
  acknowledged_at: null,
  acknowledged_text_version: null,
  acknowledgement: { text_version: 'advisor-1', text: 'Fabricated notice text.' },
  version: 3,
};

describe('the AI assistant card', () => {
  it('labels an AI brief as advisory, shows when it was made, the figures it used and the check-before-acting line', () => {
    const html = renderToString(<AdvisorBriefView brief={model} onChip={() => {}} />);
    expect(html).toContain('AI assistant (advisory)');
    expect(html).toContain('As of ');
    expect(html).toContain('Check before acting. It does not change any record.');
    expect(html).toContain('Active loans: 120');
    expect(html).toContain('Review the spread.');
  });

  it('opens a metric only from a chip that has one', () => {
    const html = renderToString(<AdvisorBriefView brief={model} onChip={() => {}} />);
    expect(html).toMatch(/<button[^>]*>Active loans: 120<\/button>/);
    expect(html).toMatch(/<span class="badge">Branch 1: Active loans: 60<\/span>/);
  });

  it('says Automatic summary, and why, on every fallback reason, and never the AI label', () => {
    for (const reason of ['off', 'scope', 'validation', 'provider_down', 'cap', 'not_ready', 'unavailable']) {
      const html = renderToString(<AdvisorBriefView brief={{ ...automatic, reason }} />);
      expect(html, reason).toContain('Automatic summary');
      expect(html, reason).not.toContain('AI assistant (advisory)');
      expect(html, reason).toContain('No AI was used.');
    }
    const html = renderToString(<AdvisorBriefView brief={{ ...automatic, reason: 'scope' }} />);
    expect(html).toContain('covers every branch');
  });

  it('draws a server text as text, never as markup', () => {
    const html = renderToString(<AdvisorBriefView brief={{ ...model, summary: '<b>x</b>' }} />);
    expect(html).toContain('&lt;b&gt;x&lt;/b&gt;');
    expect(html).not.toContain('<b>x</b>');
  });

  it('is offered only to a user who holds lending.advisor.use in a business with lending', () => {
    expect(canUseAdvisor({ permissions: ['lending.advisor.use'], modules: ['lending'] })).toBe(true);
    expect(canUseAdvisor({ permissions: ['lending.advisor.use'], modules: ['retail'] })).toBe(false);
    expect(canUseAdvisor({ permissions: ['lending.insights.read'], modules: ['lending'] })).toBe(false);
    expect(canConfigureAdvisor({ permissions: ['lending.advisor.configure'], modules: ['lending'] })).toBe(true);
    expect(canConfigureAdvisor({ permissions: ['lending.advisor.use'], modules: ['lending'] })).toBe(false);
  });
});

describe('the AI assistant card states', () => {
  it('shows a plain placeholder while loading, with no label and no claim', () => {
    const html = renderToString(<AdvisorCardState pending failed={false} brief={undefined} />);
    expect(html).toContain('Loading the summary');
    expect(html).not.toContain('Automatic summary');
    expect(html).not.toContain('AI assistant (advisory)');
  });

  it('shows one quiet line when the load failed, never a label', () => {
    const html = renderToString(<AdvisorCardState pending={false} failed brief={undefined} />);
    expect(html).toContain('The summary is not available right now');
    expect(html).not.toContain('Automatic summary');
    expect(html).not.toContain('AI assistant (advisory)');
  });

  it('draws nothing when the assistant is off for the business (the server answered 204)', () => {
    expect(renderToString(<AdvisorCardState pending={false} failed={false} brief={null} />)).toBe('');
  });

  it('draws the brief once it is there', () => {
    expect(renderToString(<AdvisorCardState pending={false} failed={false} brief={model} />)).toContain('AI assistant (advisory)');
  });
});

describe('the AI assistant settings', () => {
  it('is off by default and offers the switch only to an administrator, and only when the platform allows it', () => {
    const admin = renderToString(<AssistantSwitch saved={settings} canManage busy={false} error={null} onChange={() => {}} />);
    expect(admin).toContain('Off');
    expect(admin).toContain('Switch the assistant on');
    const other = renderToString(<AssistantSwitch saved={settings} canManage={false} busy={false} error={null} onChange={() => {}} />);
    expect(other).not.toContain('Switch the assistant on');
    expect(other).toContain('Only a business administrator');
    const notAllowed = renderToString(<AssistantSwitch saved={{ ...settings, allowed: false }} canManage busy={false} error={null} onChange={() => {}} />);
    expect(notAllowed).not.toContain('Switch the assistant on');
    expect(notAllowed).toContain('has not opened the assistant');
  });

  it('shows who accepted the notice and offers the way off once it is on', () => {
    const on = { ...settings, enabled: true, acknowledged_at: '2026-10-17T06:00:00Z', acknowledged_text_version: 'advisor-1' };
    const html = renderToString(<AssistantSwitch saved={on} canManage busy={false} error={null} onChange={() => {}} />);
    expect(html).toContain('Switch the assistant off');
    expect(html).toContain('notice was accepted');
  });

  it('shows the cap, the use today and that 0 stops every call', () => {
    const html = renderToString(<AssistantCaps saved={settings} busy={false} error={null} onSave={() => {}} />);
    expect(html).toContain('value="200000"');
    expect(html).toContain('Used today: 1,200 tokens');
    expect(html).toContain('0 stops every call');
  });
});

import { describe, expect, it } from 'vitest';
import type { SmsSettings } from '../../../api/sms';
import {
  capProblem,
  capWords,
  changeOf,
  draftOf,
  isChange,
  modeNote,
  parseCap,
  problemsOf,
  showSms,
  windowProblem,
} from './state';

// The SMS settings screen's logic (extras slice B3, issue #218). The rule to keep: a cap of 0 means stop. It is a number, it
// is sent as a number, it is shown as "0", and it is never confused with an empty box, which means "no cap of my own".

const saved: SmsSettings = {
  enabled: true,
  operator_enabled: true,
  mode: 'live',
  test_message_available: false,
  sender_id: 'BrandTest1',
  sender_status: 'approved',
  approved_sender_ids: ['BrandTest1', 'BrandTest2'],
  window_start: '08:00',
  window_end: '20:00',
  window_zone: 'Africa/Kampala',
  window_earliest: '06:00',
  window_latest: '21:00',
  window_min_minutes: 60,
  daily_cap: null,
  monthly_cap: null,
  daily_ceiling: 300,
  monthly_ceiling: 9300,
  effective_daily_cap: 300,
  effective_monthly_cap: 9300,
  used_today: 12,
  used_this_month: 250,
  include_balance: false,
  acknowledgement: { text_version: 'sms-1', text: 'Every message costs money.' },
  acknowledged_text_version: 'sms-1',
  acknowledged_at: '2031-03-10T07:00:00Z',
  acknowledged_by: '00000000-0000-4000-8000-000000000001',
  version: 4,
};

describe('a cap as typed', () => {
  it.each([
    ['', null],
    ['   ', null],
    ['0', 0],
    ['007', 7],
    [' 120 ', 120],
  ])('reads %j as %j', (text, expected) => {
    expect(parseCap(text)).toBe(expected);
  });

  it.each(['-1', '2.5', '1e3', 'ten', '1,000', '12345678', '１２'])('refuses %j, never rounds or defaults it', (text) => {
    expect(parseCap(text)).toBe('invalid');
    expect(capProblem(text, 300)).toMatch(/whole number/);
  });

  it('allows a cap up to the ceiling and refuses one above it, naming the ceiling', () => {
    expect(capProblem('300', 300)).toBeNull();
    expect(capProblem('301', 300)).toBe('At most 300. A cap can be lowered, not raised.');
    expect(capProblem('0', 300)).toBeNull();
    expect(capProblem('', 300)).toBeNull();
  });

  it('puts 0 in the box as "0" and an absent cap as an empty box', () => {
    expect(draftOf({ ...saved, daily_cap: 0, monthly_cap: null }).dailyCap).toBe('0');
    expect(draftOf({ ...saved, daily_cap: 0, monthly_cap: null }).monthlyCap).toBe('');
    expect(draftOf({ ...saved, daily_cap: 150 }).dailyCap).toBe('150');
  });

  it('says a cap of 0 stops everything, in words', () => {
    expect(capWords(0, 'day')).toBe('0 messages a day: nothing is sent while it is 0');
    expect(capWords(1, 'month')).toBe('1 message a month');
    expect(capWords(200, 'day')).toBe('200 messages a day');
  });
});

describe('the change to send', () => {
  it('is empty when nothing differs', () => {
    expect(changeOf(saved, draftOf(saved))).toEqual({});
    expect(isChange({})).toBe(false);
  });

  it('sends a zero cap as the number 0, which is a change from no cap', () => {
    const change = changeOf(saved, { ...draftOf(saved), dailyCap: '0' });
    expect(change).toEqual({ daily_cap: 0 });
    expect(change.daily_cap).toBe(0);
    expect(isChange(change)).toBe(true);
    // 0 where 0 already was is no change.
    const stopped = { ...saved, daily_cap: 0 };
    expect(changeOf(stopped, { ...draftOf(stopped), dailyCap: '0' })).toEqual({});
  });

  it('turns an emptied cap that was set into the ceiling, and an empty one that never was into nothing', () => {
    const set = { ...saved, daily_cap: 50, monthly_cap: 1000 };
    expect(changeOf(set, { ...draftOf(set), dailyCap: '', monthlyCap: '' })).toEqual({ daily_cap: 300, monthly_cap: 9300 });
    expect(changeOf(saved, { ...draftOf(saved), dailyCap: '', monthlyCap: '' })).toEqual({});
  });

  it('sends only what changed: sender, window and balance', () => {
    const d = { ...draftOf(saved), senderId: 'BrandTest2', windowStart: '09:30', includeBalance: true };
    expect(changeOf(saved, d)).toEqual({ sender_id: 'BrandTest2', window_start: '09:30', include_balance: true });
  });

  it('clears the sender with an empty string, the shared sender being the default', () => {
    expect(changeOf(saved, { ...draftOf(saved), senderId: '' })).toEqual({ sender_id: '' });
    const none = { ...saved, sender_id: null, sender_status: 'none' };
    expect(changeOf(none, draftOf(none))).toEqual({});
  });

  it('leaves a cap it cannot read out of the change (the box shows the problem instead)', () => {
    expect(changeOf(saved, { ...draftOf(saved), dailyCap: 'ten' })).toEqual({});
    expect(problemsOf(saved, { ...draftOf(saved), dailyCap: 'ten' }).daily_cap).toMatch(/whole number/);
  });
});

describe('the window', () => {
  const limits = { window_earliest: '06:00', window_latest: '21:00', window_min_minutes: 60 };

  it.each([
    ['08:00', '20:00', null],
    ['06:00', '21:00', null],
    ['10:00', '11:00', null],
    ['20:00', '08:00', 'The window must end later than it starts.'],
    ['10:00', '10:00', 'The window must end later than it starts.'],
    ['10:00', '10:59', 'The window must be at least 60 minutes.'],
    ['05:59', '12:00', 'Messages cannot start before 06:00.'],
    ['12:00', '21:01', 'Messages cannot go after 21:00.'],
    ['', '12:00', 'Choose a time for both.'],
    ['8am', '12:00', 'Choose a time for both.'],
  ])('%s to %s: %s', (start, end, problem) => {
    expect(windowProblem(start, end, limits)).toBe(problem);
  });

  it('reports a window problem with the other problems of the form', () => {
    const problems = problemsOf(saved, { ...draftOf(saved), windowStart: '22:00', windowEnd: '23:00', dailyCap: '999' });
    expect(Object.keys(problems).sort()).toEqual(['daily_cap', 'window']);
    expect(problemsOf(saved, draftOf(saved))).toEqual({});
  });
});

describe('what the system does with a message', () => {
  it('says nothing is sent until the platform switches SMS on', () => {
    expect(modeNote({ mode: 'live', operator_enabled: false, enabled: true })).toMatch(/platform has not switched SMS on/);
  });

  it('says so on a system that records or lists messages instead of sending them', () => {
    expect(modeNote({ mode: 'off', operator_enabled: true, enabled: true })).toMatch(/nothing is sent/);
    expect(modeNote({ mode: 'sink', operator_enabled: true, enabled: true })).toMatch(/never leave the platform/);
    expect(modeNote({ mode: 'allowlist', operator_enabled: true, enabled: true })).toMatch(/only a few listed numbers/);
    expect(modeNote({ mode: 'live', operator_enabled: true, enabled: true })).toBeNull();
  });
});

describe('who sees the SMS tab', () => {
  it('needs settings or notifications to be readable', () => {
    expect(showSms({ permissions: ['core.settings.read'] })).toBe(true);
    expect(showSms({ permissions: ['core.notifications.read'] })).toBe(true);
    expect(showSms({ permissions: ['lending.loans.read'] })).toBe(false);
    expect(showSms({ permissions: [] })).toBe(false);
  });
});

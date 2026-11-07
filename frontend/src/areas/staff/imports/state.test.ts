import { describe, expect, it } from 'vitest';
import type { Reconciliation } from '../../../api/imports';
import { figure, grouped, itemPlace, reasonWords, runState, signOffProblem } from './state';

// The words and figures of Imports to sign off (issue #134): exact decimals grouped and never rounded, money through the one
// formatter, and the first thing missing before a sign-off. Fabricated figures.

describe('figures', () => {
  it('groups an exact decimal without rounding it', () => {
    expect(grouped('1234567')).toBe('1,234,567');
    expect(grouped('-1234567.125')).toBe('-1,234,567.125');
    expect(grouped('1.5')).toBe('1.5');
    expect(grouped('not a number')).toBe('not a number');
  });

  it('shows money in minor units through the one formatter, and a sum past a safe integer as it is', () => {
    expect(figure('90000', 'minor', 'UGX')).toBe('UGX 90,000');
    expect(figure('-1', 'minor', 'UGX')).toBe('-UGX 1');
    expect(figure('123456789012345678901', 'minor', 'UGX')).toBe('UGX 123,456,789,012,345,678,901');
    expect(figure('1.5', 'qty', 'UGX')).toBe('1.5');
    expect(figure(undefined, 'rows', 'UGX')).toBe('0');
  });
});

describe('words', () => {
  it('names each reason plainly and places a row by file and line', () => {
    expect(reasonWords('excluded')).toBe('Left out');
    expect(reasonWords('overridden')).toBe('Warning accepted');
    expect(reasonWords('policy')).toBe('Your decision');
    expect(itemPlace({ file: 'fab.csv', line: 4 })).toBe('fab.csv, line 4');
    expect(itemPlace({ file: '-', line: 0 })).toBe('The whole import');
  });

  it('says where each import stands', () => {
    expect(runState({ status: 'committed', version: 1, signable: true })).toBe('Ready to sign');
    expect(runState({ status: 'committed', version: 1, signable: false })).toBe('A difference is not explained');
    expect(runState({ status: 'committed' })).toBe('Being checked');
    expect(runState({ status: 'signed_off', signed_at: '2026-10-02T09:00:00Z' })).toBe('Signed on 2 Oct 2026');
  });

  it('names the first thing missing before a sign-off, and nothing once all is done', () => {
    const r = { signable: true, sections: [{ key: 'counts', title: 'Rows and records' }, { key: 'ledger', title: 'Journals and trial balance' }] } as Reconciliation;
    const today = '2026-10-02';
    expect(signOffProblem({ ...r, signable: false }, new Set(), '', '', today)).toMatch(/cannot be signed yet/);
    expect(signOffProblem(r, new Set(['counts']), 'A', today, today)).toMatch(/Still to tick: Journals and trial balance/);
    expect(signOffProblem(r, new Set(['counts', 'ledger']), ' ', today, today)).toBe('Type your name.');
    expect(signOffProblem(r, new Set(['counts', 'ledger']), 'Fabricated Signer', '2026-10-01', today)).toBe("Type today's date, 2 Oct 2026.");
    expect(signOffProblem(r, new Set(['counts', 'ledger']), 'Fabricated Signer', today, today)).toBeNull();
  });
});

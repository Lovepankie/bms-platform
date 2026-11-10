import { describe, expect, it } from 'vitest';
import type { DryRun, ImportIssue } from '../../../api/imports';
import {
  IMPORT_ROUTE_PERMISSION,
  bulkWays,
  codeTitle,
  currentStage,
  groupByCode,
  holding,
  mayOpen,
  nextStep,
  quantity,
  stepLine,
  stepStates,
} from './pipeline';

// The words and the order of the import screens (issue #141): the stage progress of a run, what to do next, the problems grouped
// by kind and the permission each route declares. Fabricated data only.

const issue = (code: string, severity: string, extra: Partial<ImportIssue> = {}): ImportIssue => ({ id: `${code}-${Math.random()}`, code, severity, line: 2, version: 1, ...extra });
const dry = (extra: Partial<DryRun> = {}): DryRun => ({ at: '2026-11-09T08:00:00Z', current: true, preview_matches: true, ...extra });

describe('stage progress', () => {
  it('marks the steps before the run’s own done, its own now and the rest later', () => {
    expect(stepStates('draft')).toEqual(['now', 'later', 'later', 'later', 'later', 'later', 'later']);
    expect(stepStates('in_review')).toEqual(['done', 'done', 'now', 'later', 'later', 'later', 'later']);
    expect(stepStates('committing')).toEqual(['done', 'done', 'done', 'done', 'now', 'later', 'later']);
    expect(stepStates('signed_off')).toEqual(['done', 'done', 'done', 'done', 'done', 'done', 'now']);
  });

  it('has every step done once the copies are deleted, and none for a run stopped for good', () => {
    expect(stepStates('purged').every((s) => s === 'done')).toBe(true);
    expect(stepStates('cancelled').every((s) => s === 'later')).toBe(true);
  });

  it('says the step in words', () => {
    expect(stepLine('draft')).toBe('Step 1 of 7');
    expect(stepLine('ready')).toBe('Step 4 of 7');
    expect(stepLine('purged')).toBe('Finished');
    expect(stepLine('cancelled')).toBe('Stopped for good');
  });

  it('reads the operator’s stage of spec section 2 from the metrics: the furthest one started', () => {
    expect(currentStage(undefined)).toBeNull();
    expect(
      currentStage({
        stages: [
          { number: 1, key: 'discovery', started_at: '2026-11-01T08:00:00Z', ended_at: '2026-11-02T08:00:00Z' },
          { number: 5, key: 'dry_run', started_at: '2026-11-09T08:00:00Z' },
          { number: 6, key: 'cutover' },
        ],
      }),
    ).toEqual({ number: 5, words: 'Trial run' });
  });
});

describe('the next step', () => {
  it('goes files, check, fix, try, ready, approval in that order', () => {
    expect(nextStep({ status: 'draft' }, 0, 0, null).kind).toBe('upload');
    expect(nextStep({ status: 'uploaded' }, 1, 0, null).kind).toBe('check');
    expect(nextStep({ status: 'in_review' }, 1, 3, null)).toEqual({ kind: 'fix', open: 3 });
    expect(nextStep({ status: 'in_review' }, 1, 0, null).kind).toBe('try');
    expect(nextStep({ status: 'in_review' }, 1, 0, dry({ current: false })).kind).toBe('try');
    expect(nextStep({ status: 'in_review' }, 1, 0, dry({ preview_matches: false })).kind).toBe('try');
    expect(nextStep({ status: 'in_review' }, 1, 0, dry({ failure: 'batch 2 failed' })).kind).toBe('try');
    expect(nextStep({ status: 'in_review' }, 1, 0, dry()).kind).toBe('ready');
    expect(nextStep({ status: 'ready', commit_approved: false }, 1, 0, dry()).kind).toBe('approval');
    expect(nextStep({ status: 'ready', commit_approved: true }, 1, 0, dry()).kind).toBe('wait');
  });

  it('after the data is in: sign, then wait for the deletion, then the certificate', () => {
    expect(nextStep({ status: 'committing' }, 1, 0, null).kind).toBe('wait');
    expect(nextStep({ status: 'committed' }, 1, 0, null).kind).toBe('sign');
    expect(nextStep({ status: 'signed_off' }, 1, 0, null).kind).toBe('wait');
    expect(nextStep({ status: 'purged' }, 1, 0, null).kind).toBe('certificate');
  });
});

describe('problems by kind', () => {
  it('puts the kinds that must be fixed first, then the biggest', () => {
    const groups = groupByCode([
      issue('NEGATIVE_BALANCE', 'warning'),
      issue('NEGATIVE_BALANCE', 'warning'),
      issue('NEGATIVE_BALANCE', 'warning'),
      issue('TYPE_INVALID', 'blocking'),
      issue('NAME_MISSING', 'blocking'),
      issue('NAME_MISSING', 'blocking'),
    ]);
    expect(groups.map((g) => [g.code, g.issues.length])).toEqual([
      ['NAME_MISSING', 2],
      ['TYPE_INVALID', 1],
      ['NEGATIVE_BALANCE', 3],
    ]);
  });

  it('names each kind in plain words, an unknown code as its words, without a dash', () => {
    expect(codeTitle('TYPE_INVALID')).toBe('Not the right kind of value');
    expect(codeTitle('SOME_NEW_CODE')).toBe('Some new code');
    const dashes = new RegExp(`[-${String.fromCharCode(0x2013)}${String.fromCharCode(0x2014)}]`);
    for (const code of ['REQUIRED_MISSING', 'NEGATIVE_BALANCE', 'DATE_AFTER_CUTOVER', 'X_Y']) expect(codeTitle(code)).not.toMatch(dashes);
  });

  it('counts only blocking problems and warnings as holding the import', () => {
    expect(holding([issue('A', 'blocking'), issue('B', 'warning'), issue('C', 'info')])).toBe(2);
  });

  it('offers accepting as it is for warnings only, and the suggestion only when every line has one', () => {
    const warnings = { code: 'NEGATIVE_BALANCE', severity: 'warning', issues: [issue('NEGATIVE_BALANCE', 'warning')] };
    const blocking = { code: 'MONEY_FRACTION', severity: 'blocking', issues: [issue('MONEY_FRACTION', 'blocking', { suggestion: { value: '1501' } as unknown as ImportIssue['suggestion'] })] };
    expect(bulkWays(warnings).map((w) => w.value)).toEqual(['exclude_row', 'override', 'ask_customer']);
    expect(bulkWays(blocking).map((w) => w.value)).toEqual(['accept_suggestion', 'exclude_row', 'ask_customer']);
  });
});

describe('route permissions', () => {
  it('declares one permission per import route, the raw row’s apart', () => {
    expect(IMPORT_ROUTE_PERMISSION['/staff/setup/imports/runs']).toBe('core.imports.manage');
    expect(IMPORT_ROUTE_PERMISSION['/staff/setup/imports']).toBe('core.imports.sign_off');
    expect(IMPORT_ROUTE_PERMISSION['/platform/imports']).toBe('platform.tenants.read');
    expect(mayOpen({ permissions: ['core.imports.manage'] }, '/staff/setup/imports/runs/$runId/problems')).toBe(true);
    expect(mayOpen({ permissions: ['core.imports.sign_off'] }, '/staff/setup/imports/runs')).toBe(false);
  });

  it('groups quantities by thousands and never rounds', () => {
    expect(quantity('1234567.125')).toBe('1,234,567.125');
    expect(quantity(-1500)).toBe('-1,500');
  });
});

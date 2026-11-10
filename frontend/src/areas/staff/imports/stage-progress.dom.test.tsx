// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { StageProgress } from './stage-progress';

// The stage progress of an import (issue #141): seven steps in order, each saying in words whether it is done, the step the run is
// on, or later, so the state never rests on colour; the step it is on is the list's current step.

afterEach(() => cleanup());

const rows = () => within(screen.getByRole('list')).getAllByRole('listitem').map((li) => li.textContent);

describe('stage progress', () => {
  it('shows a run being checked: two steps done, the third now, the rest later', () => {
    render(<StageProgress status="in_review" />);
    expect(screen.getByRole('heading', { name: 'Step 3 of 7' })).toBeTruthy();
    expect(rows()).toEqual([
      'DoneUpload your files',
      'DoneCheck the files',
      'NowFix the problems and try it',
      'LaterA second person approves',
      'LaterBring the data in',
      'LaterCompare and sign',
      'LaterDelete the copies',
    ]);
    const now = screen.getByText('Fix the problems and try it').closest('li') as HTMLElement;
    expect(now.getAttribute('aria-current')).toBe('step');
    expect(document.querySelectorAll('[aria-current="step"]')).toHaveLength(1);
  });

  it('shows a run being brought in on step 5', () => {
    render(<StageProgress status="committing" />);
    expect(screen.getByRole('heading', { name: 'Step 5 of 7' })).toBeTruthy();
    expect(rows()[4]).toBe('NowBring the data in');
  });

  it('shows every step done once the copies are deleted, with no current step', () => {
    render(<StageProgress status="purged" />);
    expect(screen.getByRole('heading', { name: 'Finished' })).toBeTruthy();
    expect(rows().every((r) => r?.startsWith('Done'))).toBe(true);
    expect(document.querySelector('[aria-current="step"]')).toBeNull();
  });
});

// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import { IosSteps } from './InstallPrompt';
import { addButton, addToHomeScreen, safariTab, shareButton } from './ios-pictures';

// The pictures beside the iPhone install steps (issue #448): one drawn image per step, labelled with a short description of the
// drawing, small, and painted from the theme tokens only. The drawings have words of their own (Add to Home Screen, View More),
// so every check on the steps' words reads the step with its picture taken out.

afterEach(cleanup);

/** The words of each step, with the picture (and the words drawn in it) removed. */
function wordsOfSteps() {
  return within(screen.getByTestId('ios-install-steps'))
    .getAllByRole('listitem')
    .map((step) => {
      const copy = step.cloneNode(true) as HTMLElement;
      copy.querySelectorAll('svg').forEach((svg) => svg.remove());
      return copy.textContent ?? '';
    });
}

const STEP_WORDS = [
  ['Safari', 'normal tab', 'Private'],
  ['Share', 'square with an arrow', 'tap the address bar', '⋯', 'On an iPad, Share is at the top right.'],
  ['Add to Home Screen', 'scroll down, or tap View More'],
  ['Tap Add', 'Then open BMS'],
];

describe('the iPhone install steps with pictures', () => {
  it('renders four images, one beside each step, each a short description of its drawing', () => {
    render(<IosSteps />);
    const steps = within(screen.getByTestId('ios-install-steps')).getAllByRole('listitem');
    expect(steps).toHaveLength(4);
    const images = screen.getAllByRole('img');
    expect(images).toHaveLength(4);
    const labels = images.map((img) => img.getAttribute('aria-label') ?? '');
    labels.forEach((label) => expect(label).toMatch(/^Picture: /));
    expect(labels[0]).toContain('Safari');
    expect(labels[1]).toContain('Share');
    expect(labels[1]).toContain('On an iPad, Share is at the top right.');
    expect(labels[2]).toContain('Add to Home Screen');
    expect(labels[2]).toContain('View More');
    expect(labels[3]).toContain('Add');
    steps.forEach((step, i) => expect(within(step).getByRole('img')).toBe(images[i]));
  });

  it('keeps the words of every step beside its picture, not only inside it', () => {
    render(<IosSteps />);
    const words = wordsOfSteps();
    STEP_WORDS.forEach((expected, i) => {
      for (const phrase of expected) expect(words[i]).toContain(phrase);
    });
  });

  it('has no step words left once the words are gone and only the pictures stay', () => {
    render(<IosSteps />);
    screen
      .getByTestId('ios-install-steps')
      .querySelectorAll('.ios-step-text')
      .forEach((span) => span.remove());
    expect(screen.getAllByRole('img')).toHaveLength(4);
    wordsOfSteps().forEach((words) => expect(words.trim()).toBe(''));
  });

  it('keeps each picture under 2 KB and free of fixed colours', () => {
    for (const picture of [safariTab, shareButton, addToHomeScreen, addButton]) {
      const markup = renderToStaticMarkup(picture);
      expect(markup.length).toBeLessThan(2048);
      expect(markup).not.toMatch(/fill="#|stroke="#|style=|<image|href=/);
    }
  });
});

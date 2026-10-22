import { describe, expect, it } from 'vitest';
import { MESSAGE_MAX, messageProblem, pageBeforeFeedback, rememberPage, screenshotProblem } from './feedback-state';

// The rules of the feedback form (issue #447, ADR-065), said in plain words before anything is sent. Fabricated values only.

describe('the message', () => {
  it('is required, and blank is not a message', () => {
    expect(messageProblem('')).toBe('Write your message.');
    expect(messageProblem('   \n  ')).toBe('Write your message.');
  });

  it('takes up to 2000 characters, counted without the spaces around it', () => {
    expect(messageProblem('x'.repeat(MESSAGE_MAX))).toBeNull();
    expect(messageProblem(`  ${'x'.repeat(MESSAGE_MAX)}  `)).toBeNull();
    expect(messageProblem('x'.repeat(MESSAGE_MAX + 1))).toBe('Your message is 2001 characters. Keep it to 2000.');
  });
});

describe('the screenshot', () => {
  it('is optional', () => {
    expect(screenshotProblem(null)).toBeNull();
  });

  it('is a PNG or JPEG picture', () => {
    expect(screenshotProblem({ size: 1000, type: 'image/png' })).toBeNull();
    expect(screenshotProblem({ size: 1000, type: 'image/jpeg' })).toBeNull();
    expect(screenshotProblem({ size: 1000, type: 'image/webp' })).toBe('Choose a picture: a PNG or JPEG.');
    expect(screenshotProblem({ size: 1000, type: 'application/pdf' })).toBe('Choose a picture: a PNG or JPEG.');
  });

  it('is at most 2 MB', () => {
    expect(screenshotProblem({ size: 2 * 1024 * 1024, type: 'image/png' })).toBeNull();
    expect(screenshotProblem({ size: 2 * 1024 * 1024 + 1, type: 'image/png' })).toBe('This picture is larger than 2 MB. Choose a smaller one.');
  });
});

describe('the page the person came from', () => {
  it('is the last staff page before Feedback, never Feedback itself', () => {
    rememberPage('/staff/retail/sale');
    rememberPage('/staff/feedback');
    expect(pageBeforeFeedback()).toBe('/staff/retail/sale');
    rememberPage('/platform/feedback');
    expect(pageBeforeFeedback()).toBe('/staff/retail/sale');
  });
});

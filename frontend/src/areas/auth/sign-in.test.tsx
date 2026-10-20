import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { router as appRouter } from '../../app/router';
import { SignIn } from './sign-in';

// The staff sign-in page's first screen (FR-IAM-04, FR-IAM-15). An owner read "the password you set from your invitation" as "I never
// set a password", so the words say what to do in both cases. Markup only (the test setup has no DOM).

const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/sign-in'] }) });
const page = (node: ReactElement) => renderToStaticMarkup(<RouterContextProvider router={router}>{node}</RouterContextProvider>);

describe('the staff sign-in page', () => {
  const html = page(<SignIn />);

  it('says what to type, and what to do the first time', () => {
    expect(html).toContain('<h1 tabindex="-1">Staff sign-in</h1>');
    expect(html).toContain('Sign in with your email or phone and your password. First time here? Open the link you were sent to choose a password.');
  });

  it('no longer tells an owner to use a password they may think they never set', () => {
    expect(html).not.toContain('the password you set from your invitation');
  });

  it('has the Forgot password link, to the page that asks for it', () => {
    expect(html).toMatch(/<a [^>]*href="\/forgot-password"[^>]*>Forgot password\?<\/a>/);
  });

  it('keeps the two boxes tied to their labels', () => {
    expect(html).toContain('<label for="login">Email or phone</label>');
    expect(html).toContain('<label for="password">Password</label>');
  });
});

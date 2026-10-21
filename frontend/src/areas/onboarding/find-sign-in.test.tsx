import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { router as appRouter } from '../../app/router';
import { FindSignIn, SENT } from './find-sign-in';

// The platform home's way for staff to find their business's sign-in page (issue #383). Markup only (the test setup has no DOM).

const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/find-sign-in'] }) });
const page = (node: ReactElement) => renderToStaticMarkup(<RouterContextProvider router={router}>{node}</RouterContextProvider>);

describe('find your sign-in page', () => {
  const html = page(<FindSignIn />);

  it('asks for one thing, with the label tied to the box', () => {
    expect(html).toContain('<h1 tabindex="-1">Find your sign-in page</h1>');
    expect(html).toContain('<label for="find-email">Your work email</label>');
    expect(html).toContain('id="find-email"');
    expect(html).toContain('autoComplete="email"');
    expect(html).toContain('Email me the link');
  });

  it('explains in plain words that staff sign in on their own business page', () => {
    expect(html).toContain('Staff sign in on their own business page');
  });

  it('shows the trail back to the home page', () => {
    expect(html).toContain('aria-label="Breadcrumb"');
    expect(html).toContain('href="/"');
    expect(html).toContain('aria-current="page"');
  });

  it('has the anchors of its tour and offers it', () => {
    for (const anchor of ['find-email', 'find-send', 'find-apply']) expect(html).toContain(`data-tour="${anchor}"`);
    expect(html).toContain('Show me this page');
  });

  it('leads owners to the application', () => {
    expect(html).toContain('href="/sign-up"');
  });

  it('promises nothing about whether an address was found, once sent', () => {
    const sent = page(<FindSignIn initial="sent" />);
    expect(sent).toContain('Check your email');
    expect(sent).toContain(SENT);
    expect(sent).toContain('Back to the home page');
    expect(sent).not.toContain('find-email');
    expect(sent).not.toMatch(/no account|not found|unknown|does not exist|no business/i);
  });

  it('uses no em or en dash', () => {
    expect(html + SENT).not.toMatch(/[\u2013\u2014]/);
  });
});

import type { Tour } from './model';

// Page tours of the public pages, which have no signed-in user (issue #383). They start only from the page's own "Show me this page"
// button and save nothing. tours-public.test.tsx renders each page and fails if an anchor is missing.

export const findSignInPage: Tour = {
  id: 'page-find-sign-in',
  version: 1,
  title: 'This page: find your sign-in page',
  audience: () => true,
  autoStart: false,
  page: '/find-sign-in',
  steps: [
    {
      id: 'email',
      target: 'find-email',
      title: 'Type your work email',
      body: 'Use the email your business has for you. It is the one you were invited with.',
    },
    {
      id: 'send',
      target: 'find-send',
      title: 'Tap to get the link',
      body: 'We email you the sign-in page of your business. The page looks the same whether or not we found you, so check your inbox and your spam folder.',
    },
    {
      id: 'apply',
      target: 'find-apply',
      title: 'Owner of a new business?',
      body: 'Tap Apply to start. Staff of a business do not need to apply: your owner adds you.',
    },
  ],
};

export const PUBLIC_TOURS: readonly Tour[] = [findSignInPage];

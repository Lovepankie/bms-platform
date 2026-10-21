import { api, problemOf } from './client';

// "Staff of a business? Find your sign-in page" (issue #383): the platform home page asks for a work email and the server emails the
// sign-in page of each business where that address is an active staff user. The answer is the same words for every address, so the page
// can only repeat them and never says whether anything was sent.

export class FindSignInError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

export async function findSignInPage(email: string): Promise<string> {
  const result = await api.POST('/api/v1/platform/find-sign-in', { body: { email } });
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    const message =
      problem.code === 'rate_limited'
        ? 'Too many tries from here. Wait a few minutes and try again.'
        : (problem.detail ?? 'The request could not be sent. Try again.');
    throw new FindSignInError(message, result.response.status, problem.code);
  }
  return result.data.message ?? 'If this email is used by staff of a business, we have sent the sign-in page to it.';
}

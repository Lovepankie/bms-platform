import { api, problemOf } from './client';
import type { components } from './schema';

// Ask BMS (issue #20, ADR-096; chapter 7 section 7.11.28): whether it is on for the business, and a question answered from the
// help articles. The generated schema marks every field optional; these are the shapes the server always sends
// (core/help/internal/AskApi.java).

export interface AskArticle {
  id: string;
  title: string;
}

export interface AskAnswer {
  /** model: phrased from `cited`; cache: the same answer as before; articles: none phrased, `articles` listed; other_role; no_match */
  source: 'model' | 'cache' | 'articles' | 'other_role' | 'no_match';
  answer: string | null;
  cited: AskArticle | null;
  articles: AskArticle[];
  revision: string;
}

export interface AskStatus {
  enabled: boolean;
  revision: string;
}

// Keeps the generated types honest: a change to the contract that drops one of these breaks this line, not a screen.
export type GeneratedAskAnswer = components['schemas']['AskAnswer'];
export type GeneratedAskStatus = components['schemas']['AskStatus'];

export class AskError extends Error {
  constructor(
    message: string,
    readonly code?: string,
  ) {
    super(message);
  }
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    throw new AskError(problem.code === 'ask_bms_off' ? 'Ask BMS is not on for your business yet.' : fallback, problem.code);
  }
  return result.data;
}

export const askBms = {
  async status(): Promise<AskStatus> {
    return unwrap(await api.GET('/api/v1/help/ask'), 'Could not reach Ask BMS.') as AskStatus;
  },

  async ask(question: string): Promise<AskAnswer> {
    const locale = typeof navigator === 'undefined' ? 'en' : navigator.language;
    return unwrap(await api.POST('/api/v1/help/ask', { body: { question, locale } }), 'Could not ask now. The articles below still answer.') as AskAnswer;
  },
};

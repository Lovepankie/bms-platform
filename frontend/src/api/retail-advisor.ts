import { api } from './client';
import type { components } from './schema';
import { advisorUnwrap, type AdvisorBrief, type AdvisorSettings, type AdvisorSettingsChange } from './advisor';

// The retail AI brief client (issue #422; chapter 7 section 7.11.24a): today's shop brief and the assistant's settings for a
// shop or wholesale business. The shapes are the lending assistant's (api/advisor.ts): one switch and one pair of daily caps
// serve both, and the card draws either brief the same way. The server sends only text our code wrote.

export type GeneratedRetailAdvisorBrief = components['schemas']['RetailAdvisorBrief'];
export type GeneratedRetailAdvisorSettings = components['schemas']['RetailAdvisorSettings'];

const ifMatch = (version: number) => `"${version}"`;

export const retailAdvisor = {
  /** Null when the assistant is off for the business (204): the card is not drawn at all. */
  async brief(): Promise<AdvisorBrief | null> {
    const result = await api.GET('/api/v1/retail/advisor/brief');
    if (result.response.status === 204) return null;
    return advisorUnwrap(result, 'Could not load the summary.') as AdvisorBrief;
  },

  async settings(): Promise<AdvisorSettings> {
    return advisorUnwrap(await api.GET('/api/v1/retail/advisor/settings'), 'Could not load the assistant settings.') as AdvisorSettings;
  },

  async changeSettings(version: number, change: AdvisorSettingsChange): Promise<AdvisorSettings> {
    return advisorUnwrap(
      await api.PUT('/api/v1/retail/advisor/settings', { params: { header: { 'If-Match': ifMatch(version) } }, body: change }),
      'Could not save the assistant settings.',
    ) as AdvisorSettings;
  },
};

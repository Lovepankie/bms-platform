import type { FeedbackKind, FeedbackStatus } from '../../../api/feedback';

// The rules of the feedback form (issue #447, ADR-065), apart from the screen so they are tested on their own. The server checks
// the same rules; these say it at once and in plain words.

export const MESSAGE_MAX = 2000;
export const SCREENSHOT_MAX_BYTES = 2 * 1024 * 1024;
export const SCREENSHOT_TYPES = 'image/png,image/jpeg';

export const KINDS: readonly { value: FeedbackKind; label: string; hint: string }[] = [
  { value: 'idea', label: 'An idea', hint: 'Something that would make BMS better for you.' },
  { value: 'problem', label: 'A problem', hint: 'Something that did not work or looked wrong.' },
  { value: 'question', label: 'A question', hint: 'Something you want to ask us.' },
];

export const KIND_WORDS: Record<string, string> = { idea: 'Idea', problem: 'Problem', question: 'Question' };

export const STATUS_WORDS: Record<FeedbackStatus, string> = {
  new: 'Sent, not read yet',
  seen: 'Read by the BMS team',
  done: 'Done',
};

/** Why the message cannot be sent yet, or null. */
export function messageProblem(message: string): string | null {
  const text = message.trim();
  if (text.length === 0) return 'Write your message.';
  if (text.length > MESSAGE_MAX) return `Your message is ${text.length} characters. Keep it to ${MESSAGE_MAX}.`;
  return null;
}

/** Why a chosen file cannot be the screenshot, or null. */
export function screenshotProblem(file: { size: number; type: string } | null): string | null {
  if (!file) return null;
  if (!['image/png', 'image/jpeg'].includes(file.type)) return 'Choose a picture: a PNG or JPEG.';
  if (file.size > SCREENSHOT_MAX_BYTES) return 'This picture is larger than 2 MB. Choose a smaller one.';
  return null;
}

// The page a person was on before they opened Feedback: the staff layout remembers every page it shows, except Feedback itself,
// so the form can send where the person came from. Kept in memory only, for this tab.
let lastPage: string | null = null;

export function rememberPage(path: string) {
  if (path.startsWith('/staff') && !path.startsWith('/staff/feedback')) lastPage = path;
}

export function pageBeforeFeedback(): string | null {
  return lastPage;
}

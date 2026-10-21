// When the turn-on card asks (issue #412, ADR-057). The card offers notifications the first time someone opens the installed app,
// or right after they accept the install. "Not now" asks again after a few days, at most a few times; turning on, or the browser
// saying no, ends the asking on this device. What was answered is remembered on this device only, like the install offer: the
// question is about this device. The Notifications and app page always has the switch, whatever was answered here.

export const ASK_KEY = 'bms.push.ask';
/** "Not now" is asked again after this many days. */
export const REASK_DAYS = 3;
/** The card asks at most this many times on a device. */
export const MAX_ASKS = 3;

const DAY_MS = 24 * 3600 * 1000;

export interface AskRecord {
  /** How many times the person said Not now. */
  notNow: number;
  /** When they last said it (milliseconds), 0 when never. */
  at: number;
  /** Turned on, or blocked in the browser: never asked again on this device. */
  done: boolean;
}

const FRESH: AskRecord = { notNow: 0, at: 0, done: false };

export function readAsk(): AskRecord {
  try {
    const raw = localStorage.getItem(ASK_KEY);
    if (!raw) return FRESH;
    const parsed = JSON.parse(raw) as Partial<AskRecord>;
    return {
      notNow: Number.isFinite(parsed.notNow) ? Number(parsed.notNow) : 0,
      at: Number.isFinite(parsed.at) ? Number(parsed.at) : 0,
      done: parsed.done === true,
    };
  } catch {
    // Private mode and blocked storage throw: the card simply asks as if for the first time.
    return FRESH;
  }
}

function write(record: AskRecord): AskRecord {
  try {
    localStorage.setItem(ASK_KEY, JSON.stringify(record));
  } catch {
    // Not being able to remember the answer is no reason to keep asking now; the caller hides the card.
  }
  return record;
}

/** Whether the card may ask now. */
export function dueToAsk(record: AskRecord, now: number): boolean {
  if (record.done || record.notNow >= MAX_ASKS) return false;
  return record.notNow === 0 || now - record.at >= REASK_DAYS * DAY_MS;
}

export function recordNotNow(now: number): AskRecord {
  const current = readAsk();
  return write({ ...current, notNow: current.notNow + 1, at: now });
}

export function recordDone(): AskRecord {
  return write({ ...readAsk(), done: true });
}

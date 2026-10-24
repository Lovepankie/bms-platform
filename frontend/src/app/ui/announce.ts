// The toast to sheet relay (#520): an open Sheet hides the page from a screen reader, so a toast that appears after it opened
// tells the sheet here, and the sheet says it again in its own live region. Its own file so a toast never loads the Radix
// Dialog that the Sheet is built on.

type Hear = (message: string, from: Element | null, urgent: boolean) => void;

const listeners = new Set<Hear>();

/** Called by a toast as it appears: an open sheet repeats the message when the toast itself is hidden from a screen reader. */
export function announceToSheet(message: string, from: Element | null, urgent = false): void {
  listeners.forEach((listener) => listener(message, from, urgent));
}

/** An open sheet listens while it is open; the returned function stops it. */
export function hearToasts(listener: Hear): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

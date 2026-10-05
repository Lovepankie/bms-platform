import { useRef } from 'react';

/** A fresh key for one attempt at a money-moving write (chapter 7 section 7.8). */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = bytes.map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * One key per form open: generated on first render and kept for every re-render and every retry,
 * so a double tap or a retry after a lost response posts once. Mount a new form (a new `key` prop)
 * for the next record.
 */
export function useIdempotencyKey(): string {
  const ref = useRef<string | null>(null);
  if (ref.current === null) ref.current = newIdempotencyKey();
  return ref.current;
}

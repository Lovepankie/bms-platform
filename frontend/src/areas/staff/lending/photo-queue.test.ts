import { describe, expect, it } from 'vitest';
import { percentSent, queueReducer, type QueueItem } from './photo-queue';

const prepared = { blob: new Blob([new Uint8Array(1000)], { type: 'image/jpeg' }), fileName: 'a.jpg' };
const start = (): QueueItem[] => queueReducer([], { type: 'add', id: 1, name: 'a.jpg' });

describe('queueReducer', () => {
  it('goes preparing, ready, sending, done and keeps the prepared picture', () => {
    let s = start();
    expect(s[0]!.state.phase).toBe('preparing');
    s = queueReducer(s, { type: 'ready', id: 1, prepared });
    s = queueReducer(s, { type: 'sending', id: 1 });
    s = queueReducer(s, { type: 'progress', id: 1, sent: 500, total: 1000 });
    expect(s[0]!.state).toMatchObject({ phase: 'sending', sent: 500, total: 1000 });
    s = queueReducer(s, { type: 'done', id: 1 });
    expect(s[0]!.state).toMatchObject({ phase: 'done', prepared });
  });
  it('keeps the prepared picture after a failed send, so a retry needs no new resize', () => {
    let s = queueReducer(start(), { type: 'ready', id: 1, prepared });
    s = queueReducer(s, { type: 'sending', id: 1 });
    s = queueReducer(s, { type: 'failed', id: 1, message: 'The upload did not reach the server.' });
    expect(s[0]!.state).toEqual({ phase: 'failed', prepared, message: 'The upload did not reach the server.' });
    expect(queueReducer(s, { type: 'sending', id: 1 })[0]!.state.phase).toBe('sending');
  });
  it('holds nothing for a refused file, ignores progress when not sending, and removes by id', () => {
    const s = queueReducer(start(), { type: 'rejected', id: 1, message: 'No.' });
    expect(s[0]!.state).toEqual({ phase: 'failed', prepared: null, message: 'No.' });
    expect(queueReducer(s, { type: 'progress', id: 1, sent: 1, total: 2 })).toEqual(s);
    expect(queueReducer(s, { type: 'remove', id: 1 })).toEqual([]);
  });
});

describe('percentSent', () => {
  it('never shows 100 before the server answers', () => {
    expect(percentSent(0, 0)).toBe(0);
    expect(percentSent(500, 1000)).toBe(50);
    expect(percentSent(1000, 1000)).toBe(99);
  });
});

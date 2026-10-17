// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FormError } from '../../../api/lending-forms';
import type { Codec } from './image-prep';
import { PhotoQueueControl, usePhotoQueue, type Sender } from './photo-queue';

// The photo control as a person uses it (FR-MEM-09, FR-COL-01): a wrong kind or a huge file is refused in words and never sent, a big
// photo is shrunk once and the shrunk picture is what goes, progress shows, a failed send can be sent again without choosing or
// shrinking the picture again, and a second tap on "Send again" while one is on its way sends nothing more.

afterEach(cleanup);

const small = { encode: vi.fn(async () => new Blob([new Uint8Array(300_000)], { type: 'image/jpeg' })) };
const codec = (): Codec => ({ decode: async () => ({ width: 4000, height: 3000, close: () => undefined, encode: small.encode }) });

function Harness({ sender }: { sender: Sender<unknown> }) {
  const queue = usePhotoQueue(codec());
  return <PhotoQueueControl id="t" label="Photo of the ID" queue={queue} sender={sender} max={2} />;
}

const pick = (name: string, type: string, size: number) => {
  const file = new File([new Uint8Array(size)], name, { type });
  const input = document.getElementById('t-file') as HTMLInputElement;
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  fireEvent.change(input);
};

describe('the photo control', () => {
  it('opens the camera for "Take a photo" and the file picker for "Choose a file"', () => {
    render(<Harness sender={async () => ({})} />);
    expect(document.getElementById('t-camera')?.getAttribute('capture')).toBe('environment');
    expect(document.getElementById('t-camera')?.getAttribute('accept')).toBe('image/*');
    expect(document.getElementById('t-file')?.getAttribute('accept')).toContain('application/pdf');
    expect(screen.getByRole('button', { name: 'Take a photo' })).toBeTruthy();
  });

  it('refuses a wrong kind and an oversize PDF in words, and sends nothing', async () => {
    const sender = vi.fn(async () => ({}));
    render(<Harness sender={sender} />);
    pick('notes.docx', 'application/msword', 1000);
    expect((await screen.findByRole('alert')).textContent).toMatch(/cannot be used/);
    cleanup();
    render(<Harness sender={sender} />);
    pick('big.pdf', 'application/pdf', 6 * 1024 * 1024);
    expect((await screen.findByRole('alert')).textContent).toMatch(/5 MB/);
    expect(sender).not.toHaveBeenCalled();
  });

  it('shrinks a big photo once and sends the shrunk picture', async () => {
    const sent: number[] = [];
    render(<Harness sender={async (p, o) => { sent.push(p.blob.size); o.onProgress(p.blob.size, p.blob.size); return {}; }} />);
    pick('IMG_1.jpg', 'image/jpeg', 8 * 1024 * 1024);
    await waitFor(() => expect(screen.getByText('Saved.')).toBeTruthy());
    expect(sent).toEqual([300_000]);
    expect(small.encode.mock.calls.length).toBeGreaterThan(0);
  });

  it('keeps the prepared picture after a failed send, so "Send again" resends it without shrinking again', async () => {
    small.encode.mockClear();
    let attempts = 0;
    const sender: Sender<unknown> = async () => {
      attempts++;
      if (attempts === 1) throw new FormError('The upload did not reach the server. Check the connection and try again.', 0, 'network');
      return {};
    };
    render(<Harness sender={sender} />);
    pick('IMG_2.jpg', 'image/jpeg', 8 * 1024 * 1024);
    expect((await screen.findByRole('alert')).textContent).toMatch(/did not reach the server/);
    const encodes = small.encode.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: 'Send again' }));
    await waitFor(() => expect(screen.getByText('Saved.')).toBeTruthy());
    expect(attempts).toBe(2);
    expect(small.encode.mock.calls.length).toBe(encodes);
  });

  it('ignores a second tap on "Send again" while the first send is on its way', async () => {
    let attempts = 0;
    let release: () => void = () => undefined;
    const sender: Sender<unknown> = async () => {
      attempts++;
      if (attempts === 1) throw new FormError('The upload did not reach the server.', 0, 'network');
      await new Promise<void>((resolve) => { release = resolve; });
      return {};
    };
    render(<Harness sender={sender} />);
    pick('IMG_3.jpg', 'image/jpeg', 8 * 1024 * 1024);
    const again = await screen.findByRole('button', { name: 'Send again' });
    fireEvent.click(again);
    fireEvent.click(again);
    await waitFor(() => expect(attempts).toBe(2));
    release();
    await waitFor(() => expect(screen.getByText('Saved.')).toBeTruthy());
    expect(attempts).toBe(2);
  });
});

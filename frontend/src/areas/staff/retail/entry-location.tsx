import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { retail, type DeviceLocation, type EntryDistance } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Hint } from '../../../app/ui/Hint';
import { showDate } from './maths';

// Where each sale and usage or damage entry is made (issue #826, ADR-122), as the owner's old app kept it. The phone asks once,
// with a plain reason, and remembers the answer; a no, or a phone that gives no location, never stops an entry. The latest
// position of the last two minutes goes with the entry, also with a sale kept on the phone to send later. Only the owner
// (retail.entry_location.read) ever sees how far from the shop an entry was made, in words, never the position itself.

const ANSWER_KEY = 'bms.entry-location';
/** A position older than this is not sent with an entry. */
export const FRESH_MS = 120_000;

/** The reason the phone gives before the browser asks, in plain words. */
export const ASK_REASON = 'The owner asks the app to note where each sale is made, so a sale can be checked later. Only the owner and the business administrators see it.';

type Answer = 'yes' | 'no' | null;

function storedAnswer(): Answer {
  try {
    const v = localStorage.getItem(ANSWER_KEY);
    return v === 'yes' || v === 'no' ? v : null;
  } catch {
    return null;
  }
}

function remember(answer: 'yes' | 'no') {
  try {
    localStorage.setItem(ANSWER_KEY, answer);
  } catch {
    // A phone that keeps nothing asks again next time; nothing else changes.
  }
}

/** The position as the server takes it, or undefined when the browser gave something out of range. */
export function usable(c: { latitude: number; longitude: number; accuracy: number }): DeviceLocation | undefined {
  const { latitude, longitude, accuracy } = c;
  if (![latitude, longitude, accuracy].every(Number.isFinite)) return undefined;
  if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180 || accuracy < 0 || accuracy > 1_000_000) return undefined;
  return { latitude, longitude, accuracy_m: Math.round(accuracy * 10) / 10 };
}

function geolocation(): Geolocation | undefined {
  return typeof navigator !== 'undefined' && 'geolocation' in navigator ? navigator.geolocation : undefined;
}

export type WhereNow = {
  /** True until this phone has answered once. */
  asking: boolean;
  allow: () => void;
  decline: () => void;
  /** `{ location }` to spread into an entry's request when a recent position is known, otherwise `{}`. */
  now: () => { location?: DeviceLocation };
};

/** This phone's latest position for the next entry, asked for once with {@link ASK_REASON}. */
export function useWhereNow(): WhereNow {
  const geo = geolocation();
  const [answer, setAnswer] = useState<Answer>(storedAnswer);
  const last = useRef<{ fix: DeviceLocation; at: number } | null>(null);
  const take = useCallback(() => {
    geo?.getCurrentPosition(
      (p) => {
        const fix = usable(p.coords);
        if (fix) last.current = { fix, at: Date.now() };
      },
      (e) => {
        // Refused in the phone's settings: never asked again, and entries go on without it.
        if (e.code === e.PERMISSION_DENIED) {
          remember('no');
          setAnswer('no');
        }
      },
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: FRESH_MS },
    );
  }, [geo]);
  useEffect(() => {
    if (answer === 'yes') take();
  }, [answer, take]);
  return {
    asking: geo !== undefined && answer === null,
    allow: () => {
      remember('yes');
      setAnswer('yes');
    },
    decline: () => {
      remember('no');
      setAnswer('no');
    },
    now: () => {
      const l = last.current;
      // A fresh position for the next entry; this one never waits for it.
      if (answer === 'yes') take();
      return l && Date.now() - l.at <= FRESH_MS ? { location: l.fix } : {};
    },
  };
}

/** The one-time question, above the sale and the usage forms; gone once answered. */
export function LocationAsk({ where }: { where: WhereNow }) {
  if (!where.asking) return null;
  return (
    <div role="status" className="alert alert-info" data-testid="location-ask">
      <p>{ASK_REASON}</p>
      <p>Your phone asks you next, and saying no never stops a sale.</p>
      <div className="form-actions">
        <button type="button" onClick={where.allow}>Allow</button>
        <button type="button" className="btn-ghost" onClick={where.decline}>No thanks</button>
      </div>
    </div>
  );
}

/** How far from the shop each of these entries was made, by id; empty unless `enabled` (the owner's permission). */
export function useEntryDistances(source: 'sale' | 'usage', ids: string[], enabled: boolean): Map<string, EntryDistance> {
  const unique = [...new Set(ids.filter((id) => id !== ''))].sort();
  const q = useQuery({
    queryKey: ['retail', 'entry-distances', source, unique],
    queryFn: async () => {
      const chunks: string[][] = [];
      for (let i = 0; i < unique.length; i += 100) chunks.push(unique.slice(i, i + 100));
      return (await Promise.all(chunks.map((c) => retail.entryDistances(source, c)))).flat();
    },
    enabled: enabled && unique.length > 0,
  });
  return new Map((q.data ?? []).map((d) => [d.id ?? '', d]));
}

/** "40 m from the shop, the phone sure to about 12 m", "no location" or "shop location not set". */
export function whereWords(d: EntryDistance): string {
  const words = d.words ?? 'no location';
  return d.state === 'located' && d.accuracy_m !== undefined ? `${words}, the phone sure to about ${d.accuracy_m} m` : words;
}

/** Business set-up: each shop's location, set with "Use where I am now" by the owner standing in the shop. */
export function ShopLocationsCard() {
  const queryClient = useQueryClient();
  const shops = useQuery({ queryKey: ['retail', 'shop-locations'], queryFn: () => retail.shopLocations() });
  const [problem, setProblem] = useState('');
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['retail', 'shop-locations'] });
  const set = useMutation({
    mutationFn: async (branchId: string) => {
      const geo = geolocation();
      if (!geo) throw new Error('This phone cannot tell where it is.');
      const fix = await new Promise<DeviceLocation>((resolve, reject) =>
        geo.getCurrentPosition(
          (p) => {
            const f = usable(p.coords);
            if (f) resolve(f);
            else reject(new Error('The phone gave a location that cannot be right. Try again.'));
          },
          () => reject(new Error('The phone did not give its location. Allow location for this app in the phone settings, then try again.')),
          { enableHighAccuracy: true, timeout: 20_000, maximumAge: 0 },
        ),
      );
      return retail.setShopLocation(branchId, fix);
    },
    onMutate: () => setProblem(''),
    onError: (e) => setProblem(e instanceof Error ? e.message : 'The location was not saved.'),
    onSuccess: refresh,
    meta: { success: 'Shop location saved' },
  });
  const clear = useMutation({
    mutationFn: (branchId: string) => retail.clearShopLocation(branchId),
    onSuccess: refresh,
    meta: { success: 'Shop location cleared' },
  });
  return (
    <fieldset data-testid="shop-locations">
      <legend>Shop locations</legend>
      <Hint>
        Stand inside the shop and tap Use where I am now. Each sale and each used or damaged entry then shows you how far from the shop
        it was made. Only you and the business administrators see it.
      </Hint>
      {shops.isPending && <p className="loading">Loading</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {(shops.data ?? []).map((s) => (
          <li key={s.branch_id} className="rt-card">
            <p>
              <strong>{s.name}</strong>{' '}
              {s.has_location ? <span className="hint">Location saved{s.set_at ? ` on ${showDate(s.set_at.slice(0, 10))}` : ''}</span> : <span className="hint">No location yet</span>}
            </p>
            <p>
              <BusyButton type="button" busy={set.isPending && set.variables === s.branch_id} disabled={set.isPending} onClick={() => set.mutate(s.branch_id ?? '')}>
                Use where I am now
              </BusyButton>{' '}
              {s.has_location && (
                <BusyButton type="button" className="btn-ghost" busy={clear.isPending && clear.variables === s.branch_id} disabled={clear.isPending} onClick={() => clear.mutate(s.branch_id ?? '')}>
                  Clear
                </BusyButton>
              )}
            </p>
          </li>
        ))}
      </ul>
      {problem && <p role="alert">{problem}</p>}
    </fieldset>
  );
}

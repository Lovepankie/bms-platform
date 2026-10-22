import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { type MyReferrals, fetchMyReferrals } from '../../../api/referrals';
import { useShellBrand } from '../../../app/branding';
import { useStaff } from '../context';
import { moneyWords, showReferrals, statusWords, whatsappShare } from './state';

// Referrals (issue #464, ADR-069), under Business set-up: the business's own link to the BMS sign-up, with Copy and Share on
// WhatsApp buttons, and the businesses it referred with what they earned. BMS records the commission; the BMS team pays it
// outside the app and marks it paid. Only the owner and the administrators see this page.

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Your referrals could not be loaded. Try again.');

function When({ iso }: { iso?: string | null }) {
  if (!iso) return null;
  const d = new Date(iso);
  return <>{Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })}</>;
}

export function ShareLink({ link, code, business }: { link: string; code: string; business: string }) {
  const [copied, setCopied] = useState<'yes' | 'selected' | 'failed' | null>(null);
  const field = useRef<HTMLInputElement>(null);

  async function copy() {
    try {
      await navigator.clipboard.writeText(link);
      setCopied('yes');
      return;
    } catch {
      // No clipboard permission (an older phone browser): select the link and try the older copy command.
    }
    const input = field.current;
    if (!input) return setCopied('failed');
    try {
      input.focus();
      input.select();
      input.setSelectionRange(0, link.length);
    } catch {
      return setCopied('failed');
    }
    let done = false;
    try {
      done = typeof document.execCommand === 'function' && document.execCommand('copy');
    } catch {
      done = false;
    }
    setCopied(done ? 'yes' : 'selected');
  }

  return (
    <section className="rt-card stack" aria-labelledby="referral-link-title">
      <h2 id="referral-link-title">Your referral link</h2>
      <p>
        Send it to another business. When they sign up with it, they are listed below as referred by you. Your code is <strong>{code}</strong>: they can also type it
        in the Referred by box when they sign up.
      </p>
      <label htmlFor="referral-link">Referral link</label>
      <input id="referral-link" ref={field} readOnly value={link} onFocus={(e) => e.currentTarget.select()} spellCheck={false} />
      <p className="cluster">
        <button type="button" className="rt-primary" onClick={() => void copy()}>
          Copy
        </button>
        <a className="btn" href={whatsappShare(business, link)} target="_blank" rel="noopener noreferrer">
          Share on WhatsApp
        </a>
      </p>
      {copied === 'yes' && <p role="status" className="alert alert-success">Copied. Paste it into a message.</p>}
      {copied === 'selected' && <p role="status" className="alert alert-info">Your phone would not copy it. The link is selected: press and hold, then choose Copy.</p>}
      {copied === 'failed' && <p role="status" className="alert alert-info">Your phone would not copy it. Press and hold the link above, then choose Copy.</p>}
    </section>
  );
}

export function ReferralList({ data }: { data: MyReferrals }) {
  const items = data.items ?? [];
  return (
    <section className="rt-card stack" aria-labelledby="referral-list-title">
      <h2 id="referral-list-title">Your referrals</h2>
      {items.length === 0 ? (
        <p className="empty-state">No business has signed up with your link yet.</p>
      ) : (
        <>
          <p>
            Earned <strong>{moneyWords(data.totals, 'earned_minor')}</strong>, paid {moneyWords(data.totals, 'paid_minor')}, still to be paid{' '}
            <strong>{moneyWords(data.totals, 'due_minor')}</strong>.
          </p>
          <ul className="stack" aria-label="Businesses you referred">
            {items.map((item, i) => (
              <li key={`${item.referred_name}-${item.joined_at}-${i}`} className="stack">
                <strong>{item.referred_name}</strong>
                <span>
                  {statusWords(item.status)}, joined <When iso={item.joined_at} />
                </span>
                <span className="hint">
                  Earned {moneyWords(item.totals, 'earned_minor')}; paid {moneyWords(item.totals, 'paid_minor')}; to be paid {moneyWords(item.totals, 'due_minor')}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

export function ReferralsPage({ load = fetchMyReferrals }: { load?: () => Promise<MyReferrals> }) {
  const { me } = useStaff();
  const brand = useShellBrand();
  const allowed = showReferrals(me);
  const query = useQuery({ queryKey: ['referrals', 'mine'], queryFn: load, enabled: allowed });
  if (!allowed) {
    return (
      <main className="rt ln">
        <h1>Referrals</h1>
        <p className="empty-state">Only the owner and the administrators of the business see its referrals.</p>
      </main>
    );
  }
  const data = query.data;
  const business = brand.name || 'Our business';
  return (
    <main className="rt ln stack">
      <h1>Referrals</h1>
      <p data-tour="referrals-intro">
        Bring another business to BMS and earn a commission when it pays. BMS keeps the record; the BMS team pays you and marks it paid here.
      </p>
      {query.error && <p role="alert" className="alert alert-danger">{messageOf(query.error)}</p>}
      {!data && !query.error && <p className="loading">Loading</p>}
      <div data-tour="referrals-link">{data && <ShareLink link={data.link ?? ''} code={data.code ?? ''} business={business} />}</div>
      <div data-tour="referrals-list">{data && <ReferralList data={data} />}</div>
    </main>
  );
}

export const Route = createLazyRoute('/staff/setup/referrals')({ component: () => <ReferralsPage /> });

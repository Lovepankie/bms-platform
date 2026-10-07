import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { icons } from '../components/icons';
import { illustrations } from '../components/illustrations';
import { BrandLoader } from '../components/states';
import { BRANDING_QUERY, useShellBrand } from './branding';
import { classifyHost, loadHostConfig } from './hosts';

// The public landing page (#99, #106). On a tenant host its copy, module cards and buttons follow
// the modules the tenant has switched on, read from the public GET /api/v1/branding: a retail only
// tenant is never promised members or loans, nor shown the Member portal button. The platform host
// offers sign-up and operator sign-in (#89); an unknown host shows a short hero.

export type LandingCard = { key: string; icon: keyof typeof icons; title: string; text: string };

export type LandingCopy = { lead: string; cards: LandingCard[]; memberPortal: boolean };

const STAFF_CARD: LandingCard = {
  key: 'staff',
  icon: 'user',
  title: 'Staff',
  text: 'Sign in with the email or phone and the password from your invitation.',
};

/** What the landing page promises, from the tenant's enabled module keys (unknown keys are ignored). */
export function landingCopy(modules: readonly string[]): LandingCopy {
  const retail = modules.includes('retail');
  const lending = modules.includes('lending');
  const cards: LandingCard[] = [];
  if (retail) {
    cards.push({ key: 'retail', icon: 'sale', title: 'Sales and stock', text: 'Record sales, restock and count stock at every branch, with the profit of each day.' });
  }
  if (lending) {
    cards.push({ key: 'lending', icon: 'calendar', title: 'Members and loans', text: 'Member records, loans, schedules and repayments, with approvals built in.' });
  }
  cards.push(STAFF_CARD);
  if (lending) {
    cards.push({ key: 'members', icon: 'shield', title: 'Members', text: 'See your balances, loan schedule and payments in the member portal.' });
  }
  const lead =
    retail && lending
      ? 'Sales, stock, members and loans for your business, in one place.'
      : retail
        ? 'Sales and stock for your business, in one place.'
        : lending
          ? 'Members and loans for your business, in one place.'
          : 'Your business, in one place.';
  return { lead, cards, memberPortal: lending };
}

// The brand on the landing page: the host's logo when it has one, else a wordmark of its name.
function BrandMark() {
  const brand = useShellBrand();
  if (brand.logoSrc) return <img className="brand-logo" src={brand.logoSrc} alt={brand.name} />;
  return (
    <div className="wordmark">
      <span className="wordmark-mark" aria-hidden="true">
        {brand.name.charAt(0)}
      </span>
      <span>{brand.name}</span>
    </div>
  );
}

function Hero({ children }: { children: ReactNode }) {
  return (
    <section className="card landing-hero">
      <BrandMark />
      <span className="landing-art">{illustrations.hero}</span>
      {children}
    </section>
  );
}

/** The tenant landing for a known set of modules; exported for the tests and the style page. */
export function TenantLanding({ modules }: { modules: readonly string[] }) {
  const copy = landingCopy(modules);
  return (
    <main className="landing">
      <Hero>
        <h1>Welcome</h1>
        <p className="lead">{copy.lead}</p>
        <ul className="landing-actions">
          <li>
            <Link to="/sign-in" className="btn btn-primary btn-lg btn-block">
              Staff sign-in
            </Link>
          </li>
          {copy.memberPortal && (
            <li>
              <Link to="/member" className="btn btn-lg btn-block">
                Member portal
              </Link>
            </li>
          )}
        </ul>
        <ul className="landing-trust">
          <li>
            {icons.lock}
            <span>Two-step sign-in for staff</span>
          </li>
          <li>
            {icons.shield}
            <span>Private to your business</span>
          </li>
        </ul>
      </Hero>
      <ul className="landing-modules">
        {copy.cards.map((card) => (
          <li key={card.key} className="card feature-card">
            <span className="icon-chip">{icons[card.icon]}</span>
            <h2>{card.title}</h2>
            <p>{card.text}</p>
          </li>
        ))}
      </ul>
    </main>
  );
}

function TenantLandingLoader() {
  const branding = useQuery(BRANDING_QUERY);
  if (branding.isPending) {
    return (
      <main className="landing">
        <Hero>
          <BrandLoader />
        </Hero>
      </main>
    );
  }
  // Without the branding the page promises no module: staff can still sign in.
  return <TenantLanding modules={branding.data?.modules ?? []} />;
}

export function Landing() {
  // The host decides the area (ADR-018). The Vite dev server's X-Tenant slug counts as a tenant host.
  const hosts = useQuery({ queryKey: ['app-config'], queryFn: loadHostConfig, staleTime: Infinity });
  if (!hosts.data) return null;
  const area =
    import.meta.env.DEV && import.meta.env.VITE_DEV_TENANT ? { kind: 'tenant' as const } : classifyHost(window.location.hostname, hosts.data);
  if (area.kind === 'platform') {
    // The platform host (#89): a new business applies through sign-up; an operator signs in.
    return (
      <main className="landing">
        <Hero>
          <h1>BMS by Rincoltech</h1>
          <p className="lead">Run your shop or lending business from your phone: sales, stock, members and loans.</p>
          <ul className="landing-actions">
            <li>
              <Link to="/sign-up" className="btn btn-primary btn-lg btn-block">
                Apply to start
              </Link>
            </li>
            <li>
              <Link to="/platform/sign-in" className="btn btn-lg btn-block">
                Operator sign-in
              </Link>
            </li>
          </ul>
        </Hero>
        <ul className="landing-modules">
          <li className="card feature-card">
            <span className="icon-chip">{icons.building}</span>
            <h2>New business</h2>
            <p>Apply in a few minutes. We check every application and email you a link to sign in.</p>
          </li>
          <li className="card feature-card">
            <span className="icon-chip">{icons.user}</span>
            <h2>Already a customer</h2>
            <p>Sign in at the address in your activation email.</p>
          </li>
        </ul>
      </main>
    );
  }
  if (area.kind === 'unknown') {
    return (
      <main className="landing">
        <Hero>
          <h1>BMS Platform</h1>
          <p className="lead">No BMS tenant is served at this address. Check the address you were given.</p>
        </Hero>
      </main>
    );
  }
  return <TenantLandingLoader />;
}

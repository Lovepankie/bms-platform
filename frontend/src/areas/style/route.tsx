import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { type ReactNode, useEffect, useRef, useState } from 'react';
import { classifyHost, loadHostConfig } from '../../app/hosts';
import { landingCopy } from '../../app/landing';
import { icons } from '../../components/icons';
import { illustrations, type IllustrationName } from '../../components/illustrations';
import { BrandLoader, EmptyState, SkeletonList, StatusPanel } from '../../components/states';

// The living style page (#106, docs/ui/design-system.md): every shared component and illustration
// on one page, drawn by the same classes the screens use, so a change to the design layer can be
// checked in one place at 390px and on a desktop. Served in development builds and on the platform
// host only; a tenant host shows a short notice. It holds no data and calls no API.

const SWATCHES = [
  '--color-primary',
  '--color-accent',
  '--color-accent-tint',
  '--color-link',
  '--color-text',
  '--color-text-muted',
  '--color-border',
  '--color-border-strong',
  '--color-bg',
  '--color-success',
  '--color-warning',
  '--color-danger',
] as const;

const ART_TITLES: Record<IllustrationName, string> = {
  noSales: 'No sales yet',
  noProducts: 'No products',
  noApplications: 'No applications',
  nothingWaiting: 'Nothing waiting',
  success: 'Success',
  error: 'Error',
  hero: 'Landing hero',
  shopfront: 'Sign-up hero',
  secure: 'Sign-in',
};

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="style-section">
      <h2>{title}</h2>
      {children}
    </section>
  );
}

function useStylePageAllowed(): boolean | null {
  const hosts = useQuery({ queryKey: ['app-config'], queryFn: loadHostConfig, staleTime: Infinity });
  if (import.meta.env.DEV) return true;
  if (!hosts.data) return null;
  return classifyHost(window.location.hostname, hosts.data).kind === 'platform';
}

function StylePage() {
  const allowed = useStylePageAllowed();
  const dialog = useRef<HTMLDialogElement>(null);
  const sheet = useRef<HTMLDialogElement>(null);
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 3000);
    return () => window.clearTimeout(timer);
  }, [toast]);

  if (allowed === null) return <BrandLoader />;
  if (!allowed) {
    return (
      <main>
        <h1>Style page</h1>
        <p className="lead">This page is available in development builds and on the platform host only.</p>
      </main>
    );
  }

  return (
    <main className="style-page">
      <div className="page-header">
        <div>
          <span className="eyebrow">Design layer</span>
          <h1>Style page</h1>
          <p>Every shared component and illustration, drawn from the tokens in theme.css.</p>
        </div>
      </div>

      <Section title="Colour roles">
        <ul className="swatch-grid">
          {SWATCHES.map((name) => (
            <li key={name} className="swatch">
              <span className="swatch-chip" style={{ background: `var(${name})` }} />
              <code>{name}</code>
            </li>
          ))}
        </ul>
      </Section>

      <Section title="Type">
        <h1>Heading one</h1>
        <h2>Heading two</h2>
        <h3>Heading three</h3>
        <p className="lead">A lead paragraph introduces a page.</p>
        <p>
          Body text with <a href="#type">a link</a>, <strong>bold words</strong> and <code>code</code>.
        </p>
        <p className="hint">A hint in muted, smaller text.</p>
      </Section>

      <Section title="Buttons">
        <div className="cluster">
          <button className="btn-primary">Primary</button>
          <button>Secondary</button>
          <button className="btn-danger">Danger</button>
          <button className="btn-ghost">Ghost</button>
          <button className="btn-primary" disabled>
            Disabled
          </button>
          <button className="btn-sm">Small</button>
          <button className="btn-icon">
            {icons.arrow}
            With icon
          </button>
        </div>
        <p className="hint">Press a button to see its pressed state; Tab to see the focus ring.</p>
        <button className="btn-primary btn-lg btn-block">Large, full width</button>
      </Section>

      <Section title="Fields">
        <form className="form-stack" onSubmit={(e) => e.preventDefault()}>
          <label>
            A text field
            <input placeholder="Type here" />
            <span className="field-hint">A hint under the field.</span>
          </label>
          <label>
            A field with an error
            <input aria-invalid="true" defaultValue="Not valid" />
            <span className="field-error">Say what is wrong and how to fix it.</span>
          </label>
          <label>
            A select
            <select defaultValue="b">
              <option value="a">First choice</option>
              <option value="b">Second choice</option>
            </select>
          </label>
          <label>
            A search
            <input type="search" placeholder="Search by name or code" />
          </label>
          <label>
            A disabled field
            <input disabled defaultValue="Read only" />
          </label>
          <fieldset>
            <legend>Choice rows</legend>
            <label>
              <input type="radio" name="style-choice" defaultChecked /> Cash
            </label>
            <label>
              <input type="radio" name="style-choice" /> Mobile money
            </label>
            <label>
              <input type="checkbox" /> A checkbox row
            </label>
          </fieldset>
          <label>
            A one-time code
            <input className="input-code" inputMode="numeric" placeholder="000000" />
          </label>
        </form>
      </Section>

      <Section title="Cards and elevation">
        <div className="grid-auto">
          <div className="card card-muted">
            <h3 className="card-title">Flat</h3>
            <p>.card-muted, for secondary content.</p>
          </div>
          <div className="card">
            <h3 className="card-title">Resting</h3>
            <p>.card, the default surface.</p>
          </div>
          <div className="card card-raised">
            <h3 className="card-title">Raised</h3>
            <p>.card-raised, the one card a page leads with.</p>
          </div>
          <a className="card card-interactive" href="#cards">
            <h3 className="card-title">Interactive</h3>
            <p>.card-interactive, a card that is a link.</p>
          </a>
        </div>
      </Section>

      <Section title="Alerts and badges">
        <p className="alert alert-info">Information for the user.</p>
        <p className="alert alert-success">The step worked.</p>
        <p className="alert alert-warning">Check this before going on.</p>
        <p className="alert alert-danger">The step failed; say why.</p>
        <div className="cluster">
          <span className="badge">Neutral</span>
          <span className="badge badge-info">Info</span>
          <span className="badge badge-success">Done</span>
          <span className="badge badge-warning">Pending</span>
          <span className="badge badge-danger">Refused</span>
        </div>
      </Section>

      <Section title="Table that becomes cards on a phone">
        <div className="table-wrap table-cards" tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th>Item</th>
                <th className="num">In stock</th>
                <th>State</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td data-label="Item">Sample item A</td>
                <td data-label="In stock" className="num">12</td>
                <td data-label="State">
                  <span className="badge badge-success">In stock</span>
                </td>
              </tr>
              <tr>
                <td data-label="Item">Sample item B</td>
                <td data-label="In stock" className="num">0</td>
                <td data-label="State">
                  <span className="badge badge-warning">Out</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </Section>

      <Section title="Empty, success and error states">
        <div className="grid-auto">
          <EmptyState art="noSales" title="No sales yet.">Sales appear here as they are recorded.</EmptyState>
          <EmptyState art="noProducts" title="No products yet.">Add a product or restock to start.</EmptyState>
          <EmptyState art="noApplications" title="No applications yet.">New applications appear here.</EmptyState>
          <EmptyState art="nothingWaiting" title="Nothing waiting.">Requests for your approval appear here.</EmptyState>
          <StatusPanel tone="success" title="Saved.">The step is done.</StatusPanel>
          <StatusPanel tone="error" title="That did not work.">Say what went wrong and what to try.</StatusPanel>
        </div>
      </Section>

      <Section title="Loading">
        <BrandLoader />
        <p className="loading">Inline loading text</p>
        <SkeletonList rows={2} />
      </Section>

      <Section title="Toast and dialog">
        <div className="cluster">
          <button onClick={() => setToast('Saved.')}>Show a toast</button>
          <button onClick={() => dialog.current?.showModal()}>Open a dialog</button>
          <button onClick={() => sheet.current?.showModal()}>Open a sheet</button>
        </div>
        {toast && (
          <div className="toast toast-success" role="status">
            {toast}
          </div>
        )}
        <dialog ref={dialog} aria-labelledby="style-dialog-title">
          <h2 id="style-dialog-title">A dialog</h2>
          <p>Centred, with a backdrop. It rises in over 200 ms.</p>
          <form method="dialog" className="dialog-actions">
            <button>Cancel</button>
            <button className="btn-primary">Confirm</button>
          </form>
        </dialog>
        <dialog ref={sheet} className="sheet" aria-labelledby="style-sheet-title">
          <h2 id="style-sheet-title">A sheet</h2>
          <p>A bottom sheet on a phone, a dialog on a wider screen.</p>
          <form method="dialog" className="dialog-actions">
            <button>Close</button>
          </form>
        </dialog>
      </Section>

      <Section title="Icons">
        <ul className="art-grid art-grid-icons">
          {Object.entries(icons).map(([name, icon]) => (
            <li key={name}>
              <span className="icon-chip">{icon}</span>
              <code>{name}</code>
            </li>
          ))}
        </ul>
      </Section>

      <Section title="Illustrations">
        <ul className="art-grid">
          {(Object.keys(illustrations) as IllustrationName[]).map((name) => (
            <li key={name} className="card">
              <span className="state-art">{illustrations[name]}</span>
              <strong>{ART_TITLES[name]}</strong>
              <code>{name}</code>
            </li>
          ))}
        </ul>
      </Section>

      <Section title="Landing module cards">
        {[['retail'], ['lending'], ['lending', 'retail']].map((modules) => (
          <div key={modules.join("+")} className="style-sub">
            <h3>{modules.join(' and ')}</h3>
            <p className="lead">{landingCopy(modules).lead}</p>
            <ul className="landing-modules">
              {landingCopy(modules).cards.map((card) => (
                <li key={card.key} className="card feature-card">
                  <span className="icon-chip">{icons[card.icon]}</span>
                  <h2>{card.title}</h2>
                  <p>{card.text}</p>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </Section>
    </main>
  );
}

export const Route = createLazyRoute('/style')({ component: StylePage });

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider } from '@tanstack/react-router';
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { PlatformLanding } from '../src/app/landing';
import { Footer } from '../src/app/shell';
import { ActivateFormView } from '../src/areas/platform/application';
import type { ActivateForm } from '../src/areas/onboarding/forms';
import '../src/app/theme.css';
import '../src/app/ui/index.css';
import { layoutRouter } from './router';

// The page signup-findings.layout.test.ts lays out in a real browser (issue #155): ?page=landing draws the platform landing with
// its quiet Operator sign-in link at the foot; ?page=activate draws the operator's Activate form with Check the address, whose
// answer the test gives through the browser, as no server runs here. ?font= scales the root font size as a phone's large text
// setting does, ?theme=dark draws the dark theme. Every name and host is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;
if (query.get('theme') === 'dark') document.documentElement.dataset.theme = 'dark';
const landing = query.get('page') === 'landing';

const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
const router = layoutRouter(landing ? '/' : '/platform/applications/a1');

function Activate() {
  const [form, setForm] = useState<ActivateForm>({
    modules: ['retail'],
    term: 'monthly',
    way_in: 'trial',
    payment_note: '',
    slug: 'test-hardware-and-electricals-155',
    plan_code: 'starter',
  });
  return (
    <main>
      <h1>Application A-TEST0155</h1>
      <ActivateFormView form={form} problems={{}} busy={false} onChange={setForm} onSubmit={(e) => e.preventDefault()} />
    </main>
  );
}

createRoot(document.getElementById('root') as HTMLElement).render(
  <RouterContextProvider router={router}>
    <QueryClientProvider client={client}>
      <div className="app-shell">
        <div className="app-content">{landing ? <PlatformLanding /> : <Activate />}</div>
        <Footer />
      </div>
    </QueryClientProvider>
  </RouterContextProvider>,
);

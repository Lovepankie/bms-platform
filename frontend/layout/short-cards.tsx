import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createRoot } from 'react-dom/client';
import { mockMe } from '../src/api/retail-mock';
import { StaffContext } from '../src/areas/staff/context';
import { DayCard } from '../src/areas/staff/retail/cash-summary';
import { CategoryCard } from '../src/areas/staff/retail/expense-lists';
import { InTransitCard } from '../src/areas/staff/retail/in-transit';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page short-cards.layout.test.ts lays out in a real browser (issue #798, rule 1): a move on its way on its summary, a day of
// the cash summary on its closing and expected cash, and a closed expense category, each with the button that opens the rest.
// ?font= scales the root font size as a phone's large text setting does. Every name and figure is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

const me = mockMe('admin');
const [a, b] = me.branches ?? [];
const move = {
  id: 't1', from_branch_id: a?.id ?? '', to_branch_id: b?.id ?? '', transfer_date: '2026-10-06', age_days: 2,
  lines: [
    { line_no: 1, product_id: 'p1', description: 'Test LED bulb 9W screw, warm white, box of ten', code: 'TE-1', qty: '4.000' },
    { line_no: 2, product_id: 'p2', description: 'Test socket', code: 'TE-2', qty: '2.000' },
    { line_no: 3, product_id: 'p3', description: 'Test cable', code: 'TE-3', qty: '9.000' },
  ],
};

createRoot(document.getElementById('root') as HTMLElement).render(
  <QueryClientProvider client={new QueryClient()}>
    <StaffContext.Provider value={{ me, branch: a?.id ?? '' }}>
      <div className="app-shell"><div className="app-content"><main className="rt">
        <ul style={{ listStyle: 'none', padding: 0 }}>
          <InTransitCard transfer={move} mayReceive mayTakeBack />
          <DayCard row={{ branch_id: a?.id, business_date: '2026-10-06', closing_minor: 1234500, cash_expected_minor: 987600 }} shop="Test Branch A with a long shop name" />
        </ul>
        <CategoryCard category={{ id: 'c1', name: 'Test transport and deliveries to the far shops', active: false, version: 1, items: [{ id: 'i1', name: 'Fuel', active: true, version: 1 }] }} busy={false} change={() => undefined} />
      </main></div></div>
    </StaffContext.Provider>
  </QueryClientProvider>,
);

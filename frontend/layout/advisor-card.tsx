import { createRoot } from 'react-dom/client';
import type { AdvisorBrief } from '../src/api/advisor';
import { AdvisorBriefView } from '../src/areas/staff/insights/advisor';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page advisor-card.layout.test.ts lays out in a real browser (review of #735): the shop's AI assistant card as Home draws it,
// in the Home grid, with a chip whose label carries a long item name. ?font= scales the root font size. Every name and figure is
// fabricated.

const font = Number(new URLSearchParams(window.location.search).get('font') ?? '1');
document.documentElement.style.fontSize = `${font * 100}%`;

const longName = 'Test Extra Long Laundry Bar Soap Family Pack Lemon Fresh Scented Twelve Pieces';
const brief: AdvisorBrief = {
  advisory: true,
  source: 'model',
  reason: null,
  label: 'AI assistant (advisory)',
  as_of: '2026-10-20T06:05:00Z',
  facts_as_of: '2026-10-19',
  summary: 'Advisory summary for 19 October 2026. These are suggestions to check before acting; nothing has been changed.',
  points: [
    {
      text: `Medium priority. ${longName}: sales in the last seven days (UGX 1,250,000). Suggested action: Keep enough stock for ${longName} so the best sellers do not run out.`,
      facts: ['item_a.sales_week'],
    },
  ],
  facts: [
    { key: 'item_a.sales_week', label: `${longName}: sales in the last seven days`, display: 'UGX 1,250,000', metric: 'item.sales_week' },
    { key: 'credit.overdue', label: 'Owed past the due date', display: 'UGX 25,500,000', metric: 'credit.overdue' },
  ],
  note: 'Made from your shop figures. Check before acting. It does not change any record.',
};

createRoot(document.getElementById('root') as HTMLElement).render(
  <main className="rt">
    <section className="home-cards" aria-label="Your business today">
      <div className="home-advisor" data-testid="advisor-card">
        <AdvisorBriefView brief={brief} onChip={() => {}} />
      </div>
    </section>
  </main>,
);

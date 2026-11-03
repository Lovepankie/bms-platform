import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import type { Me } from '../../../api/client';
import { retailAdvisor } from '../../../api/retail-advisor';
import { AdvisorCardState } from '../insights/advisor';
import { holds, showRetail } from './permissions';

// The shop's AI assistant card on Home (issue #422): the retail counterpart of the Insights page card (ADR-037), drawn by the same
// view so both read the same: the label "AI assistant (advisory)" or "Automatic summary", the points our code wrote, and a chip per
// figure that opens the page behind it. The server decides what the session may see; the card only shows what it sends.

export const RETAIL_ADVISOR_USE = 'retail.advisor.use';
export const RETAIL_ADVISOR_CONFIGURE = 'retail.advisor.configure';

/** The card is for a business with the shop module and a session holding retail.advisor.use. */
export function canUseRetailAdvisor(me: Pick<Me, 'permissions'> & { modules?: string[] }): boolean {
  return showRetail(me) && holds(me, RETAIL_ADVISOR_USE);
}

/** The assistant's settings page for a shop: the switch and caps the lending assistant shares (one setting per business). */
export function canConfigureRetailAdvisor(me: Pick<Me, 'permissions'> & { modules?: string[] }): boolean {
  return showRetail(me) && holds(me, RETAIL_ADVISOR_CONFIGURE);
}

/** The page a figure of the brief opens, by its family; null when no page lists the rows behind it. */
export function pageOf(metric: string): string | null {
  const family = metric.split('.')[0];
  switch (family) {
    case 'sales':
    case 'profit':
    case 'item':
    case 'wholesale':
      return '/staff/retail/sales-analysis';
    case 'own':
      return '/staff/retail/sales';
    case 'credit':
      return '/staff/retail/credit-control';
    case 'stock':
      return metric === 'stock.slow_items' ? '/staff/retail/stock-health' : '/staff/retail/stock';
    case 'controls':
      return '/staff/retail/controls';
    case 'orders':
      return '/staff/retail/orders';
    default:
      return null;
  }
}

export function RetailAdvisorCard() {
  const navigate = useNavigate();
  const query = useQuery({ queryKey: ['retail', 'advisor', 'brief'], queryFn: retailAdvisor.brief, staleTime: 60_000 });
  // Off for the business (204): no card and no empty row in the grid.
  if (!query.isPending && !query.isError && !query.data) return null;
  return (
    <div className="home-advisor" data-tour="home-retail-brief">
      <AdvisorCardState
        pending={query.isPending}
        failed={query.isError}
        brief={query.data}
        onChip={(metric) => {
          const to = pageOf(metric);
          if (to) void navigate({ to });
        }}
      />
    </div>
  );
}

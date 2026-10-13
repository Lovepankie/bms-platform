import { Link } from '@tanstack/react-router';
import type { Customer } from '../../../api/retail';
import { useStaff } from '../context';
import { limitWords, termsWords } from './account-state';
import { canOpenAccount } from './permissions';
import { money } from './ui';

// A credit buyer's limit and terms on the Credit buyers page, with the way to their account (customer accounts, ADR-033,
// issue #196). Drawn only while the customer_accounts feature is on and the session reads sales; otherwise nothing.

export function BuyerAccount({ row }: { row: Customer }) {
  const { me } = useStaff();
  if (!canOpenAccount(me)) return null;
  return (
    <div data-tour="buyer-account" data-testid="buyer-account">
      <p className="hint">{limitWords({ credit_limit_minor: row.credit_limit_minor }, money)} {termsWords(row.terms_days)}</p>
      <p>
        <Link className="btn-sm" to="/staff/retail/accounts/$customerId" params={{ customerId: row.id ?? '' }} aria-label={`Open the account of ${row.name}`}>Open account</Link>
      </p>
    </div>
  );
}

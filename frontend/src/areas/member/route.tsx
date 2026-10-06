import { createLazyRoute } from '@tanstack/react-router';
import { icons } from '../../components/icons';

// The member self-service area (chapter 11). Its screens arrive with the member portal epic;
// it is a separate chunk from the staff area from the start.
const FEATURES = [
  { icon: 'valuation', title: 'Balances', text: 'Savings, loan and investment balances, up to date.' },
  { icon: 'calendar', title: 'Loan schedule', text: 'What is due and when, with what you have paid.' },
  { icon: 'shield', title: 'Payments', text: 'Pay by mobile money and keep every receipt.' },
] as const;

function MemberHome() {
  return (
    <main>
      <section className="card portal-intro">
        <span className="eyebrow">Member portal</span>
        <h1>Member area</h1>
        <p className="lead">Balances, schedules, applications and payments will appear here.</p>
      </section>
      <div className="grid-auto">
        {FEATURES.map((f) => (
          <section key={f.title} className="card feature-card">
            <span className="icon-chip">{icons[f.icon]}</span>
            <h2>{f.title}</h2>
            <p>{f.text}</p>
            <span>
              <span className="badge">Coming soon</span>
            </span>
          </section>
        ))}
      </div>
    </main>
  );
}

export const Route = createLazyRoute('/member')({ component: MemberHome });

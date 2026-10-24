import { createLazyRoute } from '@tanstack/react-router';
import { BusyButton } from '../../app/ui/BusyButton';
import { Card, ListRow, RowList } from '../../app/ui/ListRow';
import type { MenuLink } from '../../components/side-menu';
import { CurrentShop } from './branch-picker';
import { useStaff } from './context';
import { STAFF_SECTIONS, staffAreas } from './menu';
import { useSells } from './retail/nav';
import { moneyLinks } from './tabs';

// The two pages the bottom tabs open that are not a screen of their own (issue #539): Money lists the money pages the session may
// use, and More lists every staff area the side menu lists on a laptop, by the same permissions (menu.tsx), with who is signed in,
// the shop in use and Sign out, which on a phone have no other place now the drawer is gone.

export function MoneyPage() {
  const { me } = useStaff();
  const links = moneyLinks(me);
  return (
    <main className="tab-page">
      <div className="page-header">
        <div>
          <h1>Money</h1>
          <p>Cash, savings, spending and who owes you.</p>
        </div>
      </div>
      {links.length === 0 ? (
        <p className="empty-state">You do not have access to any money pages.</p>
      ) : (
        <div data-tour="money-list">
          <Card>
            <RowList label="Money pages">
              {links.map((l) => (
                <ListRow key={l.to} to={l.to} title={l.label} meta={l.hint} />
              ))}
            </RowList>
          </Card>
        </div>
      )}
    </main>
  );
}

/** One card of links; Retail is every shop page, so its row says so rather than repeating the shortcuts the tabs already hold. */
function AreaCard({ title, links }: { title?: string; links: MenuLink[] }) {
  if (links.length === 0) return null;
  return (
    <Card title={title}>
      <RowList label={title ?? 'Areas'}>
        {links.map((l) => (
          <ListRow key={l.to} to={l.to} title={l.label} meta={l.to === '/staff/retail' ? 'Every shop page' : undefined} />
        ))}
      </RowList>
    </Card>
  );
}

export function MorePage() {
  const { me, branch, signOut, signingOut = false } = useStaff();
  const sells = useSells(me);
  const areas = staffAreas(me, { sells });
  // As in the side menu (issue #471): headed parts only when the links name two sections.
  const named = STAFF_SECTIONS.filter((s) => areas.some((l) => l.section === s.id));
  const headed = named.length > 1;
  return (
    <main className="tab-page">
      <div className="page-header">
        <div>
          <h1>More</h1>
          <p>Everything else you can open.</p>
        </div>
      </div>
      <div data-tour="more-list">
        {headed ? (
          <>
            {named.map((s) => (
              <AreaCard key={s.id} title={s.label} links={areas.filter((l) => l.section === s.id)} />
            ))}
            <AreaCard title="Business" links={areas.filter((l) => !l.section)} />
          </>
        ) : (
          <AreaCard links={areas} />
        )}
      </div>
      <Card title="You">
        <div className="more-account">
          <CurrentShop me={me} branch={branch} />
          <p className="more-name">{me.full_name}</p>
          {signOut && (
            <BusyButton type="button" busy={signingOut} onClick={signOut} data-tour="more-sign-out">
              Sign out
            </BusyButton>
          )}
        </div>
      </Card>
    </main>
  );
}

export const MoneyRoute = createLazyRoute('/staff/money')({ component: MoneyPage });
export const MoreRoute = createLazyRoute('/staff/more')({ component: MorePage });

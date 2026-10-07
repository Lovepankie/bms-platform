import { Link, createLazyRoute } from '@tanstack/react-router';
import type { Me } from '../../../api/client';
import { useStaff } from '../context';
import { CATALOGUE_LINKS, canUse } from './permissions';
import { Gate } from './ui';

// Catalogue (#146): the lists a shop keeps for itself, so it needs no operator to add an item or a
// supplier. Each link shows only when the session holds the permissions of its screen.

export function BackToCatalogue() {
  return <p><Link to={'/staff/retail/catalogue' as never}>Back to Catalogue</Link></p>;
}

/** The catalogue screens a session may use, in the order the Catalogue home lists them. */
export function catalogueLinksFor(me: Pick<Me, 'permissions'>) {
  return CATALOGUE_LINKS.filter((l) => canUse(me, l.screen));
}

export function CatalogueLinks() {
  const { me } = useStaff();
  const links = catalogueLinksFor(me);
  return (
    <ul className="tile-grid">
      {links.map((l) => (
        <li key={l.screen}>
          <Link className="rt-tile tile" to={l.path as never}>
            <strong>{l.label}</strong>
            <span className="tile-hint">{l.hint}</span>
          </Link>
        </li>
      ))}
      {canUse(me, 'importer') && (
        <li>
          <Link className="rt-tile tile" to={'/staff/retail/catalogue/import' as never}>
            <strong>Import items</strong>
            <span className="tile-hint">Add many items from a spreadsheet file</span>
          </Link>
        </li>
      )}
    </ul>
  );
}

function CataloguePage() {
  return (
    <Gate screen="catalogue" title="Catalogue">
      <p>What would you like to look after?</p>
      <CatalogueLinks />
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/catalogue')({ component: CataloguePage });

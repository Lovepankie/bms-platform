import { Link, createLazyRoute } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import type { Me } from '../../../api/client';
import { useStaff } from '../context';
import { icons } from '../../../components/icons';
import { CATALOGUE_LINKS, canUse, type RetailScreen } from './permissions';
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

/** A form that stays closed behind a button until asked for, so a long list is not pushed below the fold on a phone. */
export function AddPanel({ label, open, onOpen, onClose, children }: { label: string; open: boolean; onOpen: () => void; onClose: () => void; children: ReactNode }) {
  if (!open) {
    return <p><button type="button" onClick={onOpen}>{label}</button></p>;
  }
  return (
    <section className="rt-card" aria-label={label}>
      {children}
      <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
    </section>
  );
}

const TILE_ICONS: Partial<Record<RetailScreen, keyof typeof icons>> = {
  products: 'stock', categories: 'catalogue', units: 'valuation', suppliers: 'restock', buyers: 'creditSales', importer: 'stocktake',
};

export function CatalogueLinks() {
  const { me } = useStaff();
  const links = catalogueLinksFor(me);
  return (
    <ul className="tile-grid">
      {links.map((l) => (
        <li key={l.screen}>
          <Link className="rt-tile tile" to={l.path as never}>
            <span className="icon-chip">{icons[TILE_ICONS[l.screen] ?? 'catalogue']}</span>
            <strong>{l.label}</strong>
            <span className="tile-hint">{l.hint}</span>
          </Link>
        </li>
      ))}
      {canUse(me, 'importer') && (
        <li>
          <Link className="rt-tile tile" to={'/staff/retail/catalogue/import' as never}>
            <span className="icon-chip">{icons.stocktake}</span>
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

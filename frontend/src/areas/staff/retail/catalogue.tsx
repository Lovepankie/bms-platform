import { Link, createLazyRoute } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import type { Me } from '../../../api/client';
import { useStaff } from '../context';
import { icons } from '../../../components/icons';
import { Tile, TileGrid } from '../../../app/ui/Tile';
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

export const TILE_ICONS: Partial<Record<RetailScreen, keyof typeof icons>> = {
  products: 'stock', categories: 'categories', units: 'units', suppliers: 'restock', buyers: 'customerAccounts', importer: 'importer', labels: 'labels',
};

export function CatalogueLinks() {
  const { me } = useStaff();
  const links = catalogueLinksFor(me);
  return (
    <TileGrid label="Catalogue pages" tour="catalogue-links">
      {links.map((l) => (
        <Tile key={l.screen} to={l.path} icon={icons[TILE_ICONS[l.screen] ?? 'catalogue']} label={l.label} hint={l.hint} tour={`catalogue-${l.screen}`} />
      ))}
      {canUse(me, 'importer') && (
        <Tile to="/staff/retail/catalogue/import" icon={icons[TILE_ICONS.importer ?? 'catalogue']} label="Import items" hint="Add many items from a spreadsheet file" tour="catalogue-importer" />
      )}
    </TileGrid>
  );
}

export function CataloguePage() {
  return (
    <Gate screen="catalogue" title="Catalogue">
      <p>What would you like to look after?</p>
      <CatalogueLinks />
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/catalogue')({ component: CataloguePage });

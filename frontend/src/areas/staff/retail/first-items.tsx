import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { retail } from '../../../api/retail';
import { Card, RowChevron } from '../../../app/ui/ListRow';
import { useStaff } from '../context';
import { holds, showRetail } from './permissions';
import { ScreenLink } from './screen-link';

// The starting card of a shop with no items yet (issue #615): a new business opened Sell, searched, found nothing and had to
// ask on WhatsApp how to add items. While the business has no item at all, Sell and Home show this card first. Whoever
// manages the catalogue gets Add your first items (the Items page, whose Add an item takes the category inline) and, with
// the import screen, Import a list; anyone else is told to ask the owner. The read is one page of the catalogue, every item
// switched on or off, under the catalogue's own cache key, so adding an item anywhere hides the card.

export const NO_ITEMS_YET = 'Your shop has no items yet. Add the things you sell, then you can sell them.';
export const ASK_OWNER_ITEMS = 'Ask the owner to add the items first.';

/** True only once the catalogue has answered with no item at all; false while it loads, fails or holds one. */
export function useNoItemsYet(): boolean {
  const { me } = useStaff();
  const items = useQuery({
    queryKey: ['retail', 'catalogue', 'first-items'],
    queryFn: ({ signal }) => retail.listCatalogue({ signal }),
    enabled: showRetail(me) && holds(me, 'retail.stock.read'),
    staleTime: 60_000,
  });
  return items.data !== undefined && (items.data.items ?? []).length === 0;
}

export function FirstItemsCard() {
  const { me } = useStaff();
  const empty = useNoItemsYet();
  if (!empty) return null;
  const manages = holds(me, 'retail.catalogue.manage');
  return (
    <Card title="Get your shop ready">
      <p data-testid="first-items-text">{NO_ITEMS_YET}</p>
      {manages ? (
        <div className="stack" data-testid="first-items-actions">
          <ScreenLink screen="products" to="/staff/retail/catalogue/products" action>Add your first items</ScreenLink>
          <ScreenLink screen="importer" to="/staff/retail/catalogue/import">Import a list</ScreenLink>
          <Link className="link-row" to="/staff/help/$articleId" params={{ articleId: 'getting-your-shop-ready' }}>
            How do I get my shop ready to sell?
            <RowChevron />
          </Link>
        </div>
      ) : (
        <p className="hint" data-testid="first-items-ask">{ASK_OWNER_ITEMS}</p>
      )}
    </Card>
  );
}

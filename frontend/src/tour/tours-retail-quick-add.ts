import type { Me } from '../api/client';
import type { Tour, TourStep } from './model';

// The page tour of Quick add (issue #632): one form for an item, its prices and how many there are, and one tap. Plain words,
// short sentences, say what to tap. tours-retail-quick-add.test.tsx renders the page and fails if an anchor is missing.

const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);

const retail = (step: Omit<TourStep, 'module'>): TourStep => ({ module: 'retail', ...step });

export const quickAddPage: Tour = {
  id: 'page-quick-add',
  version: 2,
  title: 'This page: quick add',
  audience: (me) => held(me, 'retail.catalogue.manage') && held(me, 'retail.stock.read'),
  autoStart: false,
  page: '/staff/retail/quick-add',
  steps: [
    retail({ id: 'fields', target: 'quick-add-fields', title: 'Type what you know', body: 'Type the name and choose its group. Type the selling price. The buying price is optional. Type how many you have here now.' }),
    retail({ id: 'where', target: 'quick-add-more', title: 'Unit and photo', body: 'The unit starts as a piece. Add a photo if you like.' }),
    retail({ id: 'add', target: 'quick-add-go', title: 'One tap', body: 'Tap Add. The item and its stock are saved together. Then tap Add another, or Sell it.' }),
  ],
};

export const RETAIL_QUICK_ADD_TOURS: readonly Tour[] = [quickAddPage];

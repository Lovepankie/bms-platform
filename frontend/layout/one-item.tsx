import { createRoot } from 'react-dom/client';
import { OneItemNote } from '../src/areas/staff/retail/stock';
import '../src/app/theme.css';
import '../src/app/ui/index.css';

// The page one-item.layout.test.ts lays out in a real browser (issue #336, review of #797): the note over Stock opened for one item
// from a link, with a long item name, and its Show every item button. ?font= scales the root font size as a phone's large text
// setting does. The name is invented.

const query = new URLSearchParams(window.location.search);
document.documentElement.style.fontSize = `${16 * Number(query.get('font') ?? '1')}px`;

createRoot(document.getElementById('root') as HTMLElement).render(
  <div className="app-shell"><div className="app-content"><main className="rt">
    <OneItemNote name="Test LED bulb 9W screw, warm white, box of ten with a long name" onEvery={() => undefined} />
  </main></div></div>,
);

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { Fragment, useState } from 'react';
import { SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { Segmented } from '../../../app/ui/Segmented';
import { retail, type AllBranchesStock, type StockLevel, type StockRow } from '../../../api/retail';
import { branchLabel } from '../../../auth/branch';
import { useCrumbTail } from '../../../components/breadcrumbs';
import { GroupControls, GroupedBodies, GroupedCards, sameUnitQty, useCategoryGroups } from './category-groups';
import { lineTotalMinor, milliOf } from './maths';
import { packsAndPiecesParts, type PackSize } from './units-state';
import { EmptyState, SkeletonList } from '../../../components/states';
import { BranchRequired, CategoryLabel, Gate, ItemPageLink, NoStockHere, Problem, money, useBranchView, useIsPhone, useProfitAccess } from './ui';
import { CorrectItem, mayCorrect } from './stock-correct';
import { useStaff } from '../context';
import { needsOf } from './permissions';
import { ListGroupBy, ViewSwitch, useListView } from './list-chain';
import { STOCK_NAMES, STOCK_ORDER, listUser, withoutShop, type ListSelection } from './list-chain-state';
import { GroupedStock, type StockFilter } from './stock-grouped';
import { keptWords } from './orders-state';
import { CategoryChips, FindBar, NoItem, onlyMatch, useChosenChip, useFindTerm } from './phone-find';
import { ScreenLink } from './screen-link';
import { Hint } from '../../../app/ui/Hint';
import { HelpTip } from '../../../app/ui/HelpTip';
import { WORDS } from '../../../app/glossary';
import { ElsewhereNote, allBranchesKey, noneHere, shopsOf, useStockElsewhere, type Elsewhere } from './stock-elsewhere';
import { UnfinishedCounts } from './unfinished-counts';

// Stock per branch (FR-RET-03): balances with a negative flag and a search box. The cost column
// exists only for a session holding retail.profit.read: showCost is the screen's choice and the server
// sends no cost field to anyone else.

/** Search and filters of a stock list, so its groups start again when they change (category-groups.tsx). */
type Narrowing = { searching?: boolean; filterKey?: string; pending?: boolean; /** Under a group of the grouped view the items are one plain list (issue #350). */ flat?: boolean };

/** Set by the stock page for one branch when the session may correct something there: each row then offers Correct (issue #351). */
type Correctable = { branchId: string };

/** A category group's subtotal: its quantity when the items share a unit, and what it holds at the selling price. */
function stockSubtotal(rows: { qty?: string; unit?: string; sell_minor?: number }[]): string {
  // ADR-055: no price came back (a storekeeper), so no value is shown either.
  if (rows.every((r) => r.sell_minor === undefined)) return sameUnitQty(rows) ?? '';
  const value = rows.reduce((sum, r) => sum + lineTotalMinor(r.sell_minor ?? 0, milliOf(r.qty ?? '0')), 0);
  return [sameUnitQty(rows), `worth ${money(value)} at selling price`].filter(Boolean).join(', ');
}

/** Issue #680: the two prices of an item in plain words, the buying price only for a reader of costs. */
function PriceLine({ cost, sell }: { cost?: number; sell?: number }) {
  return (
    <>
      {cost !== undefined && <>{WORDS.buyingPrice} <span className="num">{money(cost)}</span> · </>}
      {sell !== undefined && <>{WORDS.sellingPrice} <span className="num">{money(sell)}</span></>}
    </>
  );
}

/** Issue #707: on a phone the prices are on every card, so their help tips stand once above the list. */
function PriceTips({ cost }: { cost: boolean }) {
  return (
    <p className="hint">
      {cost && <>Average buying price <HelpTip tip="averageBuyingPrice" /> </>}
      {WORDS.sellingPrice} <HelpTip tip="sellingPrice" />
    </p>
  );
}

export function StockTable({ rows, showCost, searching = false, filterKey = '', pending = false, flat = false, correctable, elsewhere }: {
  rows: StockRow[];
  showCost: boolean;
  correctable?: Correctable;
  /** Issue #728: the other shop holding each item, said under an item with none here. */
  elsewhere?: ReadonlyMap<string, Elsewhere>;
} & Narrowing) {
  const away = (r: StockRow) => {
    const at = noneHere(r.qty) && !shopsOf(r) ? elsewhere?.get(r.product_id ?? '') : undefined;
    return at && <ElsewhereNote at={at} id={r.product_id ?? ''} />;
  };
  // On a phone the category sits under the item's name, so the table keeps room for the quantity and price.
  const phone = useIsPhone();
  const [corrected, setCorrected] = useState<string | null>(null);
  const g = useCategoryGroups({ screen: 'stock', items: rows, categoryOf: (r) => r.category, subtotalOf: stockSubtotal, resetKey: filterKey, searching, pending });
  const grouping = g.active && !flat;
  if (rows.length === 0) return <EmptyState title="No items found.">Try another name or code, or buy stock to add items.</EmptyState>;
  const showCategory = !grouping && phone;
  const correct = (r: StockRow) => (
    <button type="button" className="btn-sm" aria-expanded={corrected === r.product_id} aria-label={`Correct ${r.description}`} onClick={() => setCorrected(corrected === r.product_id ? null : (r.product_id ?? null))}>Correct</button>
  );
  // Issue #680: on a phone each item is a card, the quantity large and the two prices in plain words under it; the table stays wide.
  // JUSTIFICATION-A3: the phone card list the issue asks for; no card of one shop's stock existed to reuse.
  if (phone) {
    const card = (r: StockRow) => (
      <li key={r.product_id} className="rt-card stock-card">
        <strong>{r.description}</strong>
        {showCategory && r.category && <CategoryLabel category={r.category} />}
        {shopsOf(r) ? <ShopQuantities row={r} /> : <p className="stock-card-qty">In stock: <span className={r.standard_pack ? 'num qty-packs' : 'num'}><InPacks qty={r.qty ?? '0'} unit={r.unit} pack={r.standard_pack} /></span></p>}
        {r.negative && !shopsOf(r) && <p className="rt-flag">Negative</p>}
        {away(r)}
        {keptWords(r.qty, r.reserved_qty) && <p className="hint">{keptWords(r.qty, r.reserved_qty)}</p>}
        {(r.sell_minor !== undefined || (showCost && r.cost_minor !== undefined)) && <p className="hint"><PriceLine cost={showCost ? r.cost_minor : undefined} sell={r.sell_minor} /></p>}
        <ItemPageLink productId={r.product_id} name={r.description} />
        {correctable && correct(r)}
        {correctable && corrected === r.product_id && <CorrectItem row={r} branchId={correctable.branchId} onClose={() => setCorrected(null)} />}
      </li>
    );
    return (
      <>
        {!flat && <GroupControls state={g} />}
        <PriceTips cost={showCost} />
        {grouping ? <GroupedCards state={g} renderItem={card} /> : <ul className="stock-cards">{rows.map(card)}</ul>}
      </>
    );
  }
  const columns = 3 + (!phone && !grouping ? 1 : 0) + (showCost ? 1 : 0);
  const row = (r: StockRow) => (
    <Fragment key={r.product_id}>
      <tr>
        <td>
          {r.description}{showCategory && r.category && <><br /><CategoryLabel category={r.category} /></>}
          <div><ItemPageLink productId={r.product_id} name={r.description} /></div>
          {correctable && <div>{correct(r)}</div>}
        </td>
        {!phone && !grouping && <td>{r.category}</td>}
        <td className={r.standard_pack ? 'num qty-packs' : 'num'}>
          {shopsOf(r) ? <ShopQuantities row={r} /> : <InPacks qty={r.qty ?? '0'} unit={r.unit} pack={r.standard_pack} />}
          {r.negative && !shopsOf(r) && <div className="rt-flag">Negative</div>}
          {away(r)}
          {keptWords(r.qty, r.reserved_qty) && <div className="hint">{keptWords(r.qty, r.reserved_qty)}</div>}
        </td>
        {showCost && <td className="num">{r.cost_minor !== undefined ? money(r.cost_minor) : ''}</td>}
        <td className="num">{r.sell_minor !== undefined ? money(r.sell_minor) : ''}</td>
      </tr>
      {correctable && corrected === r.product_id && (
        <tr><td colSpan={columns}><CorrectItem row={r} branchId={correctable.branchId} onClose={() => setCorrected(null)} /></td></tr>
      )}
    </Fragment>
  );
  return (
    <>
    {!flat && <GroupControls state={g} />}
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>Item</th>
          {!phone && !grouping && <th>Category</th>}
          <th className="num">In stock</th>
          {/* The header's own name is its words: the tip's button is not read with every cell of the column. */}
          {showCost && <th className="num" aria-label="Average buying price">Average buying price <HelpTip tip="averageBuyingPrice" /></th>}
          <th className="num" aria-label={WORDS.sellingPrice}>{WORDS.sellingPrice} <HelpTip tip="sellingPrice" /></th>
        </tr>
      </thead>
      {grouping ? <GroupedBodies state={g} colSpan={3 + (showCost ? 1 : 0)} renderRow={row} /> : <tbody>{rows.map(row)}</tbody>}
    </table></div>
    </>
  );
}

/**
 * Issue #650: a quantity in the item's standard pack and pieces, the total in pieces on a line of its own so a narrow cell
 * wraps between the two, never inside a number. The words are packsAndPieces' own.
 */
function InPacks({ qty, unit, pack }: { qty: string; unit?: string; pack?: PackSize }) {
  const { inPacks, total } = packsAndPiecesParts(qty, unit, pack);
  return total ? <><span className="qty-in-packs">{inPacks}</span> <span className="qty-total">({total})</span></> : <>{inPacks}</>;
}

/**
 * Issue #807 (ADR-119): the item's quantity in every shop of the business, this shop first as "In stock here", a shop with
 * none at 0 and one below zero said in words as well as by its minus sign. Quantities only; nothing moves the person.
 */
function ShopQuantities({ row }: { row: StockRow }) {
  return (
    <ul className="shop-qtys" data-testid={`shops-${row.product_id}`} aria-label={`${row.description} in each shop`}>
      {(row.shops ?? []).map((s) => (
        <li key={s.branch_id} className={s.here ? 'shop-qty-here' : undefined}>
          {s.here ? 'In stock here' : s.name}:{' '}
          <span className={row.standard_pack ? 'num qty-packs' : 'num'}><InPacks qty={s.qty ?? '0'} unit={row.unit} pack={row.standard_pack} /></span>
          {s.negative && <span className="rt-flag"> Below zero</span>}
        </li>
      ))}
    </ul>
  );
}

/** One quantity with its unit, in the item's standard pack and pieces when it has one (issue #650), and the negative flag in words. */
function Qty({ qty, unit, negative, pack }: { qty: string; unit?: string; negative?: boolean; pack?: PackSize }) {
  return (
    <>
      <InPacks qty={qty} unit={unit} pack={pack} />
      {negative && <div className="rt-flag">Negative</div>}
    </>
  );
}

/** The subtotal of a group of all-branches rows: the total quantity and what it holds at the selling price. */
const totalsSubtotal = (rows: AllBranchesStock['items']) => stockSubtotal(rows.map((r) => ({ qty: r.total_qty, unit: r.unit, sell_minor: r.sell_minor })));

const branchName = (b: { code?: string; name?: string; closed?: boolean }) => `${branchLabel(b)}${b.closed ? ' (closed)' : ''}`;

/** All branches on a wide screen: a column per branch and a total, the negative flag on each cell. */
export function AllBranchesTable({ data, showCost: mayCost, searching = false, filterKey = '', pending = false, flat = false }: { data: AllBranchesStock; showCost: boolean } & Narrowing) {
  const showCost = mayCost && data.items.some((r) => r.cost_minor !== undefined);
  const g = useCategoryGroups({ screen: 'stock', items: data.items, categoryOf: (r) => r.category, subtotalOf: totalsSubtotal, resetKey: filterKey, searching, pending });
  const grouping = g.active && !flat;
  if (data.items.length === 0) return <EmptyState title="No items found.">Try another name or code, or buy stock to add items.</EmptyState>;
  const row = (r: AllBranchesStock['items'][number]) => (
    <tr key={r.product_id}>
      <td>{r.description}{!grouping && <><br /><CategoryLabel category={r.category} /></>}<div><ItemPageLink productId={r.product_id} name={r.description} /></div></td>
      <td className={r.standard_pack ? 'num qty-packs' : 'num'}><strong><Qty qty={r.total_qty ?? '0'} unit={r.unit} negative={r.negative} pack={r.standard_pack} /></strong></td>
      {(r.balances ?? []).map((c) => (
        <td key={c.branch_id} className={r.standard_pack ? 'num qty-packs' : 'num'}><Qty qty={c.qty ?? '0'} unit={r.standard_pack ? r.unit : undefined} negative={c.negative} pack={r.standard_pack} /></td>
      ))}
      {showCost && <td className="num">{r.cost_minor !== undefined ? money(r.cost_minor) : ''}</td>}
      <td className="num">{r.sell_minor !== undefined ? money(r.sell_minor) : ''}</td>
    </tr>
  );
  return (
    <>
    {!flat && <GroupControls state={g} />}
    <div className="table-wrap" tabIndex={0}><table className="stock-matrix">
      <thead>
        <tr>
          <th>Item</th>
          <th className="num">Total</th>
          {data.branches.map((b) => <th key={b.id} className="num">{branchName(b)}</th>)}
          {showCost && <th className="num" aria-label="Average buying price">Average buying price <HelpTip tip="averageBuyingPrice" /></th>}
          <th className="num" aria-label={WORDS.sellingPrice}>{WORDS.sellingPrice} <HelpTip tip="sellingPrice" /></th>
        </tr>
      </thead>
      {grouping ? <GroupedBodies state={g} colSpan={3 + data.branches.length + (showCost ? 1 : 0)} renderRow={row} /> : <tbody>{data.items.map(row)}</tbody>}
    </table></div>
    </>
  );
}

/** All branches on a phone: one card per product with the total, and a button that opens its branches. */
export function AllBranchesList({ data, showCost, searching = false, filterKey = '', pending = false, flat = false }: { data: AllBranchesStock; showCost: boolean } & Narrowing) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const g = useCategoryGroups({ screen: 'stock', items: data.items, categoryOf: (r) => r.category, subtotalOf: totalsSubtotal, resetKey: filterKey, searching, pending });
  const grouping = g.active && !flat;
  if (data.items.length === 0) return <EmptyState title="No items found.">Try another name or code, or buy stock to add items.</EmptyState>;
  const names = Object.fromEntries(data.branches.map((b) => [b.id ?? '', branchName(b)]));
  const card = (r: AllBranchesStock['items'][number]) => {
        const id = r.product_id ?? '';
        const expanded = open[id] === true;
        return (
          <li key={id} className="rt-card">
            <div className="rt-row">
              <span>
                <strong>{r.description}</strong>
                {!grouping && <><br /><CategoryLabel category={r.category} /></>}
                <br /><ItemPageLink productId={r.product_id} name={r.description} />
              </span>
              <span className="num"><strong>Total <Qty qty={r.total_qty ?? '0'} unit={r.unit} negative={r.negative} pack={r.standard_pack} /></strong></span>
            </div>
            {r.sell_minor !== undefined && <p className="hint"><PriceLine cost={showCost ? r.cost_minor : undefined} sell={r.sell_minor} /></p>}
            <button type="button" className="btn-sm" aria-expanded={expanded} onClick={() => setOpen({ ...open, [id]: !expanded })}>
              {expanded ? 'Hide branches' : 'Show branches'}
            </button>
            {expanded && (
              <div>
                {(r.balances ?? []).map((c) => (
                  <div key={c.branch_id} className="rt-row">
                    <span>{names[c.branch_id ?? '']}</span>
                    <span className="num"><Qty qty={c.qty ?? '0'} unit={r.unit} negative={c.negative} pack={r.standard_pack} /></span>
                  </div>
                ))}
              </div>
            )}
          </li>
        );
  };
  return (
    <>
      {!flat && <GroupControls state={g} />}
      <PriceTips cost={showCost} />
      {grouping ? <GroupedCards state={g} renderItem={card} /> : <ul style={{ listStyle: 'none', padding: 0 }}>{data.items.map(card)}</ul>}
    </>
  );
}

const LEVELS: { value: StockLevel | ''; label: string }[] = [
  { value: '', label: 'All items' },
  { value: 'out', label: 'Out of stock' },
  { value: 'low', label: 'Low stock' },
];

/**
 * Tabs for the stock lists: everything, out of stock (zero or less) or low stock (at or below the item's minimum, else 5 or fewer,
 * out included); out and low keep only items the shop has stocked (issue #490).
 */
export function StockLevelFilter({ level, onChange }: { level: StockLevel | ''; onChange: (level: StockLevel | '') => void }) {
  return (
    <>
      <Segmented label="Show" options={LEVELS} value={level} onChange={onChange} />
      {level === 'out' && <p className="hint">Items you have stocked with none left, or less than none.</p>}
      {level === 'low' && <Hint>Items you have stocked that are at or below their minimum, or 5 or fewer without one, including those out of stock.</Hint>}
    </>
  );
}

/** The items of an opened group of the grouped view (issue #350): the same tables as the flat list, filtered by what was opened, so Correct works here. */
function StockRows({ sel, filter, all, branchId }: { sel: ListSelection; filter: StockFilter; all: boolean; branchId: string | null }) {
  const { me } = useStaff();
  const canProfit = useProfitAccess();
  const phone = useIsPhone();
  const branch = sel.branchId ?? (all ? null : branchId);
  const categoryId = sel.categoryId ?? filter.categoryId;
  const level = filter.level || undefined;
  const one = useQuery({
    queryKey: ['retail', 'stock', branch, filter.query, categoryId, filter.negativeOnly, level ?? '', filter.includeInactive],
    queryFn: ({ signal }) => retail.listStock({ branchId: branch ?? '', query: filter.query, categoryId, negativeOnly: filter.negativeOnly, level, includeInactive: filter.includeInactive, signal }),
    enabled: branch !== null,
  });
  const many = useQuery({
    queryKey: allBranchesKey({ query: filter.query, categoryId, negativeOnly: filter.negativeOnly, level: level ?? '', includeClosed: filter.includeClosed, includeInactive: filter.includeInactive }),
    queryFn: ({ signal }) => retail.listStockAllBranches({ query: filter.query, categoryId, negativeOnly: filter.negativeOnly, level, includeClosed: filter.includeClosed, includeInactive: filter.includeInactive, signal }),
    enabled: branch === null,
  });
  const result = branch === null ? many : one;
  // Review of #729: the grouped view says where an item at 0 here is, as the flat list does.
  const elsewhere = useStockElsewhere(branch, one.data, { query: filter.query, categoryId, includeInactive: filter.includeInactive });
  const key = [branch ?? 'all', filter.query, categoryId, filter.negativeOnly, level ?? ''].join('|');
  return (
    <>
      {result.isPending && <SkeletonList label="Loading stock" />}
      <Problem error={result.error} />
      {branch !== null && one.data && <StockTable rows={one.data} showCost={canProfit} flat filterKey={key} correctable={mayCorrect(me, branch) ? { branchId: branch } : undefined} elsewhere={elsewhere} />}
      {branch === null && many.data && (phone ? <AllBranchesList data={many.data} showCost={canProfit} flat filterKey={key} /> : <AllBranchesTable data={many.data} showCost={canProfit} flat filterKey={key} />)}
    </>
  );
}

export function StockPage() {
  const { me } = useStaff();
  const { all, branchId } = useBranchView();
  const canProfit = useProfitAccess();
  const phone = useIsPhone();
  const list = useListView(listUser(me), 'stock', STOCK_ORDER);
  const grouped = list.view === 'grouped';
  const search = useLiveSearch();
  // Issue #690: digits that are a known barcode search by that item's code.
  const { query, match, looking } = useFindTerm(search.term);
  const [negativeOnly, setNegativeOnly] = useState(false);
  const [level, setLevel] = useState<StockLevel | ''>('');
  // Closed branches that still hold stock are left out of the whole-business list unless asked for (#329).
  const [showClosed, setShowClosed] = useState(false);
  // An inactive item (issue #342) leaves the list unless asked for; its history and the reports keep it.
  const [showInactive, setShowInactive] = useState(false);
  const categories = useQuery({ queryKey: ['retail', 'categories'], queryFn: () => retail.listCategories() });
  const [categoryId, setCategoryId, chipReady] = useChosenChip('stock', categories.data, categories.isError);
  const one = useQuery({
    queryKey: ['retail', 'stock', branchId, query, categoryId, negativeOnly, level, showInactive],
    queryFn: ({ signal }) => retail.listStock({ branchId: branchId ?? '', query, categoryId, negativeOnly, level: level || undefined, includeInactive: showInactive, signal }),
    enabled: !all && branchId !== null && !grouped && chipReady,
    placeholderData: keepPreviousData,
  });
  const many = useQuery({
    queryKey: allBranchesKey({ query, categoryId, negativeOnly, level, includeClosed: showClosed, includeInactive: showInactive }),
    queryFn: ({ signal }) => retail.listStockAllBranches({ query, categoryId, negativeOnly, level: level || undefined, includeClosed: showClosed, includeInactive: showInactive, signal }),
    enabled: all && !grouped && chipReady,
    placeholderData: keepPreviousData,
  });
  // Issue #728: with an item at 0 here, the other shops this person may read are asked what they hold.
  const elsewhere = useStockElsewhere(all ? null : branchId, one.data, { query, categoryId, includeInactive: showInactive }, !grouped && chipReady);
  const result = all ? many : one;
  const finding = result.isPlaceholderData || (result.isFetching && result.fetchStatus !== 'idle');
  // A known barcode shows its own item alone, not the items whose code only begins the same.
  const oneRows = onlyMatch(one.data, match, (r) => r.product_id);
  const manyData = many.data && { ...many.data, items: onlyMatch(many.data.items, match, (r) => r.product_id) ?? [] };
  const shown = all ? manyData?.items.length : oneRows?.length;
  // The whole-business view is the stock "by location": a last crumb says so (components/breadcrumbs.tsx).
  useCrumbTail(all ? 'By location' : undefined);
  const stockFilter: StockFilter = { query, categoryId, negativeOnly, level, includeClosed: showClosed, includeInactive: showInactive };
  const shopChosen = !all && branchId !== null;
  // On a phone the chips stand for the groups, so the list is one plain list with the category under each item (issue #690).
  const narrowing = { pending: result.isPlaceholderData, searching: query !== '', flat: phone, filterKey: [all ? 'all' : branchId, query, categoryId, negativeOnly, level, showInactive].join('|') };
  return (
    <Gate screen="stock" title="Stock">
      {/* Issue #690: Items and Categories one tap from My stock, before anything else. */}
      <nav className="find-entries" aria-label="Items and categories">
        <ScreenLink screen="products" to="/staff/retail/catalogue/products">Items</ScreenLink>
        <ScreenLink screen="categories" to="/staff/retail/catalogue/categories">Categories</ScreenLink>
      </nav>
      <FindBar tour="stock-search">
        <SearchBox id="stock-search" label="Search by name, code, barcode or category" search={search} fetching={finding} count={shown} />
        <CategoryChips categories={categories.data ?? []} value={categoryId} onChange={setCategoryId} />
      </FindBar>
      {!all && branchId !== null && <NoStockHere branchId={branchId} />}
      {!all && branchId !== null && <UnfinishedCounts branchId={branchId} />}
      <ViewSwitch view={list.view} onChange={list.setView} />
      <StockLevelFilter level={level} onChange={setLevel} />
      <label style={{ fontWeight: 400 }}>
        <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={negativeOnly} onChange={(e) => setNegativeOnly(e.target.checked)} />
        Show only negative stock
      </label>
      <label style={{ fontWeight: 400 }}>
        <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} />
        Show inactive items
      </label>
      {all && grouped && (
        <label style={{ fontWeight: 400 }}>
          <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} />
          Include closed shops that still hold stock
        </label>
      )}
      {all && !grouped && (showClosed || (many.data?.closed_branches ?? 0) > 0) && (
        <label style={{ fontWeight: 400 }}>
          <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} />
          {showClosed ? 'Showing closed branches' : `${many.data?.closed_branches ?? 0} closed ${many.data?.closed_branches === 1 ? 'shop' : 'shops'} with stock hidden. Show them`}
        </label>
      )}
      {!all && branchId === null && <BranchRequired permissions={needsOf('stock')} />}
      {grouped && (all || branchId !== null) && (
        <>
          <ListGroupBy chain={list.chain} order={STOCK_ORDER} names={STOCK_NAMES} onChange={list.setChain} />
          <div data-tour="stock-list">
            <GroupedStock
              view={list} filter={stockFilter} chain={withoutShop(list.chain, shopChosen)} enabled range={shopChosen ? { branchId } : {}}
              renderRows={(sel) => <StockRows sel={sel} filter={stockFilter} all={all} branchId={branchId} />}
            />
          </div>
        </>
      )}
      {!grouped && result.isPending && (all || branchId !== null) && <SkeletonList label="Loading stock" />}
      {!grouped && <Problem error={result.error} />}
      {!grouped && shown === 0 && search.term && !looking && (
        <NoItem
          term={search.term}
          onClear={search.clear}
          category={(categories.data ?? []).find((c) => c.id === categoryId)?.name}
          onSearchAll={() => { setCategoryId(''); setLevel(''); setNegativeOnly(false); setShowInactive(true); }}
          filtered={level !== '' || negativeOnly}
        />
      )}
      {!grouped && (
      <div data-tour="stock-list">
      <SearchResults fetching={finding}>
        {!all && oneRows && <StockTable rows={oneRows} showCost={canProfit} correctable={mayCorrect(me, branchId) && branchId !== null ? { branchId } : undefined} elsewhere={elsewhere} {...narrowing} />}
        {all && manyData && (phone ? <AllBranchesList data={manyData} showCost={canProfit} {...narrowing} /> : <AllBranchesTable data={manyData} showCost={canProfit} {...narrowing} />)}
      </SearchResults>
      </div>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stock')({ component: StockPage });

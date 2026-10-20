import { keepPreviousData, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday } from '../../../api/retail';
import { retailControls, type ControlsCard, type ControlsGroup, type ControlsNarrowedRange, type ControlsRecord, type ControlsSummary, type Metric } from '../../../api/retail-controls';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { DateRange, Choice, Empty, Section, rangeProblem } from './analytics-ui';
import {
  CONTROLS_LEVELS, LEVEL_NAMES, LEVEL_PLURALS, PERIODS, WITHIN_CHOICES, approverWords, canOpen, chainFor, csvOf, daysIn, dayWords, groupLine, groupTitle, groupsCsv, isLineMetric, isStockMetric, orderedCards,
  patternSentence, periodRange, previousWords, rateWords, readControlsChain, recordsCsv, selectGroup, shareWords, trendWords, whenWords, whoWords, wordsOf, writeControlsChain,
  type ControlsLevel, type Narrow, type PeriodKind,
} from './controls-state';
import { canUse } from './permissions';
import { GroupTrail } from './sales-chain';
import { chainUser, effectiveChain, pathKey, shutGroup, toggleGroup, type OpenGroups } from './sales-chain-state';
import { GroupBy } from './sales-group-by';
import { OpenSale } from './sales';
import { Gate, Problem, money, useBranchView } from './ui';

// The Controls page (issue #357, group 1; ADR-047): what deserves a second look, metrics first. Cards for the chosen
// days with the trend against the days before, then the same numbers by shop, person, day and reason (the "Group by"
// pattern of the sales lists), each group opening onto the individual records, and the people or items that stand
// out. Read only: nothing here changes a record. Values are sale prices; the below-cost card exists only when the
// server sent it (retail.profit.read). The server counts everything; nothing here adds up pages.

/** Saves text as a file on the person's device. The text is built by controls-state, so a spreadsheet never runs it as a formula. */
function download(name: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}

function ExportButton({ name, text }: { name: string; text: string }) {
  return <button type="button" className="btn-sm" onClick={() => download(name, text)}>Export CSV</button>;
}

/** The days, the shop and the cancel window the whole page is looking at. */
interface Base {
  branchId?: string;
  from: string;
  to: string;
  withinMinutes: number;
}

function Card({ card, selected, onPick, before, within }: { card: ControlsCard; selected: boolean; onPick: () => void; before: string; within: number }) {
  const words = wordsOf(card.metric as Metric);
  const count = card.count ?? 0;
  const lines = isLineMetric(card.metric ?? '');
  const sales = card.sales_affected ?? 0;
  return (
    <li>
      <button type="button" className="an-stat ctl-card" aria-pressed={selected} onClick={onPick} data-metric={card.metric}>
        <span className="an-stat-label">{words.title}</span>
        <strong className="an-stat-value num">{count}</strong>
        {card.tracked === false ? (
          <span className="hint">Not switched on for this business, so nothing is recorded.</span>
        ) : (
          <>
            <span className="hint">{count === 0 ? 'Nothing in these days' : lines ? `${count} ${count === 1 ? 'line' : 'lines'} in ${sales} ${sales === 1 ? 'sale' : 'sales'}, lines worth ${money(card.value_minor ?? 0)}` : `Worth ${money(card.value_minor ?? 0)}`}</span>
            {!isStockMetric(card.metric ?? '') && <span className="hint">{rateWords(card.rate_per_100)}</span>}
            <span className="hint">{trendWords(count, card.previous_count ?? 0, before)}</span>
            {!isStockMetric(card.metric ?? '') && shareWords(card.share_of_sales_bp) && count > 0 && <span className="hint">{shareWords(card.share_of_sales_bp)}</span>}
          </>
        )}
        <span className="visually-hidden">{words.what(within)}</span>
      </button>
    </li>
  );
}

export function ControlsCards({ data, metric, onPick, within }: { data: ControlsSummary; metric: Metric; onPick: (m: Metric) => void; within: number }) {
  const range = { from: data.period?.from ?? '', to: data.period?.to ?? '' };
  const before = previousWords(range);
  const cards = orderedCards(data.cards);
  return (
    <>
      <p className="hint">
        {daysIn(range) === 1 ? dayWords(range.from) : `${dayWords(range.from)} to ${dayWords(range.to)}`}: {data.period?.sales_count ?? 0} sales entered, {money(data.period?.sales_minor ?? 0)} sold.
        Each count is set against {before}.
      </p>
      <ul className="an-stats ctl-cards">
        {cards.map((c) => (
          <Card key={c.metric} card={c} selected={c.metric === metric} onPick={() => onPick(c.metric as Metric)} before={before} within={within} />
        ))}
      </ul>
      {data.cost_visible !== true && <p className="hint">Sales below cost are shown only to people who may read profit.</p>}
    </>
  );
}

interface Shared {
  base: Base;
  metric: Metric;
  chain: ControlsLevel[];
  open: OpenGroups;
  toggle: (key: string, label: string) => void;
  onOpenSale?: (id: string) => void;
}

const rangeOf = (base: Base, sel: Narrow): ControlsNarrowedRange => ({
  branchId: sel.branchId ?? base.branchId, from: sel.from ?? base.from, to: sel.to ?? base.to, withinMinutes: base.withinMinutes, userId: sel.userId, approverId: sel.approverId, reason: sel.reason,
});

function Records({ sel, base, metric, onOpenSale }: { sel: Narrow; base: Base; metric: Metric; onOpenSale?: (id: string) => void }) {
  const query = useInfiniteQuery({
    queryKey: ['retail', 'controls', 'records', metric, base, sel],
    queryFn: ({ pageParam, signal }) => retailControls.records(metric, rangeOf(base, sel), pageParam, signal),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.has_more ? last.next_offset : undefined),
    placeholderData: keepPreviousData,
  });
  if (query.isPending) return <p className="loading">Loading</p>;
  if (query.error) return <Problem error={query.error} />;
  const records = (query.data?.pages ?? []).flatMap((p) => p.items ?? []);
  if (records.length === 0) return <Empty>Nothing here.</Empty>;
  return (
    <>
      <ul className="rt-group-list ctl-records">
        {records.map((r, i) => <RecordRow key={`${r.sale_id}-${r.item ?? ''}-${i}`} record={r} metric={metric} onOpenSale={onOpenSale} />)}
      </ul>
      {query.hasNextPage && (
        <button type="button" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? 'Loading' : 'Show more'}</button>
      )}
      <ExportButton name={`controls-${metric}-records.csv`} text={recordsCsv(records, metric)} />
    </>
  );
}

function RecordRow({ record: r, metric, onOpenSale }: { record: ControlsRecord; metric: Metric; onOpenSale?: (id: string) => void }) {
  return (
    <li className="rt-group ctl-record">
      <div className="ctl-record-line">
        <span className="rt-group-title">{r.sale_no ?? (metric === 'quantity_corrections' ? 'Stock correction' : '')}{r.reference ? `, credit note ${r.reference}` : ''}</span>
        <span className="rt-group-total">{money(r.value_minor ?? 0)}</span>
      </div>
      <span className="rt-group-meta">{whenWords(r.at)}. {metric === 'restock_voids' ? 'Restock entered by' : metric === 'quantity_corrections' ? 'Corrected by' : 'Sale by'} {whoWords(r)}{r.branch_name ? ` at ${r.branch_name}` : ''}.{approverWords(r, metric) ? ` ${approverWords(r, metric)}.` : ''}</span>
      <span className="rt-group-meta">{r.reason ? `Reason: ${r.reason}` : 'No reason recorded'}{r.item ? `. Item: ${r.item}` : ''}</span>
      {onOpenSale && r.sale_id && !isStockMetric(metric) && <button type="button" className="btn-sm" onClick={() => onOpenSale(r.sale_id as string)}>Open sale {r.sale_no}</button>}
    </li>
  );
}

function LevelList({ index, sel, parent, root, ...shared }: Shared & { index: number; sel: Narrow; parent: string; root?: boolean }) {
  const { base, metric, chain } = shared;
  const level = chain[index] ?? 'branch';
  const words = wordsOf(metric);
  const query = useInfiniteQuery({
    queryKey: ['retail', 'controls', 'groups', metric, chain, level, base, sel],
    queryFn: ({ pageParam, signal }) => retailControls.groups(metric, chain, level, rangeOf(base, sel), pageParam, signal),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.has_more ? last.next_offset : undefined),
    placeholderData: keepPreviousData,
  });
  if (query.isPending) return <p className="loading">Loading</p>;
  if (query.error) {
    return (
      <>
        <Problem error={query.error} />
        <button type="button" onClick={() => void query.refetch()}>Try again</button>
      </>
    );
  }
  const items = (query.data?.pages ?? []).flatMap((p) => p.items ?? []);
  if (items.length === 0) return <Empty>{root ? `No ${words.title.toLowerCase()} in these days.` : 'Nothing here.'}</Empty>;
  return (
    <>
      <ul className="rt-group-list">
        {items.map((g) => <GroupRow key={g.key} index={index} level={level} group={g} sel={sel} parent={parent} {...shared} />)}
      </ul>
      {query.hasNextPage && (
        <button type="button" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? 'Loading' : `Show more ${LEVEL_PLURALS[level]}`}</button>
      )}
      <ExportButton name={`controls-${metric}-by-${level}.csv`} text={groupsCsv(level, items, metric)} />
    </>
  );
}

function GroupRow({ index, level, group, sel, parent, ...shared }: Shared & { index: number; level: ControlsLevel; group: ControlsGroup; sel: Narrow; parent: string }) {
  const { chain, open, toggle, metric, base, onOpenSale } = shared;
  const key = group.key ?? '';
  const path = pathKey(parent, level, key);
  const title = groupTitle(level, group);
  const isOpen = open.has(path);
  const here = selectGroup(sel, level, group);
  const last = index === chain.length - 1;
  const bodyId = `ctl-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
  if (!canOpen(level, group)) {
    // Opening it would ask for everybody's records: it is shown and counted, not opened.
    return (
      <li className="rt-group">
        <div className="rt-group-head">
          <span className="rt-group-title">{title}</span>{' '}
          <span className="rt-group-total">{group.count ?? 0}</span>{' '}
          <span className="rt-group-meta">{groupLine(group, metric, level, money)}. The records are not listed because this one is not known.</span>
        </div>
      </li>
    );
  }
  return (
    <li className="rt-group">
      <button type="button" className="rt-group-head" aria-expanded={isOpen} aria-controls={isOpen ? bodyId : undefined} onClick={() => toggle(path, title)}>
        <span className="rt-group-title">{title}</span>{' '}
        <span className="rt-group-total">{group.count ?? 0}</span>{' '}
        <span className="rt-group-meta">{groupLine(group, metric, level, money)}</span>
      </button>
      {isOpen && (
        <div className="rt-group-body" id={bodyId}>
          {last ? (
            <Records sel={here} base={base} metric={metric} onOpenSale={onOpenSale} />
          ) : (
            <>
              <LevelList index={index + 1} sel={here} parent={path} {...shared} />
              <ShowRecords sel={here} base={base} metric={metric} onOpenSale={onOpenSale} />
            </>
          )}
        </div>
      )}
    </li>
  );
}

/** A group that opens onto more groups can still list its records: on request, so the groups stay the first thing seen. */
function ShowRecords({ sel, base, metric, onOpenSale }: { sel: Narrow; base: Base; metric: Metric; onOpenSale?: (id: string) => void }) {
  const [shown, setShown] = useState(false);
  return (
    <div className="rt-day-sales">
      <button type="button" aria-expanded={shown} onClick={() => setShown(!shown)}>{shown ? 'Hide the records here' : 'Show the records here'}</button>
      {shown && <Records sel={sel} base={base} metric={metric} onOpenSale={onOpenSale} />}
    </div>
  );
}

function Patterns({ base }: { base: Base }) {
  const query = useQuery({ queryKey: ['retail', 'controls', 'patterns', base.branchId, base.from, base.to], queryFn: ({ signal }) => retailControls.patterns(base, signal), placeholderData: keepPreviousData });
  const items = query.data?.items ?? [];
  return (
    <Section title="Repeat patterns" hint={`Someone whose sales, or an item, come up more than 2 times as often as in their own shop. A person is whose sales they were, not who cancelled them. Only from ${query.data?.min_events ?? 3} cases over at least ${query.data?.min_out_of ?? 10} sales, so a small number never shouts. A pattern is a reason to look, not a finding. The list is by shop and name, not a ranking.`}>
      <div data-tour="controls-patterns">
        {query.isPending && <p className="loading">Loading</p>}
        <Problem error={query.error} />
        {query.error && <button type="button" onClick={() => void query.refetch()}>Try again</button>}
        {query.data && items.length === 0 && <Empty>Nothing stands out in these days.</Empty>}
        {items.length > 0 && (
          <ul className="ctl-patterns">
            {items.map((p) => <li key={`${p.subject}-${p.key}-${p.branch_id}-${p.metric}`}>{patternSentence(p, p.branch_name ?? 'the shop')}</li>)}
          </ul>
        )}
        {items.length > 0 && <ExportButton name="controls-patterns.csv" text={csvOf([['Who or what', 'Shop', 'What', 'Cases', 'Out of', 'Per 100', 'Shop per 100', 'Times'], ...items.map((p) => [p.name, p.branch_name, p.metric, p.count ?? 0, p.out_of ?? 0, p.rate_per_100, p.shop_rate_per_100, p.times])])} />}
      </div>
    </Section>
  );
}

export function ControlsPage() {
  const { me } = useStaff();
  const { all, branchId, branchName } = useBranchView();
  const today = businessToday();
  const [kind, setKind] = useState<PeriodKind>('week');
  const [custom, setCustom] = useState({ from: periodRange('month', today, { from: today, to: today }).from, to: today });
  const [within, setWithin] = useState(10);
  const [metric, setMetric] = useState<Metric>('voided_sales');
  const [openSale, setOpenSale] = useState<string | null>(null);
  const [chain, setChainState] = useState<ControlsLevel[]>(() => readControlsChain(chainUser(me)));
  const [open, setOpen] = useState<OpenGroups>(new Map());
  const range = periodRange(kind, today, custom);
  const problem = kind === 'custom' ? rangeProblem(range.from, range.to) : null;
  const base: Base = { branchId: all ? undefined : (branchId ?? undefined), from: range.from, to: range.to, withinMinutes: within };
  // Nothing is asked of the server for someone the page is shut to.
  const ready = canUse(me, 'controls') && !problem && (all || branchId !== null);
  const summary = useQuery({
    queryKey: ['retail', 'controls', 'summary', base],
    queryFn: ({ signal }) => retailControls.summary(base, signal),
    enabled: ready,
    placeholderData: keepPreviousData,
  });
  const chosen = metric !== 'below_cost' || summary.data?.cost_visible === true ? metric : 'voided_sales';
  const shown = chainFor(chosen, effectiveChain(chain, !all));
  const setChain = (next: ControlsLevel[]) => {
    setChainState(next);
    writeControlsChain(chainUser(me), next);
    setOpen(new Map());
  };
  const view = { open, shut: (key: string) => setOpen((o) => shutGroup(o, key)), shutAll: () => setOpen(new Map()) };
  const words = wordsOf(chosen);
  const canOpenSale = canUse(me, 'salesHistory');
  return (
    <Gate screen="controls" title="Controls" wide>
      {openSale ? (
        <OpenSale id={openSale} onClose={() => setOpenSale(null)} />
      ) : (
        <>
          <p className="hint">What deserves a second look: sales cancelled, goods taken back and prices changed. Nothing here changes any record.</p>
          <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
          <div data-tour="controls-period">
            <div className="rt-periods" role="group" aria-label="Days to look at">
              {PERIODS.map((p) => (
                <button key={p.kind} type="button" aria-pressed={kind === p.kind} className={kind === p.kind ? 'btn-sm ctl-period-on' : 'btn-sm'} onClick={() => { setKind(p.kind); setOpen(new Map()); }}>{p.label}</button>
              ))}
            </div>
            {kind === 'custom' && <DateRange from={custom.from} to={custom.to} onFrom={(from) => setCustom({ ...custom, from })} onTo={(to) => setCustom({ ...custom, to })} />}
            <Choice id="ctl-within" label="Cancelled at once means within" value={within} options={WITHIN_CHOICES.map((n) => ({ value: n, label: `${n} minutes` }))} onChange={setWithin} />
          </div>

          <section aria-label="Figures" data-tour="controls-cards">
            {summary.isPending && ready && <SkeletonList label="Loading the figures" />}
            <Problem error={summary.error} />
            {summary.error && <button type="button" onClick={() => void summary.refetch()}>Try again</button>}
            {summary.data && <ControlsCards data={summary.data} metric={chosen} onPick={(m) => { setMetric(m); setOpen(new Map()); }} within={within} />}
          </section>

          <Section title={words.title} hint={`${words.what(within)}${isLineMetric(chosen) ? '' : ' Person is whose sale it was; Approver is who cancelled it or took the goods back.'} Tap a line to open it.`}>
            <div data-tour="controls-tables">
              <GroupBy chain={chain} levels={CONTROLS_LEVELS} names={LEVEL_NAMES} tour="controls-group-by" onChange={(next) => setChain(next)} />
              {shown.length > 0 && ready && (
                <div className="rt-groups">
                  <GroupTrail view={view} rootLabel={words.title} />
                  <LevelList
                    key={`${chosen}-${shown.join()}`}
                    index={0} sel={{}} parent="" root base={base} metric={chosen} chain={shown} open={open}
                    toggle={(key, label) => setOpen((o) => toggleGroup(o, key, label))} onOpenSale={canOpenSale ? setOpenSale : undefined}
                  />
                </div>
              )}
              <p className="hint">A rate is the sales with this per 100 sales the person, shop or day entered, so a busy person and a quiet one can be compared. The lists are by name, not a ranking.</p>
            </div>
          </Section>

          {ready && <Patterns base={base} />}
          <p className="hint">Imported history is not counted.</p>
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/controls')({ component: ControlsPage });

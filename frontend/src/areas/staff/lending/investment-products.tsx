import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { investments, type InvestmentProduct, type InvestmentProductTerms } from '../../../api/investments';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { PRODUCTS_MANAGE, may, parsePercent, payoutWords, percent } from './investment-state';
import { InvestmentsGate } from './investments';
import { formatMinor } from '../../../components/money';
import { currencyOf, money, parseAmount, words } from './loan-state';

// Investment products setup (FR-INV-01, FR-INV-08): the list for everyone who reads investments,
// and for a tenant admin the form to create one, edit it (investments already opened keep their
// terms) or archive it. Rates are typed as percentages a year and sent as basis points.

export interface ProductDraft {
  code: string;
  name: string;
  productType: 'fixed_term' | 'recurring';
  terms: string;
  rate: string;
  method: 'flat' | 'compound';
  payout: 'at_maturity' | 'monthly' | 'quarterly';
  min: string;
  max: string;
  early: boolean;
  rule: 'forfeit_return' | 'reduced_rate';
  earlyRate: string;
  penalty: string;
}

export const EMPTY: ProductDraft = {
  code: '',
  name: '',
  productType: 'fixed_term',
  terms: '3, 6, 12',
  rate: '',
  method: 'flat',
  payout: 'at_maturity',
  min: '',
  max: '',
  early: false,
  rule: 'reduced_rate',
  earlyRate: '',
  penalty: '0',
};

/** Minor units as a person would type them back: "1,500,000" for UGX, no currency code. */
const amountText = (minor: number, currency: string | undefined) => formatMinor(minor, currencyOf(currency)).replace(/^\S+ /, '');

export function draftOf(p: InvestmentProduct): ProductDraft {
  return {
    code: p.code ?? '',
    name: p.name ?? '',
    productType: (p.product_type as ProductDraft['productType']) ?? 'fixed_term',
    terms: (p.allowed_terms_months ?? []).join(', '),
    rate: percent(p.return_rate_bp).replace('%', ''),
    method: (p.return_method as ProductDraft['method']) ?? 'flat',
    payout: (p.payout_frequency as ProductDraft['payout']) ?? 'at_maturity',
    min: amountText(p.min_amount_minor ?? 0, p.currency),
    max: p.max_amount_minor ? amountText(p.max_amount_minor, p.currency) : '',
    early: p.early_withdrawal_allowed ?? false,
    rule: (p.early_withdrawal_rule as ProductDraft['rule']) ?? 'reduced_rate',
    earlyRate: p.early_withdrawal_rate_bp !== undefined ? percent(p.early_withdrawal_rate_bp).replace('%', '') : '',
    penalty: percent(p.early_withdrawal_penalty_bp ?? 0).replace('%', ''),
  };
}

/** The terms the server expects, or the first problem in words. */
export function termsOf(d: ProductDraft, currency: string | undefined): InvestmentProductTerms | string {
  const terms = d.terms.split(/[,\s]+/).filter(Boolean).map(Number);
  if (terms.length === 0 || terms.some((t) => !Number.isInteger(t) || t < 1 || t > 120)) return 'Terms are whole months from 1 to 120, separated by commas.';
  const rate = parsePercent(d.rate);
  if (rate === null) return 'Enter the return rate as a percentage a year, for example 12 or 12.5.';
  const min = parseAmount(d.min, currency);
  if (min === null) return 'Enter the minimum amount.';
  const max = d.max.trim() === '' ? undefined : parseAmount(d.max, currency);
  if (max === null) return 'Enter a valid maximum, or leave it empty.';
  if (d.method === 'compound' && d.payout !== 'at_maturity') return 'A compounding return is paid at maturity only.';
  const earlyRate = d.early && d.rule === 'reduced_rate' ? parsePercent(d.earlyRate) : undefined;
  if (earlyRate === null) return 'Enter the reduced rate for early withdrawal.';
  const penalty = d.early ? parsePercent(d.penalty) : 0;
  if (penalty === null) return 'Enter the penalty as a percentage of principal, or 0.';
  return {
    product_type: d.productType,
    allowed_terms_months: terms,
    return_rate_bp: rate,
    return_method: d.method,
    payout_frequency: d.payout,
    min_amount_minor: min,
    max_amount_minor: max,
    early_withdrawal_allowed: d.early,
    early_withdrawal_rule: d.early ? d.rule : undefined,
    early_withdrawal_rate_bp: earlyRate,
    early_withdrawal_penalty_bp: d.early ? penalty : undefined,
  };
}

function ProductForm({ product, currency, onDone }: { product?: InvestmentProduct; currency?: string; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [d, setD] = useState<ProductDraft>(product ? draftOf(product) : EMPTY);
  const update = (patch: Partial<ProductDraft>) => setD((x) => ({ ...x, ...patch }));
  const terms = termsOf(d, currency);
  const problem = typeof terms === 'string' ? terms : null;
  const save = useMutation({
    mutationFn: () => {
      const t = terms as InvestmentProductTerms;
      return product
        ? investments.updateProduct(product.id ?? '', product.version ?? 1, { name: d.name.trim(), terms: t })
        : investments.createProduct({ code: d.code.trim().toUpperCase(), name: d.name.trim(), terms: t });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['lending', 'investment-products'] });
      onDone();
    },
  });
  const field = (id: string, label: string, value: string, set: (v: string) => void, hint?: string, mode?: 'decimal') => (
    <>
      <label htmlFor={id}>{label}</label>
      <input id={id} value={value} inputMode={mode} onChange={(e) => set(e.target.value)} autoComplete="off" />
      {hint && <p className="field-hint">{hint}</p>}
    </>
  );
  return (
    <form aria-label={product ? `Edit ${product.code ?? ''}` : 'New investment product'} onSubmit={(e) => { e.preventDefault(); if (!problem && !save.isPending) save.mutate(); }}>
      {!product && field('ip-code', 'Code', d.code, (v) => update({ code: v }), 'Capital letters and digits, for example FD12.')}
      {field('ip-name', 'Name', d.name, (v) => update({ name: v }))}
      <fieldset>
        <legend>Kind</legend>
        <label>
          <input type="radio" name="ip-type" checked={d.productType === 'fixed_term'} onChange={() => update({ productType: 'fixed_term' })} />
          Fixed term: waits for the member's choice at maturity
        </label>
        <label>
          <input type="radio" name="ip-type" checked={d.productType === 'recurring'} onChange={() => update({ productType: 'recurring' })} />
          Recurring: renews itself at maturity unless the member asks for a payout
        </label>
      </fieldset>
      {field('ip-terms', 'Terms offered (months)', d.terms, (v) => update({ terms: v }), 'For example 3, 6, 12.')}
      {field('ip-rate', 'Return rate (% a year)', d.rate, (v) => update({ rate: v }), undefined, 'decimal')}
      <label htmlFor="ip-method">Return</label>
      <select id="ip-method" value={d.method} onChange={(e) => update({ method: e.target.value as ProductDraft['method'] })}>
        <option value="flat">Flat on the amount invested</option>
        <option value="compound">Compounding monthly</option>
      </select>
      <label htmlFor="ip-payout">Return paid</label>
      <select id="ip-payout" value={d.payout} onChange={(e) => update({ payout: e.target.value as ProductDraft['payout'] })}>
        {(['at_maturity', 'monthly', 'quarterly'] as const).map((p) => (
          <option key={p} value={p}>{payoutWords(p)}</option>
        ))}
      </select>
      {field('ip-min', `Minimum amount (${currency ?? 'UGX'})`, d.min, (v) => update({ min: v }), undefined, 'decimal')}
      {field('ip-max', `Maximum amount (${currency ?? 'UGX'}, optional)`, d.max, (v) => update({ max: v }), undefined, 'decimal')}
      <label>
        <input type="checkbox" checked={d.early} onChange={(e) => update({ early: e.target.checked })} />
        Early withdrawal allowed
      </label>
      {d.early && (
        <>
          <fieldset>
            <legend>Return on early withdrawal</legend>
            <label>
              <input type="radio" name="ip-rule" checked={d.rule === 'reduced_rate'} onChange={() => update({ rule: 'reduced_rate' })} />
              Earns a reduced rate for the time held
            </label>
            <label>
              <input type="radio" name="ip-rule" checked={d.rule === 'forfeit_return'} onChange={() => update({ rule: 'forfeit_return' })} />
              Forfeits the return (returns already paid are taken back)
            </label>
          </fieldset>
          {d.rule === 'reduced_rate' && field('ip-early-rate', 'Reduced rate (% a year)', d.earlyRate, (v) => update({ earlyRate: v }), undefined, 'decimal')}
          {field('ip-penalty', 'Penalty (% of principal)', d.penalty, (v) => update({ penalty: v }), 'Taken from the principal paid back; 0 for none.', 'decimal')}
        </>
      )}
      {problem && d.rate !== '' && <p className="field-hint">{problem}</p>}
      <Problem error={save.error} />
      <button type="submit" className="rt-primary btn-block" disabled={!!problem || d.name.trim() === '' || save.isPending}>
        {save.isPending ? 'Saving' : product ? 'Save changes' : 'Create product'}
      </button>
      {product && <p className="field-hint">Investments already opened keep the terms they were opened on.</p>}
    </form>
  );
}

export function ProductCard({ p, onEdit }: { p: InvestmentProduct; onEdit?: () => void }) {
  return (
    <li className="rt-card">
      <p className="ln-item-head">
        <strong>{p.code} {p.name}</strong>
        <span className={p.status === 'active' ? 'badge badge-success' : 'badge'}>{words(p.status)}</span>
      </p>
      <p className="ln-muted">
        {p.product_type === 'recurring' ? 'Recurring' : 'Fixed term'}, {percent(p.return_rate_bp)} a year{' '}
        {p.return_method === 'compound' ? 'compounding' : 'flat'}, paid {payoutWords(p.payout_frequency).toLowerCase()}
      </p>
      <p className="ln-muted">
        Terms {(p.allowed_terms_months ?? []).join(', ')} months; from {money(p.min_amount_minor, p.currency)}
        {p.max_amount_minor ? ` to ${money(p.max_amount_minor, p.currency)}` : ''}
      </p>
      <p className="ln-muted">
        Early withdrawal:{' '}
        {p.early_withdrawal_allowed
          ? `${p.early_withdrawal_rule === 'reduced_rate' ? `reduced rate ${percent(p.early_withdrawal_rate_bp)}` : 'return forfeited'}, penalty ${percent(p.early_withdrawal_penalty_bp)}`
          : 'not allowed'}
      </p>
      {onEdit && p.status === 'active' && <button type="button" onClick={onEdit}>Edit</button>}
    </li>
  );
}

function Products() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const manage = may(me, PRODUCTS_MANAGE);
  const [editing, setEditing] = useState<InvestmentProduct | 'new' | null>(null);
  const list = useQuery({ queryKey: ['lending', 'investment-products'], queryFn: () => investments.products() });
  const archive = useMutation({
    mutationFn: (p: InvestmentProduct) => investments.archiveProduct(p.id ?? '', p.version ?? 1),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['lending', 'investment-products'] }),
  });
  const currency = list.data?.[0]?.currency;
  return (
    <InvestmentsGate title="Investment products">
      {manage && editing === null && (
        <button type="button" className="rt-primary btn-block" onClick={() => setEditing('new')}>New product</button>
      )}
      {editing !== null && (
        <section className="rt-card" aria-label="Product form">
          <ProductForm product={editing === 'new' ? undefined : editing} currency={currency} onDone={() => setEditing(null)} />
          {editing !== 'new' && (
            <button type="button" className="btn-danger btn-block" disabled={archive.isPending} onClick={() => { archive.mutate(editing); setEditing(null); }}>
              Archive this product
            </button>
          )}
          <button type="button" className="btn-ghost" onClick={() => setEditing(null)}>Cancel</button>
        </section>
      )}
      {list.isPending && <p className="loading">Loading products</p>}
      <Problem error={list.error ?? archive.error} />
      {list.data && list.data.length === 0 && <p className="empty-state">No investment product yet.</p>}
      {list.data && (
        <ul className="ln-list">
          {list.data.map((p) => (
            <ProductCard key={p.id} p={p} onEdit={manage ? () => setEditing(p) : undefined} />
          ))}
        </ul>
      )}
    </InvestmentsGate>
  );
}

export const Route = createLazyRoute('/staff/lending/investments/products')({ component: Products });

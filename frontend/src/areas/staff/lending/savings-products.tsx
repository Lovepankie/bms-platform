import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { savings, type SavingsProduct, type SavingsProductTerms } from '../../../api/savings';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { money, parseAmount, words } from './loan-state';
import { SavingsGate } from './savings';
import { mayManageProducts } from './savings-permissions';

// Savings product set-up (FR-SAV-01): each product's interest rule, posting frequency, minimum
// and opening balances, withdrawal fee and limits, and dormancy days. Once an account uses a
// product its interest terms are locked (the server refuses a change); the other rules and the
// archive switch stay editable.

export interface ProductDraft {
  code: string;
  name: string;
  rate: string;
  calc: string;
  posting: string;
  minForInterest: string;
  minOpening: string;
  minBalance: string;
  fee: string;
  maxWithdrawal: string;
  maxPerMonth: string;
  dormancyDays: string;
}

export const EMPTY_PRODUCT: ProductDraft = {
  code: '',
  name: '',
  rate: '0',
  calc: 'none',
  posting: 'monthly',
  minForInterest: '',
  minOpening: '',
  minBalance: '',
  fee: '',
  maxWithdrawal: '',
  maxPerMonth: '',
  dormancyDays: '',
};

/** The rate typed as a percentage a year ("5", "7.25") in basis points, or null. */
export function percentToBp(text: string): number | null {
  const m = /^(\d{1,3})(?:\.(\d{1,2}))?$/.exec(text.trim());
  if (!m) return null;
  const bp = Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0') || '0');
  return bp <= 10_000 ? bp : null;
}

const optionalMoney = (text: string, currency: string): number | null | undefined =>
  text.trim() === '' ? undefined : parseAmount(text, currency);

const optionalCount = (text: string): number | null | undefined => {
  if (text.trim() === '') return undefined;
  return /^\d{1,4}$/.test(text.trim()) && Number(text) > 0 ? Number(text) : null;
};

/** The request terms from a draft, or the first problem in plain words. */
export function termsOf(d: ProductDraft, currency: string): SavingsProductTerms | string {
  if (d.name.trim() === '') return 'Give the product a name.';
  const bp = d.calc === 'none' ? 0 : percentToBp(d.rate);
  if (bp === null || (d.calc !== 'none' && bp === 0)) return 'Enter the interest rate as a percentage a year, above zero.';
  const fields = {
    min_balance_for_interest_minor: optionalMoney(d.minForInterest, currency),
    min_opening_balance_minor: optionalMoney(d.minOpening, currency),
    min_balance_minor: optionalMoney(d.minBalance, currency),
    withdrawal_fee_minor: optionalMoney(d.fee, currency),
    max_withdrawal_minor: optionalMoney(d.maxWithdrawal, currency),
  };
  if (Object.values(fields).some((v) => v === null)) return 'Check the amounts: each must be a number above zero, or left empty.';
  const perMonth = optionalCount(d.maxPerMonth);
  const dormancy = optionalCount(d.dormancyDays);
  if (perMonth === null || dormancy === null) return 'Check the counts: each must be a whole number above zero, or left empty.';
  return {
    name: d.name.trim(),
    interest_rate_bp: bp,
    interest_calc: d.calc,
    interest_posting: d.posting,
    ...(fields as Record<string, number | undefined>),
    max_withdrawals_per_month: perMonth,
    dormancy_days: dormancy,
  };
}

const text = (n: number | undefined | null, currency: string): string => {
  if (n === undefined || n === null || n === 0) return '';
  return money(n, currency).replace(/^[A-Z]{3} /, '').replace(/,/g, '');
};

export function draftOf(p: SavingsProduct): ProductDraft {
  const c = p.currency ?? 'UGX';
  return {
    code: p.code ?? '',
    name: p.name ?? '',
    rate: String((p.interest_rate_bp ?? 0) / 100),
    calc: p.interest_calc ?? 'none',
    posting: p.interest_posting ?? 'monthly',
    minForInterest: text(p.min_balance_for_interest_minor, c),
    minOpening: text(p.min_opening_balance_minor, c),
    minBalance: text(p.min_balance_minor, c),
    fee: text(p.withdrawal_fee_minor, c),
    maxWithdrawal: text(p.max_withdrawal_minor, c),
    maxPerMonth: p.max_withdrawals_per_month ? String(p.max_withdrawals_per_month) : '',
    dormancyDays: p.dormancy_days ? String(p.dormancy_days) : '',
  };
}

function Field({ id, label, hint, value, onChange, disabled, inputMode }: {
  id: string;
  label: string;
  hint?: string;
  value: string;
  onChange: (v: string) => void;
  disabled?: boolean;
  inputMode?: 'decimal' | 'numeric' | 'text';
}) {
  return (
    <>
      <label htmlFor={id}>{label}</label>
      <input id={id} value={value} disabled={disabled} inputMode={inputMode} onChange={(e) => onChange(e.target.value)} autoComplete="off"
        aria-describedby={hint ? `${id}-hint` : undefined} />
      {hint && <p id={`${id}-hint`} className="field-hint">{hint}</p>}
    </>
  );
}

export function ProductForm({ product, currency, onDone }: { product?: SavingsProduct; currency: string; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [d, setD] = useState<ProductDraft>(product ? draftOf(product) : EMPTY_PRODUCT);
  const [archived, setArchived] = useState(product?.status === 'archived');
  const locked = (product?.accounts ?? 0) > 0;
  const set = (patch: Partial<ProductDraft>) => setD((x) => ({ ...x, ...patch }));
  const terms = termsOf(d, currency);
  const codeOk = product !== undefined || /^[A-Z0-9][A-Z0-9-]{1,19}$/.test(d.code);
  const save = useMutation({
    mutationFn: () => {
      if (typeof terms === 'string') throw new Error(terms);
      return product
        ? savings.updateProduct(product.id ?? '', product.version ?? 0, terms, archived ? 'archived' : 'active')
        : savings.createProduct(d.code, terms);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['savings', 'products'] });
      onDone();
    },
  });
  return (
    <form aria-label={product ? `Edit ${product.code}` : 'New savings product'} onSubmit={(e) => { e.preventDefault(); if (codeOk && typeof terms !== 'string') save.mutate(); }}>
      {!product && (
        <Field id="sp-code" label="Code" hint="2 to 20 capital letters, digits or hyphens, for example SAVE-01." value={d.code}
          onChange={(v) => set({ code: v.toUpperCase() })} />
      )}
      <Field id="sp-name" label="Name" value={d.name} onChange={(v) => set({ name: v })} />
      {locked && <p className="alert alert-info">Accounts use this product, so its interest terms are locked.</p>}
      <label htmlFor="sp-calc">Interest</label>
      <select id="sp-calc" value={d.calc} disabled={locked} onChange={(e) => set({ calc: e.target.value })}>
        <option value="none">No interest</option>
        <option value="daily_balance">On each day's closing balance</option>
        <option value="minimum_monthly_balance">On the lowest balance of each month</option>
      </select>
      {d.calc !== 'none' && (
        <>
          <Field id="sp-rate" label="Rate, percent a year" value={d.rate} disabled={locked} inputMode="decimal" onChange={(v) => set({ rate: v })} />
          <label htmlFor="sp-posting">Interest is added</label>
          <select id="sp-posting" value={d.posting} disabled={locked} onChange={(e) => set({ posting: e.target.value })}>
            <option value="monthly">Monthly</option>
            <option value="quarterly">Quarterly</option>
            <option value="yearly">Yearly</option>
          </select>
          <Field id="sp-min-int" label={`Lowest balance that earns interest (${currency}, optional)`} value={d.minForInterest} disabled={locked}
            inputMode="decimal" onChange={(v) => set({ minForInterest: v })} />
        </>
      )}
      <Field id="sp-min-open" label={`First deposit at least (${currency}, optional)`} value={d.minOpening} inputMode="decimal" onChange={(v) => set({ minOpening: v })} />
      <Field id="sp-min-bal" label={`Balance always kept (${currency}, optional)`} value={d.minBalance} inputMode="decimal" onChange={(v) => set({ minBalance: v })} />
      <Field id="sp-fee" label={`Fee per withdrawal (${currency}, optional)`} value={d.fee} inputMode="decimal" onChange={(v) => set({ fee: v })} />
      <Field id="sp-max-wd" label={`Most one withdrawal may take (${currency}, optional)`} value={d.maxWithdrawal} inputMode="decimal"
        onChange={(v) => set({ maxWithdrawal: v })} />
      <Field id="sp-per-month" label="Withdrawals allowed a month (optional)" value={d.maxPerMonth} inputMode="numeric" onChange={(v) => set({ maxPerMonth: v })} />
      <Field id="sp-dormancy" label="Days without activity before dormant (optional)" value={d.dormancyDays} inputMode="numeric"
        onChange={(v) => set({ dormancyDays: v })} />
      {product && (
        <label>
          <input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} />
          Archived: no new accounts on this product
        </label>
      )}
      {typeof terms === 'string' && d.name !== '' && <p className="field-hint">{terms}</p>}
      <Problem error={save.error} />
      <button type="submit" className="rt-primary btn-block" disabled={!codeOk || typeof terms === 'string' || save.isPending}>
        {save.isPending ? 'Saving' : product ? 'Save changes' : 'Create product'}
      </button>
      <button type="button" className="btn-block" onClick={onDone}>Cancel</button>
    </form>
  );
}

export function ProductRow({ p }: { p: SavingsProduct }) {
  const c = p.currency ?? 'UGX';
  return (
    <>
      <p className="ln-item-head">
        <strong>{p.code}</strong>
        <span className={p.status === 'active' ? 'badge badge-success' : 'badge'}>{words(p.status)}</span>
      </p>
      <p>{p.name}</p>
      <p className="ln-item-facts">
        <span>{p.interest_calc === 'none' ? 'No interest' : `${(p.interest_rate_bp ?? 0) / 100}% a year, ${p.interest_posting}`}</span>
        {(p.min_balance_minor ?? 0) > 0 && <span>Keeps {money(p.min_balance_minor, c)}</span>}
        {(p.withdrawal_fee_minor ?? 0) > 0 && <span>Fee {money(p.withdrawal_fee_minor, c)}</span>}
        {p.dormancy_days && <span>Dormant after {p.dormancy_days} days</span>}
        <span>{p.accounts ?? 0} accounts</span>
      </p>
    </>
  );
}

function Products() {
  const { me } = useStaff();
  const [editing, setEditing] = useState<string | null>(null);
  const products = useQuery({ queryKey: ['savings', 'products'], queryFn: () => savings.listProducts() });
  const currency = products.data?.[0]?.currency ?? 'UGX';
  const manage = mayManageProducts(me);
  return (
    <SavingsGate title="Savings products">
      {manage && editing === null && (
        <button type="button" className="rt-primary btn-block" onClick={() => setEditing('new')}>New product</button>
      )}
      {editing === 'new' && (
        <section className="rt-card" aria-label="New savings product">
          <ProductForm currency={currency} onDone={() => setEditing(null)} />
        </section>
      )}
      {products.isPending && <p className="loading">Loading products</p>}
      <Problem error={products.error} />
      {products.data && products.data.length === 0 && <p className="empty-state">No savings products yet.</p>}
      <ul className="ln-list">
        {(products.data ?? []).map((p) => (
          <li key={p.id} className="rt-card">
            {editing === p.id ? (
              <ProductForm product={p} currency={p.currency ?? currency} onDone={() => setEditing(null)} />
            ) : (
              <>
                <ProductRow p={p} />
                {manage && editing === null && (
                  <button type="button" onClick={() => setEditing(p.id ?? null)} aria-label={`Edit ${p.code ?? 'product'}`}>Edit</button>
                )}
              </>
            )}
          </li>
        ))}
      </ul>
    </SavingsGate>
  );
}

export const Route = createLazyRoute('/staff/lending/savings/products')({ component: Products });

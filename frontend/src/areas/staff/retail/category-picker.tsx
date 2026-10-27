import * as Popover from '@radix-ui/react-popover';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useContext, useEffect, useId, useState, type KeyboardEvent, type ReactNode } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyChoice } from '../../../app/ui/EmptyChoice';
import { Field, type FieldControlProps } from '../../../app/ui/Field';
import { Sheet } from '../../../app/ui/Sheet';
import { retail, type Category } from '../../../api/retail';
import { icons } from '../../../components/icons';
import { useIsPhone } from '../../../components/side-menu';
import { useStaff } from '../context';
import { ScreenToast } from './toast';
import { Problem, requireVersion } from './ui';

// The one category picker (issue #578), used wherever a category is chosen. The field is a button that names the category
// chosen. On a phone it opens the kit's Sheet at full height: a search box with the cursor in it, "Add a new category" for
// whoever manages the catalogue, the list with 48px rows and the chosen one tinted and ticked, and Done. On a wider screen it
// opens a Radix Popover under the field with the same search, row and list. The search box is a combobox over the listbox:
// Up and Down move the highlight (announced through aria-activedescendant), Enter picks it, Escape closes and the focus goes
// back to the field. A search matches any part of the name, whatever the case; names are shown as the owner typed them.
// "Add a new category" opens a short form in place: the name and the code start the app will make (#550, #562; the code is
// never typed), and the new category is chosen at once, with the screen's own dark toast (ScreenToast: the screen shows one
// toast, never a second one from the picker) and the same words in a live region by the field, which a screen reader hears
// once the sheet has closed. A name already taken, by a switched-off category
// too, is said in plain words before anything is sent, with the way to choose that category (switching it back on).

const MANAGE = 'retail.catalogue.manage';
const CATEGORIES = ['retail', 'catalogue', 'categories'];
// Every list of categories the screens keep: the catalogue's, and the Stock page's filter.
const CATEGORY_KEYS = [CATEGORIES, ['retail', 'categories']];

/** The category already called `name`, whatever the case and the spaces around it, switched off or not. */
export function sameName(categories: Category[], name: string): Category | undefined {
  const wanted = name.trim().toLowerCase();
  return wanted === '' ? undefined : categories.find((c) => (c.name ?? '').trim().toLowerCase() === wanted);
}

/** The categories whose name holds the search, any part of it and whatever the case. */
export function matchCategories(categories: Category[], text: string): Category[] {
  const term = text.trim().toLowerCase();
  return term === '' ? categories : categories.filter((c) => (c.name ?? '').toLowerCase().includes(term));
}

/** What the list says when nothing matches. */
export const noCategory = (term: string, canAdd: boolean) => `No category called "${term}".${canAdd ? ' Add it?' : ''}`;

const nameOf = (c: Category) => `${c.name ?? ''}${c.active === false ? ' (switched off)' : ''}`;

function AddCategory({ id, initial, existing, onAdded, onChoose, onCancel }: {
  id: string;
  initial: string;
  /** Every category of the business, switched-off ones too, to say plainly when the name is taken. */
  existing: Category[];
  onAdded: (row: Category) => void;
  /** Chooses a category that already exists; `on` says it was switched back on to be chosen. */
  onChoose: (row: Category, on: boolean) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(initial);
  const [suggested, setSuggested] = useState('');
  const [showErrors, setShowErrors] = useState(false);
  const trimmed = name.trim();
  // The code start the app will make, asked for a moment after the typing stops, so it shows without leaving the field.
  useEffect(() => {
    if (trimmed === '') return setSuggested('');
    let live = true;
    const timer = setTimeout(() => void retail.suggestCategoryPrefix(trimmed).then((p) => live && setSuggested(p), () => live && setSuggested('')), 300);
    return () => { live = false; clearTimeout(timer); };
  }, [trimmed]);
  const taken = sameName(existing, trimmed);
  const add = useMutation({ mutationFn: () => retail.createCategory(trimmed), onSuccess: onAdded });
  const switchOn = useMutation({
    mutationFn: (row: Category) => retail.updateCategory(row.id ?? '', requireVersion(row.version), { active: true }),
    onSuccess: (row) => onChoose(row, true),
  });
  const submit = () => {
    if (trimmed === '') {
      setShowErrors(true);
      document.getElementById(`${id}-add-name`)?.focus();
      return;
    }
    if (!add.isPending && !taken) add.mutate();
  };
  return (
    <div
      role="group"
      aria-labelledby={`${id}-add-title`}
      className="cp-add"
      // The picker sits inside the item's own form: its Enter adds the category and goes no further.
      onKeyDown={(e) => {
        if (e.key !== 'Enter' || (e.target as HTMLElement).tagName === 'BUTTON') return;
        e.preventDefault();
        e.stopPropagation();
        submit();
      }}
    >
      <h3 id={`${id}-add-title`}>Add a new category</h3>
      <Field
        id={`${id}-add-name`}
        label="Name of the new category"
        required
        error={trimmed === '' ? 'Type the name of the category.' : null}
        showError={showErrors}
        value={name}
        maxLength={100}
        autoComplete="off"
        autoFocus
        onChange={(e) => setName(e.target.value)}
      />
      <p className="hint" data-testid={`${id}-add-code`}>
        {suggested
          ? <>Item codes here will start with <strong>{suggested}</strong>, like {suggested}-0001, made by the app.</>
          : 'The app makes the item codes of this category from its name, like BLB-0001 for Bulbs.'}
      </p>
      {taken && (
        <div className="cp-taken" role="status">
          <p>
            {taken.active === false
              ? `There is already a category called ${taken.name ?? ''} (switched off).`
              : `There is already a category called ${taken.name ?? ''}.`}
          </p>
          {taken.active === false
            ? <BusyButton busy={switchOn.isPending} type="button" onClick={() => switchOn.mutate(taken)}>Switch {taken.name} on and choose it</BusyButton>
            : <button type="button" onClick={() => onChoose(taken, false)}>Choose {taken.name}</button>}
        </div>
      )}
      <Problem error={add.error ?? switchOn.error} />
      <p className="cluster">
        <BusyButton busy={add.isPending} type="button" className="rt-primary" disabled={taken !== undefined} onClick={submit}>Add category</BusyButton>
        <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>
      </p>
    </div>
  );
}

/** The search, the Add row and the list, the same in the sheet and the popover. */
function Choices({ id, categories, existing, value, noneLabel, canAdd, onPick, onAdded, onChoose }: {
  id: string;
  categories: Category[];
  existing: Category[];
  value: string;
  noneLabel?: string;
  canAdd: boolean;
  onPick: (id: string) => void;
  onAdded: (row: Category) => void;
  onChoose: (row: Category, on: boolean) => void;
}) {
  const [text, setText] = useState('');
  const [adding, setAdding] = useState<string | null>(null);
  const [active, setActive] = useState(-1);
  const listId = `${id}-list`;
  const optionId = useId();
  const term = text.trim();
  const matches = matchCategories(categories, text);
  // The empty choice ("All categories" on a filter) is offered only while nothing is typed.
  const options: { id: string; label: string }[] = [
    ...(noneLabel !== undefined && term === '' ? [{ id: '', label: noneLabel }] : []),
    ...matches.map((c) => ({ id: c.id ?? '', label: nameOf(c) })),
  ];
  const shownActive = active < options.length ? active : -1;

  useEffect(() => {
    if (shownActive >= 0) document.getElementById(`${optionId}-${shownActive}`)?.scrollIntoView?.({ block: 'nearest' });
  }, [shownActive, optionId]);

  if (adding !== null) return <AddCategory id={id} initial={adding} existing={existing} onAdded={onAdded} onChoose={onChoose} onCancel={() => setAdding(null)} />;

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (options.length === 0) return;
      setActive(e.key === 'ArrowDown' ? Math.min(shownActive + 1, options.length - 1) : Math.max(shownActive - 1, 0));
    } else if (e.key === 'Home' || e.key === 'End') {
      if (options.length === 0) return;
      e.preventDefault();
      setActive(e.key === 'Home' ? 0 : options.length - 1);
    } else if (e.key === 'Enter') {
      // The picker sits inside the item's own form: Enter here chooses, it never sends that form.
      e.preventDefault();
      e.stopPropagation();
      const option = shownActive >= 0 ? options[shownActive] : options.length === 1 ? options[0] : undefined;
      if (option) onPick(option.id);
      else if (options.length === 0 && canAdd && term !== '') setAdding(term);
    }
  };

  return (
    <div className="cp">
      <input
        id={`${id}-search`}
        type="search"
        role="combobox"
        aria-label="Search categories"
        aria-expanded="true"
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={shownActive >= 0 ? `${optionId}-${shownActive}` : undefined}
        aria-describedby={`${id}-status`}
        value={text}
        autoComplete="off"
        enterKeyHint="done"
        onChange={(e) => { setText(e.target.value); setActive(-1); }}
        onKeyDown={onKeyDown}
      />
      {canAdd && (
        <button type="button" className="cp-add-row" onClick={() => setAdding(term)}>
          {icons.plus}
          <span>Add a new category</span>
        </button>
      )}
      <ul id={listId} role="listbox" aria-label="Categories" className="cp-list">
        {options.map((o, i) => (
          <li
            key={o.id || 'none'}
            id={`${optionId}-${i}`}
            role="option"
            aria-selected={o.id === value}
            className={i === shownActive ? 'cp-option cp-active' : 'cp-option'}
            // The press keeps the cursor in the search box, so the keyboard stays where it was.
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => onPick(o.id)}
          >
            <span className="cp-name">{o.label}</span>
            {o.id === value && <span className="cp-tick" aria-hidden="true">{icons.check}</span>}
          </li>
        ))}
      </ul>
      {options.length === 0 && (
        <div className="cp-empty">
          <p>{term === '' ? 'No categories yet.' : noCategory(term, canAdd)}</p>
          {canAdd && term !== '' && <button type="button" onClick={() => setAdding(term)}>Add {term}</button>}
        </div>
      )}
      <p id={`${id}-status`} className="visually-hidden" role="status" aria-live="polite">
        {term === '' ? '' : matches.length === 1 ? '1 category found' : `${matches.length} categories found`}
      </p>
    </div>
  );
}

/**
 * The category field. `categories` are the choices the screen offers (it decides about switched-off ones); `noneLabel` adds an
 * empty first choice, such as "All categories" on a filter. Add a new category shows to whoever manages the catalogue, unless
 * `allowAdd` is false (a filter).
 */
export function CategoryPicker({ id, label = 'Category', categories, value, onChange, placeholder = 'Choose a category', noneLabel, allowAdd = true, required = false, error, showError, hint }: {
  id: string;
  label?: ReactNode;
  categories: Category[];
  value: string;
  onChange: (id: string) => void;
  placeholder?: string;
  noneLabel?: string;
  allowAdd?: boolean;
  required?: boolean;
  error?: string | null;
  showError?: boolean;
  hint?: ReactNode;
}) {
  const { me } = useStaff();
  const phone = useIsPhone();
  const queryClient = useQueryClient();
  const screenToast = useContext(ScreenToast);
  // What the last add or switch on said, for a screen reader; the screen's toast may be silent, as its saves have a line too.
  const [said, setSaid] = useState('');
  const [open, setOpen] = useState(false);
  const [addingFirst, setAddingFirst] = useState(false);
  const canAdd = allowAdd && (me.permissions ?? []).includes(MANAGE);
  // Every category, switched-off ones too, for the taken-name check; the catalogue's own list, so no second request. Its answer
  // also says the screen's list has loaded, so an empty one is said (issue #619) and never shown while it is on its way.
  // Read too for someone who may not add, while the screen offers none, so the empty line can say who to ask.
  const all = useQuery({ queryKey: CATEGORIES, queryFn: () => retail.listCategories(), enabled: canAdd || categories.length === 0 });
  // The category this picker added or switched on, until the screen's own list carries it: the field names it at once.
  const [kept, setKept] = useState<Category | null>(null);
  const choicesList = kept && !categories.some((c) => c.id === kept.id) ? [...categories, kept] : categories;
  const chosen = choicesList.find((c) => c.id === value);
  const shown = chosen ? nameOf(chosen) : value === '' && noneLabel !== undefined ? noneLabel : placeholder;

  const pick = (next: string) => {
    onChange(next);
    setOpen(false);
  };
  /** Puts a new or changed category in every list the screens keep, before the refetch answers, and chooses it. */
  const keep = (row: Category, message: string) => {
    for (const key of CATEGORY_KEYS) {
      queryClient.setQueryData<Category[]>(key, (was) => was && [...was.filter((c) => c.id !== row.id), row]);
      void queryClient.invalidateQueries({ queryKey: key });
    }
    void queryClient.invalidateQueries({ queryKey: ['retail', 'catalogue'] });
    setKept(row);
    screenToast?.(message, 'success');
    setSaid(message);
    pick(row.id ?? '');
  };
  const added = (row: Category) => keep(row, `Added the category ${row.name ?? ''}.${row.code_prefix ? ` Its item codes start with ${row.code_prefix}.` : ''}`);
  const chose = (row: Category, on: boolean) => (on ? keep(row, `Switched ${row.name ?? ''} on and chose it.`) : pick(row.id ?? ''));
  // Issue #619: with no category to choose, the field says so and adds the first one in place, or says who to ask.
  if (choicesList.length === 0 && noneLabel === undefined && (all.data !== undefined || all.isError)) {
    return addingFirst && canAdd
      ? <AddCategory id={id} initial="" existing={all.data ?? []} onAdded={(row) => { setAddingFirst(false); added(row); }} onChoose={(row, on) => { setAddingFirst(false); chose(row, on); }} onCancel={() => setAddingFirst(false)} />
      : <EmptyChoice id={id} label={label} required={required} load={all} text="No categories yet." ask="Ask the owner to add a category first." add={canAdd ? { label: 'Add a category', onClick: () => setAddingFirst(true) } : null} />;
  }
  const choices = <Choices id={id} categories={choicesList} existing={all.data ?? choicesList} value={value} noneLabel={noneLabel} canAdd={canAdd} onPick={pick} onAdded={added} onChoose={chose} />;

  // The field's blur is left out: opening the list moves the focus into it, which is not leaving the field unchosen. A Save with
  // no category still shows the error (showError).
  const trigger = ({ onBlur: _left, ...props }: FieldControlProps) => (
    <button
      {...props}
      type="button"
      role="combobox"
      aria-haspopup="dialog"
      aria-expanded={open}
      // focusFirstMissing reads the value of a required field to find an empty one.
      value={value}
      className={chosen || (value === '' && noneLabel !== undefined) ? 'cp-trigger' : 'cp-trigger cp-placeholder'}
      onClick={(e) => {
        // Safari does not focus a button on a tap; the field must hold the focus to be given it back on close.
        e.currentTarget.focus();
        setOpen(true);
      }}
      onKeyDown={(e) => {
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
          e.preventDefault();
          setOpen(true);
        }
      }}
    >
      <span className="cp-trigger-text">{shown}</span>
    </button>
  );

  return (
    <>
      {phone ? (
        <>
          <Field id={id} label={label} required={required} error={error} showError={showError} hint={hint} control={trigger} />
          <Sheet open={open} onClose={() => setOpen(false)} title="Choose a category" tall actions={<button type="button" className="rt-primary" onClick={() => setOpen(false)}>Done</button>}>
            {choices}
          </Sheet>
        </>
      ) : (
        <Popover.Root open={open} onOpenChange={setOpen}>
          <Field id={id} label={label} required={required} error={error} showError={showError} hint={hint} control={(props) => <Popover.Trigger asChild>{trigger(props)}</Popover.Trigger>} />
          <Popover.Portal>
            <Popover.Content
              className="cp-pop"
              align="start"
              sideOffset={4}
              collisionPadding={16}
              aria-label="Choose a category"
              onOpenAutoFocus={(e) => {
                e.preventDefault();
                document.getElementById(`${id}-search`)?.focus();
              }}
            >
              {choices}
            </Popover.Content>
          </Popover.Portal>
        </Popover.Root>
      )}
      <p className="visually-hidden" role="status" aria-live="polite" data-testid={`${id}-said`}>{said}</p>
    </>
  );
}

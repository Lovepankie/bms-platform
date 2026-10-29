# UI house rules

These are the rules every screen of the app follows (issue #508). They exist because the app was trialled with shop owners
who said it looked generated and did not feel smooth. The aim is the calm, quick feel of a good payments app. The tokens
that carry them live in `frontend/src/app/theme.css`; `frontend/src/app/ui/design-guard.test.ts` checks the ones a test can
check. The fuller design system is `docs/ui/design-system.md`.

The component kit (issue #520) carries these rules: `Segmented`, `Stepper`, `Field`, `MoneyInput`, `ListRow` and `Card`,
`Sheet`, `EmptyState`, `EmptyChoice` (issue #619), `Skeleton` and `SuccessScreen` in `frontend/src/app/ui`, styled in `kit.css` and shown together on
`/staff/kit` (administrators only). A new screen is built from them. Dialogs and sheets use `@radix-ui/react-dialog`
(the kit's `Sheet`), the pick-one control uses `@radix-ui/react-toggle-group` (`Segmented`), and a searchable list that opens
under its field on a wider screen uses `@radix-ui/react-popover` (the category picker, issue #578), all styled only with our
token CSS: no shadcn, no Tailwind. The sheet keeps live regions audible, closes by a swipe only on touch, and gives focus
back to what opened it.

## Look

1. No coloured stripe on any edge of a card, alert, toast or tile.
2. No purple or indigo default, no gradients on text, buttons or backgrounds, no glow, no glassmorphism.
3. No emoji as icons. One icon set (`frontend/src/components/icons.tsx`) at one stroke weight, and icons only where they add meaning.
4. At most 2 shadow levels and 2 radius values.
5. Content is left aligned. Only empty states and success states are centred.

## Actions

6. One primary action per screen. On a phone it is sticky and full width at the bottom, and it carries the live amount.
   It sits in the kit's `ActionBar` and is brand filled. It never looks off because of its fields: while they are not yet
   right the form passes `notReady`, the button stays enabled, sends nothing, and a press takes the person to the first missing
   field (issue #526). `disabled` stays for a block that is not about the fields.
7. Writes are optimistic, with Undo.

## Money and type

8. Every amount uses tabular figures (`font-variant-numeric: tabular-nums`) and goes through one money formatter
   (`formatMinor`, `money()` on the retail screens). UGX shows no decimals. The key amount on a screen is large: 40 to 56px,
   semibold, with its label small above it: the kit's `KeyFigure`. Tabular figures are set once on the body. Dates read
   "23 Oct 2026" through the one formatter, `showDate`.
9. At most 4 text sizes plus 1 display size, and 2 weights.

## Forms

10. Every input has a visible label. Validation runs on blur, not on every key, and the error shows under the field with an
    icon. A required field shows `Label *`: the asterisk is red and `aria-hidden`, and the control keeps
    `aria-required="true"`. A form with a required field starts with the line "Fields marked * are needed." A form where
    most fields are required may mark the few optional ones "(optional)" instead, never both styles on one form. The
    asterisk is the one allowed use of red on a normal screen. The send button stays enabled and takes the person to the
    first missing field. The kit's `Field` does all of this, and the lending forms (`form-fields.tsx`) follow it.
11. Amounts open the numeric keypad (`inputmode="numeric"`), quantities use steppers, and a choice of 2 to 5 options is a
    segmented control.
12. Touch targets are at least 48px.

## Motion

13. Motion lasts 100 to 250ms and eases out with `cubic-bezier(0.2, 0, 0, 1)`. Only `transform` and `opacity` animate.
    `prefers-reduced-motion` is respected. No bounce. The design guard scans every stylesheet under `frontend/src` and fails a
    `transition` or `@keyframes` that animates anything but `transform` and `opacity`, a transition that names no property
    (so animates all), and `ease-in` or `ease-in-out`. Its short exception list names each colour or background fade with
    its reason: a field's and a link card's edge, and the skeleton shimmer (a button's fill changes at once since issue #682). So the tour spotlight
    glides by `transform`, the side menu folds without animating its width, and shadows and focus rings change at once.
    The one longer motion is the sale-complete check, drawn in 400ms (issue #520), still by `transform` only. A skeleton may
    shimmer for 1.2s as the existing one in `feedback.css` does (accepted, 24 Oct 2026), since the global reduced-motion
    block stops it; the kit's own Skeleton is still grey rows. `frontend/src/app/ui/kit.css` also stops each of its
    animations under reduced motion. No dot or badge pulses or blinks to catch the eye; a loop that fades may only show work in
    progress. The one exception, which the owner asked for (issue #603), is the New badge of a new feature (`.new-blink`): it
    blinks by opacity for at most five seconds per page load (the clock starts with the first badge drawn; a badge drawn again
    later, as when the drawer opens again, blinks only for what is left), then stands still until the person opens the
    feature, and it never blinks under reduced motion. Five seconds keeps it inside WCAG 2.2.2, which asks for a way to pause
    only a blink that runs longer. The design guard holds this with a plain token ban: `infinite` (shorthand, longhand or
    `-webkit-`), a bare count above one or an iteration-count longhand that is not a plain number up to one (issue #605) and any `var()` but the motion tokens in an animation value fail outside
    the named work-in-progress loops (the busy button while it writes, spinners, the skeleton shimmer, the loading mark, the
    sync dot while sending) and `.new-blink`; every `.new-blink` rule must run
    `rt-new-blink` five times; and code may not set an animation (an imported value as a style key included) or loop one with
    `Infinity` or `Number.POSITIVE_INFINITY`.

## Feedback

14. A page load shows a skeleton, and nothing at all for under 1 second.
15. Toasts are dark and neutral, at the bottom, with an icon, for about 4 seconds, with at most one action. An error is never
    a blocking dialog.
    Every toast names its tone: the check of `success` only where a write succeeded, `info` for a refusal (an item already on
    the sale) or a change that waits for approval, `error` for a failure. `show()` has no default tone.
    Every successful save, sale, add, edit, delete, move, purchase and payment in the staff area says so in a success toast
    of a few plain words, because most people read very little (issue #682): the screen's own `show(text, 'success')`, or
    the words in the mutation's `meta: { success }` (or `sayDone` for a write by hand) that the app's one `DoneToast` says,
    with Undo where `meta.undo` can take it back. Success is never said twice: a screen that already shows a success note
    or screen keeps that and is exempt. The success toast guard in `busy-guard.test.ts` fails a staff write that says
    nothing, unless `// toast-exempt: <reason>` sits above it.
    A press shows at once: every button, tile, link card and row sinks to `--press-scale` and dims to `--press-opacity`,
    by transform and opacity only, in `--motion-fast` (100 to 160ms); under reduced motion it only dims. A busy button
    is the `BusyButton` (rule 6 of the design system) and a disabled one is flat grey, never only faded.
16. Status colours are only for real status. An information note is neutral grey with the info icon, never a lavender or brand
    tint.

## Words

17. Copy is plain and concrete. No dashes.
18. Words come from the glossary, `frontend/src/app/glossary.ts` (issue #547, D24): Buy stock (a record of it is a
    purchase), Count stock (a stock count), Difference, Lost or damaged stock, Customers who owe, Counted in for the
    smallest unit of an item, and Cartons, boxes and other packs. `glossary.test.ts` reads the source strings of the
    frontend (string and template literals, and all JSX text) and every tour and menu hint, and fails on a banned word
    (SKU, variance, shrinkage, UOM, restock, in any case) or a retired label (stock-take, credit buyer, pack size). It
    reads source, not the rendered page: words built at run time and the backend's refusals are not checked by it, so a
    new backend message a person reads is worded from this list by hand. Routes, API fields, permission names and error codes
    keep their old names; only words a person sees change. A tour whose words change takes a new version.

## Shell and navigation (issue #526)

19. The staff area has one 56px top bar: the business and the branch in use as its title, which switches branch; Search;
    Refresh beside it, named Refresh, its icon alone on a phone (issue #631); and one menu for who is signed in, Help, the theme,
    Sign out and Sign out everywhere (issue #647, on every screen size, after a question in the kit's Sheet). The Online dot shows only when it has news (offline,
    sending, sales waiting), and always for a manager, whose sheet settles other phones' sales; below 430px wide the controls
    sit closer, the Online dot is the dot and the number waiting alone in every state (its name keeps the words, "Offline, 3
    waiting to sync") and Ask is its icon alone; with Refresh in the bar the controls touch, each exactly a 48px target (issue #631), so the business and the branch keep their first word and the start of the
    second (issue #605; at a phone's double text size, their first two letters). An offline banner sits under the bar while the device has no network. The Powered-by footer
    shows only on More and the settings pages, and the page colour fills the viewport.
20. A phone shows one crumb, a back chevron and "Back to" the parent, with no slash; a screen never adds a second Back to
    link under its title, nor a "Branch: X" line, since the top bar names the branch: a write screen records at exactly that
    branch or asks for one (`useSingleBranch`, `useStockingBranch`, held by `branch-scope.test.tsx`).
21. An empty list, a "nothing in this range" and a no-access state are one plain line with at most one action, with no
    border or box. The no-access line (`NoAccess`) says who can give access and links Home.
22. A date range is the one `PeriodPicker`: Today, This week, This month and Custom, Custom in the kit's Sheet.
23. A related screen is a row with a chevron (`link-row`, `ScreenLink`) or a tile; an action is a button; a figure that opens
    the rows behind it is link coloured with a chevron. No navigation is an underlined word.
24. A help text is one line (`Hint`); the rest is behind its info button or in Help. A warning or a consequence that changes
    what the person does next is never cut (`Hint full`, or a `Note`).

## Empty lists on a form (issue #619)

25. A list a form needs is never a dead end. When a select or picker has no options it is the kit's `EmptyChoice`: the
    field's label, one plain line saying what is missing ("No units yet."), and one way to fill it. Where the list is made of
    names (units, categories, suppliers, customer groups, cash parties) the button adds one right there, by its name, and
    chooses it (`AddByName`); otherwise it opens the screen that adds one with `?back=` the form, and that screen comes back
    once it is added (`useComeBack`). Someone who may not add one reads who can ("Ask the owner to add a unit first."). Never
    only a "Choose a unit" with nothing under it, and never shown while the list is still loading. A needed field's way to add
    is where Save takes the person (`data-needed`, read by `focusFirstMissing`). Counted in on both item forms always offers
    Add a new unit (`UnitPicker`). An info toast never hides an error toast still showing: it is said after it. The design
    guard fails a `<select>` of a list read at run time that has no `EmptyChoice` near it and no `empty:` comment saying why it
    is never empty (a filter with its "All", a select drawn only when it has choices). The help article is
    `frontend/src/help/articles/empty-list.md`.


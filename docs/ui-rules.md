# UI house rules

These are the rules every screen of the app follows (issue #508). They exist because the app was trialled with shop owners
who said it looked generated and did not feel smooth. The aim is the calm, quick feel of a good payments app. The tokens
that carry them live in `frontend/src/app/theme.css`; `frontend/src/app/ui/design-guard.test.ts` checks the ones a test can
check. The fuller design system is `docs/ui/design-system.md`.

The component kit (issue #520) carries these rules: `Segmented`, `Stepper`, `Field`, `MoneyInput`, `ListRow` and `Card`,
`Sheet`, `EmptyState`, `Skeleton` and `SuccessScreen` in `frontend/src/app/ui`, styled in `kit.css` and shown together on
`/staff/kit` (administrators only). A new screen is built from them. Dialogs and sheets use `@radix-ui/react-dialog`
(the kit's `Sheet`) and the pick-one control uses `@radix-ui/react-toggle-group` (`Segmented`), both styled only with our
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
7. Writes are optimistic, with Undo.

## Money and type

8. Every amount uses tabular figures (`font-variant-numeric: tabular-nums`) and goes through one money formatter
   (`formatMinor`, `money()` on the retail screens). UGX shows no decimals. The key amount on a screen is large: 40 to 56px,
   semibold.
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
    its reason: a button's fill and edge, a field's and a link card's edge, and the skeleton shimmer. So the tour spotlight
    glides by `transform`, the side menu folds without animating its width, and shadows and focus rings change at once.
    The one longer motion is the sale-complete check, drawn in 400ms (issue #520), still by `transform` only. A skeleton may
    shimmer for 1.2s as the existing one in `feedback.css` does (accepted, 24 Oct 2026), since the global reduced-motion
    block stops it; the kit's own Skeleton is still grey rows. `frontend/src/app/ui/kit.css` also stops each of its
    animations under reduced motion.

## Feedback

14. A page load shows a skeleton, and nothing at all for under 1 second.
15. Toasts are dark and neutral, at the bottom, with an icon, for about 4 seconds, with at most one action. An error is never
    a blocking dialog.
    Every toast names its tone: the check of `success` only where a write succeeded, `info` for a refusal (an item already on
    the sale) or a change that waits for approval, `error` for a failure. `show()` has no default tone.
16. Status colours are only for real status.

## Words

17. Copy is plain and concrete. No dashes.

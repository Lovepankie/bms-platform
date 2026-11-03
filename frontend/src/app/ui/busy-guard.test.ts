import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// The busy guard (#456): a button that writes shows that it is working and cannot be pressed twice.
// Two source checks keep new screens honest. Both are heuristics over the source text, not a type or
// data-flow check: check 1 asks only that the pending flag appears inside some `busy=` attribute (or an
// alias of one) somewhere in the same file, not that it is the busy of the button that sends the
// mutation, so a wrong pairing in one file still passes. Review the pairing by eye.
//
// 1. A mutation sent from a press is busy. A mutation is `useMutation(` (with or without generics,
//    named or destructured) or a call of a local hook that returns one (`return useMutation(`). When its
//    mutate or mutateAsync runs from a handler attribute (onClick, onSubmit, or a component's own
//    callback such as onSave), directly or through a function of the same file, its pending flag must
//    reach a `busy={...}` attribute in that file: a BusyButton's, or a busy prop of a component that
//    hands it to one (SaveBar, a View). A local alias (`const busy = save.isPending || ...`) counts.
//    A mutation that needs no busy button carries `// busy-exempt: <reason>` in the run of `//` comment
//    lines directly above its `const ... = useMutation(` line (a comment with no reason does not count);
//    anything else is an entry in ALLOWED below with its reason.
// 2. No plain <button> is disabled by a pending or busy state: that button is a BusyButton, so it also
//    shows the spinner. A button that only waits for the request (Cancel, Close) stays plain with
//    `/* busy-exempt: <reason> */` in its tag.

const SRC = fileURLToPath(new URL('../../', import.meta.url));

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) && !name.endsWith('.d.ts') ? [path] : [];
  });
}

const sources = files(SRC).map((f) => ({ file: relative(SRC, f), text: readFileSync(f, 'utf8') }));

/** The text from `open` (an opening brace or parenthesis) to its partner, both included. */
function balanced(text: string, open: number): string {
  const pairs: Record<string, string> = { '{': '}', '(': ')' };
  const opener = text[open] ?? '';
  const closer = pairs[opener];
  if (!closer) return '';
  let depth = 0;
  for (let i = open; i < text.length; i += 1) {
    if (text[i] === opener) depth += 1;
    else if (text[i] === closer && (depth -= 1) === 0) return text.slice(open, i + 1);
  }
  return text.slice(open);
}

/** The opening tag of every plain <button>, braces balanced so an arrow function's `>` does not end it. */
function buttonTags(text: string): { tag: string; line: number }[] {
  const tags: { tag: string; line: number }[] = [];
  for (const match of text.matchAll(/<button\b/g)) {
    let depth = 0;
    let end = match.index;
    for (; end < text.length; end += 1) {
      const c = text[end];
      if (c === '{') depth += 1;
      else if (c === '}') depth -= 1;
      else if (c === '>' && depth === 0) break;
    }
    tags.push({ tag: text.slice(match.index, end + 1), line: text.slice(0, match.index).split('\n').length });
  }
  return tags;
}

/** The local hooks that return a mutation: `function useX(...) { ... return useMutation(`, or `const useX = () => useMutation(`. */
function mutationHooks(text: string): string[] {
  const hooks: string[] = [];
  for (const m of text.matchAll(/(?:return|=>)\s*useMutation\s*[<(]/g)) {
    const before = text.slice(0, m.index);
    const name = [...before.matchAll(/(?:function\s+(use\w+)|const\s+(use\w+)\s*=)/g)].at(-1);
    const hook = name?.[1] ?? name?.[2];
    if (hook) hooks.push(hook);
  }
  return hooks;
}

type Mutation = { name: string; line: number; index: number; sends: string[]; pending: string | null; exempt: boolean };

const EXEMPT = /^\/\/\s*busy-exempt:\s*\S/;

/** True when the `//` comment lines directly above the line at `index` hold an `exempt` comment (busy or toast) with a reason. */
function exempted(text: string, index: number, exempt: RegExp = EXEMPT): boolean {
  const above = text.slice(0, index).split('\n').slice(0, -1).reverse();
  for (const line of above) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('//')) return false;
    if (exempt.test(trimmed)) return true;
  }
  return false;
}

/** Every mutation a file holds, with the expressions that send it and the one that reads its pending flag. */
function mutations(text: string, hooks: string[]): Mutation[] {
  const callee = ['useMutation', ...hooks].join('|');
  const found: Mutation[] = [];
  const at = (index: number) => text.slice(0, index).split('\n').length;
  for (const m of text.matchAll(new RegExp(`const\\s+(\\w+)\\s*=\\s*(?:${callee})\\s*[<(]`, 'g'))) {
    const name = m[1] ?? '';
    found.push({ name, line: at(m.index), index: m.index, sends: [`${name}.mutate`, `${name}.mutateAsync`], pending: `${name}.isPending`, exempt: exempted(text, m.index) });
  }
  for (const m of text.matchAll(new RegExp(`const\\s*\\{([^}]*)\\}\\s*=\\s*(?:${callee})\\s*[<(]`, 'g'))) {
    const fields = new Map<string, string>();
    for (const part of (m[1] ?? '').split(',')) {
      const [key, alias] = part.split(':').map((s) => s.trim());
      if (key) fields.set(key, alias || key);
    }
    const sends = ['mutate', 'mutateAsync'].flatMap((k) => (fields.has(k) ? [fields.get(k) ?? k] : []));
    found.push({ name: sends[0] ?? 'mutation', line: at(m.index), index: m.index, sends, pending: fields.get('isPending') ?? null, exempt: exempted(text, m.index) });
  }
  return found;
}

const DOM_INPUT = /^on(Change|Input|Blur|Focus|Key\w*|Pointer\w*|Mouse\w*|Scroll|Load|Error|Toggle|Select|Paste|Drag\w*|Drop|Touch\w*|Animation\w*|Transition\w*)$/;

/** The code that runs on a press: every handler attribute but the typing and focus ones, with the local functions they call. */
function pressCode(text: string): string {
  const parts: string[] = [];
  for (const m of text.matchAll(/\s(on[A-Z]\w*)=\{/g)) {
    if (DOM_INPUT.test(m[1] ?? '')) continue;
    parts.push(balanced(text, m.index + m[0].length - 1));
  }
  return follow(text, parts);
}

/**
 * `parts` with the local functions they call, three calls deep: `onSubmit={submit}` and `function submit() { save.mutate() }`.
 * With `props`, a name that is a callback prop (`onSaved(x)`) also brings the expressions handed to it in the same file.
 */
function follow(text: string, parts: string[], props = false): string {
  const seen = new Set<string>();
  for (let depth = 0; depth < 3; depth += 1) {
    const joined = parts.join('\n');
    for (const [, name] of joined.matchAll(/\b([a-z]\w*)\b/g)) {
      if (!name || seen.has(name)) continue;
      seen.add(name);
      if (props && /^on[A-Z]/.test(name)) {
        for (const m of text.matchAll(new RegExp(`\\s${name}=\\{`, 'g'))) parts.push(balanced(text, m.index + m[0].length - 1));
      }
      const decl = new RegExp(`(?:function\\s+${name}\\s*\\(|const\\s+${name}\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*=>)`).exec(text);
      if (!decl) continue;
      const rest = text.slice(decl.index + decl[0].length);
      const body = /^\s*\{/.test(rest) || decl[0].startsWith('function') ? rest.indexOf('{') : -1;
      parts.push(body >= 0 ? balanced(rest, body) : (rest.split('\n')[0] ?? ''));
    }
  }
  return parts.join('\n');
}

/** Every `busy*={...}` (or `*Busy={...}`) attribute expression, and every local alias of a flag that reaches one. */
function busyCode(text: string): string {
  const attrs = [...text.matchAll(/\s(\w*[Bb]usy\w*)=\{/g)].map((m) => balanced(text, m.index + m[0].length - 1)).join('\n');
  const aliases = [...text.matchAll(/const\s+(\w+)\s*=\s*([^;\n]+)/g)]
    .filter(([, name]) => name && new RegExp(`\\b${name}\\b`).test(attrs))
    .map(([, , value]) => value)
    .join('\n');
  return `${attrs}\n${aliases}`;
}

const has = (code: string, expr: string) => new RegExp(`(^|[^\\w.])${expr.replace(/\./g, '\\.')}\\b`).test(code);

/** The mutations of one file that a press sends. */
function pressed(text: string, hooks: string[] = mutationHooks(text)): Mutation[] {
  const press = pressCode(text);
  return mutations(text, hooks).filter((m) => m.sends.some((send) => has(press, send)));
}

/** The mutations of one file that a press sends while nothing shows them busy. */
function unguarded(text: string, hooks: string[] = mutationHooks(text)): string[] {
  const busy = busyCode(text);
  return pressed(text, hooks)
    .filter((m) => !m.exempt && (!m.pending || !has(busy, m.pending)))
    .map((m) => `${m.line} ${m.name}`);
}

// file:mutation -> why no busy flag is needed. Keep each reason in plain words.
const ALLOWED: Record<string, string> = {};

const OWN_SPINNER = ['app/ui/BusyButton.tsx', 'app/refresh-button.tsx'];
const BUSY_STATE = /\bdisabled=\{[^}]*(isPending|\bbusy\w*|\w*[Bb]usy\b|submitting|saving|sending|confirming|replying|committing|\bpending\b)/;

function plainBusyButtons(text: string): number[] {
  return buttonTags(text)
    .filter(({ tag }) => BUSY_STATE.test(tag) && !tag.includes('busy-exempt:'))
    .map(({ line }) => line);
}

const hooks = sources.flatMap((s) => mutationHooks(s.text));

describe('busy guard', () => {
  it('finds the mutations it guards', () => {
    expect(sources.filter((s) => /useMutation\s*[<(]/.test(s.text)).length).toBeGreaterThan(50);
    expect(hooks).toContain('useSaveReceipt');
    expect(sources.flatMap(({ text }) => pressed(text, hooks)).length).toBeGreaterThan(120);
  });

  it('every mutation a press sends hands its pending flag to a busy button', () => {
    const missing = sources.flatMap(({ file, text }) =>
      unguarded(text, hooks)
        .map((m) => `${file}:${m}`)
        .filter((key) => !ALLOWED[key.replace(/:\d+ /, ':')]),
    );
    expect(missing).toEqual([]);
  });

  it('no plain <button> is disabled by a pending or busy state: that is a BusyButton', () => {
    const plain = sources
      .filter((s) => !OWN_SPINNER.includes(s.file))
      .flatMap(({ file, text }) => plainBusyButtons(text).map((line) => `${file}:${line}`));
    expect(plain).toEqual([]);
  });
});

describe('busy guard on fixtures', () => {
  const save = `const save = useMutation({ mutationFn: send });`;

  it('fails a plain button that sends and is never disabled', () => {
    const text = `function A() { ${save}\n return <button type="button" onClick={() => save.mutate()}>Save</button>; }`;
    expect(unguarded(text)).toEqual(['1 save']);
  });

  it('fails a plain button disabled by isPending, in both checks', () => {
    const text = `function A() { ${save}\n return <button type="button" disabled={save.isPending} onClick={() => save.mutate()}>Save</button>; }`;
    expect(unguarded(text)).toEqual(['1 save']);
    expect(plainBusyButtons(text)).toEqual([2]);
  });

  it('passes a BusyButton with the pending flag as busy', () => {
    const text = `function A() { ${save}\n return <BusyButton type="button" busy={save.isPending} onClick={() => save.mutate()}>Save</BusyButton>; }`;
    expect(unguarded(text)).toEqual([]);
    expect(plainBusyButtons(text)).toEqual([]);
  });

  it('sees generics, destructuring, a submit function and a local busy alias', () => {
    const generic = `const pay = useMutation<Result>({ mutationFn: send });\n<form onSubmit={submit}>\nfunction submit(e) { e.preventDefault(); pay.mutate(); }`;
    expect(unguarded(generic)).toEqual(['1 pay']);
    expect(unguarded(`${generic}\nconst busy = pay.isPending || other;\n<BusyButton busy={busy}>Pay</BusyButton>`)).toEqual([]);
    const destructured = `const { mutate: record, isPending: recording } = useMutation({ mutationFn: send });\n<button onClick={() => record()}>Record</button>`;
    expect(unguarded(destructured)).toEqual(['1 record']);
    expect(unguarded(`${destructured}\n<BusyButton busy={recording}>Record</BusyButton>`)).toEqual([]);
    expect(unguarded(`const { mutate } = useMutation({ mutationFn: send });\n<button onClick={() => mutate()}>Go</button>`)).toEqual(['1 mutate']);
  });

  it('follows a hook that returns a mutation to its callers', () => {
    const hook = `function useSave() {\n  return useMutation({ mutationFn: send });\n}`;
    expect(mutationHooks(hook)).toEqual(['useSave']);
    const caller = `const save = useSave();\n<form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>`;
    expect(unguarded(caller, ['useSave'])).toEqual(['1 save']);
    expect(unguarded(`${caller}\n<SaveBar busy={save.isPending} />`, ['useSave'])).toEqual([]);
  });

  it('honours a busy-exempt comment with a reason directly above the mutation, and nothing else', () => {
    const press = `\n<button onClick={() => save.mutate()}>Save</button>`;
    expect(unguarded(`// busy-exempt: the card shows its own busy state\n${save}${press}`)).toEqual([]);
    expect(unguarded(`  // Saved as the person types.\n  // busy-exempt: the switch says Working itself\n  ${save}${press}`)).toEqual([]);
    expect(unguarded(`// busy-exempt:\n${save}${press}`)).toEqual(['2 save']);
    expect(unguarded(`// busy-exempt: too far up\nconst other = 1;\n${save}${press}`)).toEqual(['3 save']);
    expect(unguarded(`/* busy-exempt: a tag comment */\n${save}${press}`)).toEqual(['2 save']);
  });

  it('leaves a mutation no press sends alone', () => {
    expect(unguarded(`${save}\n<input onChange={(e) => save.mutate(e.target.value)} />`)).toEqual([]);
  });
});

// The success toast guard (#682): every write in the staff area answers back in plain words. A staff mutation passes when its
// options name `meta: { success }` (the app's DoneToast says it), or its own success path shows a success toast: the
// useMutation options (or those of the local hook it comes from), each send with the rest of its block, and the local functions
// and same-file callback props (`onSaved={...}`) these call, three deep, hold a toast call with the 'success' tone or an
// `added(` of useItemAdded. Anything else carries `// toast-exempt: <kind>: <reason>` in the `//` lines directly above it. Like the
// busy guard it reads text, not data flow, so it proves a success toast is on the path, not what it says.
// JUSTIFICATION-A3: #682 asks for this guard; it reuses the busy guard's mutation parser rather than a second copy.

// Issue #824 (lead's call on the review of #828): a toast-exempt reason is one of four kinds, named first, then the reason in
// words: `saves nothing:` (a look, a check or a download), `waits for approval:`, `said elsewhere:` (a toast the screen it
// hands on to shows), or `success note:` (a note beside the form says it). Anything else is no reason to say nothing.
const TOAST_EXEMPT = /^\/\/\s*toast-exempt:\s*(?:saves nothing|waits for approval|said elsewhere|success note):\s*\S/;
// Issue #824: the owner wants a toast after every save, a success screen too, so a success screen is no reason to say nothing.
// A write whose screen replaces the form carries `// success-screen: <what>` above it: it still toasts, and the screen it
// sets is not read as a second success note.
const SUCCESS_SCREEN = /^\/\/\s*success-screen:\s*\S/;
// A toast-exempt reason that says a screen, panel or receipt takes the form's place, in any words, is a success screen.
const SCREEN_AS_EXEMPT = /\/\/\s*toast-exempt:[^\n]*\b(?:success screen|replaces?|replaced|in (?:its|the form's) place|in place of|instead of the form|(?:saved|success|receipt|done) (?:panel|screen))\b/i;
// A success-screen tag turns off the said-twice check, so it is for a screen that replaces the form, never a note beside it.
const SCREEN_ON_NOTE = /\/\/\s*success-screen:[^\n]*\bnote\b/i;

/** The toast-exempt and success-screen comments of one file that misuse their tag (#824). */
function misusedTags(text: string): string[] {
  return text.split('\n').flatMap((line, i) => (SCREEN_AS_EXEMPT.test(line) || SCREEN_ON_NOTE.test(line) ? [`${i + 1} ${line.trim()}`] : []));
}
// meta.success that is not a bare `() => null`; a toast call whose own parentheses (one level of nesting) hold the 'success'
// tone; sayDone(; or useItemAdded's added(.
const SAYS_DONE =
  /\bmeta:\s*\{[^}]*\bsuccess\s*:(?!\s*\(\s*\)\s*=>\s*null\b)|\b(?:show|\w*[Tt]oast\w*)\??\.?\((?:[^()]|\([^()]*\))*?['"]success['"](?:[^()]|\([^()]*\))*\)|\bsayDone\(|\badded\(/;

// A write by hand: a function that turns a busy flag on (`setBusy(true)`, `setSaving(true)`) and then sends. From that line to the
// end of its block, with what it calls, it must say done like a mutation, or carry `// toast-exempt: <reason>` directly above it.
const BUSY_ON = /\bset(?:Busy|Saving|Removing|Sending|Submitting|Posting|Deleting)\(true\)/g;

/** The hand-rolled writes of one file that say nothing when they work and carry no toast-exempt reason. */
function silentByHand(text: string): string[] {
  return [...text.matchAll(BUSY_ON)]
    .filter((m) => !exempted(text, m.index, TOAST_EXEMPT) && !SAYS_DONE.test(follow(text, [restOfBlock(text, m.index)], true)))
    .map((m) => `${text.slice(0, m.index).split('\n').length} ${m[0]}`);
}

/** The options of each local hook that returns a mutation, by the hook's name. */
function hookOptions(text: string): [string, string][] {
  return [...text.matchAll(/(?:return|=>)\s*useMutation\s*[<(]/g)].flatMap((m) => {
    const name = [...text.slice(0, m.index).matchAll(/(?:function\s+(use\w+)|const\s+(use\w+)\s*=)/g)].at(-1);
    const hook = name?.[1] ?? name?.[2];
    return hook ? [[hook, balanced(text, text.indexOf('(', m.index + m[0].length - 1))] as [string, string]] : [];
  });
}

/** The text from `from` to the end of the block it sits in: a send with what runs after it. */
function restOfBlock(text: string, from: number): string {
  let depth = 0;
  for (let i = from; i < text.length; i += 1) {
    if (text[i] === '{') depth += 1;
    else if (text[i] === '}' && (depth -= 1) < 0) return text.slice(from, i);
  }
  return text.slice(from);
}

/**
 * The code after a send: to the end of its block, or, once the send has left the expression it sits in (a callback handed
 * to a call), to the end of that statement. A send in a top-level callback of a component (`useEntryChecks(..., () =>
 * save.mutate())`) would otherwise read the whole rest of the component, and any toast in it passed Move stock while its
 * save said nothing (#824). A send that is a statement of its own block reads the rest of the block. Strings are skipped.
 */
function restOfSend(text: string, from: number): string {
  const rest = restOfBlock(text, from);
  let depth = 0;
  for (let i = 0; i < rest.length; i += 1) {
    const c = rest[i] ?? '';
    if (c === "'" || c === '"' || c === '`') {
      for (i += 1; i < rest.length && (rest[i] !== c || rest[i - 1] === '\\'); i += 1);
    } else if (c === '{' || c === '(') depth += 1;
    else if (c === '}' || c === ')') depth -= 1;
    else if (depth < 0 && (c === ';' || c === '\n')) return rest.slice(0, i);
  }
  return rest;
}

/** The code that runs when a mutation works: its options, its hook's, each send to the end of its statement, and what they call. */
function successCode(text: string, m: Mutation, hookOpts: Map<string, string>, successOnly = false): string {
  const callee = /=\s*(\w+)/.exec(text.slice(m.index))?.[1] ?? '';
  const parts = [balanced(text, text.indexOf('(', m.index)), hookOpts.get(callee) ?? ''];
  for (const send of m.sends) {
    for (const call of text.matchAll(new RegExp(`(^|[^\\w.])${send.replace('.', '\\.')}\\s*\\(`, 'g'))) parts.push(restOfSend(text, call.index + call[0].length - 1));
  }
  if (!successOnly) return follow(text, parts, true);
  // Stripped after following too: following a name can bring back a function whose failure path sets a message.
  return withoutCatch(withoutOnError(follow(text, parts.map(withoutOnError), true)));
}

/** The mutations of one file that say nothing when they work and carry no toast-exempt reason. */
function silent(text: string, hooks: string[] = mutationHooks(text), hookOpts = new Map(hookOptions(text))): string[] {
  return mutations(text, hooks)
    .filter((m) => !exempted(text, m.index, TOAST_EXEMPT) && !SAYS_DONE.test(successCode(text, m, hookOpts)))
    .map((m) => `${m.line} ${m.name}`);
}

// One success signal per action (review of #685): a success path that shows a toast and also sets a success note
// (`setDone('Saved.')`, `setMessage(...)`, `setSaved(true)`) says it twice. A full success screen that replaces the form keeps
// its screen, also toasts (#824) and is marked `// success-screen: <what>`, so it never reaches this check. Clearing a note
// (`setMessage(null)`) is not a note, and an onError handler or a catch block is not the success path. The check reads state
// names, so a state that holds what a write returned is named for what it holds (`setVoided`, `setReport`); renaming a
// success note to slip past this list is the same defect, and review must refuse it.
const SUCCESS_NOTE = /\bset(?:Done|Saved|Message|Notice|Success)\((?!\s*(?:null|false|undefined|''|"")\s*\))/;

/** `code` without its `onError:` handlers (a name, or an arrow function with a balanced body), so a failure path is not read. */
function withoutOnError(code: string): string {
  let out = code;
  for (let at = out.search(/\bonError:/); at >= 0; at = out.search(/\bonError:/)) {
    let end = at + 'onError:'.length;
    while (/\s/.test(out[end] ?? '')) end += 1;
    if (out[end] === '(') {
      end += balanced(out, end).length;
      end = out.indexOf('=>', end) + 2;
      while (/\s/.test(out[end] ?? '')) end += 1;
    }
    if (out[end] === '{' || out[end] === '(') end += balanced(out, end).length;
    else end += /^[^,}\n]*/.exec(out.slice(end))?.[0].length ?? 0;
    out = out.slice(0, at) + out.slice(end);
  }
  return out;
}

/**
 * True when the mutation's own component draws a success note straight off its result (`save.isSuccess && <Success>`,
 * `if (save.data) return <p className="alert alert-success">`), with no state set: its `isSuccess` or `data`, then a note close by.
 */
function notesItself(text: string, m: Mutation): boolean {
  const rest = text.slice(m.index);
  const own = rest.slice(0, rest.search(/\n(?:export )?function |$/));
  return [...own.matchAll(new RegExp(`\\b${m.name}\\.(?:isSuccess|data)\\b`, 'g'))].some((use) => /alert-success|<Success\b/.test(own.slice(use.index, use.index + 300)));
}

// JUSTIFICATION-A3: round 3 of the #685 review asks the success-twice check to cover writes by hand too.
/** `code` without its `catch (...) { ... }` blocks, which say a failure. */
function withoutCatch(code: string): string {
  let out = code;
  for (let at = out.search(/\bcatch\s*(?:\([^()]*\))?\s*\{/); at >= 0; at = out.search(/\bcatch\s*(?:\([^()]*\))?\s*\{/)) {
    const open = out.indexOf('{', at);
    out = out.slice(0, at) + out.slice(open + balanced(out, open).length);
  }
  return out;
}

/** The writes by hand of one file whose success path both shows a toast and sets a success note. */
function doubledByHand(text: string): string[] {
  return [...text.matchAll(BUSY_ON)]
    .filter((m) => !exempted(text, m.index, TOAST_EXEMPT) && !exempted(text, m.index, SUCCESS_SCREEN))
    .filter((m) => {
      // Stripped after following too: following a name can bring back the whole function the write sits in.
      const code = withoutCatch(withoutOnError(follow(text, [withoutCatch(restOfBlock(text, m.index))], true)));
      return SAYS_DONE.test(code) && SUCCESS_NOTE.test(code);
    })
    .map((m) => `${text.slice(0, m.index).split('\n').length} ${m[0]}`);
}

/** The mutations of one file whose success path both shows a toast and sets, or draws, a success note. */
function doubled(text: string, hooks: string[] = mutationHooks(text), hookOpts = new Map(hookOptions(text))): string[] {
  return mutations(text, hooks)
    .filter((m) => !exempted(text, m.index, TOAST_EXEMPT) && !exempted(text, m.index, SUCCESS_SCREEN))
    .filter((m) => {
      const code = successCode(text, m, hookOpts, true);
      return SAYS_DONE.test(code) && (SUCCESS_NOTE.test(code) || notesItself(text, m));
    })
    .map((m) => `${m.line} ${m.name}`);
}

const hookOpts = new Map(sources.flatMap(({ text }) => hookOptions(text)));
// Issue #824: every screen with a mutation, so the operator console and the offline sales screen too. Writes by hand are
// checked in the staff area and the offline sales screen.
const staff = sources.filter((s) => /^(?:areas\/staff|areas\/platform|offline)\//.test(s.file));
const shop = staff.filter((s) => !s.file.startsWith('areas/platform/'));

describe('success toast guard', () => {
  it('finds the staff mutations it guards', () => {
    expect(staff.flatMap(({ text }) => mutations(text, hooks)).length).toBeGreaterThan(150);
  });

  it('every staff mutation says in a toast when it worked, or says why not', () => {
    expect(staff.flatMap(({ file, text }) => silent(text, hooks, hookOpts).map((m) => `${file}:${m}`))).toEqual([]);
  });

  it('no staff mutation says its success twice, in a toast and a success note', () => {
    expect(staff.flatMap(({ file, text }) => doubled(text, hooks, hookOpts).map((m) => `${file}:${m}`))).toEqual([]);
  });

  it('no staff write by hand says its success twice, in a toast and a success note', () => {
    expect(shop.flatMap(({ file, text }) => doubledByHand(text).map((m) => `${file}:${m}`))).toEqual([]);
  });

  it('every staff write by hand says in a toast when it worked, or says why not', () => {
    expect(shop.flatMap(({ text }) => [...text.matchAll(BUSY_ON)]).length).toBeGreaterThan(20);
    expect(shop.flatMap(({ file, text }) => silentByHand(text).map((m) => `${file}:${m}`))).toEqual([]);
  });

  it('never takes a success screen as a reason to say nothing, nor a note as a success screen (#824)', () => {
    expect(staff.flatMap(({ file, text }) => misusedTags(text).map((m) => `${file}:${m}`))).toEqual([]);
  });
});

describe('success toast guard on fixtures', () => {
  const press = `\n<BusyButton busy={save.isPending} onClick={() => save.mutate()}>Save</BusyButton>`;

  it('fails a mutation that says nothing when it works', () => {
    expect(silent(`const save = useMutation({ mutationFn: send, onSuccess: () => close() });${press}`)).toEqual(['1 save']);
  });

  it('passes meta.success, a success toast in its options, at its send or after an awaited send', () => {
    expect(silent(`const save = useMutation({ mutationFn: send, meta: { success: 'Item saved' } });${press}`)).toEqual([]);
    expect(silent(`const save = useMutation({ mutationFn: send, onSuccess: () => show('Item saved', 'success') });${press}`)).toEqual([]);
    expect(silent(`const save = useMutation({ mutationFn: send });\n<button onClick={() => save.mutate(x, { onSuccess: () => show('Saved', 'success') })} />`)).toEqual([]);
    expect(silent(`const save = useMutation({ mutationFn: send });\nasync function go() { await save.mutateAsync(x); show('Saved', 'success'); }`)).toEqual([]);
  });

  it('never passes an info or error toast as a success, a success tone outside the call, or words that are always null', () => {
    expect(silent(`const save = useMutation({ mutationFn: send, onSuccess: () => show('Sent for approval', 'info') });${press}`)).toEqual(['1 save']);
    expect(silent(`const save = useMutation({ mutationFn: send, onSuccess: () => show(x, 'info') });\nconst tone = ok ? 'success' : 'info'`)).toEqual(['1 save']);
    expect(silent(`const save = useMutation({ mutationFn: send, meta: { success: () => null } });${press}`)).toEqual(['1 save']);
    expect(silent(`const save = useMutation({ mutationFn: send, onSuccess: (r) => show(savedWords(r), r.ok ? 'success' : 'info') });`)).toEqual([]);
  });

  it('fails a success said twice, and passes a toast alone, a cleared note, an error note and a success screen', () => {
    expect(doubled(`const save = useMutation({ mutationFn: send, onSuccess: () => setDone('Saved.'), meta: { success: 'Saved' } });`)).toEqual(['1 save']);
    expect(doubled(`const save = useMutation({ mutationFn: send, onSuccess: () => { setMessage(t); show(t, 'success'); } });`)).toEqual(['1 save']);
    expect(doubled(`const save = useMutation({ mutationFn: send, onSuccess: () => setMessage(null), meta: { success: 'Saved' } });`)).toEqual([]);
    expect(doubled(`const save = useMutation({ mutationFn: send, onError: (e) => setMessage(e.message), meta: { success: 'Saved' } });`)).toEqual([]);
    expect(doubled(`// success-screen: the receipt replaces the form\nconst save = useMutation({ mutationFn: send, onSuccess: () => setDone(r), meta: { success: 'Saved' } });`)).toEqual([]);
    expect(silent(`// success-screen: the receipt replaces the form\nconst save = useMutation({ mutationFn: send, onSuccess: () => setDone(r) });`)).toEqual(['2 save']);
    expect(doubled(`const save = useMutation({ mutationFn: send, meta: { success: 'Saved' } });\nreturn save.isSuccess && <Success>Saved.</Success>;`)).toEqual(['1 save']);
  });

  it('fails a write by hand that toasts and sets a success note, and passes a note set only on failure', () => {
    const write = (after: string) => `async function save() {\n  setBusy(true);\n  try {\n    await api.save();\n    sayDone('Saved');${after}\n  } catch (e) {\n    setMessage(e.message);\n  } finally {\n    setBusy(false);\n  }\n}`;
    expect(doubledByHand(write("\n    setMessage('Saved.');"))).toEqual(['2 setBusy(true)']);
    expect(doubledByHand(write(''))).toEqual([]);
  });

  it('fails a write by hand that says nothing, and passes sayDone or a toast-exempt reason above it', () => {
    const write = (after: string) => `async function save() {\n  setBusy(true);\n  try {\n    await api.save();${after}\n  } finally {\n    setBusy(false);\n  }\n}`;
    expect(silentByHand(write(''))).toEqual(['2 setBusy(true)']);
    expect(silentByHand(write("\n    sayDone('Member saved');"))).toEqual([]);
    expect(silentByHand(write('').replace('  setBusy(true);', '  // toast-exempt: said elsewhere: the welcome page that opens says it in its toast\n  setBusy(true);'))).toEqual([]);
  });

  it('refuses a success screen as a toast-exempt reason in other words, and a success-screen tag on a note (#824)', () => {
    for (const reason of ['success screen, the receipt', 'the receipt replaces the form', 'the Saved panel shows', 'the done screen in its place', 'it shows in place of the form']) {
      expect(misusedTags(`// toast-exempt: ${reason}\nconst save = useMutation({ mutationFn: send });`), reason).toHaveLength(1);
    }
    expect(misusedTags(`// success-screen: the Saved note under the form\nconst save = useMutation({ mutationFn: send });`)).toHaveLength(1);
    expect(misusedTags(`// toast-exempt: waits for approval: it only asks, nothing is saved yet\n// success-screen: the receipt replaces the form`)).toEqual([]);
    // A screen named under an allowed kind is still refused.
    expect(misusedTags(`// toast-exempt: said elsewhere: the receipt replaces the form`)).toHaveLength(1);
  });

  it('reads a send in a top-level callback to the end of its statement, not the rest of the component (#824)', () => {
    const form = `const save = useMutation({ mutationFn: send });\nconst entry = useChecks(() => save.mutate());\nconst add = (p) => added(p.id, p.name);`;
    expect(silent(form)).toEqual(['1 save']);
    expect(silent(`const save = useMutation({ mutationFn: send });\nasync function go() {\n  await save.mutateAsync(x);\n  show('Saved', 'success');\n}`)).toEqual([]);
    // A declaration between the send and its toast inside one callback still counts (review of #828).
    expect(silent(`const save = useMutation({ mutationFn: send });\nconst go = async () => {\n  const r = await save.mutateAsync(x);\n  const n = r.no;\n  show(\`Saved \${n}\`, 'success');\n};`)).toEqual([]);
    // A toast in a later statement that does not start with a keyword is not the send's.
    expect(silent(`const save = useMutation({ mutationFn: send });\nconst entry = useChecks(() => save.mutate());\nuseEffect(() => show('Saved', 'success'));`)).toEqual(['1 save']);
    // A brace or parenthesis in a string does not move the depth.
    expect(silent(`const save = useMutation({ mutationFn: send });\nconst entry = useChecks(() => save.mutate(')'));\nconst add = (p) => added(p.id, p.name);`)).toEqual(['1 save']);
  });

  it('follows a local function and a callback prop of the same file', () => {
    const form = `const save = useMutation({ mutationFn: send, onSuccess: (r) => done(r) });\nfunction done(r) { onSaved(r); }`;
    expect(silent(form)).toEqual(['1 save']);
    expect(silent(`${form}\n<Form onSaved={(r) => show('Expense recorded', 'success')} />`)).toEqual([]);
  });

  it('follows a hook that returns a mutation to its options', () => {
    const hook = `function useSave() {\n  return useMutation({ mutationFn: send, meta: { success: 'Saved' } });\n}`;
    expect(silent(`${hook}\nconst save = useSave();`, ['useSave'])).toEqual([]);
  });

  it('honours a toast-exempt comment with a reason directly above the mutation, and nothing else', () => {
    const save = `const save = useMutation({ mutationFn: send });`;
    for (const kind of ['saves nothing', 'waits for approval', 'said elsewhere', 'success note']) {
      expect(silent(`// toast-exempt: ${kind}: why, in words\n${save}`), kind).toEqual([]);
    }
    expect(silent(`// toast-exempt:\n${save}`)).toEqual(['2 save']);
    expect(silent(`// toast-exempt: saves nothing:\n${save}`)).toEqual(['2 save']);
    // A reason of no allowed kind does not count, however it is worded (review of #828).
    expect(silent(`// toast-exempt: the receipt screen that opens says it\n${save}`)).toEqual(['2 save']);
    expect(silent(`// toast-exempt: the receipt replaces the form\n${save}`)).toEqual(['2 save']);
    expect(silent(`// busy-exempt: the switch says Working itself\n${save}`)).toEqual(['2 save']);
  });
});

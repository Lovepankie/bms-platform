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

type Mutation = { name: string; line: number; sends: string[]; pending: string | null; exempt: boolean };

const EXEMPT = /^\/\/\s*busy-exempt:\s*\S/;

/** True when the `//` comment lines directly above the line at `index` hold a `busy-exempt:` with a reason. */
function exempted(text: string, index: number): boolean {
  const above = text.slice(0, index).split('\n').slice(0, -1).reverse();
  for (const line of above) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('//')) return false;
    if (EXEMPT.test(trimmed)) return true;
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
    found.push({ name, line: at(m.index), sends: [`${name}.mutate`, `${name}.mutateAsync`], pending: `${name}.isPending`, exempt: exempted(text, m.index) });
  }
  for (const m of text.matchAll(new RegExp(`const\\s*\\{([^}]*)\\}\\s*=\\s*(?:${callee})\\s*[<(]`, 'g'))) {
    const fields = new Map<string, string>();
    for (const part of (m[1] ?? '').split(',')) {
      const [key, alias] = part.split(':').map((s) => s.trim());
      if (key) fields.set(key, alias || key);
    }
    const sends = ['mutate', 'mutateAsync'].flatMap((k) => (fields.has(k) ? [fields.get(k) ?? k] : []));
    found.push({ name: sends[0] ?? 'mutation', line: at(m.index), sends, pending: fields.get('isPending') ?? null, exempt: exempted(text, m.index) });
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
  // Follow local functions three calls deep: `onSubmit={submit}` and `function submit() { save.mutate() }`.
  const seen = new Set<string>();
  for (let depth = 0; depth < 3; depth += 1) {
    const joined = parts.join('\n');
    for (const [, name] of joined.matchAll(/\b([a-z]\w*)\b/g)) {
      if (!name || seen.has(name)) continue;
      seen.add(name);
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

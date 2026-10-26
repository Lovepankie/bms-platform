import { revision, sources } from 'virtual:help-corpus';
import type { Me } from '../api/client';
import { stepVisible, type Module } from '../tour/model';

// The help corpus (issue #598, ADR-095): one short Markdown article per task, kept in src/help/articles and bundled with the app,
// so How do I...? needs no network and no AI. Each article names its job, the permissions it applies to (the same rules as a tour
// step), the screens whose How do I? link opens it, and words a person may search with. #20 answers questions from the same files
// and keys its cache on REVISION.

export const REVISION = revision;

/** The jobs the articles are grouped by, in the order a shop owner meets them. */
export const JOBS = [
  { id: 'sell', label: 'Sell' },
  { id: 'buy-stock', label: 'Buy stock' },
  { id: 'move-stock', label: 'Move stock' },
  { id: 'count-stock', label: 'Count stock' },
  { id: 'items', label: 'Items and categories' },
  { id: 'branches', label: 'Branches and stores' },
  { id: 'users', label: 'Users and invitations' },
  { id: 'money', label: 'Money' },
  { id: 'loans', label: 'Loans' },
] as const;

export type JobId = (typeof JOBS)[number]['id'];

/** A run of text, bold where the Markdown had `**`. */
export type Inline = { text: string; strong: boolean }[];

export interface ArticleStep {
  text: Inline;
  /** The picture's file under /help/ and its caption, which is also drawn on the picture. */
  picture?: { file: string; caption: string };
}

export interface Article {
  id: string;
  title: string;
  job: JobId;
  order: number;
  module?: Module;
  needs: string[];
  needsAny: string[];
  /** Route paths whose How do I? link opens this article (a `$name` segment matches any one segment). */
  screens: string[];
  words: string[];
  intro: Inline[];
  steps: ArticleStep[];
  notes: Inline[];
}

const list = (value: string | undefined) => (value ?? '').split(',').map((v) => v.trim()).filter(Boolean);

/** `**bold**` is the only inline mark the articles use. */
export function inline(text: string): Inline {
  return text.split('**').map((part, i) => ({ text: part, strong: i % 2 === 1 })).filter((part) => part.text !== '');
}

/** Reads one article: the front matter between `---` lines, paragraphs, a numbered list of steps with a picture each, `>` notes. */
export function parseArticle(id: string, source: string): Article {
  const match = /^---\n([\s\S]*?)\n---\n([\s\S]*)$/.exec(source.replace(/\r\n/g, '\n'));
  if (!match) throw new Error(`${id}: no front matter`);
  const meta: Record<string, string> = {};
  for (const line of (match[1] ?? '').split('\n')) {
    const at = line.indexOf(':');
    if (at > 0) meta[line.slice(0, at).trim()] = line.slice(at + 1).trim();
  }
  const job = JOBS.find((j) => j.id === meta.job)?.id;
  if (!meta.title || !job) throw new Error(`${id}: a title and a known job are needed`);
  const article: Article = {
    id,
    title: meta.title,
    job,
    order: Number(meta.order ?? 0),
    module: meta.module === 'retail' || meta.module === 'lending' ? meta.module : undefined,
    needs: list(meta.needs),
    needsAny: list(meta.needs_any),
    screens: list(meta.screens),
    words: list(meta.words),
    intro: [],
    steps: [],
    notes: [],
  };
  for (const block of (match[2] ?? '').trim().split(/\n\s*\n/)) {
    const lines = block.split('\n');
    if (lines[0]?.startsWith('> ')) {
      article.notes.push(inline(lines.map((l) => l.replace(/^>\s?/, '')).join(' ')));
    } else if (/^\d+\. /.test(lines[0] ?? '')) {
      let step: ArticleStep | undefined;
      for (const line of lines) {
        const picture = /^\s*!\[([^\]]+)\]\(([^)]+)\)\s*$/.exec(line);
        if (/^\d+\. /.test(line)) article.steps.push((step = { text: inline(line.replace(/^\d+\. /, '')) }));
        else if (picture && step) step.picture = { caption: picture[1] ?? '', file: picture[2] ?? '' };
        else if (step) step.text = [...step.text, ...inline(` ${line.trim()}`)];
      }
    } else {
      article.intro.push(inline(lines.join(' ')));
    }
  }
  return article;
}

/** Every article, by job in the JOBS order, then by its own order. */
export const ARTICLES: Article[] = Object.entries(sources)
  .map(([id, source]) => parseArticle(id, source))
  .sort((a, b) => JOBS.findIndex((j) => j.id === a.job) - JOBS.findIndex((j) => j.id === b.job) || a.order - b.order);

/** Whether this person may read the article: the same permission and module rules as a tour step. */
export function articleVisible(article: Article, me: Me): boolean {
  const needsAny = article.needsAny.length > 0 ? article.needsAny : undefined;
  return stepVisible({ id: article.id, title: article.title, body: '', needs: article.needs, needsAny, module: article.module }, me);
}

export function articlesFor(me: Me, articles: readonly Article[] = ARTICLES): Article[] {
  return articles.filter((a) => articleVisible(a, me));
}

const plainText = (runs: Inline) => runs.map((r) => r.text).join('');

/** Lower case, letters and digits only, and a plural or -ing ending taken off, so "counting" finds "count". */
function stem(word: string): string {
  if (word.length > 5 && word.endsWith('ing')) return word.slice(0, -3);
  if (word.length > 4 && word.endsWith('ies')) return `${word.slice(0, -3)}y`;
  if (word.length > 3 && word.endsWith('s') && !word.endsWith('ss')) return word.slice(0, -1);
  return word;
}

const words = (text: string) => text.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean).map(stem);

// Words that say nothing about the task, so "how do I move stock" ranks by "move" and "stock".
const STOP = new Set(['how', 'do', 'i', 'a', 'an', 'the', 'to', 'of', 'in', 'on', 'my', 'is', 'it', 'and', 'or', 'can', 'what', 'why', 'does', 'for', 'from', 'with', 'me', 'at', 'this', 'that']);

/**
 * The articles that match what was typed, best first: each word typed scores 3 in the title, 2 in the article's search words and
 * 1 in its text, a word of three letters or more also matching the start of a longer one. An article must match every word that
 * is not a stop word; an empty search is every article in order.
 */
export function searchArticles(articles: readonly Article[], typed: string): Article[] {
  const wanted = words(typed).filter((w) => !STOP.has(w));
  if (wanted.length === 0) return [...articles];
  const hits = (pool: string[], w: string) => pool.some((p) => p === w || (w.length >= 3 && p.startsWith(w)));
  return articles
    .map((article) => {
      const title = words(article.title);
      const tags = words(article.words.join(' '));
      const body = words([...article.intro, ...article.steps.map((s) => s.text), ...article.notes].map(plainText).join(' '));
      let score = 0;
      for (const w of wanted) {
        const s = (hits(title, w) ? 3 : 0) + (hits(tags, w) ? 2 : 0) + (hits(body, w) ? 1 : 0);
        if (s === 0) return { article, score: 0 };
        score += s;
      }
      return { article, score };
    })
    .filter((r) => r.score > 0)
    .sort((a, b) => b.score - a.score)
    .map((r) => r.article);
}

const covers = (screen: string, path: string) => {
  const want = screen.split('/');
  const have = path.replace(/\/$/, '').split('/');
  return want.length === have.length && want.every((part, i) => part.startsWith('$') || part === have[i]);
};

/** The first article this person may read whose screens include the page at `path`, for its How do I? link. */
export function articleForScreen(path: string, me: Me, articles: readonly Article[] = ARTICLES): Article | undefined {
  return articles.find((a) => a.screens.some((s) => covers(s, path)) && articleVisible(a, me));
}

export function articleById(id: string, articles: readonly Article[] = ARTICLES): Article | undefined {
  return articles.find((a) => a.id === id);
}

/** Everything an article says, as plain text: for the glossary and dash checks. */
export function articleText(article: Article): string {
  return [article.title, ...article.intro.map(plainText), ...article.steps.flatMap((s) => [plainText(s.text), s.picture?.caption ?? '']), ...article.notes.map(plainText)].join('\n');
}

// After `npm run build` (issue #598, ADR-095): the built service worker precaches the help articles and every picture, so How do
// I...? works offline. A change to the PWA's includeAssets or globPatterns that drops them fails here, not on a pilot's phone.
import { readFileSync, readdirSync } from 'node:fs';

const sw = readFileSync(new URL('../dist/sw.js', import.meta.url), 'utf8');
const pictures = readdirSync(new URL('../public/help/', import.meta.url)).filter((n) => n.endsWith('.webp'));
const missing = pictures.filter((name) => !sw.includes(`"help/${name}"`));
// The articles ship inside a script of the build: the one holding an article's title must be precached too.
const articles = new URL('../src/help/articles/', import.meta.url);
const title = /^title: (.+)$/m.exec(readFileSync(new URL('count-stock.md', articles), 'utf8'))?.[1] ?? '';
const scripts = readdirSync(new URL('../dist/assets/', import.meta.url)).filter((n) => n.endsWith('.js'));
const holding = scripts.filter((n) => readFileSync(new URL(`../dist/assets/${n}`, import.meta.url), 'utf8').includes(title));
if (pictures.length === 0 || missing.length > 0) {
  console.error(`The service worker does not precache ${missing.length || 'any'} help picture(s): ${missing.join(', ')}`);
  process.exit(1);
}
if (title === '' || holding.length === 0 || !holding.every((n) => sw.includes(`"assets/${n}"`))) {
  console.error(`The service worker does not precache the script holding the help articles (${holding.join(', ') || 'none found'}).`);
  process.exit(1);
}
console.log(`The service worker precaches all ${pictures.length} help pictures and the articles (${holding.join(', ')}).`);

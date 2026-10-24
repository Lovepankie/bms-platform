import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import { TOURS } from '../tour/tours';
import { CATALOGUE_LINKS, SCREENS } from '../areas/staff/retail/permissions';
import { BANNED_WORDS, RETIRED_WORDS, WORDS, offendingWords } from './glossary';

// The glossary guard (issue #547): no banned word (SKU, variance, shrinkage, UOM, restock, in any case) and no retired label
// (stock-take, credit buyer, pack size) shows in the copy of the app. It reads every source file's strings and JSX text, and
// every tour and menu hint. It reads source, not the rendered page: words built at run time and the backend's refusals are not
// checked here. Names a person never sees stay as they are: routes, API fields, error
// codes, permission names, data-tour targets, imports and type names.

const SRC = fileURLToPath(new URL('../', import.meta.url));

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$|\.d\.ts$/.test(name) ? [path] : [];
  });
}

// JSX attributes whose value is a name for the code, not words for a person.
const CODE_ATTRIBUTES = new Set(['to', 'href', 'className', 'id', 'htmlFor', 'key', 'name', 'value', 'screen', 'queryKey', 'art', 'icon', 'type', 'role', 'autoComplete', 'inputMode', 'filterKey', 'kind', 'draftName']);

// Properties and attributes whose value is words for a person even when it is one lower case word ('purchases').
const COPY_NAMES = new Set(['noun', 'plural', 'rowsWord', 'label', 'title', 'hint', 'rootLabel', 'aria-label', 'placeholder']);

/** A string a person may read: words with a space, or one capitalised word (Stock-take too); a route, a code or a key is not. */
const isCopy = (text: string) => !text.startsWith('/') && (/\S\s+\S/.test(text) || /^[A-Z][a-z'-]+[.!?]?$/.test(text.trim()));

/** Whether a string literal names something for the code: an import, a type, a property key, a data attribute. */
function isCodeName(node: ts.Node): boolean {
  const parent = node.parent;
  if (ts.isImportDeclaration(parent) || ts.isExportDeclaration(parent) || ts.isLiteralTypeNode(parent) || ts.isExternalModuleReference(parent)) return true;
  if ((ts.isPropertyAssignment(parent) || ts.isPropertySignature(parent)) && parent.name === node) return true;
  if (ts.isElementAccessExpression(parent) && parent.argumentExpression === node) return true;
  if (ts.isJsxAttribute(parent)) {
    const name = parent.name.getText();
    return CODE_ATTRIBUTES.has(name) || name.startsWith('data-');
  }
  return false;
}

/** Whether a string is shown as it is: a child of a JSX element, or the value of a property or attribute that holds words. */
function isNamedCopy(node: ts.Node): boolean {
  let parent = node.parent;
  if (ts.isJsxExpression(parent)) {
    if (!ts.isJsxAttribute(parent.parent)) return true;
    parent = parent.parent;
  }
  if (ts.isPropertyAssignment(parent) && parent.initializer === node) return COPY_NAMES.has(parent.name.getText().replace(/['"]/g, ''));
  if (ts.isJsxAttribute(parent)) return COPY_NAMES.has(parent.name.getText());
  return false;
}

/** The copy of one source: every string, template and JSX text a person may read, with its line. */
function copyOfSource(path: string, text: string): { line: number; text: string }[] {
  const source = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true, path.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const found: { line: number; text: string }[] = [];
  const add = (node: ts.Node, text: string) => {
    if (isCopy(text) || isNamedCopy(node)) found.push({ line: source.getLineAndCharacterOfPosition(node.getStart()).line + 1, text });
  };
  const visit = (node: ts.Node) => {
    if ((ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) && !isCodeName(node)) add(node, node.text);
    else if (ts.isTemplateExpression(node)) add(node, [node.head.text, ...node.templateSpans.map((s) => s.literal.text)].join('X'));
    else if (ts.isJsxText(node) && node.text.trim() !== '') found.push({ line: source.getLineAndCharacterOfPosition(node.getStart()).line + 1, text: node.text.trim() });
    ts.forEachChild(node, visit);
  };
  visit(source);
  return found;
}

const copyOf = (path: string) => copyOfSource(path, readFileSync(path, 'utf8'));
const offencesIn = (text: string) => copyOfSource('fixture.tsx', text).flatMap((c) => offendingWords(c.text));

describe('glossary (issue #547)', () => {
  it('holds the renamed words', () => {
    expect(WORDS.buyStock).toBe('Buy stock');
    expect(WORDS.countStock).toBe('Count stock');
    expect(WORDS.difference).toBe('Difference');
    expect(WORDS.lostOrDamaged).toBe('Lost or damaged stock');
    expect(WORDS.customersWhoOwe).toBe('Customers who owe');
    expect(WORDS.countedIn).toBe('Counted in');
    expect(WORDS.packs).toBe('Cartons, boxes and other packs');
  });

  it('uses no banned or retired word and no dash in its own words', () => {
    for (const word of Object.values(WORDS)) {
      expect(offendingWords(word)).toEqual([]);
      expect(word).not.toMatch(/[\u2013\u2014]/);
    }
  });

  it('finds a banned word in any case and leaves names alone', () => {
    expect(offendingWords('Save restock')).toEqual(['restock']);
    expect(offendingWords('RESTOCKED')).toEqual(['restock']);
    expect(offendingWords('Review the Variance')).toEqual(['variance']);
    expect(offendingWords('Shrinkage by branch')).toEqual(['shrinkage']);
    expect(offendingWords('Type the sku')).toEqual(['sku']);
    expect(offendingWords('Two UOMs')).toEqual(['uom']);
    expect(offendingWords('Open the Stock-take')).toEqual(['stock-take']);
    expect(offendingWords('Add a credit buyer')).toEqual(['credit buyer']);
    expect(offendingWords('Buy stock, then count stock')).toEqual([]);
    expect(isCopy('restock_voids')).toBe(false);
    expect(isCopy('/staff/retail/restock')).toBe(false);
    expect(isCopy('Restock')).toBe(true);
    expect(isCopy('Save the restock')).toBe(true);
    expect(isCopy('Stock-take')).toBe(true);
    expect(BANNED_WORDS.length + Object.keys(RETIRED_WORDS).length).toBeGreaterThan(5);
  });

  it('reads one word JSX text and strings shown in JSX, and leaves code names alone', () => {
    expect(offencesIn('export const A = () => <a href="/x">Stock-take</a>;')).toEqual(['stock-take']);
    expect(offencesIn('export const A = () => <span>restocks</span>;')).toEqual(['restock']);
    expect(offencesIn("export const A = () => <span>{'restocks'}</span>;")).toEqual(['restock']);
    expect(offencesIn("export const A = () => <Link label={'restocks'} to=\"/staff/retail/restocks\">Buy stock</Link>;")).toEqual(['restock']);
    expect(offencesIn("export const A = () => <Link to=\"/staff/retail/restock\" data-tour=\"restock-save\">Buy stock</Link>;")).toEqual([]);
    expect(offencesIn("const code = 'restock_voids'; const kind = 'restock'; type K = 'restock';")).toEqual([]);
  });

  it('finds no banned or retired word in the copy of any screen', () => {
    const all = files(SRC);
    expect(all.length).toBeGreaterThan(100);
    const offences = all.flatMap((path) =>
      copyOf(path).flatMap(({ line, text }) => {
        const words = offendingWords(text);
        return words.length > 0 ? [`${relative(SRC, path)}:${line} ${words.join(', ')}: ${text.slice(0, 120)}`] : [];
      }),
    );
    expect(offences).toEqual([]);
  });

  it('finds no banned or retired word in any tour', () => {
    expect(TOURS.length).toBeGreaterThan(20);
    const offences = TOURS.flatMap((t) => [t.title, ...t.steps.flatMap((s) => [s.title, s.body])].filter((text) => offendingWords(text).length > 0).map((text) => `${t.id}: ${text}`));
    expect(offences).toEqual([]);
  });

  it('finds no banned or retired word in the menu labels and their help', () => {
    const offences = [...SCREENS, ...CATALOGUE_LINKS].flatMap((s) => [s.label, s.hint]).filter((text) => offendingWords(text).length > 0);
    expect(offences).toEqual([]);
  });

  it('names the screens with the glossary words', () => {
    const label = (screen: string) => [...SCREENS, ...CATALOGUE_LINKS].find((s) => s.screen === screen)?.label;
    expect(label('restock')).toBe(WORDS.buyStock);
    expect(label('stocktake')).toBe(WORDS.countStock);
    expect(label('buyers')).toBe(WORDS.customersWhoOwe);
  });
});

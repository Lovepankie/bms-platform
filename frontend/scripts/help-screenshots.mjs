// The pictures of the How do I...? articles (issue #598, ADR-095): real phone screenshots, 412x900, each with its caption drawn
// on it and the thing to tap ringed, written to public/help as WebP. Fabricated data only:
//
//   the shop screens  `VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1 npx vite --port 5174` (the retail mock; this script renames its two
//                     branches to a shop and a store and gives one item a buying history, in the browser only)
//   the rest          `VITE_DEV_TENANT=demo npx vite --port 5173` against a local API seeded with `make seed` and `make seed-lending`,
//                     signed in as the seeded owner (HELP_OWNER_EMAIL, HELP_OWNER_PASSWORD), with a store branch, an invited
//                     cashier and two payouts waiting for approval (see docs/ui/how-do-i-598/README.md)
//
//   node scripts/help-screenshots.mjs [article-id ...]
//
// The caption is the alt text of the picture in the article's Markdown, so the two never differ.

import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { chromium } from 'playwright';

const MOCK = process.env.HELP_MOCK_URL ?? 'http://localhost:5174';
const REAL = process.env.HELP_REAL_URL ?? 'http://localhost:5173';
const ARTICLES = new URL('../src/help/articles/', import.meta.url);
const OUT = new URL('../public/help/', import.meta.url);
const W = 412;
const H = 900;

/** Every picture's caption, by file name, read from the articles. */
const CAPTIONS = Object.fromEntries(
  readdirSync(ARTICLES)
    .filter((n) => n.endsWith('.md'))
    .flatMap((n) => [...readFileSync(new URL(n, ARTICLES), 'utf8').matchAll(/!\[([^\]]+)\]\(([^)]+)\)/g)].map((m) => [m[2], m[1]])),
);

const browser = await chromium.launch();
const phone = { viewport: { width: W, height: H }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, reducedMotion: 'reduce', locale: 'en-GB' };
const composer = await (await browser.newContext({ viewport: { width: W, height: H } })).newPage();

/** Draws the ring and the caption on a 2x screenshot and writes it as a 412x900 WebP. */
async function save(file, png, box) {
  const caption = CAPTIONS[file];
  if (!caption) throw new Error(`${file}: no article uses this picture`);
  const webp = await composer.evaluate(
    async ({ src, box, caption, W, H }) => {
      const img = new Image();
      img.src = src;
      await img.decode();
      const c = document.createElement('canvas');
      c.width = W;
      c.height = H;
      const g = c.getContext('2d');
      g.imageSmoothingQuality = 'high';
      g.drawImage(img, 0, 0, W, H);
      if (box) {
        g.lineWidth = 3;
        g.strokeStyle = '#c2410c';
        g.beginPath();
        g.roundRect(Math.max(2, box.x - 5), Math.max(2, box.y - 5), Math.min(W - 4, box.width + 10), box.height + 10, 10);
        g.stroke();
      }
      g.font = 'bold 19px "DejaVu Sans", Arial, sans-serif';
      const words = caption.split(' ');
      const lines = [];
      let line = '';
      for (const word of words) {
        const next = line ? `${line} ${word}` : word;
        if (g.measureText(next).width > W - 64 && line) {
          lines.push(line);
          line = word;
        } else line = next;
      }
      lines.push(line);
      const height = lines.length * 26 + 24;
      // The caption goes where it covers least of the ring: at the bottom unless the ring is low on the screen.
      const low = box && box.y + box.height > H - height - 40;
      const y = low ? 16 : H - height - 16;
      g.fillStyle = 'rgba(17, 24, 39, 0.94)';
      g.beginPath();
      g.roundRect(16, y, W - 32, height, 12);
      g.fill();
      g.fillStyle = '#ffffff';
      g.textBaseline = 'top';
      lines.forEach((l, i) => g.fillText(l, 32, y + 13 + i * 26));
      return c.toDataURL('image/webp', 0.82);
    },
    { src: `data:image/png;base64,${png.toString('base64')}`, box, caption, W, H },
  );
  writeFileSync(new URL(file, OUT), Buffer.from(webp.split(',')[1], 'base64'));
  console.log(`wrote ${file}`);
}

/** Rings `target` (a locator, or nothing) and saves the screen as `file`. */
async function shoot(page, file, target) {
  let box = null;
  if (target) {
    await target.scrollIntoViewIfNeeded();
    await page.waitForTimeout(300);
    box = await target.boundingBox();
  }
  await page.waitForTimeout(300);
  await save(file, await page.screenshot(), box);
}

/** Closes a tour that started by itself on this page load. */
async function noTour(page) {
  const close = page.getByRole('button', { name: 'Close the tour' });
  try {
    await close.waitFor({ timeout: 1500 });
    await close.click();
  } catch {
    // No tour this time.
  }
}

async function go(page, base, path) {
  await page.goto(base + path);
  await page.waitForLoadState('networkidle');
  await noTour(page);
}

const openMenu = async (page) => {
  await page.getByRole('button', { name: 'Menu', exact: true }).click();
  await page.waitForTimeout(400);
};

// The retail mock, renamed to a shop and a store, with a usual buying quantity of 24 for every item so a warning can show.
async function mockPage() {
  const ctx = await browser.newContext(phone);
  await ctx.route('**/src/api/retail-mock.ts*', async (route) => {
    const res = await route.fetch();
    let body = await res.text();
    // The dev server hands the module over compiled, with double quotes.
    body = body
      .replace(/code: ["']BR1["'], name: ["']Test Branch A["']/, 'code: "NTD", name: "Test Shop Ntinda"')
      .replace(/code: ["']BR2["'], name: ["']Test Branch B["'], is_head_office: false/, 'code: "KRK", name: "Test Store Kireka", is_head_office: false, sells_goods: false')
      .replace('buy: none,', 'buy: { lines_read: 12, median_qty: "24" },');
    await route.fulfill({ response: res, body });
  });
  return ctx.newPage();
}

async function realPage() {
  const ctx = await browser.newContext(phone);
  const page = await ctx.newPage();
  await page.goto(`${REAL}/sign-in`);
  await page.getByLabel('Email or phone').fill(process.env.HELP_OWNER_EMAIL ?? 'owner@demo.example.test');
  await page.getByLabel('Password').fill(process.env.HELP_OWNER_PASSWORD ?? '');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL(/\/staff/);
  await page.waitForLoadState('networkidle');
  await noTour(page);
  return page;
}

const tour = (page, id) => page.locator(`[data-tour="${id}"]`).first();
const button = (page, name) => page.getByRole('button', { name, exact: true }).first();
const link = (page, name) => page.getByRole('link', { name, exact: true }).first();
const menuLink = (page, name) => page.locator('.side-menu-drawer, .side-menu').getByRole('link', { name, exact: true }).first();

async function chooseBranch(page, name) {
  await openMenu(page);
  await page.getByRole('combobox', { name: 'Switch branch' }).selectOption({ label: name });
  await page.waitForTimeout(500);
  const close = page.getByRole('button', { name: 'Close menu' }).first();
  if (await close.isVisible()) await close.click();
  await page.waitForTimeout(300);
}

/** Opens a page inside the app, without a page load, so the mock keeps what was saved. */
async function spa(page, path) {
  await page.evaluate((to) => {
    window.history.pushState({}, '', to);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
  await page.waitForTimeout(1500);
}

async function addToSale(page, typed, name) {
  await page.getByLabel('Find an item by name, code or category').fill(typed);
  await page.getByRole('option', { name: new RegExp(name) }).first().click();
  await page.waitForTimeout(300);
}

// Each article's pictures, in its step order: what to open, what to do and what to ring.
const SHOTS = {
  'record-a-sale': {
    async mock(page) {
      await go(page, MOCK, '/staff/retail');
      await chooseBranch(page, 'Test Shop Ntinda (NTD)');
      await openMenu(page);
      await shoot(page, 'record-a-sale-1.webp', menuLink(page, 'Sell'));
      await menuLink(page, 'Sell').click();
      await page.waitForLoadState('networkidle');
      await page.getByLabel('Find an item by name, code or category').fill('bulb');
      await page.waitForTimeout(500);
      await shoot(page, 'record-a-sale-2.webp', page.getByRole('listbox').first());
      await page.getByRole('option', { name: /LED bulb 9W/ }).first().click();
      await page.waitForTimeout(3500);
      await shoot(page, 'record-a-sale-3.webp', tour(page, 'sale-payment'));
      await button(page, '10,000').click();
      await shoot(page, 'record-a-sale-4.webp', tour(page, 'sale-save'));
    },
  },
  'why-no-sale-at-a-store': {
    async mock(page) {
      await go(page, MOCK, '/staff/retail/sale');
      await chooseBranch(page, 'Test Store Kireka (KRK) (store)');
      await page.waitForTimeout(800);
      await shoot(page, 'why-no-sale-at-a-store-1.webp', page.getByTestId('store-branch'));
      await shoot(page, 'why-no-sale-at-a-store-2.webp', page.getByRole('button', { name: /^Sell at / }).first());
      await chooseBranch(page, 'Test Shop Ntinda (NTD)');
    },
  },
  'buy-stock': {
    async mock(page) {
      await go(page, MOCK, '/staff/retail/restock');
      await page.getByLabel('Supplier', { exact: true }).selectOption({ label: 'Test Supplier 01' });
      await shoot(page, 'buy-stock-1.webp', tour(page, 'restock-supplier'));
      await page.getByLabel('Find an item by name, code or category').first().fill('socket');
      await page.getByRole('option', { name: /Double socket/ }).first().click();
      await page.waitForTimeout(3500);
      await page.getByLabel(/^Cost each/).first().fill('8000');
      await page.getByLabel('Test Shop Ntinda (NTD)', { exact: true }).fill('24');
      await page.keyboard.press('Tab');
      await shoot(page, 'buy-stock-2.webp', page.locator('.line-card').first());
      await page.getByLabel('Cash', { exact: true }).check();
      await shoot(page, 'buy-stock-3.webp', tour(page, 'restock-paid'));
    },
  },
  'fix-a-purchase': {
    async mock(page) {
      // The mock keeps what is saved only until the next page load, so the purchase is saved and its list opened by links.
      await go(page, MOCK, '/staff/retail/restock');
      await page.getByLabel('Find an item by name, code or category').first().fill('socket');
      await page.getByRole('option', { name: /Double socket/ }).first().click();
      await page.getByLabel(/^Cost each/).first().fill('8000');
      await page.getByLabel('Test Shop Ntinda (NTD)', { exact: true }).fill('24');
      await page.getByLabel('Cash', { exact: true }).check();
      await tour(page, 'restock-save').click();
      await page.waitForTimeout(4000);
      await shoot(page, 'fix-a-purchase-1.webp', tour(page, 'restock-past'));
      await tour(page, 'restock-past').click();
      await page.waitForLoadState('networkidle');
      await shoot(page, 'fix-a-purchase-2.webp', page.getByRole('button', { name: /^Correct purchase/ }).first());
      await spa(page, '/staff/retail/restock');
      await page.getByLabel('Find an item by name, code or category').first().fill('socket');
      await page.getByRole('option', { name: /Double socket/ }).first().click();
      await page.waitForTimeout(3500);
      await page.getByLabel(/^Cost each/).first().fill('8000');
      await page.getByLabel('Test Shop Ntinda (NTD)', { exact: true }).fill('12');
      await page.keyboard.press('Tab');
      await shoot(page, 'fix-a-purchase-3.webp', page.locator('.line-card').first());
    },
  },
  'move-stock-store-to-shop': {
    async mock(page) {
      await go(page, MOCK, '/staff/retail');
      await openMenu(page);
      await page.getByRole('combobox', { name: 'Switch branch' }).selectOption({ label: 'Test Store Kireka (KRK) (store)' });
      await page.waitForTimeout(600);
      await shoot(page, 'move-stock-store-to-shop-1.webp', page.locator('.menu-shop-branch').first());
      await menuLink(page, 'Move stock').click();
      await page.waitForLoadState('networkidle');
      await page.getByLabel('To branch').selectOption({ label: 'Test Shop Ntinda (NTD)' });
      await shoot(page, 'move-stock-store-to-shop-2.webp', tour(page, 'transfer-to'));
      await page.getByLabel('Find an item by name, code or category').first().fill('socket');
      await page.getByRole('option', { name: /Double socket/ }).first().click();
      await page.waitForTimeout(3500);
      await page.locator('input[id^="tq-"]').first().fill('4');
      await shoot(page, 'move-stock-store-to-shop-3.webp', page.locator('[id^="tq-"]').first().locator('xpath=ancestor::*[contains(@class,"card")][1]'));
      await shoot(page, 'move-stock-store-to-shop-4.webp', button(page, 'Move stock'));
      await chooseBranch(page, 'Test Shop Ntinda (NTD)');
    },
  },
  'count-stock': {
    async mock(page) {
      await go(page, MOCK, '/staff/retail');
      await openMenu(page);
      await shoot(page, 'count-stock-1.webp', menuLink(page, 'Count stock'));
      await menuLink(page, 'Count stock').click();
      await page.waitForLoadState('networkidle');
      await page.getByLabel(/Find an item/).first().fill('switch');
      await page.getByRole('option', { name: /Single switch/ }).first().click();
      await page.waitForTimeout(3500);
      await page.getByLabel(/Single switch/).first().fill('15');
      await shoot(page, 'count-stock-2.webp', page.locator('.line-card').filter({ hasText: 'Single switch' }).first());
      await shoot(page, 'count-stock-3.webp', tour(page, 'count-review'));
      // The first tap leaves the count box (the line moves to Counted items); the second asks for the review.
      const commit = button(page, 'Commit the count');
      for (let i = 0; i < 6 && !(await commit.isVisible()); i++) {
        const next = ['Confirm', 'Finish anyway'].map((name) => button(page, name));
        const shown = (await next[0].isVisible()) ? next[0] : (await next[1].isVisible()) ? next[1] : tour(page, 'count-review');
        await shown.click();
        await page.waitForTimeout(900);
      }
      await shoot(page, 'count-stock-4.webp', button(page, 'Commit the count'));
    },
  },
  'entry-warnings': {
    async mock(page) {
      await go(page, MOCK, '/staff/retail/restock');
      await page.getByLabel('Find an item by name, code or category').first().fill('tape');
      await page.getByRole('option', { name: /Insulation tape/ }).first().click();
      await page.waitForTimeout(3500);
      await page.getByLabel(/^Cost each/).first().fill('1200');
      await page.getByLabel('Test Shop Ntinda (NTD)', { exact: true }).fill('240');
      await page.getByLabel('Cash', { exact: true }).check();
      await tour(page, 'restock-save').click();
      await page.waitForTimeout(800);
      const note = page.locator('.entry-warning').first();
      await shoot(page, 'entry-warnings-1.webp', note.locator('p').first());
      await shoot(page, 'entry-warnings-2.webp', note.locator('.entry-warning-actions'));
    },
  },
  'add-a-category': {
    async mock(page) {
      await go(page, MOCK, '/staff/retail/catalogue');
      await shoot(page, 'add-a-category-1.webp', link(page, 'Categories'));
      await go(page, MOCK, '/staff/retail/catalogue/categories');
      await button(page, 'Add a category').click();
      await page.getByLabel('Name of the new category').fill('Test Bulbs');
      await page.keyboard.press('Tab');
      await page.waitForTimeout(800);
      await shoot(page, 'add-a-category-2.webp', tour(page, 'named-add'));
      await page.getByRole('button', { name: 'Cancel' }).first().click().catch(() => undefined);
      await shoot(page, 'add-a-category-3.webp', page.getByRole('link', { name: /^Open Lighting/ }));
    },
  },
  'add-an-item': {
    async mock(page) {
      await go(page, MOCK, '/staff/retail/catalogue/categories');
      await shoot(page, 'add-an-item-1.webp', page.getByRole('link', { name: /^Open Lighting/ }));
      await page.getByRole('link', { name: /^Open Lighting/ }).click();
      await page.waitForLoadState('networkidle');
      await tour(page, 'category-add-item').click();
      await page.getByLabel(/^Description/).fill('LED tube 18W');
      await page.getByLabel(/^Counted in/).selectOption({ label: 'piece' });
      await page.getByLabel(/^Selling price/).fill('15000');
      await shoot(page, 'add-an-item-2.webp', page.locator('form').filter({ has: page.getByLabel(/^Description/) }).first());
      await button(page, 'Add item').click();
      await page.waitForTimeout(3500);
      await shoot(page, 'add-an-item-3.webp', tour(page, 'category-items').getByText(/LED tube 18W/).first());
    },
  },
  'store-or-shop': {
    async mock(page) {
      await go(page, MOCK, '/staff/retail/transfer');
      await chooseBranch(page, 'Test Store Kireka (KRK) (store)');
      await page.getByLabel('To branch').selectOption({ label: 'Test Shop Ntinda (NTD)' });
      await shoot(page, 'store-or-shop-2.webp', tour(page, 'transfer-to'));
      await go(page, MOCK, '/staff/retail/sale');
      await shoot(page, 'store-or-shop-3.webp', page.getByTestId('store-branch'));
      await chooseBranch(page, 'Test Shop Ntinda (NTD)');
    },
  },
  'switch-branch': {
    async mock(page) {
      await go(page, MOCK, '/staff/retail/stock');
      await shoot(page, 'switch-branch-1.webp', button(page, 'Menu'));
      await openMenu(page);
      await shoot(page, 'switch-branch-2.webp', page.locator('.menu-shop-branch').first());
      await page.getByRole('combobox', { name: 'Switch branch' }).selectOption({ label: 'Test Store Kireka (KRK) (store)' });
      await page.waitForTimeout(600);
      await page.getByRole('button', { name: 'Close menu' }).first().click();
      await page.waitForTimeout(800);
      await shoot(page, 'switch-branch-3.webp', page.locator('.top-title, .staff-bar-top').getByText('Test Store Kireka (KRK)').first());
      await chooseBranch(page, 'Test Shop Ntinda (NTD)');
    },
  },
  'add-a-branch': {
    async real(page) {
      await go(page, REAL, '/staff/setup');
      await shoot(page, 'add-a-branch-1.webp', page.getByRole('link', { name: /^Branches/ }).first());
      await go(page, REAL, '/staff/setup/branches');
      await tour(page, 'branches-add').click();
      await page.getByLabel(/^Name/).fill('Test Store Kireka');
      await page.getByRole('radio', { name: /^Store/ }).click();
      await page.getByLabel('Where is it?').fill('Kireka market');
      await shoot(page, 'add-a-branch-2.webp', page.getByRole('form', { name: 'Add a branch' }));
      await button(page, 'Add the branch').click();
      await page.waitForTimeout(1500);
      const card = page.locator('li').filter({ hasText: 'Test Store Kireka' }).first();
      await shoot(page, 'add-a-branch-3.webp', card);
      await shoot(page, 'store-or-shop-1.webp', card.getByText('Store (does not sell)'));
    },
  },
  'invite-someone': {
    async real(page) {
      await go(page, REAL, '/staff/users');
      await shoot(page, 'invite-someone-1.webp', tour(page, 'staff-invite'));
      await tour(page, 'staff-invite').click();
      await page.waitForLoadState('networkidle');
      await page.getByLabel('Full name').fill('Test Seller 03');
      await page.getByLabel('Phone number').fill('0700 000 103');
      await page.locator('[data-tour="invite-role"] select').selectOption({ label: 'Cashier' });
      await page.getByRole('checkbox', { name: /^Head Office/ }).check();
      await shoot(page, 'invite-someone-2.webp', tour(page, 'invite-role'));
      await tour(page, 'invite-send').click();
      await page.waitForTimeout(1500);
      await shoot(page, 'invite-someone-3.webp', page.getByRole('link', { name: 'Send on WhatsApp' }));
    },
  },
  'change-roles': {
    async real(page) {
      await go(page, REAL, '/staff/users');
      const card = page.locator('li').filter({ hasText: 'Test Officer 01' }).first();
      await shoot(page, 'change-roles-1.webp', card.getByRole('button', { name: 'Change roles' }));
      await card.getByRole('button', { name: 'Change roles' }).click();
      await page.waitForTimeout(600);
      const form = page.getByRole('form', { name: /^Change the roles of/ });
      await form.getByRole('checkbox', { name: /^Test Store Kireka/ }).check();
      await shoot(page, 'change-roles-2.webp', form.locator('fieldset').first());
      await shoot(page, 'change-roles-3.webp', form.getByRole('button', { name: 'Save the roles' }));
      await form.getByRole('button', { name: 'Cancel' }).click();
    },
  },
  'invitation-link-used-or-replaced': {
    async real(page) {
      const fresh = await browser.newPage(phone);
      await fresh.goto(`${REAL}/accept-invitation#token=${'0'.repeat(64)}`);
      await fresh.waitForLoadState('networkidle');
      for (const field of await fresh.locator('input[type="password"]').all()) await field.fill('Test-Passw0rd-598!');
      await fresh.getByRole('button').filter({ hasText: /continue|save|set|join|choose/i }).first().click().catch(() => undefined);
      await fresh.waitForTimeout(1500);
      await shoot(fresh, 'invitation-link-used-or-replaced-1.webp', fresh.getByText(/used or replaced/).first());
      await fresh.close();
      await go(page, REAL, '/staff/users');
      const card = page.locator('li').filter({ hasText: 'Test Cashier 02' }).first();
      await shoot(page, 'invitation-link-used-or-replaced-2.webp', card.getByRole('button', { name: 'Send a new link' }));
      await card.getByRole('button', { name: 'Send a new link' }).click();
      await page.waitForTimeout(1500);
      await shoot(page, 'invitation-link-used-or-replaced-3.webp', tour(page, 'staff-link'));
    },
  },
  'approve-a-request': {
    async real(page) {
      await go(page, REAL, '/staff/approvals');
      await shoot(page, 'approve-a-request-1.webp', tour(page, 'approvals-list'));
      await shoot(page, 'approve-a-request-2.webp', page.locator('.decision').first());
    },
  },
  'choose-what-needs-approval': {
    async real(page) {
      await go(page, REAL, '/staff/setup');
      await shoot(page, 'choose-what-needs-approval-1.webp', page.getByRole('link', { name: /^Approval rules/ }).first());
      await go(page, REAL, '/staff/setup/approvals');
      // A lending business keeps a second person on every lending action; what it changes is the amount it starts from.
      const rule = page.getByTestId('rule-loan_disbursement');
      await rule.getByRole('textbox').first().fill('500000');
      await shoot(page, 'choose-what-needs-approval-2.webp', rule);
      await tour(page, 'approval-rules-save').click();
      await shoot(page, 'choose-what-needs-approval-3.webp', page.getByRole('group', { name: 'Check before you save' }));
    },
  },
  'loan-application': {
    async real(page) {
      await go(page, REAL, '/staff/lending');
      await shoot(page, 'loan-application-1.webp', tour(page, 'loan-apply'));
      await tour(page, 'loan-apply').click();
      await page.waitForLoadState('networkidle');
      await noTour(page);
      await page.getByLabel(/^Find the member/).first().fill('Test');
      await page.waitForTimeout(1200);
      await page.getByRole('button', { name: /Test Borrower 03/ }).first().click();
      await page.waitForTimeout(800);
      await page.getByLabel(/^Loan product/).selectOption({ index: 1 });
      await page.waitForTimeout(800);
      await page.getByLabel(/^Amount asked for/).fill('500000');
      await page.getByLabel(/^How long/).fill('6');
      await shoot(page, 'loan-application-2.webp', tour(page, 'application-loan'));
      await shoot(page, 'loan-application-3.webp', tour(page, 'application-send'));
    },
  },
  'decide-a-loan-application': {
    async real(page) {
      await go(page, REAL, '/staff/lending');
      await page.locator('#loan-status').selectOption('appraised');
      await page.waitForTimeout(1200);
      await shoot(page, 'decide-a-loan-application-1.webp', tour(page, 'loan-status'));
      await page.locator('a[href*="/staff/lending/loans/"]').first().click();
      await page.waitForLoadState('networkidle');
      await noTour(page);
      await shoot(page, 'decide-a-loan-application-2.webp', tour(page, 'application-next'));
      await tour(page, 'application-next').click();
      await page.waitForLoadState('networkidle');
      await noTour(page);
      await page.getByRole('radio', { name: 'Approve' }).check();
      await page.waitForTimeout(500);
      await shoot(page, 'decide-a-loan-application-3.webp', tour(page, 'decide-choice'));
    },
  },
  'close-the-day': {
    async mock(page) {
      // A sale first, in the same page load, so the day has takings; then the pages by in-app moves, which keep the mock's data.
      await go(page, MOCK, '/staff/retail/sale');
      await addToSale(page, 'socket', 'Double socket');
      await button(page, 'Exact').click();
      await tour(page, 'sale-save').click();
      await page.waitForTimeout(2500);
      await spa(page, '/staff/retail/cash-summary');
      await shoot(page, 'close-the-day-1.webp', tour(page, 'cash-days').getByText(/Test Shop Ntinda \(NTD\), \d+ \w+ \d{4}/).first().locator('xpath=..'));
      await spa(page, '/staff/retail/savings');
      await shoot(page, 'close-the-day-2.webp', tour(page, 'savings-form'));
      await spa(page, '/staff/retail/banking');
      await page.getByLabel('Amount banked').fill('12000');
      await shoot(page, 'close-the-day-3.webp', tour(page, 'banking-form'));
    },
  },
};

const wanted = process.argv.slice(2);
const run = Object.entries(SHOTS).filter(([id]) => wanted.length === 0 || wanted.includes(id));
let mock;
let real;
for (const [id, shots] of run) {
  console.log(id);
  try {
    if (shots.mock) await shots.mock((mock ??= await mockPage()));
    if (shots.real) await shots.real((real ??= await realPage()));
  } catch (e) {
    // The screen as it was when the step failed, to see why.
    if (process.env.HELP_DEBUG) await (shots.real ? real : mock)?.screenshot({ path: process.env.HELP_DEBUG, fullPage: true });
    throw e;
  }
}
await browser.close();

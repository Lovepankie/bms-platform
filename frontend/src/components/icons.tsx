import type { ReactNode } from 'react';

// Inline SVG icons for the design layer (#95, docs/ui/design-system.md): 24px line icons that
// take the text colour, drawn here so no image is fetched and the CSP needs nothing extra. Every
// icon is decorative (aria-hidden); the text next to it carries the meaning.

function Svg({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {children}
    </svg>
  );
}

export const icons = {
  home: (
    <Svg>
      <path d="M3 11l9-7 9 7" />
      <path d="M5 10v10h14V10" />
      <path d="M10 20v-6h4v6" />
    </Svg>
  ),
  sale: (
    <Svg>
      <path d="M3 4h2l2.4 11.2a2 2 0 0 0 2 1.6h7.7a2 2 0 0 0 2-1.5L21 8H6.2" />
      <circle cx="10" cy="20" r="1.2" />
      <circle cx="17" cy="20" r="1.2" />
    </Svg>
  ),
  // A delivery motorbike: customer orders with delivery (issue #385).
  orders: (
    <Svg>
      <circle cx="6" cy="17" r="3" />
      <circle cx="18" cy="17" r="3" />
      <path d="M6 17h7l3-6h-4" />
      <path d="M16 11l1.5-4H20" />
      <path d="M3 13V8h6v5" />
    </Svg>
  ),
  creditSales: (
    <Svg>
      <rect x="3" y="6" width="18" height="12" rx="2" />
      <path d="M3 10h18" />
      <path d="M7 15h4" />
    </Svg>
  ),
  // A till receipt: every sale.
  salesHistory: (
    <Svg>
      <path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" />
      <path d="M9 8h6M9 12h6" />
    </Svg>
  ),
  restock: (
    <Svg>
      <path d="M1 6h13v10H1z" />
      <path d="M14 9h4l3 3v4h-7" />
      <circle cx="5.5" cy="17.5" r="1.8" />
      <circle cx="17.5" cy="17.5" r="1.8" />
    </Svg>
  ),
  transfer: (
    <Svg>
      <path d="M4 8h14" />
      <path d="M14 4l4 4-4 4" />
      <path d="M20 16H6" />
      <path d="M10 12l-4 4 4 4" />
    </Svg>
  ),
  transfers: (
    <Svg>
      <path d="M10 6h11M10 12h11M10 18h11" />
      <path d="M3 4.5l2 1.5-2 1.5M3 10.5l2 1.5-2 1.5M3 16.5l2 1.5-2 1.5" />
    </Svg>
  ),
  // A map pin: stock in each store and shop.
  stockByLocation: (
    <Svg>
      <path d="M12 21s7-6.5 7-12a7 7 0 0 0-14 0c0 5.5 7 12 7 12z" />
      <circle cx="12" cy="9" r="2.5" />
    </Svg>
  ),
  // A parcel on the move: stock sent and not yet received.
  inTransit: (
    <Svg>
      <rect x="9" y="6" width="12" height="12" rx="1" />
      <path d="M13 6v4h4V6" />
      <path d="M2 9h4M1 12h5M2 15h4" />
    </Svg>
  ),
  usage: (
    <Svg>
      <path d="M12 3l9 16H3z" />
      <path d="M12 10v4" />
      <path d="M12 17h.01" />
    </Svg>
  ),
  stock: (
    <Svg>
      <path d="M21 8l-9-5-9 5 9 5 9-5z" />
      <path d="M3 8v8l9 5 9-5V8" />
      <path d="M12 13v8" />
    </Svg>
  ),
  settings: (
    <Svg>
      <path d="M4 7h10M18 7h2M4 17h2M10 17h10" />
      <circle cx="16" cy="7" r="2" />
      <circle cx="8" cy="17" r="2" />
    </Svg>
  ),
  priceLists: (
    <Svg>
      <path d="M3 12V4h8l10 10-8 8z" />
      <circle cx="7.5" cy="8.5" r="1.3" />
    </Svg>
  ),
  customerGroups: (
    <Svg>
      <circle cx="9" cy="8" r="3" />
      <path d="M3 20v-1a5 5 0 0 1 5-5h2a5 5 0 0 1 5 5v1" />
      <path d="M16 5.2a3 3 0 0 1 0 5.6M18 14.3a5 5 0 0 1 3 4.7v1" />
    </Svg>
  ),
  // A customer beside the lines of their statement: credit limits and statements.
  customerAccounts: (
    <Svg>
      <circle cx="8" cy="8" r="3" />
      <path d="M3 20a5 5 0 0 1 10 0" />
      <path d="M15 8h6M15 12h6M15 16h4" />
    </Svg>
  ),
  documents: (
    <Svg>
      <path d="M7 3h8l4 4v14H7z" />
      <path d="M15 3v4h4M10 12h6M10 16h6" />
    </Svg>
  ),
  // A speech bubble with quote marks: prices offered to a buyer.
  quotations: (
    <Svg>
      <path d="M21 12a8.5 8.5 0 0 1-12.2 7.7L4 21l1.3-4.4A8.5 8.5 0 1 1 21 12z" />
      <path d="M8.5 10h2.5v2.2a2.2 2.2 0 0 1-2 2.3M13.5 10H16v2.2a2.2 2.2 0 0 1-2 2.3" />
    </Svg>
  ),
  returns: (
    <Svg>
      <path d="M9 14L4 9l5-5" />
      <path d="M4 9h10a6 6 0 0 1 0 12h-3" />
    </Svg>
  ),
  // The purchasing screens (issue #198), each with its own drawing (issue #483): an order sent out, a delivery coming into a tray, an
  // invoice with money on it, money going out, and what is owed by supplier.
  purchaseOrders: (
    <Svg>
      <path d="M13 21H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h8l4 4v6" />
      <path d="M9 9h5M9 13h4" />
      <path d="M15 18h6M18 15l3 3-3 3" />
    </Svg>
  ),
  goodsReceived: (
    <Svg>
      <path d="M3 13h5l1 3h6l1-3h5v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
      <path d="M12 3v9M8 8l4 4 4-4" />
    </Svg>
  ),
  supplierBills: (
    <Svg>
      <path d="M6 3h9l4 4v13a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z" />
      <path d="M14 3v4h4" />
      <path d="M12 9.5v8M14 11h-2.75a1.25 1.25 0 0 0 0 2.5h1.5a1.25 1.25 0 0 1 0 2.5H10" />
    </Svg>
  ),
  supplierPayments: (
    <Svg>
      <circle cx="9" cy="12" r="6" />
      <path d="M9 9.5v5" />
      <path d="M15 12h7M19 9l3 3-3 3" />
    </Svg>
  ),
  supplierPayables: (
    <Svg>
      <path d="M4 4v16" />
      <path d="M4 7h10M4 12h15M4 17h6" />
    </Svg>
  ),
  // Landed costs (issue #199): a ship for freight, duty and clearing; credit notes: an invoice with money taken off.
  landedCosts: (
    <Svg>
      <path d="M3 15h18l-3 5H6z" />
      <path d="M7 15V9h10v6" />
      <path d="M12 9V5h3" />
    </Svg>
  ),
  supplierCredits: (
    <Svg>
      <path d="M13 21H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h8l4 4v5" />
      <path d="M9 9h5M9 13h3" />
      <circle cx="17" cy="17" r="4" />
      <path d="M15 17h4" />
    </Svg>
  ),
  // A clock: what each credit buyer owes, by how late.
  customerAgeing: (
    <Svg>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </Svg>
  ),
  // Branches, one above two: which are stores and which are shops.
  branchRoles: (
    <Svg>
      <rect x="9" y="3" width="6" height="5" rx="1" />
      <rect x="3" y="16" width="6" height="5" rx="1" />
      <rect x="15" y="16" width="6" height="5" rx="1" />
      <path d="M12 8v4M6 16v-4h12v4" />
    </Svg>
  ),
  stocktake: (
    <Svg>
      <path d="M9 4h6v3H9z" />
      <path d="M15 5h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h3" />
      <path d="M9 13l2 2 4-4" />
    </Svg>
  ),
  // A gem: what the stock is worth.
  valuation: (
    <Svg>
      <path d="M6 4h12l3 5-9 11L3 9z" />
      <path d="M3 9h18M9 4l3 5 3-5" />
    </Svg>
  ),
  // An open book: the catalogue of items.
  catalogue: (
    <Svg>
      <path d="M12 6c-2-1.5-5-2-8-2v14c3 0 6 .5 8 2 2-1.5 5-2 8-2V4c-3 0-6 .5-8 2z" />
      <path d="M12 6v14" />
    </Svg>
  ),
  profit: (
    <Svg>
      <path d="M3 17l6-6 4 4 8-8" />
      <path d="M15 7h6v6" />
    </Svg>
  ),
  // A safe: money set aside for the day (the retail cash book).
  savings: (
    <Svg>
      <rect x="3" y="4" width="18" height="15" rx="2" />
      <circle cx="12" cy="11.5" r="3.5" />
      <path d="M12 8v1M12 14v1M7 19v2M17 19v2" />
    </Svg>
  ),
  // A shopping bag: the Retail item of the side menu (issue #483), apart from Sale's cart.
  retail: (
    <Svg>
      <path d="M5 8h14l-1 13H6z" />
      <path d="M9 10V6a3 3 0 0 1 6 0v4" />
    </Svg>
  ),
  // A shop front with an awning: the Shop heading of the side menu and the Shop card on Home (issue #483).
  shop: (
    <Svg>
      <path d="M3 10l2-5h14l2 5" />
      <path d="M4 10v9h16v-9" />
      <path d="M9 19v-5h6v5" />
    </Svg>
  ),
  // A stack of coins: the lending Savings item (issue #483), apart from the retail cash book's safe.
  coins: (
    <Svg>
      <ellipse cx="12" cy="6" rx="7" ry="3" />
      <path d="M5 6v6c0 1.7 3.1 3 7 3s7-1.3 7-3V6" />
      <path d="M5 12v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" />
    </Svg>
  ),
  // A seedling: lending investments.
  investments: (
    <Svg>
      <path d="M12 21v-9" />
      <path d="M12 12c0-4 3-6 7-6 0 4-3 6-7 6z" />
      <path d="M12 14c0-3-2.2-5-6-5 0 3 2.2 5 6 5z" />
    </Svg>
  ),
  // A light bulb: lending insights.
  insights: (
    <Svg>
      <path d="M9 18h6M10 21h4" />
      <path d="M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z" />
    </Svg>
  ),
  // A sparkle: the AI assistant.
  assistant: (
    <Svg>
      <path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z" />
      <path d="M19 15l.8 2.2 2.2.8-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z" />
    </Svg>
  ),
  // A page with a bar chart: Reports.
  reports: (
    <Svg>
      <path d="M6 3h12a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z" />
      <path d="M9 17v-3M12 17V9M15 17v-5" />
    </Svg>
  ),
  // An arrow coming in to a coin: Receive payments, apart from Banked's bank building (issue #483).
  receivePayments: (
    <Svg>
      <circle cx="15" cy="12" r="6" />
      <path d="M15 9.5v5" />
      <path d="M2 12h7M6 9l3 3-3 3" />
    </Svg>
  ),
  // A bar chart with a cross: delivery fees taken and orders cancelled.
  ordersReport: (
    <Svg>
      <path d="M3 20h18" />
      <path d="M6 16v-6M10 16V6M14 16v-4" />
      <path d="M17 5l4 4M21 5l-4 4" />
    </Svg>
  ),
  // The catalogue tiles (issue #483): a folder of categories, a ruler for units, an upload for the importer and a barcode label.
  categories: (
    <Svg>
      <path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
      <path d="M8 13h8" />
    </Svg>
  ),
  units: (
    <Svg>
      <path d="M3 17L17 3l4 4L7 21z" />
      <path d="M7 13l2 2M10 10l2 2M13 7l2 2" />
    </Svg>
  ),
  importer: (
    <Svg>
      <path d="M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" />
      <path d="M12 15V3M7 8l5-5 5 5" />
    </Svg>
  ),
  labels: (
    <Svg>
      <rect x="2" y="5" width="20" height="14" rx="2" />
      <path d="M6 9v6M9 9v6M12 9v6M15 9v6M18 9v6" />
    </Svg>
  ),
  // A coin held out on an open hand: the Lending heading of the side menu (issue #471).
  lending: (
    <Svg>
      <circle cx="15" cy="6" r="3" />
      <path d="M2 14h3.5l3.5-1h3a1.5 1.5 0 0 1 0 3H9" />
      <path d="M12.5 16l5.2-2.4a1.6 1.6 0 0 1 1.9 2.5L15 20H5.5L2 19" />
    </Svg>
  ),
  banking: (
    <Svg>
      <path d="M3 9l9-5 9 5" />
      <path d="M5 9v9M9.7 9v9M14.3 9v9M19 9v9" />
      <path d="M3 21h18" />
    </Svg>
  ),
  // A wallet: money spent.
  expenses: (
    <Svg>
      <path d="M3 7a2 2 0 0 1 2-2h12v4" />
      <path d="M3 7v11a2 2 0 0 0 2 2h15V9H5a2 2 0 0 1-2-2" />
      <path d="M16 14.5h.01" />
    </Svg>
  ),
  withdrawals: (
    <Svg>
      <rect x="3" y="7" width="18" height="12" rx="2" />
      <path d="M12 3v8M9 8l3 3 3-3" />
      <circle cx="12" cy="15" r="1.5" />
    </Svg>
  ),
  // A note with an arrow out and an arrow back: money advanced, and repaid.
  advances: (
    <Svg>
      <rect x="4" y="8.5" width="16" height="7" rx="1.5" />
      <circle cx="12" cy="12" r="1.5" />
      <path d="M6 4.5h13M16.5 2l2.5 2.5-2.5 2.5" />
      <path d="M18 19.5H5M7.5 17L5 19.5 7.5 22" />
    </Svg>
  ),
  // Two notes: the cash of each shop, each day.
  cashSummary: (
    <Svg>
      <rect x="2" y="8" width="16" height="10" rx="1.5" />
      <path d="M6 5h14.5a1.5 1.5 0 0 1 1.5 1.5V15" />
      <circle cx="10" cy="13" r="2" />
    </Svg>
  ),
  // A pair of scales: expected against banked.
  bankingReport: (
    <Svg>
      <path d="M12 4v16M7 20h10M5 7h14" />
      <path d="M5 7l-3 6a3 3 0 0 0 6 0zM19 7l-3 6a3 3 0 0 0 6 0z" />
    </Svg>
  ),
  expensesReport: (
    <Svg>
      <path d="M12 3a9 9 0 1 0 9 9h-9z" />
      <path d="M15 3.5A9 9 0 0 1 20.5 9H15z" />
    </Svg>
  ),
  // A checklist: the expense categories and items.
  expenseSetup: (
    <Svg>
      <rect x="3" y="4" width="5" height="5" rx="1" />
      <rect x="3" y="15" width="5" height="5" rx="1" />
      <path d="M11 6.5h10M11 17.5h10" />
    </Svg>
  ),
  salesAnalysis: (
    <Svg>
      <path d="M4 20V10M10 20V4M16 20v-8M22 20H2" />
    </Svg>
  ),
  margins: (
    <Svg>
      <path d="M19 5L5 19" />
      <circle cx="7" cy="7" r="2.5" />
      <circle cx="17" cy="17" r="2.5" />
    </Svg>
  ),
  stockHealth: (
    <Svg>
      <path d="M3 12h4l3-8 4 16 3-8h4" />
    </Svg>
  ),
  // A gauge: who owes and how late.
  creditControl: (
    <Svg>
      <path d="M4 18a8 8 0 1 1 16 0" />
      <path d="M12 18l4-5" />
      <path d="M3 18h18" />
    </Svg>
  ),
  evaluation: (
    <Svg>
      <path d="M4 20V8l5 4 4-8 7 6v10z" />
    </Svg>
  ),
  controls: (
    <Svg>
      <path d="M12 3l8 3v6c0 4.5-3.2 8-8 9-4.8-1-8-4.5-8-9V6z" />
      <path d="M9 12l2 2 4-4" />
    </Svg>
  ),
  user: (
    <Svg>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21a8 8 0 0 1 16 0" />
    </Svg>
  ),
  lock: (
    <Svg>
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </Svg>
  ),
  calendar: (
    <Svg>
      <rect x="3" y="5" width="18" height="16" rx="2" />
      <path d="M3 10h18M8 3v4M16 3v4" />
    </Svg>
  ),
  shield: (
    <Svg>
      <path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />
      <path d="M9 12l2 2 4-4" />
    </Svg>
  ),
  building: (
    <Svg>
      <path d="M4 21V5a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v16" />
      <path d="M15 10h4a1 1 0 0 1 1 1v10" />
      <path d="M8 8h3M8 12h3M8 16h3M2 21h20" />
    </Svg>
  ),
  // Two arrows chasing round: refresh the page (issue #430).
  refresh: (
    <Svg>
      <path d="M20 11a8 8 0 0 0-14.3-4.5L4 8.5" />
      <path d="M4 4v4.5h4.5" />
      <path d="M4 13a8 8 0 0 0 14.3 4.5l1.7-2" />
      <path d="M20 20v-4.5h-4.5" />
    </Svg>
  ),
  arrow: (
    <Svg>
      <path d="M5 12h14M13 6l6 6-6 6" />
    </Svg>
  ),
  // The theme button in the header (issue #452): a sun for Light, a moon for Dark, a phone for Same as my phone, and the tick of the
  // current choice. JUSTIFICATION-A3: four new icons in the one icon table, the same shape as every entry above.
  sun: (
    <Svg>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </Svg>
  ),
  moon: (
    <Svg>
      <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" />
    </Svg>
  ),
  // The phone side menu (issue #455): the menu button, its close button and the areas that had no icon.
  menu: (
    <Svg>
      <path d="M4 7h16M4 12h16M4 17h16" />
    </Svg>
  ),
  close: (
    <Svg>
      <path d="M6 6l12 12M18 6L6 18" />
    </Svg>
  ),
  approve: (
    <Svg>
      <circle cx="12" cy="12" r="9" />
      <path d="M8 12l3 3 5-6" />
    </Svg>
  ),
  people: (
    <Svg>
      <circle cx="9" cy="8" r="3.5" />
      <path d="M2.5 20a6.5 6.5 0 0 1 13 0" />
      <path d="M16 4.5a3.5 3.5 0 0 1 0 7M18 14a6.5 6.5 0 0 1 3.5 6" />
    </Svg>
  ),
  bell: (
    <Svg>
      <path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z" />
      <path d="M10 21h4" />
    </Svg>
  ),
  chat: (
    <Svg>
      <path d="M4 5h16v11H9l-5 4z" />
    </Svg>
  ),
  phone: (
    <Svg>
      <rect x="7" y="2.5" width="10" height="19" rx="2" />
      <path d="M11 18.5h2" />
    </Svg>
  ),
  check: (
    <Svg>
      <path d="M5 12.5l4.5 4.5L19 7.5" />
    </Svg>
  ),
  // A barcode inside a viewfinder: the camera button of the Scan box (issue #345).
  scan: (
    <Svg>
      <path d="M3 8V5a2 2 0 0 1 2-2h3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3" />
      <path d="M7 8v8M10 8v8M13 8v8M17 8v8" />
    </Svg>
  ),
  signOut: (
    <Svg>
      <path d="M10 4H5v16h5" />
      <path d="M14 8l4 4-4 4M18 12H9" />
    </Svg>
  ),
} as const;

export type IconName = keyof typeof icons;

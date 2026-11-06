// The words of every HelpTip (issue #707): the small question mark after a label that is not obvious. They live here, in one
// place, so they can be checked and translated. Each is one or two short sentences in plain words, with no dashes. `label` is
// the words the tip sits beside, exactly as the screen shows them; the button is named "More about" them, so a screen reader
// hears what is on the page. src/app/ui/help-tips-guard.test.ts proves every tip here is drawn on its screen.

export interface HelpTipWords {
  label: string;
  text: string;
}

export const HELP_TIPS = {
  averageBuyingPrice: {
    label: 'Average buying price',
    text: 'What one of this item has cost you on average, across all the stock you bought. The app works it out each time you buy stock.',
  },
  sellingPrice: {
    label: 'Selling price',
    text: 'What a customer pays you for one. Change it on the item\'s page.',
  },
  lowStockLevel: {
    label: 'Warn me when stock is below',
    text: 'When stock falls below this number, the app tells you, so you can buy more in time.',
  },
  priceList: {
    label: 'Price list',
    text: 'Special prices for some customers, such as wholesale buyers. Customers in this group pay the prices on the list.',
  },
  onCredit: {
    label: 'On credit',
    text: 'Sales the customer has not paid for yet. They count as profit only when the money comes in.',
  },
  itemSizes: {
    label: 'Does it come in different sizes, colours or types?',
    text: 'Say Yes when one item comes in several sizes or colours, such as bulbs of 40W and 60W. Each one gets its own price and stock.',
  },
  joinSizes: {
    label: 'Put items together as sizes',
    text: 'Use it when each size was added as its own item. Each one keeps its code too, so its labels and past sales still match.',
  },
  countItNow: {
    label: 'Count it now',
    text: 'Opens Count stock with this item ready. Type how many are on the shelf, then review and save the count.',
  },
  // Issue #728: a count started twice and never saved, because the last step said Commit.
  saveCount: {
    label: 'Save the count',
    text: 'Your stock changes only when you save the count.',
  },
  appraisal: {
    label: 'Appraisal',
    text: 'The check of whether the member can repay, done before the loan is decided. Its score only advises, and a person decides.',
  },
  guarantor: {
    label: 'Guarantor',
    text: 'A member who promises to pay if the borrower does not. Say how much each one guarantees.',
  },
  collateral: {
    label: 'Collateral',
    text: 'Something of value the member pledges, such as land or a vehicle. It can be sold if the loan is not repaid.',
  },
  outstanding: {
    label: 'Outstanding',
    text: 'What is still left to pay, after the payments made so far.',
  },
  inArrears: {
    label: 'In arrears',
    text: 'Money that was due and was not paid on time. These loans need a follow up.',
  },
  // Issue #709 (D93, ADR-110): after every Single unit and Package label.
  singleUnit: {
    label: 'Single unit',
    text: 'The one thing a customer can buy on its own, like one piece, one metre or one litre.',
  },
  package: {
    label: 'Package',
    text: 'Something that holds several single units, like a box of 10 pieces.',
  },
  // Issue #798 (rule 3 of #336): the words left without a tip by the audit.
  tokensADay: {
    label: 'Tokens a day',
    text: 'Tokens measure how much the assistant reads and writes. It stops for the day when it reaches this limit.',
  },
  kyc: {
    label: 'KYC',
    text: 'Know your customer: whether the member\'s details and ID have been given and checked.',
  },
  portfolioAtRisk: {
    label: 'Portfolio at risk',
    text: 'The money still owed on loans with a late payment, by how late it is, out of all the money owed on loans.',
  },
  postedLinesOnly: {
    label: 'From posted journal lines only',
    text: 'These figures come from what is recorded in the books, not from what the loan schedules expect.',
  },
} as const satisfies Record<string, HelpTipWords>;

export type HelpTipKey = keyof typeof HELP_TIPS;
